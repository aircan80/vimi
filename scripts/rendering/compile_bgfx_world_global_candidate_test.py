"""Global candidate shader wrapper checks keep DX11 opt-in and production resources untouched."""
import sys
import unittest
from unittest.mock import patch
import compile_bgfx_shaders as compiler
import compile_bgfx_world_global_candidate as candidate

CANDIDATE = compiler.ROOT / "rendering-bgfx/src/test/resources/com/vibeminer/rendering/bgfx/shaders/global-candidate"


def run(*arguments):
    with patch.object(sys, "argv", ["world-global-candidate", *arguments]):
        candidate.main()


class GlobalCandidateShaderWrapperTests(unittest.TestCase):
    def setUp(self):
        patches = [patch.object(compiler, "SOURCES", compiler.SOURCES),
                   patch.object(compiler, "PROFILES", dict(compiler.PROFILES)),
                   patch.object(compiler, "tools", side_effect=AssertionError("network"))]
        for item in patches:
            item.start()
            self.addCleanup(item.stop)

    def test_default_verification_checks_committed_six_binaries_without_dx11(self):
        run("--verify")
        self.assertNotIn("dx11", compiler.PROFILES)
        self.assertEqual("world-global", compiler.SOURCES.name)

    def test_dx11_verification_requires_generated_dx11_binaries(self):
        with self.assertRaises(ValueError):
            run("--include-dx11", "--verify")

    def test_dx11_generation_without_opt_in_is_rejected(self):
        with patch.object(compiler, "compile_profiles") as compile_profiles, self.assertRaises(SystemExit):
            run("--profile", "dx11")
        compile_profiles.assert_not_called()

    def test_windows_default_generation_selects_dx11_only_with_opt_in(self):
        for arguments, expected in ((("--include-dx11",), ["dx11"]), ((), ["glsl", "spirv", "metal"])):
            with self.subTest(arguments=arguments):
                with patch.object(compiler, "host", return_value="windows-x64"), \
                        patch.object(compiler, "compile_profiles", return_value={"binaries": {}}) as compile_profiles:
                    run(*arguments)
                self.assertEqual(expected, compile_profiles.call_args.args[2])
                self.assertEqual(CANDIDATE, compile_profiles.call_args.args[3])

    def test_sidecar_digest_mismatch_fails_verification(self):
        manifest = {"binaries": {"glsl/vs_model.bin": {"sha256": "0" * 64}}}
        with patch.object(compiler, "verify", return_value=manifest), self.assertRaises(ValueError):
            run("--verify")


if __name__ == "__main__":
    unittest.main()
