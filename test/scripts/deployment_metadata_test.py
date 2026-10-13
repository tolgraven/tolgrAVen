import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class DeploymentMetadata(unittest.TestCase):
    def test_caprover_identity_and_restart_preserve_timestamp(self):
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / 'deployment.json'
            env = dict(os.environ, DEPLOY_FILE=str(file),
                       DEPLOY_REVISION_FILE=str(Path(temp) / 'missing-revision'),
                       CAPROVER_GIT_COMMIT_SHA='a' * 40,
                       DEPLOY_REPOSITORY='another/site')
            command = ['sh', str(ROOT / 'docker-entrypoint.sh'), 'echo', 'started']
            self.assertEqual(subprocess.run(command, env=env, capture_output=True,
                                           text=True, check=True).stdout, 'started\n')
            first = file.read_bytes()
            value = json.loads(first)
            self.assertEqual(value['commit'], 'a' * 40)
            self.assertEqual(value['repository'], 'another/site')
            self.assertRegex(value['deployed-at'], r'^\d{4}-\d{2}-\d{2}T.*Z$')
            env['CAPROVER_GIT_COMMIT_SHA'] = 'b' * 40
            subprocess.run(command, env=env, check=True, capture_output=True)
            self.assertEqual(file.read_bytes(), first)

    def test_prebuilt_image_identity_wins_over_coolify_checkout(self):
        with tempfile.TemporaryDirectory() as temp:
            image = Path(temp) / 'image'
            runtime = Path(temp) / 'runtime'
            image.mkdir()
            runtime.mkdir()
            file = runtime / 'deployment.json'
            revision = image / 'revision'
            revision.write_text('c' * 40)
            (runtime / 'revision').write_text('e' * 40)
            subprocess.run(['sh', str(ROOT / 'docker-entrypoint.sh'), 'true'],
                           env=dict(os.environ, DEPLOY_FILE=str(file),
                                    DEPLOY_REVISION_FILE=str(revision), SOURCE_COMMIT='d' * 40),
                           check=True, capture_output=True)
            self.assertEqual(json.loads(file.read_text())['commit'], 'c' * 40)

    def test_coolify_runtime_identity_without_a_baked_revision(self):
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / 'deployment.json'
            subprocess.run(['sh', str(ROOT / 'docker-entrypoint.sh'), 'true'],
                           env=dict(os.environ, DEPLOY_FILE=str(file),
                                    DEPLOY_REVISION_FILE=str(Path(temp) / 'missing-revision'),
                                    CAPROVER_GIT_COMMIT_SHA='', SOURCE_COMMIT='d' * 40),
                           check=True, capture_output=True)
            self.assertEqual(json.loads(file.read_text())['commit'], 'd' * 40)

    def test_invalid_identity_cannot_inject_json(self):
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / 'deployment.json'
            subprocess.run(['sh', str(ROOT / 'docker-entrypoint.sh'), 'true'],
                           env=dict(os.environ, DEPLOY_FILE=str(file),
                                    DEPLOY_REVISION_FILE=str(Path(temp) / 'missing-revision'),
                                    DEPLOY_COMMIT='bad"sha', DEPLOY_REPOSITORY='bad"repo'),
                           check=True, capture_output=True)
            self.assertEqual(json.loads(file.read_text())['commit'], 'unknown')

if __name__ == '__main__':
    unittest.main()
