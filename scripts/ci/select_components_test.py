"""Boundary cases for CI selection, including Git path handling and fallbacks."""

import contextlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch

import select_components as selection


class SelectionTest(unittest.TestCase):
    def test_documentation_changes_skip_expensive_jobs(self):
        self.assertFalse(selection.select(["README.md", "HANDOFF.md", "docs/format.md"]))
        self.assertFalse(selection.select(["object-store/README.md", "docs/example.svg", "LICENSE"]))

    def test_component_and_public_interface_changes_select_component(self):
        for path in [
            "object-store/src/main/java/com/vibeminer/store/ObjectWriter.java",
            "object-store/src/test/java/com/vibeminer/store/FormatTest.java",
            "object-store/src/testFixtures/java/Contract.java",
            "object-store/build.gradle",
            "object-store/gradle.lockfile",
            "generated-store/src/main/java/com/vibeminer/generated/GeneratedStore.java",
            "generated-store/src/testFixtures/java/com/vibeminer/generated/MemoryGeneratedStore.java",
            "generated-store/build.gradle",
            "generated-store/gradle.lockfile",
            "network-clients/src/main/java/com/vibeminer/network/CdnClient.java",
            "network-clients/src/testFixtures/java/com/vibeminer/network/MemorySources.java",
            "network-clients/build.gradle",
            "network-clients/gradle.lockfile",
            "object-access/src/main/java/com/vibeminer/access/ObjectSource.java",
            "object-access/build.gradle",
            "builds/src/main/java/com/vibeminer/builds/Builds.java",
            "builds/build.gradle",
            "files/src/main/java/com/vibeminer/files/Files.java",
            "files/build.gradle",
            "formats-tables/src/main/java/Table.java",
            "formats-tables/build.gradle",
            "formats-assets/src/main/java/com/vibeminer/formats/AssetSource.java",
            "formats-assets/src/testFixtures/java/com/vibeminer/formats/AssetFilesFixture.java",
            "formats-assets/build.gradle",
            "formats-assets/gradle.lockfile",
            "concepts-core/src/main/java/Boundary.java",
            "concepts-core/src/testFixtures/java/MemoryBoundary.java",
            "concepts-core/build.gradle",
            "concepts-core/gradle.lockfile",
            "concepts-items/src/main/java/Boundary.java",
            "concepts-items/src/testFixtures/java/MemoryBoundary.java",
            "concepts-items/build.gradle",
            "concepts-items/gradle.lockfile",
            "concepts-creatures/src/main/java/Boundary.java",
            "concepts-creatures/src/testFixtures/java/MemoryBoundary.java",
            "concepts-creatures/build.gradle",
            "concepts-creatures/gradle.lockfile",
            "concepts-effects/src/main/java/Boundary.java",
            "concepts-effects/src/testFixtures/java/MemoryBoundary.java",
            "concepts-effects/build.gradle",
            "concepts-effects/gradle.lockfile",

        ]:
            with self.subTest(path=path):
                self.assertTrue(selection.select([path]))

    def test_shared_changes_and_unknown_paths_select_broad_checks(self):
        for path in [
            "build.gradle", "settings.gradle", "gradle.properties", "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar", "config/checkstyle/checkstyle.xml",
            ".github/workflows/unit-tests.yml", "scripts/ci/select_components.py",
            "future-component/src/Thing.java", "docs/executable-example.java",
        ]:
            with self.subTest(path=path):
                self.assertTrue(selection.select([path]))

    def test_executable_directories_do_not_skip_based_on_markdown_extension(self):
        self.assertTrue(selection.select([".github/README.md"]))
        self.assertTrue(selection.select(["scripts/instructions.md"]))

    def test_mixed_documentation_and_code_runs_tests(self):
        self.assertTrue(selection.select(["README.md", "object-store/build.gradle"]))

    def test_no_changes_skip_tests(self):
        self.assertFalse(selection.select([]))

    def test_git_paths_preserve_spaces_newlines_and_deletions(self):
        with tempfile.TemporaryDirectory() as temporary:
            previous = Path.cwd()
            os.chdir(temporary)
            try:
                subprocess.run(["git", "init", "--quiet"], check=True)
                path = Path("docs/odd name\nfile.md")
                path.parent.mkdir()
                path.write_text("first", encoding="utf-8")
                base = self.commit()
                path.unlink()
                Path("code.java").write_text("code", encoding="utf-8")
                head = self.commit()
                self.assertEqual({str(path), "code.java"}, set(selection.changed_paths(base, head)))
                self.assertEqual(["code.java"], selection.changed_paths(selection.ZERO_SHA, head))
                self.assertTrue(selection.select(selection.changed_paths(base, head)))
            finally:
                os.chdir(previous)

    @staticmethod
    def commit():
        subprocess.run(["git", "add", "--all"], check=True)
        subprocess.run([
            "git", "-c", "user.name=CI Fixture", "-c", "user.email=ci@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Fixture",
        ], check=True)
        return subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()

    def test_missing_base_falls_back_to_running_tests(self):
        output = io.StringIO()
        with patch("sys.argv", ["select", "--event", "push", "--base", "missing", "--head", "a" * 40]):
            with contextlib.redirect_stdout(output):
                selection.main()
        self.assertEqual("object_store=true\n", output.getvalue())

    def test_manual_and_scheduled_dispatch_always_select_tests(self):
        for event in ["workflow_dispatch", "schedule"]:
            output = io.StringIO()
            with patch("sys.argv", ["select", "--event", event, "--head", "a" * 40]):
                with contextlib.redirect_stdout(output):
                    selection.main()
            self.assertEqual("object_store=true\n", output.getvalue())


