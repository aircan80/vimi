"""Build self-contained native prerelease packages on the matching operating system."""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TAG = re.compile(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)-(alpha|beta|rc)\.([1-9]\d*)\Z")
UPGRADE_UUID = "0ec41097-39e4-44d1-a12c-c93c20f9ca74"
JVM_OPTIONS_FILE = ROOT / "gui/jvm-options.txt"


def jvm_options(path=JVM_OPTIONS_FILE):
    """App JVM options shared with `gradlew :gui:run`: one per line, '#' comments."""
    text = Path(path).read_text(encoding="utf-8-sig")
    lines = (re.sub(r"(^|\s)#.*$", "", line).strip() for line in text.splitlines())
    return [line for line in lines if line]


MODULES = "java.base,java.desktop,java.logging,java.net.http,java.xml,java.sql,java.management,java.naming,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.localedata"


def version(tag):
    match = TAG.fullmatch(tag)
    if not match:
        raise ValueError("Only versioned alpha/beta/rc prerelease tags are accepted")
    major, minor, patch, channel, serial = match.groups()
    # Native installers need numeric components; retain exact semantic tag in app metadata.
    build = int(patch) * 1000 + {"alpha": 0, "beta": 300, "rc": 600}[channel] + int(serial)
    if int(major) >= 255 or int(minor) > 255 or int(serial) >= 300 or build > 65535:
        raise ValueError("Version exceeds native installer limits")
    # macOS rejects a zero first component; offset it consistently on every OS.
    return f"{int(major) + 1}.{int(minor)}.{build}"


def target():
    system = {"Linux": "linux", "Darwin": "macos", "Windows": "windows"}.get(platform.system())
    arch = {"x86_64": "x64", "amd64": "x64", "aarch64": "arm64", "arm64": "arm64"}.get(platform.machine().lower())
    if not system or not arch or (system == "windows" and arch != "x64"):
        raise ValueError("Unsupported prerelease package target")
    return system, arch


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


NATIVE_SUFFIXES = (".so", ".dll", ".dylib", ".jnilib")
# NativeLibLoader messages under -Djavafx.verbose=true (OpenJFX 25).
PATH_LOAD = re.compile(r"^Loaded (.+) from (?:java\.library\.path|relative path)\r?$", re.MULTILINE)
SYSTEM_LOAD = re.compile(r"^System\.loadLibrary\(.+\) succeeded", re.MULTILINE)
RESOURCE_LOAD = re.compile(r"^(?:Loaded|Unpacked) library .+ from resource|^Loading library .+ from resource failed", re.MULTILINE)


def javafx_glass(system):
    return {"linux": "libglass.so", "macos": "libglass.dylib", "windows": "glass.dll"}[system]


def require_class_only_javafx(staging, javafx_native, system):
    """Packaged JavaFX loads natives from the image; JARs that embed them would extract to ~/.openjfx/cache."""
    jars = sorted(staging.glob("javafx-*.jar"))
    if not jars:
        raise ValueError("GUI distribution has no JavaFX JARs")
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            embedded = [name for name in archive.namelist() if name.lower().endswith(NATIVE_SUFFIXES)]
        if embedded:
            raise ValueError(f"{jar.name} embeds JavaFX natives ({embedded[0]}) that would extract into ~/.openjfx/cache; "
                             "build with -PjavafxSdkRepository from scripts/ci/javafx_sdk.py")
    if not (javafx_native / javafx_glass(system)).is_file():
        raise ValueError("Verified JavaFX native directory lacks the platform glass library")


def check_native_loading(log, image, cache, ui):
    """Assert JavaFX loaded its natives from the app image and never used a JavaFX cache directory."""
    if cache.exists():
        raise ValueError("Packaged application extracted JavaFX natives into a cache directory")
    if RESOURCE_LOAD.search(log):
        raise ValueError("Packaged application loaded JavaFX natives from JAR resources")
    if SYSTEM_LOAD.search(log):
        raise ValueError("Packaged application loaded a JavaFX native through System.loadLibrary, not the app image")
    if ui:
        root = os.path.normcase(os.path.realpath(image))
        loaded = [match.group(1) for match in PATH_LOAD.finditer(log)]
        if not loaded:
            raise ValueError("Packaged application reported no JavaFX native library loads")
        outside = [path for path in loaded if not os.path.normcase(os.path.realpath(path)).startswith(root + os.sep)]
        if outside:
            raise ValueError(f"Packaged application loaded JavaFX natives outside the app image: {outside[0]}")
        if not any(Path(path).name.startswith(("glass", "libglass")) for path in loaded):
            raise ValueError("Packaged application did not load the JavaFX glass library")
        return loaded
    return []


