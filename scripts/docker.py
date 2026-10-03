#!/usr/bin/env python3
"""Local builds and publishing through a loopback-only, Hetzner S3-backed registry."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = os.environ.get('DOCKER_REGISTRY', '127.0.0.1:5005')
PLATFORM = os.environ.get('DOCKER_PLATFORM', 'linux/arm64')
PROFILE = os.environ.get('S3_PROFILE', 'hetzner')
ENDPOINT = os.environ.get('S3_ENDPOINT', 'https://hel1.your-objectstorage.com')
BUCKET = os.environ.get('S3_BUCKET', 'tolgraven')
REGISTRY_IMAGE = 'registry:3@sha256:ddf754342cfc8acc51a56d5d0ab6af06826461864460636d8bd5c546dab2a7b8'


def run(*args, capture=False, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None, **kwargs).stdout


def registry_up():
    name = 'tolgraven-build-registry'
    result = subprocess.run(['docker', 'inspect', name], capture_output=True, text=True)
    if result.returncode == 0:
        container = json.loads(result.stdout)[0]
        if not container['State']['Running']:
            run('docker', 'start', name)
        return
    # Never print credentials or place them in argv, source control, or images.
    credentials = json.loads(run('aws', 'configure', 'export-credentials', '--profile', PROFILE,
                                 '--format', 'process', capture=True))
    env = {
        'REGISTRY_STORAGE': 's3',
        'REGISTRY_STORAGE_S3_REGION': 'us-east-1',
        'REGISTRY_STORAGE_S3_REGIONENDPOINT': ENDPOINT,
        'REGISTRY_STORAGE_S3_FORCEPATHSTYLE': 'true',
        'REGISTRY_STORAGE_S3_BUCKET': BUCKET,
        'REGISTRY_STORAGE_S3_ROOTDIRECTORY': '/docker-registry',
        'REGISTRY_STORAGE_S3_ACCESSKEY': credentials['AccessKeyId'],
        'REGISTRY_STORAGE_S3_SECRETKEY': credentials['SecretAccessKey'],
        'REGISTRY_STORAGE_REDIRECT_DISABLE': 'true',
        'REGISTRY_HTTP_ADDR': ':5000',
        'REGISTRY_LOG_LEVEL': 'warn',
    }
    if credentials.get('SessionToken'):
        env['REGISTRY_STORAGE_S3_SESSIONTOKEN'] = credentials['SessionToken']
    with tempfile.NamedTemporaryFile(mode='w') as f:
        for key, value in env.items():
            if '\n' in value:
                raise SystemExit('Invalid multiline registry configuration')
            f.write(f'{key}={value}\n')
        f.flush()
        run('docker', 'run', '-d', '--name', name, '--restart', 'unless-stopped',
            '-p', '127.0.0.1:5005:5000', '--env-file', f.name, REGISTRY_IMAGE)
    for _ in range(30):
        try:
            with urllib.request.urlopen('http://127.0.0.1:5005/v2/', timeout=2) as r:
                if r.status == 200:
                    return
        except OSError:
            time.sleep(1)
    raise SystemExit('Registry did not become ready; inspect docker logs tolgraven-build-registry')


def builder_tag():
    digest = hashlib.sha256()
    for name in ('Dockerfile.builder', 'project.clj', 'package.json', 'package-lock.json'):
        digest.update(name.encode() + b'\0' + (ROOT / name).read_bytes())
    return f'{REGISTRY}/tolgraven/builder:deps-{digest.hexdigest()[:16]}-{PLATFORM.split("/")[-1]}'


def prefab(publish=False):
    image = builder_tag()
    present = subprocess.run(['docker', 'image', 'inspect', image], capture_output=True).returncode == 0
    if not present:
        run('docker', 'build', '--platform', PLATFORM, '--progress=plain',
            '-f', 'Dockerfile.builder', '-t', image, '.')
    if publish:
        registry_up()
        run('docker', 'push', image)
        alias = f'{REGISTRY}/tolgraven/builder:java21-node22-v1'
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
    registry_up()
    run('docker', 'push', image)
    print(f'Published {image} to s3://{BUCKET}/docker-registry/', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['registry', 'prefab', 'build', 'push', 'deploy'])
    args = parser.parse_args()
    if args.action == 'registry':
        registry_up()
    elif args.action == 'prefab':
        print(prefab(publish=True))
    else:
        image = build()
        if args.action in ('push', 'deploy'):
            publish(image)
        if args.action == 'deploy':
            host = os.environ.get('COOLIFY_SSH_HOST', 'bux')
            preview = os.environ.get('COOLIFY_PR', '45')
            command = shlex.join(['python3', '/usr/local/lib/tolgraven/deploy-image.py', image, '--pr', preview])
            try:
                run('ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', host, command, timeout=1000)
            except subprocess.TimeoutExpired:
                raise SystemExit('SSH/deployment timed out. Check the Coolify job and server recovery state before retrying.') from None


if __name__ == '__main__':
    main()
