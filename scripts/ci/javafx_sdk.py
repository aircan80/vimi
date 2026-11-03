"""Pinned OpenJFX SDK adapter for Linux ARM64, whose Maven classifiers are absent."""
import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import shutil
import urllib.request
import zipfile

VERSION = "25.0.1"
URL = f"https://download2.gluonhq.com/openjfx/{VERSION}/openjfx-{VERSION}_linux-aarch64_bin-sdk.zip"
SHA256 = "08415556b12bafce11e22f1ec35cb305f1d6b3a8eeef95b02b5b1007dd2f76e3"
PREFIX = f"javafx-sdk-{VERSION}/"
MODULES = ("base", "graphics", "controls")


def prepare(archive, destination):
    if hashlib.sha256(archive).hexdigest() != SHA256:
        raise ValueError("JavaFX SDK checksum differs")
    import io
    destination.mkdir(parents=True, exist_ok=True)
    native, artifacts, legal = (destination / name for name in ("native", "artifacts", "legal"))
    for directory in (native, artifacts, legal):
        directory.mkdir(exist_ok=True)
    total = 0
    with zipfile.ZipFile(io.BytesIO(archive)) as package:
        for info in package.infolist():
            path = PurePosixPath(info.filename)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError("Invalid SDK archive path")
            if not info.filename.startswith(PREFIX) or info.is_dir():
                continue
            relative = info.filename[len(PREFIX):]
            module = next((name for name in MODULES if relative == f"lib/javafx.{name}.jar"), None)
            if module:
                output = artifacts / f"javafx-{module}-{VERSION}-linux-aarch64.jar"
            elif relative.startswith("lib/") and path.name.endswith(".so") and path.name.startswith(("libglass", "libprism", "libjavafx_font", "libjavafx_iio", "libdecora")):
                output = native / path.name
            elif any(relative.startswith(f"legal/javafx.{name}/") for name in MODULES):
                output = legal / relative[len("legal/"):]
            else:
                continue
            total += info.file_size
            if info.file_size > 64 << 20 or total > 128 << 20:
                raise ValueError("SDK extraction exceeds bounds")
            output.parent.mkdir(parents=True, exist_ok=True)
            with package.open(info) as source, output.open("wb") as target:
                shutil.copyfileobj(source, target)
    if any(not (artifacts / f"javafx-{name}-{VERSION}-linux-aarch64.jar").is_file() for name in MODULES) or not (native / "libglass.so").is_file():
        raise ValueError("Required JavaFX SDK files are absent")
    (legal / "SDK_SOURCE.txt").write_text(f"OpenJFX {VERSION}\n{URL}\nSHA-256 {SHA256}\nSource: https://github.com/openjdk/jfx\n", encoding="utf-8")
    return artifacts.resolve(), native.resolve(), legal.resolve()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    if args.archive:
        archive = args.archive.read_bytes()
    else:
        with urllib.request.urlopen(URL, timeout=60) as response:
            archive = response.read(128 << 20)
    artifacts, native, legal = prepare(archive, args.directory)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"repository={artifacts}\nnative={native}\nlegal={legal}\n")
    print(f"Prepared verified OpenJFX {VERSION} Linux ARM64")


if __name__ == "__main__":
    main()
