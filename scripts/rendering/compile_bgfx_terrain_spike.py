"""Build or verify authored terrain-spike shaders with the existing pinned toolchain."""
import argparse
from pathlib import Path
import compile_bgfx_shaders as compiler


def main(source_directory=None, output_directory=None):
    compiler.SOURCES = source_directory or compiler.ROOT / "rendering-bgfx/src/test/shaders/terrain-spike"
    output = output_directory or compiler.ROOT / "rendering-bgfx/src/test/resources/com/vibeminer/rendering/bgfx/terrain-spike"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--profile", action="append", choices=tuple(compiler.PROFILES))
    parser.add_argument("--tool-directory", type=Path, default=compiler.ROOT / ".local/toolchains/bgfx-3.4.3")
    args = parser.parse_args()
    sources = compiler.SOURCES
    for relative in ("", "sky"):
        compiler.SOURCES = sources / relative
        if args.verify:
            compiler.verify(output / relative)
        else:
            target = compiler.host()
            profiles = args.profile or (["dx11"] if target == "windows-x64" else ["glsl", "spirv", "metal"])
            compiler.compile_profiles(args.tool_directory, target, profiles, output / relative)
    print("Verified terrain and sky shader profiles" if args.verify else "Generated terrain and sky shader profiles")


if __name__ == "__main__":
    main()
