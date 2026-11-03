"""Build self-contained native prerelease packages on the matching operating system."""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TAG = re.compile(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)-(alpha|beta|rc)\.([1-9]\d*)\Z")
UPGRADE_UUID = "0ec41097-39e4-44d1-a12c-c93c20f9ca74"
MODULES = "java.base,java.desktop,java.logging,java.net.http,java.xml,java.sql,java.management,java.naming,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.localedata"


def version(tag):
    match = TAG.fullmatch(tag)
    if not match:
        raise ValueError("Only versioned alpha/beta/rc prerelease tags are accepted")
    major, minor, patch, channel, serial = match.groups()
    # Native installers need numeric components; retain exact semantic tag in app metadata.
    build = int(patch) * 1000 + {"alpha": 0, "beta": 300, "rc": 600}[channel] + int(serial)
    if int(major) > 255 or int(minor) > 255 or int(serial) >= 300 or build > 65535:
        raise ValueError("Version exceeds native installer limits")
    return f"{int(major)}.{int(minor)}.{build}"


def target():
    system = {"Linux": "linux", "Darwin": "macos", "Windows": "windows"}.get(platform.system())
    arch = {"x86_64": "x64", "amd64": "x64", "aarch64": "arm64", "arm64": "arm64"}.get(platform.machine().lower())
    if not system or not arch or (system == "windows" and arch != "x64"):
        raise ValueError("Unsupported prerelease package target")
    return system, arch


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def native_name(system):
    return {"linux": "libsqlite3.so.0", "macos": "libsqlite3.dylib", "windows": "sqlite3.dll"}[system]


def run(args, **options):
    subprocess.run([str(arg) for arg in args], check=True, **options)


def image_launcher(image, system):
    return image / ({"linux": "bin/VibeMiner", "macos": "Contents/MacOS/VibeMiner", "windows": "VibeMiner.exe"}[system])


