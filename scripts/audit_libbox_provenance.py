#!/usr/bin/env python3
"""Offline, fail-closed provenance audit for the exact pinned Android libbox.

Reads Go 1.18+ inline buildinfo from the AArch64 ELF (without executing it),
compares each embedded dependency version and h1 digest to snapshots of
upstream sing-box's go.mod and go.sum at an immutable Git commit.

IMPORTANT: Self-reported build metadata and AAR SHA-256 are NOT proof of
reproducible source-to-binary compilation or freedom from vulnerabilities.
"""
import hashlib
import re
import sys
import zipfile
from pathlib import Path

import audit_libbox

ROOT = Path(__file__).resolve().parent
UPSTREAM_COMMIT = "c005f35300ce76427dc285481ec5695f5b179cd2"
MOD_FILE = ROOT / "provenance/sing-box-v1.14.4-go.mod"
SUM_FILE = ROOT / "provenance/sing-box-v1.14.4-go.sum"
MOD_SHA256 = "e5f23c00072adeee7285fabe8ea8dab9049ed8ae5e86fbf06b7a033b6126d24c"
SUM_SHA256 = "a93ea3b691cdfbba29d49ac63ed8352601306d89625e0abc87e41241434e8592"
EXPECTED_GO = "go1.26.10"
EXPECTED_DEPS = 113
MAIN_MODULE = "github.com/sagernet/sing-box"
BUILDINFO_MAGIC = b"\xff Go buildinf:"
MAX_MODULE_BLOB = 512 * 1024
EXPECTED_SETTINGS = {
    "-buildmode": "c-shared",
    "-trimpath": "true",
    "GOOS": "android",
    "GOARCH": "arm64",
    "CGO_ENABLED": "1",
}


def _read_uvarint(data: bytes, pos: int) -> tuple[int, int]:
    number = 0
    for offset in range(10):
        if pos >= len(data):
            raise ValueError("Truncated Go buildinfo varint")
        value = data[pos]
        pos += 1
        number |= (value & 0x7F) << (7 * offset)
        if not value & 0x80:
            return number, pos
    raise ValueError("Invalid Go buildinfo varint")


def _read_inline(data: bytes, pos: int) -> tuple[bytes, int]:
    length, pos = _read_uvarint(data, pos)
    if length > MAX_MODULE_BLOB or pos + length > len(data):
        raise ValueError("Invalid Go buildinfo string length")
    return data[pos:pos + length], pos + length


def buildinfo(native: bytes) -> tuple[str, dict, dict]:
    at = native.find(BUILDINFO_MAGIC)
    if at < 0 or at + 32 > len(native):
        raise ValueError("Go build information missing")
    header = native[at:at + 32]
    if header[14] != 8 or not header[15] & 2:
        raise ValueError("Expected inline 64-bit Go build information")
    go_version_bytes, pos = _read_inline(native, at + 32)
    framed, _ = _read_inline(native, pos)
    if len(framed) < 33 or framed[-17] != 10:
        raise ValueError("Invalid framed Go module information")
    try:
        go_version = go_version_bytes.decode("utf-8")
        data = framed[16:-16].decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError("Malformed Go buildinfo UTF-8") from exc
    modules: dict[str, tuple[str, str]] = {}
    settings: dict[str, str] = {}
    main_count = 0
    for line in data.splitlines():
        parts = line.split("\t")
        if parts[0] == "mod":
            main_count += 1
            if len(parts) < 3 or parts[1] != MAIN_MODULE or parts[2] != "(devel)":
                raise ValueError("Unrecognized main Go module")
        elif parts[0] == "dep":
            if len(parts) != 4 or not parts[3].startswith("h1:"):
                raise ValueError("Dependency lacks recorded upstream checksum")
            name, version, checksum = parts[1:]
            if not name or name in modules:
                raise ValueError("Empty or repeated embedded Go dependency")
            modules[name] = (version, checksum)
        elif parts[0] == "build":
            if len(parts) != 2 or "=" not in parts[1]:
                raise ValueError("Unexpected Go build setting")
            key, value = parts[1].split("=", 1)
            if key in settings:
                raise ValueError("Repeated Go build setting")
            settings[key] = value
    if main_count != 1 or not modules:
        raise ValueError("Main Go module or dependency inventory missing")
    return go_version, modules, settings