def native_name(system):
    return {"linux": "libsqlite3.so.0", "macos": "libsqlite3.dylib", "windows": "sqlite3.dll"}[system]


def run(args, **options):
    subprocess.run([str(arg) for arg in args], check=True, **options)


def image_launcher(image, system):
    return image / ({"linux": "bin/VibeMiner", "macos": "Contents/MacOS/VibeMiner", "windows": "VibeMiner.exe"}[system])


def require_collectors(report):
    """The packaged app must run the shared jvm-options.txt collector (ZGC)."""
    names = [name for name in report.get("collectors", "").split(",") if name]
    if "-XX:+UseZGC" in jvm_options() and not any(name.startswith("ZGC") for name in names):
        raise ValueError(f"Packaged app is not running ZGC: {names}")


def smoke(image, system, output, ui):
    report = output / ("ui-smoke.json" if ui else "backend-smoke.json")
    if report.exists():
        report.unlink()
    with tempfile.TemporaryDirectory(prefix="vimi-javafx-cache-check-") as sentinel:
        # Any JavaFX native extraction lands here instead of the shared ~/.openjfx/cache.
        cache = Path(sentinel) / "cache"
        environment = dict(os.environ)
        # The JVM accepts double-quoted JAVA_TOOL_OPTIONS values, so a temp path may hold spaces.
        environment["JAVA_TOOL_OPTIONS"] = " ".join(filter(None, [
            environment.get("JAVA_TOOL_OPTIONS"), "-Djavafx.verbose=true", f'"-Djavafx.cachedir={cache}"']))
        try:
            result = subprocess.run([str(image_launcher(image, system)), "--package-ui-smoke-test" if ui else "--package-smoke-test",
                                     "--smoke-report", str(report)], timeout=90, env=environment,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
        except subprocess.TimeoutExpired as timeout:
            captured = timeout.stdout or b""
            sys.stdout.write(captured.decode("utf-8", "replace") if isinstance(captured, bytes) else captured)
            raise
        sys.stdout.write(result.stdout)
        if result.returncode:
            raise subprocess.CalledProcessError(result.returncode, result.args)
        deadline = time.monotonic() + 30
        while not report.is_file() and time.monotonic() < deadline:
            time.sleep(.1)
        data = json.loads(report.read_text(encoding="utf-8"))
        if data.get("status") != "ok" or data.get("ui") != ui:
            raise ValueError("Packaged application smoke check failed")
        loaded = check_native_loading(result.stdout, image, cache, ui)
    print(f"JavaFX natives loaded from the app image: {len(loaded)} libraries, no cache extraction")
    return data


# jpackage would derive deb Depends from the shared-library closure of every ELF file in
# the image, using the build runner's package names (Ubuntu 24.04 t64 and libjpeg-turbo8
# names), which do not install on Debian or older Ubuntu. Pin the direct runtime needs of
# the bundled JDK and JavaFX natives instead, with alternatives for the t64 renames, so
# the same deb installs on Debian 12/13 and Ubuntu 22.04/24.04.
DEB_DEPENDS = (
    "libc6",
    "xdg-utils",
    "libasound2t64 | libasound2",
    "libx11-6",
    "libxext6",
    "libxi6",
    "libxrender1",
    "libxtst6",
    "libxxf86vm1",
    "libgl1",
    "libfreetype6",
    "libfontconfig1",
    "libglib2.0-0t64 | libglib2.0-0",
    "libgtk-3-0t64 | libgtk-3-0",
    "libatk1.0-0t64 | libatk1.0-0",
    "libcairo2",
    "libcairo-gobject2",
    "libgdk-pixbuf-2.0-0",
    "libpango-1.0-0",
    "libpangocairo-1.0-0",
    "libpangoft2-1.0-0",
    "libharfbuzz0b",
    "libstdc++6",
    "libgcc-s1",
)
# bgfx dlopens the Vulkan loader, so no ELF lists it as NEEDED; it falls back without it.
DEB_RECOMMENDS = ("libvulkan1",)
# The deb package providing each shared library an image ELF may list as NEEDED. Libraries
# bundled in the image itself (the JDK's libjvm.so, libfreetype.so, ...) need no package.
DEB_SONAME_PACKAGES = {
    **dict.fromkeys(("libc.so.6", "libm.so.6", "libdl.so.2", "libpthread.so.0", "librt.so.1",
                     "ld-linux-x86-64.so.2", "ld-linux-aarch64.so.1"), "libc6"),
    "libstdc++.so.6": "libstdc++6",
    "libgcc_s.so.1": "libgcc-s1",
    "libasound.so.2": "libasound2t64 | libasound2",
    "libX11.so.6": "libx11-6",
    "libXext.so.6": "libxext6",
    "libXi.so.6": "libxi6",
    "libXrender.so.1": "libxrender1",
    "libXtst.so.6": "libxtst6",
    "libXxf86vm.so.1": "libxxf86vm1",
    "libGL.so.1": "libgl1",
    "libfreetype.so.6": "libfreetype6",
    "libfontconfig.so.1": "libfontconfig1",
    "libharfbuzz.so.0": "libharfbuzz0b",
    **dict.fromkeys(("libglib-2.0.so.0", "libgobject-2.0.so.0", "libgio-2.0.so.0", "libgthread-2.0.so.0",
                     "libgmodule-2.0.so.0"), "libglib2.0-0t64 | libglib2.0-0"),
    **dict.fromkeys(("libgtk-3.so.0", "libgdk-3.so.0"), "libgtk-3-0t64 | libgtk-3-0"),
    "libatk-1.0.so.0": "libatk1.0-0t64 | libatk1.0-0",
    "libcairo.so.2": "libcairo2",
    "libcairo-gobject.so.2": "libcairo-gobject2",
    "libgdk_pixbuf-2.0.so.0": "libgdk-pixbuf-2.0-0",
    "libpango-1.0.so.0": "libpango-1.0-0",
    "libpangocairo-1.0.so.0": "libpangocairo-1.0-0",
    "libpangoft2-1.0.so.0": "libpangoft2-1.0-0",
}
# jpackage's RPM Requires come from its scriptlets and shortcut handling only.
RPM_REQUIRES = {"/bin/sh", "xdg-utils"}


def write_deb_control_template(resources):
    """jpackage --resource-dir control template whose Depends is the fixed DEB_DEPENDS."""
    if resources.exists():
        shutil.rmtree(resources)
    resources.mkdir(parents=True)
    (resources / "control").write_text("\n".join([
        "Package: APPLICATION_PACKAGE",
        "Version: APPLICATION_VERSION_WITH_RELEASE",
        "Section: APPLICATION_SECTION",
        "Maintainer: APPLICATION_MAINTAINER",
        "Priority: optional",
        "Architecture: APPLICATION_ARCH",
        "Provides: APPLICATION_PACKAGE",
        "Description: APPLICATION_DESCRIPTION",
        "Depends: " + ", ".join(DEB_DEPENDS),
        "Recommends: " + ", ".join(DEB_RECOMMENDS),
        "Installed-Size: APPLICATION_INSTALLED_SIZE",
        "APPLICATION_HOMEPAGE",
    ]) + "\n", encoding="utf-8")


def parse_depends(field):
    return tuple(" | ".join(choice.strip() for choice in entry.split("|")) for entry in field.split(",") if entry.strip())


def check_deb_depends(deb):
    field = subprocess.check_output(["dpkg-deb", "--field", str(deb), "Depends"], text=True).strip()
    depends = parse_depends(field)
    if depends != DEB_DEPENDS:
        raise ValueError(f"deb Depends differs from the allowlist: {field}")
    print(f"deb Depends ({len(depends)}): {field}")
    recommends = subprocess.check_output(["dpkg-deb", "--field", str(deb), "Recommends"], text=True).strip()
    if parse_depends(recommends) != DEB_RECOMMENDS:
        raise ValueError(f"deb Recommends differs from the allowlist: {recommends}")
    print(f"deb Recommends: {recommends}")


ELF_MAGIC = b"\x7fELF"
# ELF class, byte order and e_machine of each Linux packaging arch: EM_X86_64, EM_AARCH64.
ELF_TARGETS = {"x64": (2, 1, 62), "arm64": (2, 1, 183)}
# EI_OSABI values a Linux loader accepts: System V and GNU/Linux.
ELF_LINUX_ABIS = (0, 3)


def elf_target(data):
    """(class, byte order, OS ABI, e_machine) from an ELF header."""
    if len(data) < 0x14 or data[:4] != ELF_MAGIC:
        raise ValueError("truncated or not an ELF header")
    machine, = struct.unpack_from("<H" if data[5] == 1 else ">H", data, 0x12)
    return data[4], data[5], data[7], machine


def loads_on(data, arch):
    """Whether a Linux loader for this packaging arch would load the ELF (not FreeBSD, not another CPU)."""
    wide, order, abi, machine = elf_target(data)
    return (wide, order, machine) == ELF_TARGETS[arch] and abi in ELF_LINUX_ABIS


def elf_dynamic(data):
    """(SONAME or None, [NEEDED...]) of an ELF shared object or executable; None if not a dynamic ELF."""
    if data[:4] != ELF_MAGIC:
        return None
    if len(data) < 0x40 or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError("truncated ELF header or unknown ELF class/byte order")
    wide = data[4] == 2
    order = "<" if data[5] == 1 else ">"
    if wide:
        phoff, = struct.unpack_from(order + "Q", data, 0x20)
        phentsize, phnum = struct.unpack_from(order + "HH", data, 0x36)
        header, dyn = order + "IIQQQQQQ", order + "qQ"
    else:
        phoff, = struct.unpack_from(order + "I", data, 0x1C)
        phentsize, phnum = struct.unpack_from(order + "HH", data, 0x2A)
        header, dyn = order + "IIIIIIII", order + "iI"
    loads, dynamic = [], None
    for index in range(phnum):
        fields = struct.unpack_from(header, data, phoff + index * phentsize)
        # ELF64 orders (type, flags, offset, vaddr, paddr, filesz, ...); ELF32 (type, offset, vaddr, paddr, filesz, ...).
        kind, offset, vaddr, size = (fields[0], fields[2], fields[3], fields[5]) if wide else fields[:3] + (fields[4],)
        if kind == 1:
            loads.append((vaddr, offset, size))
        elif kind == 2:
            dynamic = (offset, size)
    if dynamic is None:
        return None
    entries, strtab = [], None
    for position in range(dynamic[0], dynamic[0] + dynamic[1], struct.calcsize(dyn)):
        tag, value = struct.unpack_from(dyn, data, position)
        if tag == 0:
            break
        if tag == 5:
            strtab = value
        entries.append((tag, value))
    if not any(tag in (1, 14) for tag, _ in entries):
        return None, []
    if strtab is None:
        raise ValueError("ELF dynamic section has NEEDED or SONAME entries but no string table")
    file_offset = next((offset + strtab - vaddr for vaddr, offset, size in loads if vaddr <= strtab < vaddr + size), None)
    if file_offset is None:
        raise ValueError("ELF dynamic section has no loadable string table")

    def string(value):
        start = file_offset + value
        return data[start:data.index(b"\0", start)].decode("utf-8")

    soname = next((string(value) for tag, value in entries if tag == 14), None)
    return soname, [string(value) for tag, value in entries if tag == 1]


def image_elves(root):
    """(location, data) of every ELF file in the image and every ELF entry in its JARs."""
    for path in sorted(Path(root).rglob("*")):
        if path.is_symlink() or not path.is_file():
            continue
        with path.open("rb") as stream:
            magic = stream.read(4)
        if magic == ELF_MAGIC:
            yield path.relative_to(root).as_posix(), path.read_bytes()
        elif path.suffix == ".jar":
            with zipfile.ZipFile(path) as archive:
                for entry in archive.infolist():
                    if entry.is_dir():
                        continue
                    with archive.open(entry) as stream:
                        if stream.read(4) != ELF_MAGIC:
                            continue
                    yield f"{path.relative_to(root).as_posix()}!/{entry.filename}", archive.read(entry)


def required_deb_packages(elves, arch):
    """(deb packages, skipped count) for the NEEDED sonames of the ELFs that load on this arch.

    JARs such as zstd-jni carry natives for other operating systems and CPUs; those are skipped
    so they neither add foreign sonames (FreeBSD's libc.so.7) nor provide a library by name.
    Fails naming any unmapped library or unreadable ELF.
    """
    provided, needed, skipped = set(), {}, 0
    for location, data in elves:
        try:
            if not loads_on(data, arch):
                skipped += 1
                continue
            dynamic = elf_dynamic(data)
        except (ValueError, struct.error, IndexError, OverflowError, TypeError) as error:
            raise ValueError(f"Cannot read ELF {location}: {error}") from error
        if dynamic is None:
            continue
        soname, libraries = dynamic
        provided.add(soname or PurePosixPath(location).name)
        for library in libraries:
            needed.setdefault(library, location)
    unmapped = sorted((library, location) for library, location in needed.items()
                      if library not in provided and library not in DEB_SONAME_PACKAGES)
    if unmapped:
        raise ValueError("No deb package is mapped for " + ", ".join(f"{library} (needed by {location})" for library, location in unmapped)
                         + "; add it to DEB_SONAME_PACKAGES and DEB_DEPENDS")
    packages = {DEB_SONAME_PACKAGES[library] for library in needed if library not in provided}
    missing = sorted(packages - set(DEB_DEPENDS))
    if missing:
        raise ValueError(f"deb Depends lacks packages the image needs: {missing}")
    return packages, skipped


def check_rpm_requires(rpm):
    listed = subprocess.check_output(["rpm", "-qp", "--requires", str(rpm)], text=True).split("\n")
    requires = {line.strip() for line in listed if line.strip() and not line.startswith("rpmlib(")}
    if requires != RPM_REQUIRES:
        raise ValueError(f"rpm Requires differs from the allowlist: {sorted(requires)}")
    print(f"rpm Requires: {sorted(requires)}")


def verify_macos_signatures(image):
    """The bundle seal and every bundled native library, including JavaFX's, must verify."""
    run(["codesign", "--verify", "--deep", "--strict", "--verbose=2", image], timeout=300)
    libraries = sorted((image / "Contents/app/native").rglob("*.dylib"))
    if not any(path.name == "libglass.dylib" for path in libraries):
        raise ValueError("macOS app image lacks the bundled JavaFX glass library")
    for library in libraries:
        run(["codesign", "--verify", "--strict", library], timeout=60)


def check_image(image, system, report, tag):
    """Checks every shipped form of the image needs, --image-only included.

    Nothing changes the image after these checks: the archive and installers are built from it
    as checked. On macOS that covers the bundle seal and every bundled native's signature.
    """
    if report.get("version") != tag:
        raise ValueError("Packaged version differs from requested tag")
    require_collectors(report)
    if system == "macos":
        verify_macos_signatures(image)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--sqlite", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--javafx-native", required=True, type=Path,
                        help="verified JavaFX native directory from scripts/ci/javafx_sdk.py")
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
    require_class_only_javafx(staging, args.javafx_native, system)
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
               "--java-options", f"-Dvibeminer.sqlite.library=$APPDIR/native/{native_name(system)}",
               # JavaFX natives ship in the image; class-only JARs make JavaFX load them from here.
               "--java-options", "-Djava.library.path=$APPDIR/native/javafx"]
    for option in jvm_options():
        command += ["--java-options", option]
    if system == "macos":
        command += ["--mac-package-identifier", "com.vibeminer.desktop", "--mac-package-name", "VibeMiner"]
    run(command, timeout=600)
    report = smoke(image, system, output, args.ui_smoke)
    check_image(image, system, report, args.tag)
    if args.image_only:
        print(image)
        return
    stem = f"VibeMiner-{args.tag}-{system}-{arch}"
    assets = []
    if system == "linux":
        packages, skipped = required_deb_packages(image_elves(image), arch)
        print(f"Image NEEDED sonames map to {len(packages)} declared deb packages "
              f"({skipped} ELFs for other operating systems or CPUs skipped)")
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
                resources = ROOT / "gui/build/native-packages/deb-resources"
                write_deb_control_template(resources)
                command += ["--linux-deb-maintainer", "329744803+aircan80@users.noreply.github.com",
                            "--resource-dir", resources]
            else:
                command += ["--linux-rpm-license-type", "GPLv3"]
        run(command, timeout=600)
        produced = list(destination.glob(f"*.{package_type}"))
        if len(produced) != 1:
            raise ValueError("Expected exactly one native installer")
        if package_type == "deb":
            check_deb_depends(produced[0])
        elif package_type == "rpm":
            check_rpm_requires(produced[0])
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
