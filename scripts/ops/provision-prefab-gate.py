#!/usr/bin/env python3
"""Install the trusted control service, then switch the existing GitHub App hook.

Uses the user's persistent SSH transport. Build/push happens on bux with a bounded
BuildKit worker; site builds still belong to Coolify's native deployment queue.
"""
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import shlex
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
FILES = {'prefab_gate.py': 'host/prefab_gate.py', 'prefab-gate.php': 'coolify/prefab-gate.php',
         'buildkitd.toml': 'host/prefab-buildkitd.toml', 'Dockerfile': 'host/prefab-gate.Dockerfile'}


def remote(arguments, data=None):
    return subprocess.run(['ssh', 'bux', shlex.join(arguments)], input=data, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'start', 'wire', 'status'])
    args = parser.parse_args()
    subprocess.run(['ssh', '-O', 'check', 'bux'], check=True)
    image = None
    if args.action == 'prepare':
        content = {name: (ROOT / 'scripts/ops' / path).read_bytes() for name, path in FILES.items()}
        digest = hashlib.sha256()
        for name, value in sorted(content.items()):
            digest.update(name.encode() + b'\0' + value)
        image = 'registry.bux.tolgraven.se/tolgraven/prefab-gate:' + digest.hexdigest()[:16]
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode='w') as tar:
            for name, value in content.items():
                entry = tarfile.TarInfo(name)
                entry.size, entry.mode = len(value), 0o644
                tar.addfile(entry, io.BytesIO(value))
        remote(['install', '-d', '-m', '0700', '/data/coolify/prefab-gate',
                '/data/coolify/prefab-gate/build', '/data/coolify/prefab-gate/state'])
        remote(['tar', '-x', '-C', '/data/coolify/prefab-gate/build'], archive.getvalue())
        # This builder is separate from application builds and retains its cache.
        remote(['sh', '-ec', 'docker buildx inspect tolgraven-prefab >/dev/null 2>&1 || '
                'docker buildx create --name tolgraven-prefab --driver docker-container '
                '--driver-opt cpu-period=100000,cpu-quota=150000,memory=4g,memory-swap=4g '
                '--buildkitd-config /data/coolify/prefab-gate/build/buildkitd.toml'])
        remote(['docker', 'buildx', 'build', '--builder', 'tolgraven-prefab', '--push',
                '--provenance=false', '--sbom=false', '-t', image, '/data/coolify/prefab-gate/build'])
    request = base64.b64encode(json.dumps({'action': args.action, 'image': image}).encode()).decode()
    script = ("<?php $input=json_decode(base64_decode('" + request + "'),true); ?>\n").encode()
    script += (ROOT / 'scripts/ops/coolify/provision-prefab-gate.php').read_bytes()
    remote(['docker', 'exec', '-i', 'coolify', 'php'], script)


if __name__ == '__main__':
    main()
