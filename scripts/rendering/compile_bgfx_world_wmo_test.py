"""WMO shader wrapper checks preserve independent recipes and offline verification."""
import sys
import unittest
from unittest.mock import patch
import compile_bgfx_shaders as compiler
import compile_bgfx_world_wmo as wmo


class WmoShaderWrapperTests(unittest.TestCase):
    def test_offline_verification_selects_only_dedicated_wmo_resources(self):
        with patch.object(compiler, "SOURCES", compiler.SOURCES):
            with patch.object(sys, "argv", ["world-wmo", "--verify"]):
                with patch.object(compiler, "verify") as verify, patch.object(compiler, "tools", side_effect=AssertionError("network")):
                    wmo.main()
                    verify.assert_called_once_with(compiler.ROOT / "rendering-bgfx/src/main/resources/com/vibeminer/rendering/bgfx/shaders/world-wmo")
                    self.assertEqual("world-wmo", compiler.SOURCES.name)

    def test_explicit_generation_passes_current_host_and_exact_profiles(self):
        with patch.object(compiler, "SOURCES", compiler.SOURCES):
            with patch.object(sys, "argv", ["world-wmo", "--profile", "spirv"]):
                with patch.object(compiler, "host", return_value="linux-x64"), patch.object(compiler, "compile_profiles") as compile_profiles:
                    wmo.main()
                    self.assertEqual(["spirv"], compile_profiles.call_args.args[2])
                    self.assertEqual("world-wmo", compile_profiles.call_args.args[3].name)


if __name__ == "__main__":
    unittest.main()
