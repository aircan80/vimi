"""Locate and diagnose architecture-matched SQLite for hosted integration jobs."""

import argparse
import ctypes
import hashlib
import io
import os
from pathlib import Path
import platform
import subprocess
import urllib.request
import zipfile

# SQLite's published SHA3-256: https://www.sqlite.org/download.html
WINDOWS_URL = "https://www.sqlite.org/2026/sqlite-dll-win-x64-3540100.zip"
WINDOWS_SHA3 = "c2d09c8babb9399277055c057c97a53f1ffd7c5f11d0c1f2118ffdfa39f2daa4"


def windows_dll(archive, directory):
    if hashlib.sha3_256(archive).hexdigest() != WINDOWS_SHA3:
        raise ValueError("SQLite archive checksum mismatch")
    with zipfile.ZipFile(io.BytesIO(archive)) as package:
        # Extract only the intended DLL, never arbitrary archive paths.
        dll = package.read("sqlite3.dll")
    directory.mkdir(parents=True, exist_ok=True)
    library = directory / "sqlite3.dll"
    library.write_bytes(dll)
    return library


def locate(directory):
    system, machine = platform.system(), platform.machine().lower()
    if system == "Windows":
        if machine not in {"amd64", "x86_64"}:
            raise ValueError("The Windows CI SQLite bundle requires an x64 runner/JDK")
        with urllib.request.urlopen(WINDOWS_URL, timeout=60) as response:
            return windows_dll(response.read(2 << 20), directory)
    if system == "Darwin":
        prefix = subprocess.check_output(["brew", "--prefix", "sqlite"], text=True).strip()
        return Path(prefix) / "lib/libsqlite3.dylib"
    if system == "Linux":
        triplet = {"x86_64": "x86_64-linux-gnu", "aarch64": "aarch64-linux-gnu"}[machine]
        candidates = [Path("/usr/lib") / triplet / "libsqlite3.so.0",
                      Path("/usr/lib64/libsqlite3.so.0"), Path("/usr/lib/libsqlite3.so.0")]
        for library in candidates:
            if library.is_file():
                return library
        raise FileNotFoundError("Install the architecture-matched SQLite shared library")
    raise ValueError("Unsupported CI platform")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    library = locate(args.directory).resolve(strict=True)
    sqlite = ctypes.CDLL(str(library))
    sqlite.sqlite3_libversion.restype = ctypes.c_char_p
    print(f"SQLite {sqlite.sqlite3_libversion().decode('ascii')}")
    print(f"Runner {platform.system()} / {platform.machine()} / {ctypes.sizeof(ctypes.c_void_p) * 8}-bit")
    print(f"Native library: {library}")
    if sqlite.sqlite3_threadsafe() == 0:
        raise ValueError("SQLite must be compiled with thread safety")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"library={library}\n")


if __name__ == "__main__":
    main()
