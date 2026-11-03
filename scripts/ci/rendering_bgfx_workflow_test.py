"""The native rendering component must participate in normal and packaged validation."""
from pathlib import Path
import unittest

import select_components


ROOT = Path(__file__).resolve().parents[2]


class NativeRenderingWorkflowTest(unittest.TestCase):
    def test_shader_or_native_manifest_changes_select_all_current_platform_checks(self):
        for path in [
            "rendering-bgfx/src/main/shaders/vs_model.sc",
            "rendering-bgfx/src/main/resources/com/vibeminer/rendering/bgfx/shaders/dx11/vs_model.bin",
            "rendering-bgfx/lwjgl-runtime.json",
        ]:
            with self.subTest(path=path):
                self.assertTrue(select_components.select([path]))
        self.assertFalse(select_components.select(["rendering-bgfx/README.md"]))

    def test_existing_native_matrices_include_module_tasks_and_retain_reports(self):
        for workflow, task in [("unit-tests.yml", "test"), ("integration-tests.yml", "check")]:
            contents = (ROOT / ".github/workflows" / workflow).read_text(encoding="utf-8")
            with self.subTest(workflow=workflow):
                self.assertIn(f":rendering-bgfx:{task}", contents)
                self.assertIn("rendering-bgfx/build/reports/", contents)
                self.assertIn("rendering-bgfx/build/test-results/", contents)

    def test_packaging_runs_root_checks_that_include_native_rendering(self):
        lifecycle = (ROOT / "build.gradle").read_text(encoding="utf-8")
        self.assertIn("':rendering-bgfx:check'", lifecycle)
        self.assertIn("':rendering-bgfx:assemble'", lifecycle)
        packaging = (ROOT / ".github/workflows/desktop-prereleases.yml").read_text(encoding="utf-8")
        self.assertIn("./gradlew check :gui:installDist", packaging)


if __name__ == "__main__":
    unittest.main()
