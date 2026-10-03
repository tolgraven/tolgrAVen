#!/usr/bin/env python3
"""Local builds and publishing through the authenticated S3-backed registry on bux."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = os.environ.get('DOCKER_REGISTRY', 'registry.bux.tolgraven.se')
PLATFORM = os.environ.get('DOCKER_PLATFORM', 'linux/arm64')
SERVER_REGISTRY = '127.0.0.1:5005'


def run(*args, capture=False, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None, **kwargs).stdout


def registry_check():
    # Docker handles credentials through docker login and the configured credential store.
    # This request deliberately verifies that unauthenticated public access is denied.
    try:
        with urllib.request.urlopen(f'https://{REGISTRY}/v2/', timeout=15):
            raise SystemExit('Registry unexpectedly permits unauthenticated access')
    except urllib.error.HTTPError as error:
        if error.code != 401 or not error.headers.get('WWW-Authenticate', '').startswith('Basic'):
            raise SystemExit(f'Registry health check failed: HTTP {error.code}') from None
    print(f'HTTPS registry ready: {REGISTRY} (authentication required)', flush=True)


def remote_config_digest(image):
    result = subprocess.run(['docker', 'manifest', 'inspect', image],
                            capture_output=True, text=True)
    if result.returncode:
        if 'manifest unknown' in result.stderr.lower() or 'no such manifest' in result.stderr.lower():
            return None
        raise SystemExit(f'Cannot inspect {image}; check docker login and registry connectivity.\n{result.stderr}')
    return json.loads(result.stdout).get('config', {}).get('digest')


def builder_tag():
    digest = hashlib.sha256()
    for name in ('Dockerfile.builder', 'project.clj', 'package.json', 'package-lock.json'):
        digest.update(name.encode() + b'\0' + (ROOT / name).read_bytes())
    return f'{REGISTRY}/tolgraven/builder:deps-{digest.hexdigest()[:16]}-{PLATFORM.split("/")[-1]}'


def prefab(publish=False):
    image = builder_tag()
    present = subprocess.run(['docker', 'image', 'inspect', image], capture_output=True).returncode == 0
    if not present:
        # Prefer the existing dependency-hash image over reinstalling its dependencies.
        if remote_config_digest(image):
            run('docker', 'pull', image)
        else:
            run('docker', 'build', '--platform', PLATFORM, '--progress=plain',
                '-f', 'Dockerfile.builder', '-t', image, '.')
    if publish:
        registry_check()
        image_id = json.loads(run('docker', 'image', 'inspect', image, capture=True))[0]['Id']
        if remote_config_digest(image) != image_id:
            run('docker', 'push', image)
        else:
            print('Published dependency hash unchanged; reusing prefab.', flush=True)
        alias = f'{REGISTRY}/tolgraven/builder:java21-node22-v1'
        if remote_config_digest(alias) != image_id:
            run('docker', 'tag', image, alias)
            run('docker', 'push', alias)
    return image


def build():
    builder = prefab()
    revision = run('git', 'rev-parse', 'HEAD', capture=True).strip()
    dirty = bool(run('git', 'status', '--porcelain', capture=True).strip())
    tag = revision[:12] + ('-dirty' if dirty else '') + '-' + time.strftime('%Y%m%d%H%M%S', time.gmtime())
    image = f'{REGISTRY}/tolgraven/site:{tag}'
    run('docker', 'build', '--platform', PLATFORM, '--progress=plain',
        '--build-arg', f'BUILDER_IMAGE={builder}', '--build-arg', f'VCS_REF={revision}',
        '-t', image, '.')
    state = ROOT / '.local-wip' / 'docker'
    state.mkdir(parents=True, exist_ok=True)
    (state / 'last-image').write_text(image + '\n')
    print(f'Built {image}', flush=True)
    return image


def publish(image):
    registry_check()
    run('docker', 'push', image)
    print(f'Published {image} through bux to Hetzner S3.', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['registry', 'prefab', 'build', 'push', 'deploy'])
    args = parser.parse_args()
    if args.action == 'registry':
        registry_check()
    elif args.action == 'prefab':
        print(prefab(publish=True))
    else:
        image = build()
        if args.action in ('push', 'deploy'):
            publish(image)
        if args.action == 'deploy':
            host = os.environ.get('COOLIFY_SSH_HOST', 'bux')
            preview = os.environ.get('COOLIFY_PR', '45')
            # Bux reaches the same repository through its private loopback endpoint.
            server_image = f'{SERVER_REGISTRY}/tolgraven/site:{image.rsplit(":", 1)[1]}'
            command = shlex.join(['python3', '/usr/local/lib/tolgraven/deploy-image.py', server_image, '--pr', preview])
            try:
                run('ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', host, command, timeout=1000)
            except subprocess.TimeoutExpired:
                raise SystemExit('SSH/deployment timed out. Check the Coolify job and server recovery state before retrying.') from None


if __name__ == '__main__':
    main()
