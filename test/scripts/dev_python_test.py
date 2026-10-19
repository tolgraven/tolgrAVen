"""Exercise setup failure/retry and execution without touching global packages."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "dev_python", Path(__file__).resolve().parents[2] / "scripts/dev/python.py")
setup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(setup)


class DevelopmentPythonTest(unittest.TestCase):
    def test_healthy_environment_never_installs_or_contacts_registry(self):
        with patch.object(setup, "ready", return_value=True), \
                patch.object(setup.venv, "EnvBuilder") as builder, \
                patch.object(setup.subprocess, "run") as run:
            self.assertEqual(setup.interpreter(setup.ENVIRONMENT), setup.prepare())
            builder.assert_not_called()
            run.assert_not_called()

    def test_failed_install_is_retryable_and_does_not_run_target(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(setup, "ENVIRONMENT", Path(directory) / "python"), \
                patch.object(setup, "ready", return_value=False), \
                patch.object(setup.venv, "EnvBuilder") as builder, \
                patch.object(setup.subprocess, "run") as run:
            run.side_effect = subprocess.CalledProcessError(1, "pip")
            with self.assertRaises(subprocess.CalledProcessError):
                setup.main(["must-not-run.py"])
            self.assertEqual(1, run.call_count)
            run.side_effect = None
            setup.prepare()
            self.assertEqual(2, builder.return_value.create.call_count)
            install = run.call_args_list[1].args[0]
            self.assertIn("fonttools==4.60.2", install)
            self.assertIn("Brotli==1.2.0", install)
            self.assertIn("--no-deps", install)

    def test_execution_uses_isolated_interpreter_and_preserves_arguments_and_exit(self):
        with patch.object(setup, "prepare", return_value=Path("/local/python")), \
                patch.object(setup.subprocess, "run", return_value=subprocess.CompletedProcess([], 7)) as run:
            self.assertEqual(7, setup.main(["path with spaces.py", "one argument"]))
            run.assert_called_once_with(["/local/python", "-I", "path with spaces.py", "one argument"])


if __name__ == "__main__":
    unittest.main()
