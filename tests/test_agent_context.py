"""Test portable handoff lookup and routes without contacting devices."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SKILL = ROOT / ".agents/skills/rokid-glasses"
SCRIPT = SKILL / "scripts/context.py"
spec = importlib.util.spec_from_file_location("agent_context", SCRIPT)
context = importlib.util.module_from_spec(spec)
spec.loader.exec_module(context)


class AgentContextTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.temp = Path(self.temporary.name)

    def test_all_routes_resolve_existing_repo_files_and_do_not_authorize_actions(self):
        routes = json.loads(context.MANIFEST.read_text())
        self.assertEqual(set(routes["tasks"]), {"overview", "wifi", "helper", "ota", "root", "optimize", "publish"})
        for task in routes["tasks"]:
            with self.subTest(task=task):
                result = context.build_context(ROOT, task)
                self.assertFalse(result["device_contacted"])
                self.assertFalse(result["current_device_qualified"])
                self.assertFalse(result["execution_authorized_by_skill"])
                self.assertEqual(result["historically_tested_device_count"], 1)
                for resource in result["read_files"] + result["source_files_if_needed"]:
                    self.assertTrue(Path(resource).is_relative_to(ROOT))
                    self.assertTrue(Path(resource).is_file())

    def test_auto_lookup_from_nested_source_directory(self):
        found = context.resolve_repository(cwd=ROOT / "app/src/local/syc/rokidadb", environment={})
        self.assertEqual(found, ROOT)

    def test_explicit_bad_path_is_not_silently_replaced_with_original_repo(self):
        with self.assertRaisesRegex(ValueError, "Configured path"):
            context.resolve_repository(self.temp, cwd=ROOT, environment={})

    def test_environment_override_must_also_be_valid(self):
        with self.assertRaisesRegex(ValueError, "Configured path"):
            context.resolve_repository(cwd=ROOT, environment={"ROKID_TOOLKIT_ROOT": str(self.temp)})

    def test_explicit_repo_takes_precedence_over_environment(self):
        self.assertEqual(context.resolve_repository(ROOT, environment={"ROKID_TOOLKIT_ROOT": str(self.temp)}), ROOT)

    def test_detached_skill_without_checkout_fails_with_actionable_error(self):
        with self.assertRaisesRegex(ValueError, "full repository"):
            context.resolve_repository(cwd=self.temp, skill_dir=self.temp / "installed-skill", environment={})

    def test_detached_skill_works_with_relocated_checkout(self):
        installed = self.temp / "installed-skill"
        shutil.copytree(SKILL, installed, ignore=shutil.ignore_patterns("__pycache__"))
        checkout = self.temp / "renamed-checkout"
        for relative in context.MARKERS:
            target = checkout / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        for name in ["README.md", "docs/WORKLOG.md", "docs/AGENT_USAGE.md"]:
            target = checkout / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        result = subprocess.run([sys.executable, str(installed / "scripts/context.py"),
                                 "--repo", str(checkout), "--task", "overview"],
                                cwd=self.temp, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report["repository"], str(checkout.resolve()))
        self.assertTrue(all(Path(path).is_relative_to(checkout.resolve()) for path in report["read_files"]))
        self.assertTrue(all(Path(path).is_relative_to(installed.resolve()) for path in report["skill_references_if_needed"]))

    def test_route_cannot_escape_repository(self):
        with self.assertRaisesRegex(ValueError, "out-of-repository"):
            context.repo_file(ROOT, "../outside.md")

    def test_unknown_task_is_rejected(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--task", "flash-everything"], capture_output=True)
        self.assertNotEqual(result.returncode, 0)

    def test_context_bootstrap_never_invokes_adb_or_other_external_commands(self):
        # An empty PATH proves routing needs no adb/git/ssh and launches no subprocesses.
        result = subprocess.run([sys.executable, str(SCRIPT), "--repo", str(ROOT), "--task", "ota"],
                                env={"PATH": ""}, cwd=self.temp, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(json.loads(result.stdout)["device_contacted"])


if __name__ == "__main__":
    unittest.main()
