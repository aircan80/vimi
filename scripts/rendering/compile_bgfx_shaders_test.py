"""Offline manifest and compiler-policy checks, independent of native graphics or the network."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import compile_bgfx_shaders as shaders


class ShaderBuildTests(unittest.TestCase):
    def authored(self, directory):
        manifest = shaders.metadata()
        manifest["binaries"] = {}
        for profile in shaders.PROFILES:
            for name, stage in (("vs_model", b"VSH"), ("fs_model", b"FSH")):
                relative = f"{profile}/{name}.bin"
                data = stage + b"\x0bfixture"
                output = directory / relative
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(data)
                compiler_host = "windows-x64" if profile == "dx11" else "linux-x64"
                shader_platform, shader_profile = shaders.PROFILES[profile]
                manifest["binaries"][relative] = {"sha256": shaders.digest(data),
                    "compilerHost": compiler_host, "compilerSha256": shaders.TOOLS[compiler_host][1],
                    "platform": shader_platform, "profile": shader_profile,
                    "stage": "vertex" if name == "vs_model" else "fragment"}
        (directory / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return manifest

    def test_verify_never_downloads_and_rejects_missing_or_modified_blobs(self):
        with tempfile.TemporaryDirectory() as scratch:
            directory = Path(scratch)
            self.authored(directory)
            with patch.object(shaders.urllib.request, "urlopen", side_effect=AssertionError("network")):
                shaders.verify(directory)
                (directory / "metal/fs_model.bin").write_bytes(b"changed")
                with self.assertRaises(ValueError):
                    shaders.verify(directory)
                (directory / "metal/fs_model.bin").unlink()
                with self.assertRaises(FileNotFoundError):
                    shaders.verify(directory)

    def test_verify_requires_current_sources_all_profiles_and_pinned_compiler(self):
        with tempfile.TemporaryDirectory() as scratch:
            directory = Path(scratch)
            original = self.authored(directory)
            mutations = []
            changed = copy.deepcopy(original)
            changed["inputs"]["vs_model.sc"] = "0" * 64
            mutations.append(changed)
            changed = copy.deepcopy(original)
            del changed["binaries"]["dx11/vs_model.bin"]
            mutations.append(changed)
            changed = copy.deepcopy(original)
            changed["binaries"]["glsl/vs_model.bin"]["compilerSha256"] = "0" * 64
            mutations.append(changed)
            for manifest in mutations:
                (directory / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaises(ValueError):
                    shaders.verify(directory)

    def test_unavailable_dx11_cross_compilation_fails_before_download(self):
        with patch.object(shaders, "tools", side_effect=AssertionError("download")):
            with self.assertRaises(ValueError):
                shaders.compile_profiles(Path("unused"), "linux-x64", ["dx11"])

    def test_download_checksum_is_checked_before_execution(self):
        with tempfile.TemporaryDirectory() as scratch:
            destination = Path(scratch) / "shaderc"
            response = unittest.mock.MagicMock()
            response.__enter__.return_value.read.return_value = b"wrong tool"
            with patch.object(shaders.urllib.request, "urlopen", return_value=response):
                with self.assertRaises(ValueError):
                    shaders.fetch("https://example.invalid/shaderc", "0" * 64, destination)
            self.assertFalse(destination.exists())

    def test_instance_data_uses_bgfx_d3d_instance_texcoords(self):
        # D3D11 binds i_dataN to TEXCOORD(31-N); TEXCOORD7-N silently reads per-vertex data.
        with tempfile.TemporaryDirectory() as scratch:
            directory = Path(scratch)
            varying = directory / "varying.def.sc"
            varying.write_text("vec3 a_position : POSITION;\nvec4 i_data0 : TEXCOORD31;\n"
                               "vec4 i_data1 : TEXCOORD30;\nvec4 i_data2 : TEXCOORD29;\n", encoding="utf-8")
            shaders.check_instance_semantics(directory)
            varying.write_text("vec4 i_data0 : TEXCOORD7;\nvec4 i_data1 : TEXCOORD6;\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "TEXCOORD31"):
                shaders.check_instance_semantics(directory)

    def test_every_repository_varying_definition_uses_bgfx_instance_texcoords(self):
        checked = 0
        for varying in sorted((shaders.ROOT / "rendering-bgfx/src").glob("*/shaders/**/varying.def.sc")):
            shaders.check_instance_semantics(varying.parent)
            checked += "i_data" in varying.read_text(encoding="utf-8")
        # The terrain, spike and global-candidate families still instance; the production World
        # stream terrain is an indexed mesh (issue #373).
        self.assertGreaterEqual(checked, 3)


if __name__ == "__main__":
    unittest.main()
