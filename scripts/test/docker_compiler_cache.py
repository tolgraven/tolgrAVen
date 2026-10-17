#!/usr/bin/env python3
"""Verify release compiler reuse on fresh Docker layers; use the capped BuildKit runner."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--image', required=True)
parser.add_argument('--report', type=Path, required=True)
args = parser.parse_args()
args.report.mkdir(parents=True, exist_ok=False)
cache = 'compiler-check-' + uuid.uuid4().hex[:12]
recipe = args.report / 'Dockerfile'
recipe.write_text('''# syntax=docker/dockerfile:1
FROM ''' + args.image + '''
WORKDIR /usr/src/app
COPY . .
ARG SAMPLE
RUN if [ "$SAMPLE" = changed ]; then printf '\\n(set! (.-compilerCacheMarker js/globalThis) "cache-invalidation-proof")\\n' >> src/frontend/bootstrap/entrance.cljs; fi \\
 && touch src/frontend/bootstrap/entrance.cljs
RUN --mount=type=cache,id=''' + cache + ''',target=/compiler-cache,sharing=locked \\
    JAVA_TOOL_OPTIONS="-Xms64m -Xmx1536m -XX:ActiveProcessorCount=2" \\
    SHADOW_CLJS='{:cache-root "/compiler-cache/shadow"}' \\
    node scripts/build/compiler-cache.mjs shadow /compiler-cache \\
    lein with-profile prod,provided run -m shadow.cljs.devtools.cli release document
RUN test -s resources/public/js/document/entrance.js \\
 && if [ "$SAMPLE" = changed ]; then grep -q cache-invalidation-proof resources/public/js/document/entrance.js; \\
    else ! grep -q cache-invalidation-proof resources/public/js/document/entrance.js; fi
''')
records = []
for sample in ['cold', 'warm', 'changed', 'restored']:
    start = time.monotonic()
    log = args.report / (sample + '.log')
    with log.open('w') as out:
        result = subprocess.run(['docker', 'buildx', 'build', '--output', 'type=cacheonly',
            '--build-arg', 'SAMPLE=' + sample, '--progress=plain', '-f', str(recipe), str(ROOT)],
            stdout=out, stderr=subprocess.STDOUT)
    text = log.read_text()
    match = re.search(r'Build completed\. \((\d+) files, (\d+) compiled, (\d+) warnings, ([\d.]+)s\)', text)
    row = {'sample': sample, 'seconds': round(time.monotonic()-start, 2), 'exit': result.returncode,
           'compiled': int(match[2]) if match else None, 'compiler_seconds': float(match[4]) if match else None}
    records.append(row)
    (args.report / 'result.json').write_text(json.dumps(records, indent=2)+'\n')
    print(json.dumps(row), flush=True)
    if result.returncode or not match or (sample == 'warm' and int(match[2]) != 0) or (sample in ['changed','restored'] and not int(match[2])):
        raise RuntimeError('Release cache verification failed; inspect retained logs')
