import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('prefab_gate', ROOT / 'scripts/ops/host/prefab_gate.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def pull_request(action='synchronize'):
    return {'repository': {'full_name': gate.REPOSITORY}, 'number': 201, 'action': action,
            'pull_request': {'base': {'ref': 'master'}, 'head': {'sha': 'a' * 40,
                'repo': {'full_name': gate.REPOSITORY, 'fork': False}}, 'author_association': 'OWNER'}}


class GateFlow(unittest.TestCase):
    def test_tracked_symlink_cannot_redirect_the_generated_smoke_recipe(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checkout = root / 'checkout'
            checkout.mkdir()
            protected = root / 'trusted-adapter.php'
            protected.write_text('trusted control code')
            (checkout / 'prefab-check.Dockerfile').symlink_to(protected)
            def inspect_command(args, **kwargs):
                generated = Path(args[args.index('-f') + 1])
                self.assertEqual(generated.parent, root)
                self.assertIn(b'FROM media-tools', generated.read_bytes())
            with patch.object(gate, 'ROOT', root), patch.object(gate, 'read_blob', return_value=b'FROM scratch\n'), \
                    patch.object(gate, 'command', side_effect=inspect_command):
                gate.verify_runtime(checkout, 'fixture:image', 'arm64', None)
            self.assertEqual(protected.read_text(), 'trusted control code')
            self.assertEqual(list(root.glob('runtime-*.Dockerfile')), [])

    def test_forks_untrusted_associations_and_other_branches_never_build(self):
        self.assertEqual(gate.event_target('pull_request', pull_request())['sha'], 'a' * 40)
        for field, value in [('author_association', 'CONTRIBUTOR'), ('base', {'ref': 'other'}),
                             ('head', {'sha': 'a' * 40, 'repo': {'full_name': 'attacker/repo', 'fork': True}})]:
            payload = pull_request()
            payload['pull_request'][field] = value
            self.assertIsNone(gate.event_target('pull_request', payload))
        payload = pull_request()
        payload['pull_request']['head']['sha'] = 'main; shell-command'
        with self.assertRaises(ValueError):
            gate.event_target('pull_request', payload)

    def test_real_signed_http_delivery_survives_restart_and_deduplicates(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(gate, 'ROOT', Path(directory)), \
                patch.dict(os.environ, {'WEBHOOK_SECRET': 'fixture-secret'}):
            gate.initialize()
            server = gate.ThreadingHTTPServer(('127.0.0.1', 0), gate.Handler)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                body = json.dumps(pull_request()).encode()
                headers = {'X-GitHub-Event': 'pull_request', 'X-GitHub-Delivery': 'fixture-delivery',
                    'Content-Type': 'application/json', 'X-Hub-Signature-256': 'sha256=' +
                    hmac.new(b'fixture-secret', body, hashlib.sha256).hexdigest()}
                url = 'http://127.0.0.1:' + str(server.server_port) + '/github'
                for _ in range(2):
                    with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=headers)) as response:
                        self.assertEqual(response.status, 202)
                gate.initialize()
                with gate.database() as db:
                    self.assertEqual(db.execute('SELECT count(*) FROM jobs').fetchone()[0], 1)
                    self.assertEqual(db.execute('SELECT body FROM jobs').fetchone()[0], body)
                headers['X-Hub-Signature-256'] = 'sha256=invalid'
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(urllib.request.Request(url, data=body, headers=headers))
                self.assertEqual(error.exception.code, 401)
                error.exception.close()
                with gate.database() as db:
                    self.assertEqual(db.execute('SELECT count(*) FROM jobs').fetchone()[0], 1)
            finally:
                server.shutdown()
                server.server_close()

    def test_close_and_stale_jobs_cannot_build_or_recreate_previews(self):
        closed = pull_request('closed')
        job = {'event': 'pull_request', 'body': json.dumps(closed).encode(),
               'target': json.dumps(gate.event_target('pull_request', closed))}
        with patch.object(gate, 'prefab') as build, patch.object(gate, 'forward') as forward:
            gate.process(job)
            build.assert_not_called()
            forward.assert_called_once_with('pull_request', job['body'], manual=True)
        job['target'] = json.dumps(gate.event_target('pull_request', pull_request()))
        with patch.object(gate, 'current', return_value=False), patch.object(gate, 'prefab') as build, \
                patch.object(gate, 'forward') as forward, patch.object(gate, 'status') as status:
            gate.process(job)
            build.assert_not_called()
            forward.assert_not_called()
            status.assert_not_called()

    def test_failed_verification_never_reports_success_or_dispatches_deployment(self):
        job = {'id': 1, 'event': 'pull_request', 'body': b'{}',
               'target': json.dumps(gate.event_target('pull_request', pull_request()))}
        with tempfile.TemporaryDirectory() as directory, patch.object(gate, 'ROOT', Path(directory)), \
                patch.object(gate, 'current', return_value=True), patch.object(gate, 'command'), \
                patch.object(gate, 'prefab', side_effect=RuntimeError('codec failure')), \
                patch.object(gate, 'status') as status, patch.object(gate, 'forward') as forward:
            gate.initialize()
            with self.assertRaisesRegex(RuntimeError, 'codec failure'):
                gate.process(job)
            forward.assert_not_called()
            self.assertEqual([call.args[1] for call in status.call_args_list], ['pending'])

    def test_gate_hash_agrees_with_bb_and_rejects_an_uncommitted_pin(self):
        # Exercise actual Git blobs and the independently implemented BB pin.
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            subprocess.run(['git', 'init', '-q', directory], check=True)
            for file in [*gate.INPUTS, 'Dockerfile']:
                target = path / file
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / file).read_bytes())
            subprocess.run(['git', '-C', directory, 'add', '.'], check=True)
            subprocess.run(['git', '-C', directory, '-c', 'core.hooksPath=/dev/null',
                '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                'commit', '-qm', 'fixture'], check=True)
            expected = subprocess.check_output(['bb', '--classpath', str(ROOT / 'scripts'),
                '-m', 'build.docker', 'check-pin'], cwd=path, text=True).strip()
            self.assertEqual(gate.checked_image(directory), expected)
            (path / 'project.clj').write_text('changed')
            # Working tree edits must not change the fetched commit's pin.
            self.assertEqual(gate.checked_image(directory), expected)
            (path / '.dockerignore').symlink_to(ROOT / 'Dockerfile')
            with self.assertRaisesRegex(ValueError, 'regular checkout files'):
                gate.checked_image(directory)


if __name__ == '__main__':
    unittest.main()
