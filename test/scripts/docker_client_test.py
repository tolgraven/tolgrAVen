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


class DeployScopeTest(unittest.TestCase):
    def test_fork_cannot_deploy_over_tolgraven(self):
        with patch.object(m, 'run', return_value='https://github.com/example/new-site.git'):
            with self.assertRaisesRegex(SystemExit, 'scoped to tolgraven'):
                m.require_tolgraven_checkout()


class CleanupSafetyTest(unittest.TestCase):
    def image(self, identity, age, *tags):
        return {'Id': identity, 'Created': age, 'RepoTags': list(tags)}

    def test_preserves_containers_rollback_prefab_cms_and_unrelated_tags(self):
        repo = m.REGISTRY + '/tolgraven/'
        images = [self.image('latest', '9', repo+'site:new'),
                  self.image('previous', '8', repo+'site:previous'),
                  self.image('old', '7', repo+'site:old', 'another-project:saved'),
                  self.image('used', '1', repo+'site:used'),
                  self.image('prefab', '5', repo+'builder:current'),
                  self.image('old-prefab', '4', 'tolgraven-builder:local'),
                  self.image('cms', '5', repo+'strapi:v2'),
                  self.image('old-cms', '3', repo+'strapi:v1')]
        self.assertEqual(set(m.image_cleanup_plan(images, {'used'}, 'prefab', 'latest')),
                         {repo+'site:old', 'tolgraven-builder:local', repo+'strapi:v1'})

    def test_missing_current_prefab_retains_builders_and_explicit_current(self):
        repo = m.REGISTRY + '/tolgraven/'
        images = [self.image('one', '9', repo+'site:one'), self.image('two', '8', repo+'site:two'),
                  self.image('current', '1', repo+'site:current'),
                  self.image('builder', '1', repo+'builder:only')]
        self.assertEqual([], m.image_cleanup_plan(images, set(), current_id='current'))


if __name__ == '__main__':
    unittest.main()
