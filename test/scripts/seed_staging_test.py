import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('seed_staging', ROOT / 'scripts/seed-staging-supabase.py')
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)


class SeedSafetyTest(unittest.TestCase):
    def test_same_database_is_rejected_before_any_command(self):
        with patch.object(seed, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'Distinct'):
                seed.ownership('same', 'same')
            run.assert_not_called()

    def test_target_data_is_not_overwritten(self):
        source = dict.fromkeys(seed.TABLES, 0)
        target = dict(source, **{'public.blog_posts': 1})
        with patch('sys.argv', ['seed', '--source-service', 'source', '--target-service', 'target']), \
                patch.object(seed, 'ownership', return_value={}), \
                patch.object(seed, 'counts', side_effect=[source, target]), \
                patch.object(seed, 'sql', return_value='0') as sql, patch.object(seed, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'Target contains data'):
                seed.main()
            run.assert_not_called()
            self.assertTrue(all(call.args[1].startswith('select') for call in sql.call_args_list))

    def test_nonempty_object_storage_requires_payload_transfer(self):
        source = dict.fromkeys(seed.TABLES, 0)
        source['storage.objects'] = 1
        with patch('sys.argv', ['seed', '--source-service', 'source', '--target-service', 'target']), \
                patch.object(seed, 'ownership', return_value={}), \
                patch.object(seed, 'counts', return_value=source), patch.object(seed, 'sql') as sql:
            with self.assertRaisesRegex(RuntimeError, 'storage objects'):
                seed.main()
            sql.assert_not_called()

    def test_sql_error_does_not_echo_record_literals(self):
        from subprocess import CompletedProcess
        failure = CompletedProcess([], 1, '', "ERROR: invalid input syntax: 'private-record'\nDETAIL: sensitive row")
        with patch.object(seed.subprocess, 'run', return_value=failure):
            with self.assertRaises(RuntimeError) as caught:
                seed.run(['psql'])
            self.assertNotIn('private-record', str(caught.exception))
            self.assertNotIn('sensitive row', str(caught.exception))


if __name__ == '__main__':
    unittest.main()
