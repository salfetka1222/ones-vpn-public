"""Offline tests for pinned sing-box Go build information and source lockfiles."""
import hashlib
import importlib.util
import sys
import unittest
from pathlib import Path

target = Path(__file__).with_name("audit_libbox_provenance.py")
sys.path.insert(0, str(target.parent))
spec = importlib.util.spec_from_file_location("audit_libbox_provenance", target)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)

SOURCE_MOD = b"""module github.com/sagernet/sing-box

go 1.25.5

require (
    example.com/crypto v1.2.3
    example.com/network v2.0.1
)
"""
SOURCE_SUM = b"""example.com/crypto v1.2.3 h1:alpha
example.com/network v2.0.1 h1:beta
"""


def varint(number):
    out = bytearray()
    while number >= 128:
        out.append((number & 127) | 128)
        number >>= 7
    out.append(number)
    return bytes(out)


def fake_go_elf(
    *,
    go="go1.26.10",
    network_version="v2.0.1",
    network_sum="h1:beta",
    arch="arm64",
    extra_record="",
):
    lines = [
        "path\tgithub.com/sagernet/sing-box/cmd",
        "mod\tgithub.com/sagernet/sing-box\t(devel)\t",
        "dep\texample.com/crypto\tv1.2.3\th1:alpha",
        f"dep\texample.com/network\t{network_version}\t{network_sum}",
        "build\t-buildmode=c-shared",
        "build\t-trimpath=true",
        "build\tGOOS=android",
        f"build\tGOARCH={arch}",
        "build\tCGO_ENABLED=1",
    ]
    if extra_record:
        lines.append(extra_record)
    modinfo = b"F" * 16 + ("\n".join(lines) + "\n").encode() + b"G" * 16
    head = audit.BUILDINFO_MAGIC + bytes([8, 2]) + bytes(16)
    assert len(head) == 32
    return (
        b"\x7fELF" + bytes(50) + head
        + varint(len(go)) + go.encode()
        + varint(len(modinfo)) + modinfo
    )


def run(binary, mod=SOURCE_MOD, sums=SOURCE_SUM, go_version="go1.26.10"):
    return audit.validate(
        binary, mod, sums,
        mod_sha256=hashlib.sha256(SOURCE_MOD).hexdigest(),
        sum_sha256=hashlib.sha256(SOURCE_SUM).hexdigest(),
        expected_dep_count=2,
        expected_go_version=go_version,
    )


class LibboxProvenanceTests(unittest.TestCase):
    def test_matching_binary_metadata_and_upstream_lockfiles(self):
        summary = run(fake_go_elf())
        self.assertEqual(summary["go"], "go1.26.10")
        self.assertEqual(summary["dependencies"], 2)
        self.assertEqual(summary["platform"], "android/arm64")

    def test_compiled_dependency_version_mismatch_rejected(self):
        with self.assertRaisesRegex(ValueError, "go.mod"):
            run(fake_go_elf(network_version="v2.1.0"))

    def test_compiled_dependency_hash_mismatch_rejected(self):
        with self.assertRaisesRegex(ValueError, "go.sum"):
            run(fake_go_elf(network_sum="h1:corrupted"))

    def test_wrong_toolchain_rejected(self):
        with self.assertRaisesRegex(ValueError, "toolchain"):
            run(fake_go_elf(go="go1.25.5"))

    def test_wrong_abi_rejected(self):
        with self.assertRaisesRegex(ValueError, "GOARCH"):
            run(fake_go_elf(arch="amd64"))

    def test_unexpected_dependency_count_rejected(self):
        binary = fake_go_elf(extra_record="dep\texample.com/extra\tv1.0.0\th1:unknown")
        with self.assertRaisesRegex(ValueError, "number"):
            run(binary)

    def test_snapshot_tampering_rejected(self):
        with self.assertRaisesRegex(ValueError, "go.mod"):
            run(fake_go_elf(), mod=SOURCE_MOD + b"# changed\n")
        with self.assertRaisesRegex(ValueError, "go.sum"):
            run(fake_go_elf(), sums=SOURCE_SUM + b"# changed\n")

    def test_missing_or_malformed_buildinfo_rejected(self):
        for binary in (
            b"not a Go binary",
            fake_go_elf().replace(audit.BUILDINFO_MAGIC, b"X" * len(audit.BUILDINFO_MAGIC)),
            fake_go_elf().replace(audit.BUILDINFO_MAGIC + bytes([8, 2]), audit.BUILDINFO_MAGIC + bytes([8, 0])),
        ):
            with self.subTest(length=len(binary)):
                with self.assertRaises(ValueError):
                    run(binary)

    def test_duplicate_dependency_rejected(self):
        duplicate = "dep\texample.com/network\tv2.0.1\th1:beta"
        with self.assertRaisesRegex(ValueError, "repeated"):
            run(fake_go_elf(extra_record=duplicate))


if __name__ == "__main__":
    unittest.main()
