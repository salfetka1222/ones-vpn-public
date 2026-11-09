#!/usr/bin/env python3
"""Fail-closed static validation of the pinned sing-box Android AAR.

Only validates the exact binary and archive/ELF identity. This is not an
independent source-code audit or proof that the artifact was reproducibly built.
"""
import hashlib
import io
import struct
import sys
import zipfile
from pathlib import Path

PINNED_SHA256 = "9b6d9a862907e77683ba1f00dbcb28c5d76f3e431c039f50f22a38f7b8471ede"
EXPECTED_ENTRIES = frozenset({
    "AndroidManifest.xml",
    "proguard.txt",
    "classes.jar",
    "jni/arm64-v8a/libbox.so",
    "R.txt",
    "res/",
})
NATIVE_ENTRY = "jni/arm64-v8a/libbox.so"


def verify(path: Path, expected_digest: str = PINNED_SHA256) -> dict:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            size += len(chunk)
            if size > 40 * 1024 * 1024:
                raise ValueError("AAR exceeds 40 MiB limit")
            digest.update(chunk)
    actual_digest = digest.hexdigest()
    if actual_digest != expected_digest:
        raise ValueError("Pinned libbox SHA-256 mismatch")
    try:
        with zipfile.ZipFile(path) as aar:
            infos = aar.infolist()
            names = [info.filename for info in infos]
            if len(names) != len(set(names)):
                raise ValueError("Duplicate AAR entries")
            if set(names) != EXPECTED_ENTRIES:
                raise ValueError("Unexpected or missing AAR entries/ABIs")
            for info in infos:
                if info.flag_bits & 0x1:
                    raise ValueError("Encrypted AAR entry")
                if info.filename.endswith("/") != info.is_dir():
                    raise ValueError("Unexpected AAR entry kind")
                if info.file_size > 90 * 1024 * 1024:
                    raise ValueError("Oversized AAR entry")
            native = aar.getinfo(NATIVE_ENTRY)
            if not 1024 * 1024 < native.file_size <= 90 * 1024 * 1024:
                raise ValueError("Unexpected native library size")
            with aar.open(native) as binary:
                header = binary.read(20)
            if len(header) != 20 or header[:6] != b"\x7fELF\x02\x01":
                raise ValueError("Native libbox is not 64-bit little-endian ELF")
            if struct.unpack_from("<H", header, 16)[0] != 3:
                raise ValueError("Native libbox is not ELF shared-object type")
            if struct.unpack_from("<H", header, 18)[0] != 183:
                raise ValueError("Native libbox ELF machine is not AArch64")
            classes = aar.read("classes.jar")
            if not zipfile.is_zipfile(io.BytesIO(classes)):
                raise ValueError("Java bindings are not a valid JAR")
    except (zipfile.BadZipFile, EOFError) as error:
        raise ValueError("Invalid libbox AAR archive") from error
    return {"sha256": actual_digest, "bytes": size, "native": NATIVE_ENTRY}


if __name__ == "__main__":
    filename = Path(sys.argv[1]) if len(sys.argv) == 2 else Path("app/libs/libbox.aar")
    try:
        result = verify(filename)
    except (OSError, ValueError, RuntimeError) as error:
        raise SystemExit(f"libbox preflight failed: {error}")
    print(f"Verified pinned libbox: SHA-256={result['sha256']}, "
          f"bytes={result['bytes']}, ABI=arm64-v8a, ELF=AArch64")
