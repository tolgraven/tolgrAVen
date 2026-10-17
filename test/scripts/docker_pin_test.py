import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
MANIFESTS = ['Dockerfile.builder', 'project.clj', 'package.json', 'package-lock.json', 'scripts/media/images.clj']


class PrefabCommitPin(unittest.TestCase):
    def test_pin_uses_staged_dependencies_and_preserves_unstaged_edits(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True)
            git('init', '-q')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            for name in MANIFESTS:
                p = root / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text('original ' + name + '\n')
            (root / 'Dockerfile').write_text('ARG BUILDER_IMAGE=old\nFROM ${BUILDER_IMAGE}\n')
            git('add', '.')
            git('-c', 'core.hooksPath=/dev/null', 'commit', '-qm', 'fixture')
            (root / 'project.clj').write_text('staged dependency change\n')
            git('add', 'project.clj')
            digest = hashlib.sha256()
            for name in MANIFESTS:
                digest.update((name + '\0').encode())
                digest.update(git('show', ':' + name).encode())
            (root / 'project.clj').write_text('unstaged dependency change\n')
            (root / 'Dockerfile').write_text((root / 'Dockerfile').read_text() + '# preserve working edit\n')
            subprocess.run(['bb', '--classpath', str(ROOT / 'scripts'), '-m', 'build.docker', 'pin-staged'],
                           cwd=root, check=True, capture_output=True, text=True)
            staged = git('show', ':Dockerfile')
            self.assertIn('deps-' + digest.hexdigest()[:16], staged)
            self.assertNotIn('preserve working edit', staged)
            self.assertIn('# preserve working edit', (root / 'Dockerfile').read_text())
            self.assertEqual(git('show', ':project.clj'), 'staged dependency change\n')
            self.assertEqual((root / 'project.clj').read_text(), 'unstaged dependency change\n')


if __name__ == '__main__':
    unittest.main()
