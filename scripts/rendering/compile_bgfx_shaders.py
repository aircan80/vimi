"""Build-time-only pinned bgfx shaders. Normal builds use --verify without network access."""
import argparse
import hashlib
import json
import os
import platform
import re
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
LWJGL_VERSION = "3.4.3"
BGFX_API = 156
BGFX_COMMIT = "780f7bf00a29573473b90dcfab9f92a7d8687702"
TOOL_VERSION = "shaderc, bgfx shader compiler tool, version 1.19.156."
TOOLS = {
    "linux-x64": ("linux/x64/bgfx-tools/shaderc", "10539b3c641f6cf3da04638e150a3d3aa1b7caa9508a843cee41614f8e91f200"),
    "macos-x64": ("macosx/x64/bgfx-tools/shaderc", "83f311c5560c8aa756ede028a7dbb9157b61ae101274c119919e7e33e517c4e3"),
    "macos-arm64": ("macosx/arm64/bgfx-tools/shaderc", "50a6284bfefc9f7ec8ae39e835d599e72650b1fd290ad541d5784b869d7662b3"),
    "windows-x64": ("windows/x64/bgfx-tools/shaderc.exe", "d5eb81c22d68283bda50791242312e3a34f3b71ca25be883b0ff924633525a7a"),
}
INCLUDES = {
    "bgfx_shader.sh": "5ad79a2ff5d1b1fd1f22d986f2b0b5dc49f89ffd5ce18e57966b6ab894094508",
}
PROFILES = {
    "glsl": ("linux", "330"),
    "spirv": ("linux", "spirv"),
    "metal": ("osx", "metal"),
    "dx11": ("windows", "s_5_0"),
}
SOURCES = ROOT / "rendering-bgfx/src/main/shaders"
RESOURCES = ROOT / "rendering-bgfx/src/main/resources/com/vibeminer/rendering/bgfx/shaders"
MAX_DOWNLOAD = 32 << 20
# BGFX_CONFIG_INSTANCE_DATA_FIRST_TEXCOORD in src/config.h at BGFX_COMMIT. The D3D11/D3D12 runtimes
# bind per-instance data i_dataN to TEXCOORD(31-N). The old TEXCOORD7-N convention compiles, but
# D3D11 then feeds those inputs from the per-vertex stream, so instanced draws silently vanish.
INSTANCE_DATA_FIRST_TEXCOORD = 31
INSTANCE_SEMANTIC = re.compile(r"\bi_data(\d+)\s*:\s*(\w+)")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def host():
    system = {"Linux": "linux", "Darwin": "macos", "Windows": "windows"}.get(platform.system())
    arch = {"x86_64": "x64", "amd64": "x64", "aarch64": "arm64", "arm64": "arm64"}.get(platform.machine().lower())
    value = f"{system}-{arch}"
    if value not in TOOLS:
        raise ValueError(f"No pinned shader compiler for {value}; consume committed binaries or generate them on a supported build host")
    return value