def _snapshot_checksum(data: bytes, expected: str, label: str) -> None:
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError(f"Pinned upstream {label} checksum mismatch")


def validate(
    binary: bytes,
    go_mod: bytes,
    go_sum: bytes,
    *,
    mod_sha256: str = MOD_SHA256,
    sum_sha256: str = SUM_SHA256,
    expected_dep_count: int = EXPECTED_DEPS,
    expected_go_version: str = EXPECTED_GO,
) -> dict:
    _snapshot_checksum(go_mod, mod_sha256, "go.mod")
    _snapshot_checksum(go_sum, sum_sha256, "go.sum")
    version, deps, settings = buildinfo(binary)
    if version != expected_go_version:
        raise ValueError("Unexpected Go toolchain version")
    for key, expected in EXPECTED_SETTINGS.items():
        if settings.get(key) != expected:
            raise ValueError(f"Unexpected Go build setting: {key}")
    if len(deps) != expected_dep_count:
        raise ValueError("Unexpected number of compiled dependencies")

    mod_text = go_mod.decode("utf-8")
    if not mod_text.startswith(f"module {MAIN_MODULE}\n"):
        raise ValueError("Unexpected upstream Go module path")
    declared: dict[str, str] = {}
    for line in mod_text.splitlines():
        match = re.fullmatch(
            r"\s*([^\s()]+)\s+(v\S+)(?:\s+//.*)?\s*", line
        )
        if match:
            name, version_pin = match.groups()
            if name in declared:
                raise ValueError("Duplicate upstream go.mod requirement")
            declared[name] = version_pin
    if not declared:
        raise ValueError("No pinned upstream Go requirements")

    known_sums: dict[tuple[str, str], str] = {}
    for line in go_sum.decode("utf-8").splitlines():
        parts = line.split()
        if len(parts) != 3:
            raise ValueError("Malformed upstream go.sum record")
        name, module_version, checksum = parts
        key = (name, module_version)
        if key in known_sums and known_sums[key] != checksum:
            raise ValueError("Conflicting upstream go.sum hashes")
        known_sums[key] = checksum

    for name, (module_version, checksum) in deps.items():
        if declared.get(name) != module_version:
            raise ValueError("Embedded dependency does not match upstream go.mod")
        if known_sums.get((name, module_version)) != checksum:
            raise ValueError("Embedded dependency checksum does not match go.sum")
    return {
        "go": version,
        "dependencies": len(deps),
        "upstream_declared": len(declared),
        "upstream_commit": UPSTREAM_COMMIT,
        "platform": "android/arm64",
    }


def verify_aar(path: Path) -> dict:
    audit_libbox.verify(path)
    with zipfile.ZipFile(path) as archive:
        binary = archive.read(audit_libbox.NATIVE_ENTRY)
    return validate(binary, MOD_FILE.read_bytes(), SUM_FILE.read_bytes())


if __name__ == "__main__":
    path = Path(sys.argv[1]) if len(sys.argv) == 2 else Path("app/libs/libbox.aar")
    try:
        result = verify_aar(path)
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as exc:
        raise SystemExit(f"libbox provenance audit failed: {exc}")
    print(
        f"libbox buildinfo provenance: {result['go']}, "
        f"{result['dependencies']}/{result['dependencies']} compiled dependencies "
        f"match pinned upstream versions and h1 checksums; "
        f"source tag {result['upstream_commit'][:10]} "
        "(not reproducible-build proof)"
    )
