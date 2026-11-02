"""Opt-in Docker/Registry 3 check; uses only an isolated loopback registry."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('prefab_gate', ROOT / 'scripts/ops/host/prefab_gate.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


@unittest.skipUnless(os.environ.get('RUN_PREFAB_REGISTRY_TEST') == '1',
                     'Set RUN_PREFAB_REGISTRY_TEST=1 after pulling registry:3')
class RegistryPromotion(unittest.TestCase):
    def test_oci_images_replace_legacy_aliases_without_deletion_or_stale_platforms(self):
        with tempfile.TemporaryDirectory(prefix='prefab-registry-client-') as directory:
            environment = dict(os.environ)
            for key in ('DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS',
                        'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'):
                environment.pop(key, None)
            # No external requests or credentials: every fixture uses loopback.
            environment['DOCKER_CONFIG'] = directory
            docker = ['docker', '--host=unix:///var/run/docker.sock']

            def run(*args, check=True):
                return subprocess.run([*docker, *args], env=environment,
                                      capture_output=True, text=True, check=check)

            name = 'codex-prefab-registry-' + uuid.uuid4().hex[:8]
            run('run', '-d', '--rm', '--name', name, '--cpus=0.25', '--memory=128m',
                '-p', '127.0.0.1::5000', 'registry:3')
            try:
                ports = json.loads(run('inspect', '--format', '{{json .NetworkSettings.Ports}}', name).stdout)
                host = '127.0.0.1:' + ports['5000/tcp'][0]['HostPort']
                client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

                def request(path, method='GET', data=None, headers=None):
                    return client.open(urllib.request.Request('http://' + host + path,
                        method=method, data=data, headers=headers or {}), timeout=10)

                for attempt in range(30):
                    try:
                        request('/v2/').close()
                        break
                    except OSError:
                        if attempt == 29:
                            raise
                        time.sleep(.1)

                def native(kind, arch, version):
                    config = json.dumps({'architecture': arch, 'os': 'linux',
                        'rootfs': {'type': 'layers', 'diff_ids': []}, 'author': version}).encode()
                    digest = 'sha256:' + hashlib.sha256(config).hexdigest()
                    with request('/v2/fixture/blobs/uploads/', 'POST', b'') as response:
                        upload = urllib.parse.urlsplit(response.headers['Location'])
                    query = urllib.parse.parse_qsl(upload.query) + [('digest', digest)]
                    with request(upload.path + '?' + urllib.parse.urlencode(query), 'PUT', config,
                                 {'Content-Type': 'application/octet-stream'}):
                        pass
                    media = ('application/vnd.oci.image.manifest.v1+json' if kind == 'oci'
                             else 'application/vnd.docker.distribution.manifest.v2+json')
                    body = json.dumps({'schemaVersion': 2, 'mediaType': media, 'config': {
                        'mediaType': ('application/vnd.oci.image.config.v1+json' if kind == 'oci'
                                      else 'application/vnd.docker.container.image.v1+json'),
                        'digest': digest, 'size': len(config)}, 'layers': []}).encode()
                    with request('/v2/fixture/manifests/' + version + '-' + arch, 'PUT', body,
                                 {'Content-Type': media}):
                        pass
                    return {'mediaType': media, 'digest': 'sha256:' + hashlib.sha256(body).hexdigest(),
                            'size': len(body), 'platform': {'architecture': arch, 'os': 'linux'}}

                legacy = [native('docker', arch, 'legacy') for arch in ('arm64', 'amd64')]
                index_type = 'application/vnd.docker.distribution.manifest.list.v2+json'
                old_index = json.dumps({'schemaVersion': 2, 'mediaType': index_type,
                                        'manifests': legacy}).encode()
                with request('/v2/fixture/manifests/java21-node22-v2', 'PUT', old_index,
                             {'Content-Type': index_type}):
                    pass
                for alias, arch in [('java21-node22-v2-arm64', 'arm64'),
                                    ('java21-node22-v2-amd64', 'amd64'),
                                    ('java21-node22-v1', 'arm64')]:
                    with request('/v2/fixture/manifests/legacy-' + arch,
                                 headers={'Accept': legacy[0]['mediaType']}) as response:
                        body = response.read()
                    with request('/v2/fixture/manifests/' + alias, 'PUT', body,
                                 {'Content-Type': legacy[0]['mediaType']}):
                        pass

                original_command = gate.command

                def loopback_command(args, **kwargs):
                    # The production command retains TLS; only this HTTP fixture needs the flag.
                    if args[:3] in (['docker', 'manifest', 'create'], ['docker', 'manifest', 'push']):
                        args = [*args[:3], '--insecure', *args[3:]]
                    return original_command([*docker, *args[1:]], **kwargs)

                registry = host + '/fixture'
                for version in ('current', 'next'):
                    expected = {arch: native('oci', arch, version) for arch in ('arm64', 'amd64')}
                    with patch.dict(os.environ, environment, clear=True), \
                            patch.object(gate, 'REGISTRY', registry), \
                            patch.object(gate, 'command', side_effect=loopback_command):
                        with open(Path(directory) / 'promotion.log', 'ab') as log:
                            gate.promote(registry + ':' + version, log)
                    for alias, arches in [('java21-node22-v2', ['arm64', 'amd64']),
                                          ('java21-node22-v2-arm64', ['arm64']),
                                          ('java21-node22-v2-amd64', ['amd64']),
                                          ('java21-node22-v1', ['arm64'])]:
                        with request('/v2/fixture/manifests/' + alias,
                                     headers={'Accept': index_type}) as response:
                            actual = json.load(response)
                        self.assertEqual(actual['mediaType'], 'application/vnd.oci.image.index.v1+json')
                        self.assertEqual({x['platform']['architecture']: x['digest']
                                          for x in actual['manifests']},
                                         {arch: expected[arch]['digest'] for arch in arches})
            finally:
                run('rm', '-f', name)


if __name__ == '__main__':
    unittest.main()
