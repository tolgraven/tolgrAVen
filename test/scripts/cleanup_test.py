import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[2] / 'scripts/dev'
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('cleanup', SCRIPTS / 'cleanup.py')
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)


class CleanupTests(unittest.TestCase):
    def setUp(self):
        fixture = tempfile.TemporaryDirectory()
        self.addCleanup(fixture.cleanup)
        self.root = Path(fixture.name).resolve() / 'repo'
        self.target = self.root.parent / 'worktree'
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        (self.root / '.gitignore').write_text('.local-wip/\nprivate.edn\nnode_modules/\n')
        subprocess.run(['git', '-C', str(self.root), 'add', '.gitignore'], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Fixture', '-c',
                        'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture'], check=True)
        subprocess.run(['git', '-C', str(self.root), 'worktree', 'add', '-qb',
                        'codex/fixture', str(self.target)], check=True)

    def test_linked_worktrees_share_repository_local_cache_but_not_watched_builds(self):
        self.assertEqual(cleanup.cpu.shadow_cache_root(self.root), cleanup.cpu.shadow_cache_root(self.target))
        self.assertEqual(cleanup.cpu.shadow_cache_root(self.target), self.root / '.local-wip/shadow-caches')
        watched = self.root / '.shadow-cljs/builds'; watched.mkdir(parents=True)
        (watched / 'sentinel').write_text('live watch')
        with mock.patch.object(cleanup.cpu, 'STATE', self.root / '.local-wip/state'):
            cleanup.cpu.prune_shadow_caches(checkout=self.target, all_unused=True)
        self.assertEqual((watched / 'sentinel').read_text(), 'live watch')

    def test_main_detached_dirty_and_live_worktrees_are_preserved(self):
        with self.assertRaisesRegex(RuntimeError, 'primary'):
            cleanup.registered_worktree(self.root, self.root)
        (self.target / 'unfinished.cljs').write_text('unfinished')
        with self.assertRaisesRegex(RuntimeError, 'untracked'):
            cleanup.worktree_plan(self.root, self.target)
        (self.target / 'unfinished.cljs').unlink()
        with mock.patch.object(cleanup, 'merged_task', return_value=123), \
                mock.patch.object(cleanup, 'active_cache_writers', return_value=[]), \
                mock.patch.object(cleanup, 'active_processes', return_value=[{'pid': 123}]):
            with self.assertRaisesRegex(RuntimeError, 'Processes'):
                cleanup.worktree_plan(self.root, self.target)
        subprocess.run(['git', '-C', str(self.target), 'checkout', '--detach', '-q'], check=True)
        with self.assertRaisesRegex(RuntimeError, 'Detached'):
            cleanup.registered_worktree(self.root, self.target)

    def test_active_compiler_writer_blocks_worktree_removal(self):
        with mock.patch.object(cleanup, 'merged_task', return_value=123), \
                mock.patch.object(cleanup, 'active_cache_writers', return_value=['locked']):
            with self.assertRaisesRegex(RuntimeError, 'Compiler writers'):
                cleanup.worktree_plan(self.root, self.target)

    def test_cache_parent_symlink_cannot_redirect_cleanup_outside_repository(self):
        outside = self.root.parent / 'outside'; outside.mkdir()
        (self.root / '.local-wip').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, 'escapes'):
            cleanup.cpu.shadow_cache_root(self.target)

    def test_only_the_exact_merged_head_without_open_followup_can_be_retired(self):
        old = '[{"number":1,"state":"MERGED","headRefOid":"old","mergeCommit":{"oid":"merge"}}]'
        with mock.patch.object(cleanup.subprocess, 'check_output', return_value=old):
            with self.assertRaisesRegex(RuntimeError, 'exact'):
                cleanup.merged_task(self.root, 'codex/fixture', 'new')
        opened = '[{"number":2,"state":"OPEN","headRefOid":"new","mergeCommit":null}]'
        with mock.patch.object(cleanup.subprocess, 'check_output', return_value=opened):
            with self.assertRaisesRegex(RuntimeError, 'open PR'):
                cleanup.merged_task(self.root, 'codex/fixture', 'new')

    def test_ignored_task_records_and_private_config_are_archived_without_dependencies(self):
        (self.target / '.local-wip').mkdir()
        (self.target / '.local-wip/review.json').write_text('verification')
        (self.target / 'private.edn').write_text('private config')
        (self.target / 'node_modules').mkdir()
        (self.target / 'node_modules/reproducible').write_text('dependency')
        head = cleanup.git(self.target, 'rev-parse', 'HEAD')
        archive = cleanup.archive_ignored(self.root, self.target, head)
        with tarfile.open(archive) as data:
            self.assertEqual(sorted(data.getnames()), ['.local-wip/review.json', 'private.edn'])
            self.assertEqual(data.extractfile('private.edn').read(), b'private config')
        self.assertEqual(os.stat(archive).st_mode & 0o777, 0o600)


if __name__ == '__main__':
    unittest.main()
