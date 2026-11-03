"""Verified matching OpenJFX SDK notices for desktop targets; ARM64 Maven adapter."""
import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import shutil
import urllib.request
import zipfile

VERSION = "25.0.1"
TARGETS = {
    "linux-arm64": ("linux-aarch64", "08415556b12bafce11e22f1ec35cb305f1d6b3a8eeef95b02b5b1007dd2f76e3"),
    "linux-x64": ("linux-x64", "a205615de1deb345bf5ca93da32dad23a5f535c9ff59661656b8cee6da469496"),
    "windows-x64": ("windows-x64", "39e5bf08c51578d55cce4832fc61c0faa477dded0e31bb85393a4049e6c62f48"),
    "macos-x64": ("osx-x64", "5156912b15a702f831a30b8ca58caa56c3ff7426a0537205a2bec6c86fb1c749"),
    "macos-arm64": ("osx-aarch64", "d16571cf466dbdb9a7c985a150df8a5bdc5af75ae0715c6865350c723ca33696"),
}
REQUIRED_NOTICES = ("LICENSE", "ASSEMBLY_EXCEPTION", "ADDITIONAL_LICENSE_INFO")
PREFIX = f"javafx-sdk-{VERSION}/"
MODULES = ("base", "graphics", "controls")


def sdk_source(platform):
    slug, checksum = TARGETS[platform]
    return f"https://download2.gluonhq.com/openjfx/{VERSION}/openjfx-{VERSION}_{slug}_bin-sdk.zip", checksum


def prepare(archive, destination, platform="linux-arm64"):
    url, checksum = sdk_source(platform)
    adapter = platform == "linux-arm64"
    if hashlib.sha256(archive).hexdigest() != checksum:
        raise ValueError("JavaFX SDK checksum differs")
    import io
    destination.mkdir(parents=True, exist_ok=True)
    native, artifacts, legal = (destination / name for name in ("native", "artifacts", "legal"))
    for directory in ((native, artifacts, legal) if adapter else (legal,)):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir()
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
            if module and adapter:
                output = artifacts / f"javafx-{module}-{VERSION}-linux-aarch64.jar"
            elif adapter and relative.startswith("lib/") and path.name.endswith(".so") and path.name.startswith(("libglass", "libprism", "libjavafx_font", "libjavafx_iio", "libdecora")):
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
    if adapter and (any(not (artifacts / f"javafx-{name}-{VERSION}-linux-aarch64.jar").is_file() for name in MODULES) or not (native / "libglass.so").is_file()):
        raise ValueError("Required JavaFX SDK files are absent")
    if any(not (legal / f"javafx.{module}" / notice).is_file() for module in MODULES for notice in REQUIRED_NOTICES):
        raise ValueError("Required JavaFX SDK legal notices are absent")
    (legal / "SDK_SOURCE.txt").write_text(f"OpenJFX {VERSION}\n{url}\nSHA-256 {checksum}\nSource: https://github.com/openjdk/jfx\n", encoding="utf-8")
    return (artifacts.resolve() if adapter else None, native.resolve() if adapter else None, legal.resolve())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--platform", choices=tuple(TARGETS), default="linux-arm64")
    args = parser.parse_args()
    if args.archive:
        archive = args.archive.read_bytes()
    else:
        with urllib.request.urlopen(sdk_source(args.platform)[0], timeout=60) as response:
            archive = response.read(128 << 20)
    artifacts, native, legal = prepare(archive, args.directory, args.platform)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            if artifacts is not None:
                output.write(f"repository={artifacts}\nnative={native}\n")
            output.write(f"legal={legal}\n")
    print(f"Prepared verified OpenJFX {VERSION} {args.platform}")


if __name__ == "__main__":
    main()
