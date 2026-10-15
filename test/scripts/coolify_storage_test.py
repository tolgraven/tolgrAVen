import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('storage', Path(__file__).resolve().parents[2] / 'scripts/ops/host/coolify-storage.py')
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)


class StorageGuardsTest(unittest.TestCase):
    def test_unexpected_device_aborts_before_mutation(self):
        with patch.object(storage, 'run', return_value='another-volume'), patch.object(storage, 'save') as save:
            with self.assertRaisesRegex(RuntimeError, 'Unexpected volume'):
                storage.prepare()
            save.assert_not_called()

    def test_missing_mount_never_copies_onto_root_disk(self):
        with patch.object(storage, 'run', return_value=storage.UUID), patch.object(storage, 'mounted', return_value=False), patch.object(storage.subprocess, 'run') as operation:
            with self.assertRaisesRegex(RuntimeError, 'must be mounted'):
                storage.copy_data()
            operation.assert_not_called()

    def test_active_deployment_prevents_stopping_services(self):
        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder) / 'state.json'
            state.write_text(json.dumps({'phase': 'prepared'}))
            with patch.object(storage, 'STATE', state), patch.object(storage, 'verify_volume'), patch.object(storage, 'check_admission'), patch.object(storage, 'quiesce_admission'), patch.object(storage, 'resume_admission'), patch.object(storage.time, 'monotonic', side_effect=[0, 1801]), patch.object(storage.subprocess, 'run', return_value=subprocess.CompletedProcess('deployment probe', 42)), patch.object(storage, 'run') as operation:
                with self.assertRaisesRegex(RuntimeError, 'did not drain'):
                    storage.migrate()
                operation.assert_not_called()
                self.assertEqual(json.loads(state.read_text())['phase'], 'prepared')

    def test_partial_migration_requires_recovery_instead_of_repeating(self):
        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder) / 'state.json'
            state.write_text(json.dumps({'phase': 'copying'}))
            with patch.object(storage, 'STATE', state), patch.object(storage, 'verify_volume'), patch.object(storage, 'run') as operation:
                with self.assertRaisesRegex(RuntimeError, 'already started'):
                    storage.migrate()
                operation.assert_not_called()


if __name__ == '__main__':
    unittest.main()
