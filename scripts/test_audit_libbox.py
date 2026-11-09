"""Offline regression tests for the pinned Android native archive preflight."""
import hashlib
import importlib.util
import io
import struct
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

target = Path(__file__).with_name("audit_libbox.py")
spec = importlib.util.spec_from_file_location("audit_libbox", target)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def jar_bytes() -> bytes:
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as jar:
        jar.writestr("libbox/Binding.class", b"\xca\xfe\xba\xbe")
    return out.getvalue()


def native_bytes(*, machine=183, typ=3) -> bytes:
    header = bytearray(20)
    header[0:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<HH", header, 16, typ, machine)
    return bytes(header) + b"\x00" * (1024 * 1024 + 1)


class LibboxAuditTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name, "libbox.aar")

    def create(self, *, entry_name="jni/arm64-v8a/libbox.so", binary=None,
               extra=None, java=None):
        entries = {
            "AndroidManifest.xml": b"<manifest/>",
            "proguard.txt": b"",
            "classes.jar": jar_bytes() if java is None else java,
            entry_name: native_bytes() if binary is None else binary,
            "R.txt": b"",
            "res/": b"",
        }
        if extra:
            entries.update(extra)
        with zipfile.ZipFile(self.path, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, payload in entries.items():
                archive.writestr(name, payload)
        return hashlib.sha256(self.path.read_bytes()).hexdigest()

    def test_expected_archive_and_arm64_elf_accepted(self):
        digest = self.create()
        result = audit.verify(self.path, digest)
        self.assertEqual(digest, result["sha256"])
        self.assertEqual(audit.NATIVE_ENTRY, result["native"])

    def test_unpinned_binary_rejected_even_with_valid_structure(self):
        self.create()
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            audit.verify(self.path)

    def test_other_abi_or_unexpected_archive_entry_rejected(self):
        for kwargs in (
            {"entry_name": "jni/armeabi-v7a/libbox.so"},
            {"extra": {"jni/x86_64/libbox.so": b"foreign"}},
            {"extra": {"../../traversal": b"forbidden"}},
        ):
            with self.subTest(kwargs=kwargs):
                digest = self.create(**kwargs)
                with self.assertRaisesRegex(ValueError, "entries"):
                    audit.verify(self.path, digest)

    def test_non_elf_and_foreign_elf_are_rejected(self):
        for binary in (
            b"bad!" + b"\0" * (1024 * 1024 + 30),
            native_bytes(machine=62),
            native_bytes(typ=2),
        ):
            with self.subTest(binary_head=binary[:20]):
                digest = self.create(binary=binary)
                with self.assertRaisesRegex(ValueError, "Native libbox"):
                    audit.verify(self.path, digest)

    def test_invalid_java_binding_archive_rejected(self):
        digest = self.create(java=b"not-a-jar")
        with self.assertRaisesRegex(ValueError, "Java bindings"):
            audit.verify(self.path, digest)

    def test_duplicate_zip_entry_rejected(self):
        digest = self.create()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.path, "a") as archive:
                archive.writestr("R.txt", b"duplicate")
        digest = hashlib.sha256(self.path.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            audit.verify(self.path, digest)


if __name__ == "__main__":
    unittest.main()
