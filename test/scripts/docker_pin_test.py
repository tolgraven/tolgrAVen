import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
MANIFESTS = ['Dockerfile.builder', 'package.json', 'package-lock.json']


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
            (root / 'Dockerfile.builder').write_text('ARG TOOLCHAIN_IMAGE=old\nFROM ${TOOLCHAIN_IMAGE}\n')
            (root / 'Dockerfile.toolchain').write_text('FROM scratch AS toolchain\n')
            (root / 'Dockerfile').write_text('ARG BUILDER_IMAGE=old\nFROM ${BUILDER_IMAGE}\n')
            git('add', '.')
            git('-c', 'core.hooksPath=/dev/null', 'commit', '-qm', 'fixture')
            (root / 'package-lock.json').write_text('staged dependency change\n')
            git('add', 'package-lock.json')
            (root / 'package-lock.json').write_text('unstaged dependency change\n')
            (root / 'Dockerfile').write_text((root / 'Dockerfile').read_text() + '# preserve working edit\n')
            (root / 'Dockerfile.builder').write_text((root / 'Dockerfile.builder').read_text() + '# preserve builder edit\n')
            subprocess.run(['bb', '--classpath', str(ROOT / 'scripts'), '-m', 'build.docker', 'pin-staged'],
                           cwd=root, check=True, capture_output=True, text=True)
            staged = git('show', ':Dockerfile')
            digest = hashlib.sha256()
            for name in MANIFESTS:
                digest.update((name + '\0').encode())
                digest.update(git('show', ':' + name).encode())
            self.assertIn('deps-' + digest.hexdigest()[:16], staged)
            self.assertNotIn('preserve working edit', staged)
            self.assertIn('# preserve working edit', (root / 'Dockerfile').read_text())
            self.assertIn('# preserve builder edit', (root / 'Dockerfile.builder').read_text())
            self.assertNotIn('preserve builder edit', git('show', ':Dockerfile.builder'))
            self.assertEqual(git('show', ':package-lock.json'), 'staged dependency change\n')
            self.assertEqual((root / 'package-lock.json').read_text(), 'unstaged dependency change\n')

    def test_node_and_maven_changes_do_not_rebuild_infrastructure(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in [*MANIFESTS, 'Dockerfile', 'Dockerfile.toolchain']:
                (root / name).write_bytes((ROOT / name).read_bytes())
            def pins():
                subprocess.run(['bb', '--classpath', str(ROOT / 'scripts'), '-m', 'build.docker', 'pin'],
                               cwd=root, check=True, capture_output=True)
                return ((root / 'Dockerfile.builder').read_text().split('ARG TOOLCHAIN_IMAGE=')[1].splitlines()[0],
                        (root / 'Dockerfile').read_text().split('ARG BUILDER_IMAGE=')[1].splitlines()[0])
            original = pins()
            (root / 'project.clj').write_text('changed application Maven dependencies')
            (root / 'scripts/media').mkdir(parents=True)
            (root / 'scripts/media/images.clj').write_text('changed application converter')
            self.assertEqual(pins(), original)
            (root / 'package-lock.json').write_text('changed npm dependencies')
            node = pins()
            self.assertEqual(node[0], original[0])
            self.assertNotEqual(node[1], original[1])
            with (root / 'Dockerfile.toolchain').open('a') as file:
                file.write('\nRUN echo changed system packages\n')
            infra = pins()
            self.assertNotEqual(infra[0], node[0])
            self.assertNotEqual(infra[1], node[1])


if __name__ == '__main__':
    unittest.main()