class RequiredGateTest(unittest.TestCase):
    def test_actual_workflow_gate_accepts_only_success_or_documentation_skip(self):
        workflows = Path(__file__).resolve().parents[2] / ".github/workflows"
        for workflow in ["unit-tests.yml", "integration-tests.yml"]:
            self.check_gate(workflows / workflow)

    def check_gate(self, workflow):
        gate = textwrap.dedent(workflow.read_text(encoding="utf-8").split(
            "      - name: Require selected platforms to pass", 1
        )[1].split("        run: |\n", 1)[1])
        for selected in ["true", "false", ""]:
            for selection_status in ["success", "failure", "cancelled", "skipped"]:
                for unit_status in ["success", "failure", "cancelled", "skipped"]:
                    expected = selection_status == "success" and (
                        (selected == "true" and unit_status == "success")
                        or (selected == "false" and unit_status == "skipped")
                    )
                    with self.subTest(selected=selected, selection=selection_status, unit=unit_status):
                        result = subprocess.run(["bash", "-eo", "pipefail", "-c", gate], env={
                            **os.environ, "OBJECT_STORE": selected,
                            "SELECTION_RESULT": selection_status, "UNIT_RESULT": unit_status,
                            "INTEGRATION_RESULT": unit_status,
                        })
                        self.assertEqual(expected, result.returncode == 0)


class BroaderGateTest(unittest.TestCase):
    def test_crash_and_interop_must_both_pass_or_both_be_deliberately_skipped(self):
        workflow = Path(__file__).resolve().parents[2] / ".github/workflows/storage-compatibility.yml"
        gate = textwrap.dedent(workflow.read_text(encoding="utf-8").split(
            "      - name: Require selected broader suites to pass", 1
        )[1].split("        run: |\n", 1)[1])
        for selected in ["true", "false", ""]:
            for selection_status in ["success", "failure", "cancelled", "skipped"]:
                for crash in ["success", "failure", "cancelled", "skipped"]:
                    for interop in ["success", "failure", "cancelled", "skipped"]:
                        expected = selection_status == "success" and (
                            (selected == "true" and crash == interop == "success")
                            or (selected == "false" and crash == interop == "skipped")
                        )
                        with self.subTest(selected=selected, selection=selection_status,
                                          crash=crash, interop=interop):
                            result = subprocess.run(["bash", "-eo", "pipefail", "-c", gate], env={
                                **os.environ, "OBJECT_STORE": selected,
                                "SELECTION_RESULT": selection_status,
                                "CRASH_RESULT": crash, "INTEROP_RESULT": interop,
                            })
                            self.assertEqual(expected, result.returncode == 0)


