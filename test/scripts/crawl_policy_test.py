import importlib.util
import json
import os
import subprocess
import tempfile
import threading
import unittest
from http.client import HTTPConnection
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts/ops/host' / (name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


robots = module('crawl_robots')
policy = module('crawl_policy')


class CrawlPolicyTest(unittest.TestCase):
    def apply_fixture(self, root):
        config = root / 'config.json'
        override = root / 'override.ejs'
        generated = root / 'generated'
        shared = root / 'shared'
        (generated / 'conf.d').mkdir(parents=True)
        shared.mkdir()
        original = json.dumps({'appDefinitions': {}})
        config.write_text(original)
        config.chmod(0o640)
        (generated / 'nginx.conf').write_text('http {\n}\n')
        (generated / 'conf.d/app.conf').write_text('server {\n}\n')
        (shared / 'http.conf').write_text('previous http include\n')
        args = (config, override, generated, shared, original,
                {'appDefinitions': {}, 'nginxBaseConfig': 'updated'},
                'server {\n' + policy.SERVER_INCLUDE + '\n}\n',
                'fixture-nginx', root / 'backup')
        paths = [config, override, generated / 'nginx.conf', generated / 'conf.d/app.conf',
                 shared / 'http.conf', shared / 'server.conf']
        return args, {p: policy.snapshot(p) for p in paths}

    def test_nginx_validation_failure_restores_all_files(self):
        with tempfile.TemporaryDirectory() as directory:
            args, saved = self.apply_fixture(Path(directory))
            with patch.object(policy, 'command', side_effect=RuntimeError('invalid nginx')) as command:
                with self.assertRaisesRegex(RuntimeError, 'invalid nginx'):
                    policy.apply_policy(*args)
            self.assertEqual([('docker', 'exec', 'fixture-nginx', 'nginx', '-t')],
                             [call.args for call in command.call_args_list])
            self.assertEqual(saved, {p: policy.snapshot(p) for p in saved})
            self.assertEqual(0o700, args[-1].stat().st_mode & 0o777)
            for backup in args[-1].iterdir():
                self.assertEqual(0o600, backup.stat().st_mode & 0o777)

    def test_partial_persistence_failure_restores_config_and_includes(self):
        with tempfile.TemporaryDirectory() as directory:
            args, saved = self.apply_fixture(Path(directory))
            private_write = policy.private_write

            def write(path, text):
                if path == args[1]:
                    path.write_text('partially written override')
                    raise OSError('fixture disk full')
                private_write(path, text)

            with patch.object(policy, 'command') as command, patch.object(policy, 'private_write', write):
                with self.assertRaisesRegex(OSError, 'fixture disk full'):
                    policy.apply_policy(*args)
            self.assertEqual(saved, {p: policy.snapshot(p) for p in saved})
            self.assertEqual(('docker', 'exec', 'fixture-nginx', 'nginx', '-s', 'reload'),
                             command.call_args_list[-1].args)

    def test_success_validates_before_persistence_and_keeps_bind_mount_inode(self):
        with tempfile.TemporaryDirectory() as directory:
            args, saved = self.apply_fixture(Path(directory))
            nginx_path = args[2] / 'nginx.conf'
            inode = nginx_path.stat().st_ino
            calls = []

            def command(*argv):
                calls.append(argv)
                if argv[-1] == '-t':
                    self.assertEqual(saved[args[0]][0], args[0].read_bytes())
                    self.assertFalse(args[1].exists())
                    self.assertIn(policy.HTTP_INCLUDE, nginx_path.read_text())
                return ''

            with patch.object(policy, 'command', command):
                policy.apply_policy(*args)
            self.assertEqual(inode, nginx_path.stat().st_ino)
            self.assertEqual(args[5], json.loads(args[0].read_text()))
            self.assertEqual(args[6], args[1].read_text())
            self.assertEqual(('docker', 'service', 'update', '--force', 'captain-captain'), calls[-1])

    def test_concurrent_control_plane_edit_is_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            args, saved = self.apply_fixture(Path(directory))

            def command(*_args):
                args[0].write_text('concurrent control-plane edit')
                return ''

            with patch.object(policy, 'command', command):
                with self.assertRaisesRegex(ValueError, 'configuration changed'):
                    policy.apply_policy(*args)
            self.assertEqual('concurrent control-plane edit', args[0].read_text())
            self.assertEqual({p: state for p, state in saved.items() if p != args[0]},
                             {p: policy.snapshot(p) for p in saved if p != args[0]})

    def test_failed_control_plane_restart_reinitializes_restored_templates(self):
        for recovery_failure in [False, True]:
            with self.subTest(recovery_failure=recovery_failure), tempfile.TemporaryDirectory() as directory:
                args, saved = self.apply_fixture(Path(directory))
                calls = []
                restarts = []

                def command(*argv):
                    calls.append(argv)
                    if argv[1:3] == ('service', 'update'):
                        restarts.append(argv)
                        if len(restarts) == 1:
                            raise RuntimeError('initial restart failed after replacement began')
                        self.assertEqual(saved, {p: policy.snapshot(p) for p in saved})
                        if recovery_failure:
                            raise OSError('recovery restart failed')
                    return ''

                with patch.object(policy, 'command', command):
                    with self.assertRaisesRegex(RuntimeError, 'initial restart failed') as raised:
                        policy.apply_policy(*args)
                self.assertEqual(2, len(restarts))
                self.assertEqual(('docker', 'exec', 'fixture-nginx', 'nginx', '-s', 'reload'), calls[-2])
                self.assertEqual(saved, {p: policy.snapshot(p) for p in saved})
                if recovery_failure:
                    self.assertIsInstance(raised.exception.__cause__, OSError)

    def test_coolify_plan_only_inspects_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docker = root / 'docker'
            docker.write_text('#!/bin/sh\ncase "$*" in\n*Config.Image*) echo traefik:v3.6;;\n*Gateway*) echo 172.18.0.1;;\n*) exit 99;;\nesac\n')
            docker.chmod(0o755)
            result = subprocess.run(['bash', str(ROOT / 'scripts/ops/host/setup-crawl-robots.sh')],
                                    env={**os.environ, 'PATH': str(root) + ':' + os.environ['PATH']},
                                    text=True, capture_output=True, check=True)
            self.assertIn('172.18.0.1:18082', result.stdout)
            self.assertIn('apply installs', result.stdout)

    def test_host_scope_is_suffix_bounded(self):
        for host in ['bux.tolgraven.se', 'box.tolgraven.se', 'web.bux.tolgraven.se',
                     'one.two.box.tolgraven.se', 'WEB.BUX.TOLGRAVEN.SE:443']:
            self.assertTrue(robots.private_host(host), host)
        for host in ['tolgraven.se', 'www.tolgraven.se', 'a.site.tolgraven.se',
                     'a.portfolio.tolgraven.se', 'bux.tolgraven.se.example.com',
                     'notbux.tolgraven.se', 'web.bux.tolgraven.se@evil.example']:
            self.assertFalse(robots.private_host(host), host)

    def test_real_responder_get_head_and_unrelated_paths(self):
        server = robots.RobotsServer(('127.0.0.1', 0), robots.RobotsHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            for method, host, path, status in [('GET', 'web.bux.tolgraven.se', '/robots.txt', 200),
                                               ('GET', 'one.two.box.tolgraven.se', '/robots.txt?x=1', 200),
                                               ('HEAD', 'bux.tolgraven.se', '/robots.txt', 200),
                                               ('GET', 'tolgraven.se', '/robots.txt', 404),
                                               ('GET', 'web.bux.tolgraven.se', '/api/data', 404)]:
                connection = HTTPConnection(*server.server_address, timeout=2)
                connection.request(method, path, headers={'Host': host})
                response = connection.getresponse()
                self.assertEqual(status, response.status)
                body = response.read()
                if status == 200:
                    self.assertEqual('text/plain; charset=utf-8', response.getheader('Content-Type'))
                    self.assertEqual(str(len(robots.BODY)), response.getheader('Content-Length'))
                    self.assertEqual(b'' if method == 'HEAD' else robots.BODY, body)
                connection.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_persisted_templates_preserve_other_fields_and_are_idempotent(self):
        default_base = 'http {\n include /etc/nginx/conf.d/*.conf;\n}\n'
        default_root = 'server {\n server_name _;\n}\nserver {\n server_name captain.box.tolgraven.se;\n}\n'
        custom = 'server {\n server_name <%-s.publicDomain%>;\n location / { proxy_pass $upstream; }\n}\n'
        source = {'hashedPassword': 'fixture-only', 'unknown': {'preserve': True},
                  'appDefinitions': {'private': {'customNginxConfig': custom, 'envVars': ['fixture-only']},
                                     'public': {'customDomain': ['a.site.tolgraven.se']}}}
        changed = policy.patch_config(source, default_base, default_root)
        self.assertEqual('fixture-only', changed['hashedPassword'])
        self.assertEqual(source['unknown'], changed['unknown'])
        self.assertEqual(source['appDefinitions']['public'], changed['appDefinitions']['public'])
        self.assertEqual(source['appDefinitions']['private']['envVars'], changed['appDefinitions']['private']['envVars'])
        self.assertEqual(2, changed['nginxCaptainConfig'].count(policy.SERVER_INCLUDE))
        self.assertEqual(1, changed['appDefinitions']['private']['customNginxConfig'].count(policy.SERVER_INCLUDE))
        self.assertEqual(changed, policy.patch_config(changed, default_base, default_root))
        self.assertNotIn('nginxBaseConfig', source)
        self.assertEqual(custom, source['appDefinitions']['private']['customNginxConfig'])
        self.assertEqual(1, policy.inject('http {\n}\n', 'http', policy.HTTP_INCLUDE).count(policy.HTTP_INCLUDE))
        with self.assertRaises(ValueError):
            policy.inject('not a template', 'server', policy.SERVER_INCLUDE)


if __name__ == '__main__':
    unittest.main()
