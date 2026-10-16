import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('session_metadata', Path(__file__).parents[2] / 'scripts/dev/session_metadata.py')
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)
ID = '01234567-89ab-cdef-0123-456789abcdef'
OTHER = '11234567-89ab-cdef-0123-456789abcdef'


class SessionMetadataTest(unittest.TestCase):
    def test_names_directory_history_and_prompt_exclusion(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            sessions = home / 'sessions'
            sessions.mkdir()
            rollout = sessions / 'own.jsonl'
            events = [
                {'type': 'session_meta', 'payload': {'cwd': '/repo'}},
                {'type': 'turn_context', 'payload': {'cwd': '/work/first'}},
                {'type': 'response_item', 'payload': {'type': 'function_call', 'name': 'exec_command', 'arguments': json.dumps({'workdir': '/work/second'})}},
                {'type': 'response_item', 'payload': {'type': 'custom_tool_call', 'name': 'exec', 'input': 'await tools.exec_command({"workdir":"/work/retired","cmd":"true"})'}},
                {'type': 'response_item', 'payload': {'type': 'message', 'content': 'SECRET /work/unrelated'}},
            ]
            rollout.write_text('\n'.join(json.dumps(e) for e in events))
            (home / 'session_index.jsonl').write_text('\n'.join(json.dumps(e) for e in [
                {'id': ID, 'thread_name': 'Former name'},
                {'id': OTHER, 'thread_name': 'UNRELATED'},
                {'id': ID, 'thread_name': 'Current name'},
            ]))
            with sqlite3.connect(home / 'state_5.sqlite') as db:
                db.execute('create table threads (id text, name text, title text, preview text, first_user_message text, cwd text, rollout_path text)')
                db.execute('insert into threads values (?,?,?,?,?,?,?)', (ID, 'Newest name', 'SECRET', 'SECRET', 'SECRET', '/repo', str(rollout)))
            result = metadata.metadata(home, [ID])
            self.assertEqual(result[ID]['title'], 'Newest name')
            self.assertEqual(result[ID]['titles'], ['Former name', 'Current name', 'Newest name'])
            self.assertEqual(result[ID]['worktrees'], ['/repo', '/work/first', '/work/second', '/work/retired'])
            self.assertNotIn('SECRET', json.dumps(result))
            self.assertNotIn('UNRELATED', json.dumps(result))

    def test_missing_or_incompatible_local_state_keeps_index(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / 'session_index.jsonl').write_text('broken\n' + json.dumps({'id': ID, 'thread_name': 'Recorded name'}))
            (home / 'state_5.sqlite').write_text('broken')
            self.assertEqual(metadata.metadata(home, [ID])[ID]['title'], 'Recorded name')