class AssetWorkflowWiringTest(unittest.TestCase):
    def test_assets_run_in_each_selected_suite_and_retain_reports(self):
        root = Path(__file__).resolve().parents[2]
        for name, task, report in [
            ("unit-tests.yml", ":formats-assets:test", "formats-assets/build/test-results/test/"),
            ("integration-tests.yml", ":formats-assets:check", "formats-assets/build/test-results/"),
            ("storage-compatibility.yml", ":formats-assets:interopTest", "formats-assets/build/test-results/interopTest/"),
        ]:
            text = (root / ".github/workflows" / name).read_text(encoding="utf-8")
            with self.subTest(workflow=name):
                self.assertIn(task, text)
                self.assertIn(report, text)

    def test_asset_reference_checkout_contains_original_sources_and_exact_fixtures(self):
        root = Path(__file__).resolve().parents[2]
        text = (root / ".github/workflows/storage-compatibility.yml").read_text(encoding="utf-8")
        for path in [
            "/src/backend/VibeMiner.Formats/",
            "/tests/backend/VibeMiner.Formats.Testing/ModelFixtures.cs",
            "/tests/backend/VibeMiner.Formats.Tests/Models/AppearanceModels.cs",
        ]:
            self.assertIn(path, text)


class ConceptWorkflowWiringTest(unittest.TestCase):
    PORTED_MODULES = ("concepts-core", "concepts-items", "concepts-creatures", "concepts-effects")
    MODULES = PORTED_MODULES + ("concepts-talents",)

    def test_concepts_run_and_retain_reports_in_every_selected_suite(self):
        root = Path(__file__).resolve().parents[2]
        for workflow, task, report in [
            ("unit-tests.yml", "test", "test-results/test/"),
            ("integration-tests.yml", "check", "test-results/"),
            ("storage-compatibility.yml", "interopTest", "test-results/interopTest/"),
        ]:
            text = (root / ".github/workflows" / workflow).read_text(encoding="utf-8")
            modules = self.PORTED_MODULES if task == "interopTest" else self.MODULES
            for module in modules:
                with self.subTest(workflow=workflow, module=module):
                    self.assertIn(f":{module}:{task}", text)
                    self.assertIn(f"{module}/build/{report}", text)
                    self.assertIn(f"{module}/build/reports/", text)

    def test_root_lifecycle_and_helper_scripts_include_all_concepts(self):
        root = Path(__file__).resolve().parents[2]
        settings = (root / "settings.gradle").read_text(encoding="utf-8")
        lifecycle = (root / "build.gradle").read_text(encoding="utf-8")
        checks = (root / "scripts/check.sh").read_text(encoding="utf-8")
        interop = (root / "scripts/interop.sh").read_text(encoding="utf-8")
        for module in self.MODULES:
            with self.subTest(module=module):
                self.assertIn(f"include '{module}'", settings)
                self.assertIn(f"':{module}:check'", lifecycle)
                self.assertIn(f"':{module}:assemble'", lifecycle)
                self.assertIn(f":{module}:test", checks)
                if module in self.PORTED_MODULES:
                    self.assertIn(f":{module}:interopTest", interop)

    def test_concept_reference_sources_and_release_assembly_are_available(self):
        root = Path(__file__).resolve().parents[2]
        text = (root / ".github/workflows/storage-compatibility.yml").read_text(encoding="utf-8")
        self.assertIn("/src/backend/VibeMiner.Concepts/", text)
        self.assertIn("/tests/backend/VibeMiner.Formats.Testing/InMemoryTables.cs", text)
        self.assertIn("dotnet build ../vibeminer/src/backend/VibeMiner.Concepts/VibeMiner.Concepts.csproj --configuration Release", text)


if __name__ == "__main__":
    unittest.main()
