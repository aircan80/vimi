"""Build/verify only isolated global candidate GLSL/SPIR-V/Metal test resources.

DX11 is explicitly absent. This is not a default production 48-blob manifest.
"""
import argparse
from pathlib import Path
import compile_bgfx_shaders as compiler


def main():
    compiler.SOURCES = compiler.ROOT / "rendering-bgfx/src/main/shaders/world-global"
    # Narrow verifier is deliberate: default compiler/production forty stay unchanged.
    compiler.PROFILES = {k: v for k, v in compiler.PROFILES.items() if k != "dx11"}
    output = compiler.ROOT / "rendering-bgfx/src/test/resources/com/vibeminer/rendering/bgfx/shaders/global-candidate"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--tool-directory", type=Path, default=compiler.ROOT / ".local/toolchains/bgfx-3.4.3")
    args = parser.parse_args()
    if args.verify:
        manifest = compiler.verify(output)
    else:
        manifest = compiler.compile_profiles(args.tool_directory, compiler.host(), list(compiler.PROFILES), output)
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