def fetch(url, expected, destination):
    if destination.is_file() and digest(destination.read_bytes()) == expected:
        return destination
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read(MAX_DOWNLOAD + 1)
    if len(data) > MAX_DOWNLOAD or digest(data) != expected:
        raise ValueError(f"Pinned shader tool/include checksum differs: {url}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".download")
    temporary.write_bytes(data)
    os.replace(temporary, destination)
    return destination


def tools(directory, target):
    path, checksum = TOOLS[target]
    compiler = fetch(f"https://build.lwjgl.org/release/{LWJGL_VERSION}/{path}", checksum,
                     directory / target / Path(path).name)
    if target != "windows-x64":
        compiler.chmod(0o755)
    reported = subprocess.check_output([compiler, "--version"], text=True).strip()
    if reported != TOOL_VERSION:
        raise ValueError("Shader compiler version differs from the pinned native API")
    include = directory / "include"
    for name, checksum in INCLUDES.items():
        fetch(f"https://raw.githubusercontent.com/LWJGL-CI/bgfx/{BGFX_COMMIT}/src/{name}", checksum, include / name)
    return compiler, include


def inputs():
    names = ("vs_model.sc", "fs_model.sc", "varying.def.sc")
    return {name: digest((SOURCES / name).read_bytes()) for name in names} | INCLUDES


def check_instance_semantics(source_directory=None):
    varying = (source_directory or SOURCES) / "varying.def.sc"
    for match in INSTANCE_SEMANTIC.finditer(varying.read_text(encoding="utf-8")):
        expected = f"TEXCOORD{INSTANCE_DATA_FIRST_TEXCOORD - int(match[1])}"
        if match[2] != expected:
            raise ValueError(f"i_data{match[1]} must use {expected} to match bgfx instance binding "
                             f"(found {match[2]}): {varying.name}")


def metadata():
    return {"format": "vimi-bgfx-shaders-v1", "lwjglVersion": LWJGL_VERSION,
            "bgfxApi": BGFX_API, "bgfxCommit": BGFX_COMMIT, "shadercVersion": TOOL_VERSION,
            "inputs": inputs()}


def verify(directory=RESOURCES):
    check_instance_semantics()
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    expected = metadata()
    if any(manifest.get(key) != value for key, value in expected.items()):
        raise ValueError("Shader manifest differs from sources, compiler version or native revision")
    binaries = manifest.get("binaries", {})
    required = {f"{profile}/{name}.bin" for profile in PROFILES for name in ("vs_model", "fs_model")}
    if set(binaries) != required:
        raise ValueError("Shader manifest must contain vertex and fragment binaries for GLSL330, SPIR-V, Metal and DX11")
    for name in sorted(required):
        record = binaries[name]
        if record.get("compilerHost") not in TOOLS or record.get("compilerSha256") != TOOLS[record["compilerHost"]][1]:
            raise ValueError(f"Unpinned compiler in shader manifest: {name}")
        profile = name.split("/")[0]
        expected_platform, expected_profile = PROFILES[profile]
        expected_stage = "vertex" if name.endswith("vs_model.bin") else "fragment"
        if (record.get("platform"), record.get("profile"), record.get("stage")) != (expected_platform, expected_profile, expected_stage):
            raise ValueError(f"Shader profile/stage differs from its manifest: {name}")
        if profile == "dx11" and record["compilerHost"] != "windows-x64":
            raise ValueError("DX11 shaders require the pinned Windows build host")
        data = (directory / name).read_bytes()
        stage = b"VSH" if name.endswith("vs_model.bin") else b"FSH"
        if len(data) < 4 or len(data) > 1 << 20 or data[:3] != stage or digest(data) != record.get("sha256"):
            raise ValueError(f"Shader blob differs from its manifest: {name}")
    return manifest


def compile_profiles(directory, target, profiles, output=RESOURCES):
    if "dx11" in profiles and target != "windows-x64":
        raise ValueError("Generate DX11 shaders on Windows with the pinned shaderc and system D3DCompiler; cross-compilation requires unbundled Wine/D3D4Linux")
    check_instance_semantics()
    compiler, include = tools(directory, target)
    result = metadata()
    prior = output / "manifest.json"
    binaries = {}
    if prior.is_file():
        previous = json.loads(prior.read_text(encoding="utf-8"))
        if all(previous.get(key) == value for key, value in result.items()):
            binaries.update(previous.get("binaries", {}))
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="vimi-bgfx-shaders-") as scratch:
        for profile in profiles:
            shader_platform, shader_profile = PROFILES[profile]
            for name, stage in [("vs_model", "vertex"), ("fs_model", "fragment")]:
                built = Path(scratch) / f"{profile}-{name}.bin"
                arguments = ["--platform", shader_platform, "--profile", shader_profile,
                             "--type", stage, "--varyingdef", str(SOURCES / "varying.def.sc"),
                             "-i", str(include), "-f", str(SOURCES / f"{name}.sc"), "-o", str(built)]
                if profile == "dx11":
                    arguments += ["-O", "3", "--Werror"]
                subprocess.run([compiler, *arguments], check=True, timeout=120)
                data = built.read_bytes()
                if len(data) > 1 << 20:
                    raise ValueError("Shader binary exceeds its asset bound")
                relative = f"{profile}/{name}.bin"
                destination = output / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
                binaries[relative] = {"sha256": digest(data), "compilerHost": target,
                                      "compilerSha256": TOOLS[target][1],
                                      "platform": shader_platform, "profile": shader_profile, "stage": stage}
    result["binaries"] = dict(sorted(binaries.items()))
    (output / "manifest.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--fetch-tools", action="store_true")
    parser.add_argument("--profile", action="append", choices=tuple(PROFILES))
    parser.add_argument("--tool-directory", type=Path, default=ROOT / ".local/toolchains/bgfx-3.4.3")
    args = parser.parse_args()
    if args.verify:
        verify()
        print("Verified eight offline bgfx shader blobs against pinned source/tool manifests")
        return
    target = host()
    if args.fetch_tools:
        compiler, include = tools(args.tool_directory, target)
        print(f"Verified {compiler} and {include}")
        return
    profiles = args.profile or (["dx11"] if target == "windows-x64" else ["glsl", "spirv", "metal"])
    compile_profiles(args.tool_directory, target, profiles)
    print("Generated " + ", ".join(profiles) + "; --verify requires all four profiles before packaging")


if __name__ == "__main__":
    main()