def smoke(image, system, output, ui):
    report = output / ("ui-smoke.json" if ui else "backend-smoke.json")
    if report.exists():
        report.unlink()
    run([image_launcher(image, system), "--package-ui-smoke-test" if ui else "--package-smoke-test", "--smoke-report", report], timeout=90)
    deadline = time.monotonic() + 30
    while not report.is_file() and time.monotonic() < deadline:
        time.sleep(.1)
    data = json.loads(report.read_text(encoding="utf-8"))
    if data.get("status") != "ok" or data.get("ui") != ui:
        raise ValueError("Packaged application smoke check failed")
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--sqlite", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--javafx-native", type=Path)
    parser.add_argument("--javafx-legal", type=Path)
    parser.add_argument("--image-only", action="store_true")
    parser.add_argument("--ui-smoke", action="store_true")
    args = parser.parse_args()
    package_version = version(args.tag)
    system, arch = target()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    # Work only in the dedicated generated packaging directory, never installed/user data.
    staging = ROOT / "gui/build/package-input"
    if staging.exists():
        shutil.rmtree(staging)
    source = ROOT / "gui/build/install/vimi-gui-prototype/lib"
    if not source.is_dir():
        raise FileNotFoundError("Run :gui:installDist before packaging")
    staging.mkdir(parents=True)
    for jar in source.glob("*.jar"):
        shutil.copy2(jar, staging / jar.name)
    main_jar = "gui-0.1.0-SNAPSHOT.jar"
    if not (staging / main_jar).is_file():
        raise FileNotFoundError("GUI distribution main JAR is missing")
    native = staging / "native"
    native.mkdir()
    sqlite = args.sqlite.resolve(strict=True)
    shutil.copy2(sqlite, native / native_name(system))
    if args.javafx_native:
        shutil.copytree(args.javafx_native, native / "javafx")
    legal = staging / "legal"
    legal.mkdir()
    shutil.copy2(ROOT / "LICENSE", legal / "LICENSE-vimi")
    shutil.copy2(ROOT / "docs/prereleases.md", legal / "PRERELEASES.md")
    shutil.copy2(ROOT / "docs/third-party-runtime.md", legal / "THIRD-PARTY.md")
    if not args.javafx_legal or not args.javafx_legal.is_dir():
        raise ValueError("Verified platform OpenJFX legal notices are required")
    shutil.copytree(args.javafx_legal, legal / "openjfx-sdk")
    java_home = Path(os.environ["JAVA_HOME"])
    jpackage = java_home / "bin" / ("jpackage.exe" if system == "windows" else "jpackage")
    images = ROOT / "gui/build/package-images"
    image = images / ("VibeMiner.app" if system == "macos" else "VibeMiner")
    if image.exists():
        shutil.rmtree(image)
    icon = ROOT / "gui/src/main/resources/branding" / {"linux": "vibeminer.png", "macos": "vibeminer.icns", "windows": "vibeminer.ico"}[system]
    command = [jpackage, "--type", "app-image", "--name", "VibeMiner", "--input", staging,
               "--main-jar", main_jar, "--main-class", "com.vimi.gui.Launcher", "--dest", images,
               "--app-version", package_version, "--vendor", "VibeMiner", "--icon", icon,
               "--description", f"VibeMiner prerelease {args.tag}", "--add-modules", MODULES,
               # Voice decoding deliberately invokes the bundled child java executable.
               "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
               "--java-options", "--enable-native-access=ALL-UNNAMED",
               "--java-options", f"-Dvimi.version={args.tag}",
               "--java-options", f"-Dvibeminer.sqlite.library=$APPDIR/native/{native_name(system)}"]
    if args.javafx_native:
        command += ["--java-options", "-Djava.library.path=$APPDIR/native/javafx"]
    if system == "macos":
        command += ["--mac-package-identifier", "com.vibeminer.desktop", "--mac-package-name", "VibeMiner"]
    run(command, timeout=600)
    report = smoke(image, system, output, args.ui_smoke)
    if report.get("version") != args.tag:
        raise ValueError("Packaged version differs from requested tag")
    if args.image_only:
        print(image)
        return
    stem = f"VibeMiner-{args.tag}-{system}-{arch}"
    assets = []
    if system == "linux":
        portable = output / f"{stem}.tar.gz"
        with tarfile.open(portable, "w:gz") as archive:
            archive.add(image, arcname="VibeMiner")
        types = ["deb", "rpm"]
    else:
        portable = output / f"{stem}.zip"
        if system == "macos":
            run(["ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", image, portable], timeout=300)
        else:
            shutil.make_archive(str(portable.with_suffix("")), "zip", image.parent, image.name)
        types = ["msi"] if system == "windows" else ["dmg"]
    assets.append(portable)
    for package_type in types:
        destination = ROOT / "gui/build/native-packages" / package_type
        if destination.exists():
            shutil.rmtree(destination)
        destination.mkdir(parents=True, exist_ok=True)
        command = [jpackage, "--type", package_type, "--app-image", image, "--name", "VibeMiner",
                   "--app-version", package_version, "--dest", destination, "--vendor", "VibeMiner",
                   "--description", f"VibeMiner prerelease {args.tag}", "--license-file", ROOT / "LICENSE",
                   "--about-url", "https://github.com/aircan80/vimi"]
        if system == "windows":
            command += ["--win-per-user-install", "--win-menu", "--win-menu-group", "VibeMiner",
                        "--win-shortcut-prompt", "--win-dir-chooser", "--win-upgrade-uuid", UPGRADE_UUID]
        elif system == "linux":
            command += ["--linux-package-name", "vibeminer", "--linux-menu-group", "Development",
                        "--linux-shortcut", "--linux-app-category", "Development"]
            if package_type == "deb":
                command += ["--linux-deb-maintainer", "329744803+aircan80@users.noreply.github.com"]
            else:
                command += ["--linux-rpm-license-type", "GPLv3"]
        run(command, timeout=600)
        produced = list(destination.glob(f"*.{package_type}"))
        if len(produced) != 1:
            raise ValueError("Expected exactly one native installer")
        asset = output / f"{stem}.{package_type}"
        shutil.copy2(produced[0], asset)
        assets.append(asset)
    sqlite_api=ctypes.CDLL(str(sqlite))
    sqlite_api.sqlite3_libversion.restype=ctypes.c_char_p
    sqlite_version=sqlite_api.sqlite3_libversion().decode("ascii")
    manifest = {"tag": args.tag, "sqliteVersion": sqlite_version, "packageVersion": package_version, "platform": f"{system}-{arch}",
                "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "sqliteSha256": digest(sqlite), "smoke": report,
                "files": [{"name": asset.name, "sha256": digest(asset), "bytes": asset.stat().st_size} for asset in assets]}
    (output / f"manifest-{system}-{arch}.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Built {args.tag} for {system}-{arch}: {len(assets)} artifacts")


if __name__ == "__main__":
    main()
