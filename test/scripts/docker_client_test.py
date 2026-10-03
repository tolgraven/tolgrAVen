import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('docker_client', Path(__file__).resolve().parents[2] / 'scripts/docker.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class PrefabReuse(unittest.TestCase):
    def test_dependency_hash_ignores_source_edits(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(m, 'ROOT', Path(directory)):
            for name in ('Dockerfile.builder', 'project.clj', 'package.json', 'package-lock.json'):
                (m.ROOT/name).write_text(name)
            before = m.builder_tag()
            (m.ROOT/'source.cljs').write_text('new application code')
            self.assertEqual(before, m.builder_tag())
            (m.ROOT/'package-lock.json').write_text('changed dependencies')
            self.assertNotEqual(before, m.builder_tag())

    def test_published_prefab_is_not_rebuilt_or_pushed(self):
        with patch.object(m, 'builder_tag', return_value='registry/prefab:hash'), \
             patch.object(m.subprocess, 'run', return_value=subprocess.CompletedProcess([],0)), \
             patch.object(m, 'run', return_value='[{"Id":"same-image"}]') as run, \
             patch.object(m, 'registry_check'), \
             patch.object(m, 'remote_config_digest', return_value='same-image'):
            m.prefab(publish=True)
            self.assertEqual([c.args[:3] for c in run.call_args_list], [('docker','image','inspect')])

    def test_missing_local_prefab_is_pulled_instead_of_rebuilt(self):
        with patch.object(m, 'builder_tag', return_value='registry/prefab:hash'), \
             patch.object(m.subprocess, 'run', return_value=subprocess.CompletedProcess([],1)), \
             patch.object(m, 'run') as run, \
             patch.object(m, 'remote_config_digest', return_value='existing-image'):
            m.prefab()
            run.assert_called_once_with('docker','pull','registry/prefab:hash')

    def test_authentication_failure_is_not_treated_as_missing_prefab(self):
        with patch.object(m.subprocess,'run',return_value=subprocess.CompletedProcess([],1,'','unauthorized')):
            with self.assertRaises(SystemExit):
                m.remote_config_digest('registry/prefab:hash')


if __name__ == '__main__':
    unittest.main()
