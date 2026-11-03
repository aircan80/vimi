"""The authored feasibility tool must never overwrite production shader resources."""
import unittest
from unittest.mock import patch
import compile_bgfx_terrain_spike as spike


class TerrainShaderIsolationTest(unittest.TestCase):
    def test_generation_passes_separate_output_explicitly(self):
        compiler = spike.compiler
        production = compiler.RESOURCES
        output = compiler.ROOT / "rendering-bgfx/src/test/resources/com/vibeminer/rendering/bgfx/terrain-spike"
        with patch.object(compiler, "SOURCES"), patch.object(compiler, "host", return_value="linux-x64"), patch.object(compiler, "compile_profiles") as build, patch("sys.argv", ["terrain-spike"]):
            spike.main()
            self.assertEqual([call.args[3] for call in build.call_args_list], [output, output / "sky"])
            self.assertEqual(compiler.SOURCES, compiler.ROOT / "rendering-bgfx/src/test/shaders/terrain-spike/sky")
        self.assertEqual(compiler.RESOURCES, production)

    def test_verification_passes_separate_output_explicitly(self):
        compiler = spike.compiler
        output = compiler.ROOT / "rendering-bgfx/src/test/resources/com/vibeminer/rendering/bgfx/terrain-spike"
        with patch.object(compiler, "SOURCES"), patch.object(compiler, "verify") as verify, patch("sys.argv", ["terrain-spike", "--verify"]):
            spike.main()
        self.assertEqual([call.args[0] for call in verify.call_args_list], [output, output / "sky"])
