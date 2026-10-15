#!/usr/bin/env python3
"""Report or remove old unused tolgrAVen deployment images; never prune volumes."""
import argparse
import datetime as dt
import fcntl
import json
from pathlib import Path
import re
import subprocess

APPLICATIONS = {'bsok8o8csgso8c00g00k0csg', 'o84wgo08wcs048ss8sokgkgw'}
CMS = {'127.0.0.1:5005/tolgraven/strapi', 'registry.bux.tolgraven.se/tolgraven/strapi'}
AGE = dt.timedelta(hours=24)
KEEP = 3


def managed(reference):
    repository, _, tag = reference.rpartition(':')
    return ((repository in APPLICATIONS and re.fullmatch(r'(?:pr-\d+-)?[0-9a-f]{40}', tag) is not None)
            or (repository in CMS and re.fullmatch(r'[0-9a-f]{12}-\d{14}', tag) is not None))


def created(value):
    # Docker's nanosecond RFC3339 timestamp is accepted by fromisoformat.
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))


def candidates(images, containers, now):
    protected = {item['Image'] for item in containers}
    repositories = {}
    for image in images:
        for tag in image.get('RepoTags') or []:
            if managed(tag):
                repositories.setdefault(tag.rpartition(':')[0], []).append(image)
    for group in repositories.values():
        ordered = sorted({image['Id']: image for image in group}.values(), key=lambda image: created(image['Created']), reverse=True)
        protected.update(image['Id'] for image in ordered[:KEEP])
    return sorted(tag for image in images
                  if image['Id'] not in protected and now - created(image['Created']) > AGE
                  for tag in image.get('RepoTags') or [] if managed(tag))


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True, timeout=180)


def inspect(kind, ids):
    return json.loads(docker(kind, 'inspect', *ids)) if ids else []


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    with open('/run/lock/tolgraven-storage.lock', 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        state = Path('/var/lib/tolgraven/storage-migration.json')
        if state.exists() and json.loads(state.read_text())['phase'] != 'verified':
            raise RuntimeError('Storage migration is not yet verified; cleanup deferred')
        ids = docker('image', 'ls', '-q', '--no-trunc').split()
        images = inspect('image', sorted(set(ids)))
        containers = inspect('container', docker('ps', '-aq', '--no-trunc').split())
        tags = candidates(images, containers, dt.datetime.now(dt.timezone.utc))
        print(json.dumps({'apply': args.apply, 'image_references': tags, 'retention_hours': int(AGE.total_seconds() / 3600), 'keep_per_repository': KEEP}), flush=True)
        if not args.apply:
            return
        for tag in tags:
            # Recheck container ownership immediately before removal. No force:
            # Docker also rejects deletion if a concurrent deployment acquired it.
            image = inspect('image', [tag])[0]
            current = inspect('container', docker('ps', '-aq', '--no-trunc').split())
            if any(item['Image'] == image['Id'] for item in current):
                continue
            result = subprocess.run(['docker', 'image', 'rm', tag], check=False)
            if result.returncode:
                print('Retained image after concurrent reference or deletion failure:', tag, flush=True)
        # Docker protects in-use cache records. Named/active images and all
        # containers, networks and data volumes remain outside these prune calls.
        print(docker('buildx', 'prune', '--force', '--filter', 'until=6h', '--max-used-space', '8GB'), flush=True)
        print(docker('image', 'prune', '--force', '--filter', 'until=24h'), flush=True)


if __name__ == '__main__':
    main()
