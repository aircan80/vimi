"""Build or verify production terrain shaders with the reviewed pinned feasibility profiles."""
import compile_bgfx_shaders as compiler
import compile_bgfx_terrain_spike as terrain


if __name__ == "__main__":
    terrain.main(compiler.ROOT / "rendering-bgfx/src/main/shaders/terrain",
                 compiler.ROOT / "rendering-bgfx/src/main/resources/com/vibeminer/rendering/bgfx/shaders/terrain")
