#!/usr/bin/env python3
"""Prove portable Maven-cache reuse with real, network-disabled Docker builds.

Run through tolgraven-cpu run --mode buildkit. Requires the freshly built prefab;
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
    source = (ROOT / 'Dockerfile').read_text()
    # Isolate this test's cache identity, retaining the real recipe and defaults.
    source = source.replace('id=m2,', 'id=m2-check-' + token + ',')
    # Cache IDs do not invalidate an existing RUN result. Force this seed to run.
    source = source.replace('FROM dependencies AS dependency-cache\n',
        'FROM dependencies AS dependency-cache\nRUN echo ' + token + ' > /tmp/cache-check-run\n')
    version = re.search(r'\[org.clojure/clojure "([^"]+)"\]', (ROOT / 'project.clj').read_text()).group(1)
    rows = []
    images = []
    try:
        for name in ['cold', 'warm-without-baked-clojure']:
            recipe = source
            if name.startswith('warm'):
                # The second build must find this artifact in the persisted mount.
                recipe = recipe.replace('FROM dependencies AS dependency-cache\n',
                    'FROM dependencies AS dependency-cache\nRUN rm -rf /root/.m2/repository/org/clojure/clojure/' + version + '\n')
            file = output / (name + '.Dockerfile')
            file.write_text(recipe)
            image = 'tolgraven-m2-check:' + token + '-' + name
            images.append(image)
            command = ['docker', 'buildx', 'build', '--load', '--network=none',
                '--target', 'dependency-cache',
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
