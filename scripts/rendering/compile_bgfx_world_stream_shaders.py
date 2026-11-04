"""Build or verify production persistent-World shaders."""
import argparse
from pathlib import Path
import compile_bgfx_shaders as compiler


def main():
    compiler.SOURCES = compiler.ROOT / "rendering-bgfx/src/main/shaders/world-stream"
    output = compiler.ROOT / "rendering-bgfx/src/main/resources/com/vibeminer/rendering/bgfx/shaders/world-stream"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--profile", action="append", choices=tuple(compiler.PROFILES))
    parser.add_argument("--tool-directory", type=Path, default=compiler.ROOT / ".local/toolchains/bgfx-3.4.3")
    args = parser.parse_args()
    if args.verify:
        compiler.verify(output)
    else:
        target = compiler.host()
        profiles = args.profile or (["dx11"] if target == "windows-x64" else ["glsl", "spirv", "metal"])
        compiler.compile_profiles(args.tool_directory, target, profiles, output)


if __name__ == "__main__":
    main()
