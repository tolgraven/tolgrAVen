#!/usr/bin/env python3
"""Prove existing Maven-cache reuse with real, network-disabled Docker builds.

Run through the MacBook launcher or directly on bux. Requires a populated m2 cache;
never touches the application runtime or the watched output directories.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    output = args.report.resolve()
    output.mkdir(parents=True, exist_ok=False)
    token = uuid.uuid4().hex[:12]
    source = ('ARG BUILDER_IMAGE\nFROM ${BUILDER_IMAGE} AS dependency-check\n'
        'WORKDIR /usr/src/app\nCOPY project.clj ./\nARG CACHE_CHECK_CASE\n'
        'RUN --mount=type=cache,id=m2,target=/root/m2,sharing=locked '
        'echo "$CACHE_CHECK_CASE" > /tmp/cache-check-run && '
        "lein -o with-profile user,uberjar,provided update-in : assoc :prep-tasks '^:replace []' -- classpath > /tmp/classpath\n"
        # No repository artifacts may remain in the resulting layer.
        'RUN test ! -d /root/m2/org && test ! -d /root/.m2/repository/org\n')
    rows = []
    images = []
    try:
        for name in ['fresh-application-layer', 'warm-persistent-m2']:
            recipe = source
            file = output / (name + '.Dockerfile')
            file.write_text(recipe)
            image = 'tolgraven-m2-check:' + token + '-' + name
            images.append(image)
            command = ['docker', 'buildx', 'build', '--load', '--network=none',
                '--target', 'dependency-check',
                '--build-arg', 'CACHE_CHECK_CASE=' + token + '-' + name,
                '--build-arg', 'BUILDER_IMAGE=' + args.image, '--progress=plain',
                '-f', str(file), '-t', image, str(ROOT)]
            start = time.monotonic()
            with (output / (name + '.log')).open('w') as log:
                result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
            text = (output / (name + '.log')).read_text()
            row = {'case': name, 'exit': result.returncode, 'seconds': time.monotonic() - start,
                'repository_downloads': len(re.findall(r'Retrieving .* from (?:central|clojars)', text)),
                'apt_runs': len(re.findall(r'\] RUN .*apt-get install', text))}
            rows.append(row)
            print(json.dumps(row), flush=True)
            if result.returncode or row['repository_downloads'] or row['apt_runs']:
                raise RuntimeError('Offline Maven-cache check failed; inspect retained build logs')
        (output / 'result.json').write_text(json.dumps({'passed': True, 'image': args.image, 'cases': rows}, indent=2) + '\n')
    finally:
        if images:
            subprocess.run(['docker', 'image', 'rm', *images], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
