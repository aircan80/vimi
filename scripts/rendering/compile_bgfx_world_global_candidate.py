"""Build/verify isolated global candidate test resources.

DX11 requires explicit opt-in until its generated artifacts are reviewed.
This does not change the production shader manifest.
"""
import argparse
from pathlib import Path
import compile_bgfx_shaders as compiler


def main():
    compiler.SOURCES = compiler.ROOT / "rendering-bgfx/src/main/shaders/world-global"
    # Narrow verifier is deliberate: default compiler/production forty stay unchanged.
    output = compiler.ROOT / "rendering-bgfx/src/test/resources/com/vibeminer/rendering/bgfx/shaders/global-candidate"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--include-dx11", action="store_true")
    parser.add_argument("--profile", action="append", choices=tuple(compiler.PROFILES))
    parser.add_argument("--tool-directory", type=Path, default=compiler.ROOT / ".local/toolchains/bgfx-3.4.3")
    args = parser.parse_args()
    if not args.include_dx11:
        compiler.PROFILES = {k: v for k, v in compiler.PROFILES.items() if k != "dx11"}
    if args.profile and any(p not in compiler.PROFILES for p in args.profile):
        parser.error("DX11 generation requires --include-dx11")
    if args.verify:
        manifest = compiler.verify(output)
    else:
        target = compiler.host()
        profiles = args.profile or (["dx11"] if target == "windows-x64" and args.include_dx11 else ["glsl", "spirv", "metal"])
        manifest = compiler.compile_profiles(args.tool_directory, target, profiles, output)
    for name, record in manifest["binaries"].items():
        sidecar = output / (name + ".sha256")
        value = record["sha256"] + "\n"
        if args.verify:
            if sidecar.read_text(encoding="ascii") != value:
                raise ValueError("Candidate runtime shader digest differs")
        else:
            sidecar.write_text(value, encoding="ascii")


if __name__ == "__main__":
    main()
