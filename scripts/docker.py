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


def image_kind(reference):
    repository = reference.rsplit(':', 1)[0]
    for registry in (REGISTRY, SERVER_REGISTRY):
        for kind in ('site', 'strapi', 'builder'):
            if repository == f'{registry}/tolgraven/{kind}':
                return kind
    return {'tolgraven-builder': 'builder', 'tolgraven-fallback-check': 'legacy'}.get(repository)


def image_cleanup_plan(images, in_use, prefab_id=None, current_id=None):
    """Remove managed tags only; retain rollback, CMS, prefab and container images."""
    protected = set(in_use) | {prefab_id, current_id}
    newest = sorted(images, key=lambda image: image['Created'], reverse=True)
    for kind, count in (('site', 2), ('strapi', 1)):
        candidates = [image['Id'] for image in newest
                      if any(image_kind(tag) == kind for tag in (image.get('RepoTags') or []))]
        protected.update(candidates[:count])
    if prefab_id is None:
        # Without a known replacement, never discard a dependency prefab.
        protected.update(image['Id'] for image in images
                         if any(image_kind(tag) == 'builder' for tag in (image.get('RepoTags') or [])))
    return [tag for image in images if image['Id'] not in protected
            for tag in (image.get('RepoTags') or []) if image_kind(tag)]


def prune_images(current=None):
    ids = list(dict.fromkeys(run('docker', 'image', 'ls', '--quiet', '--no-trunc', capture=True).split()))
    if not ids:
        return
    images = json.loads(run('docker', 'image', 'inspect', *ids, capture=True))
    containers = run('docker', 'ps', '-aq', capture=True).split()
    in_use = set(run('docker', 'inspect', '--format', '{{.Image}}', *containers,
                     capture=True).split()) if containers else set()
    if current is None:
        saved = ROOT / '.local-wip/docker/last-image'
        current = saved.read_text().strip() if saved.exists() else None
    by_tag = {tag: image['Id'] for image in images for tag in (image.get('RepoTags') or [])}
    obsolete = image_cleanup_plan(images, in_use, by_tag.get(builder_tag()), by_tag.get(current))
    if obsolete:
        # No --force: Docker also protects a container created after our snapshot.
        result = subprocess.run(['docker', 'image', 'rm', *obsolete], cwd=ROOT)
        if result.returncode:
            print('Some image tags were retained; Docker may have found a new container reference.', flush=True)
    else:
        print('No obsolete local tolgraven image tags.', flush=True)


def require_tolgraven_checkout():
    # A fork retains this Makefile, but must never deploy over tolgraven's staging app.
    origin = run('git', 'remote', 'get-url', 'origin', capture=True).strip()
    normalized = origin.removesuffix('.git').replace('git@github.com:', 'https://github.com/')
    if normalized.lower().rstrip('/') != 'https://github.com/tolgraven/tolgraven':
        raise SystemExit('make docker is scoped to tolgraven staging. For another site, use '
                         'scripts/provision-site.py deploy with that site manifest.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['registry', 'prefab', 'build', 'push', 'deploy', 'clean'])
    args = parser.parse_args()
    if args.action == 'deploy':
        require_tolgraven_checkout()
    if args.action == 'registry':
        registry_check()
    elif args.action == 'clean':
        prune_images()
        run('docker', 'image', 'prune', '--force')
        run('docker', 'buildx', 'prune', '--force', '--max-used-space', '4GB')
    elif args.action == 'prefab':
        print(prefab(publish=True))
        prune_images()
    else:
        image = build()
        if args.action in ('push', 'deploy'):
            publish(image)
        prune_images(current=image)
        if args.action == 'deploy':
            host = os.environ.get('COOLIFY_SSH_HOST', 'bux')
            preview = os.environ.get('COOLIFY_PR', '0')
            # Bux reaches the same repository through its private loopback endpoint.
            server_image = f'{SERVER_REGISTRY}/tolgraven/site:{image.rsplit(":", 1)[1]}'
            command = shlex.join(['python3', '/usr/local/lib/tolgraven/deploy-image.py', server_image, '--pr', preview])
            try:
                run('ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', host, command, timeout=1000)
            except subprocess.TimeoutExpired:
                raise SystemExit('SSH/deployment timed out. Check the Coolify job and server recovery state before retrying.') from None


if __name__ == '__main__':
    main()
