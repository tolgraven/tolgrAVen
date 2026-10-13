#!/usr/bin/env python3
"""Measure isolated Shadow disk reuse through the installed, capped launcher."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkout', default='.')
    parser.add_argument('--report', required=True)
    args = parser.parse_args()
    checkout = Path(args.checkout).resolve()
    launcher = Path.home() / '.local/bin/tolgraven-cpu'
    compile_command = ['lein', 'with-profile', 'provided', 'run', '-m',
                       'shadow.cljs.devtools.cli', 'compile', 'document']
    shell_compile = shlex.join(compile_command)
    samples = [
        ('cold-refresh', 'refresh', compile_command),
        ('unchanged', 'reuse', compile_command),
        ('reporting-variation', 'reuse', ['bash', '-lc', 'echo cache-report-variation; ' + shell_compile]),
        ('changed-source', 'reuse', ['bash', '-lc',
            "printf '\\n(def cache-validation-marker 42)\\n' >> src/bootstrap/bootstrap/entrance.cljs; " + shell_compile]),
        ('restored-source', 'reuse', compile_command)]
    results = []
    report = Path(args.report).resolve()
    report.parent.mkdir(parents=True, exist_ok=True)
    for label, mode, command in samples:
        started = time.monotonic()
        status = subprocess.run([str(launcher), 'status'], capture_output=True, text=True)
        process = subprocess.run([str(launcher), 'run', '--checkout', str(checkout), '--shadow-cache', mode,
                                  '--', *command], capture_output=True, text=True)
        elapsed = time.monotonic() - started
        text = process.stdout + process.stderr
        log = report.with_name(report.stem + '-' + label + '.log')
        log.write_text(text)
        output = re.search(r'Isolated output: (.+)', text)
        compiled = re.search(r'Build completed\. \((\d+) files, (\d+) compiled, (\d+) warnings, ([\d.]+)s\)', text)
        cache = re.search(r'Shadow cache: ([0-9a-f]{24})', text)
        if process.returncode or not output or not compiled or not cache:
            raise RuntimeError(label + ' did not complete successfully; inspect ' + str(log))
        snapshot = Path(output.group(1).strip())
        javascript = snapshot / 'resources/public/js/document/cljs-runtime/bootstrap.entrance.js'
        # Fresh output must be written from cached analysis; invalidating a source
        # must also replace its output on the next source-restoration invocation.
        contents = javascript.read_text()
        marker = 'cache_validation_marker' in contents
        if marker != (label == 'changed-source'):
            raise RuntimeError('Unexpected cached output for ' + label)
        record = {'label': label, 'wall_seconds_including_queue': round(elapsed, 3),
                  'files': int(compiled[1]), 'compiled': int(compiled[2]), 'warnings': int(compiled[3]),
                  'compiler_seconds': float(compiled[4]), 'cache_key': cache[1],
                  'snapshot': str(snapshot), 'log': str(log), 'marker_present': marker,
                  'budget': {'cpus': 1.5, 'memory': '4g', 'heap': '2048m'},
                  'status_before': json.loads(status.stdout) if status.returncode == 0 else None}
        results.append(record)
        report.write_text(json.dumps(results, indent=2) + '\n')
        print(json.dumps({key: value for key, value in record.items() if key != 'status_before'}), flush=True)
    if len({record['cache_key'] for record in results}) != 1:
        raise RuntimeError('Reporting/source changes incorrectly selected different caches.')
    if results[1]['compiled'] or results[2]['compiled']:
        raise RuntimeError('Unchanged source failed to reuse its compiler cache.')
    if not results[3]['compiled'] or not results[4]['compiled']:
        raise RuntimeError('Changed/restored source failed to invalidate its compiler cache.')
    print('Verified fresh outputs, unchanged reuse and changed-source invalidation.')


if __name__ == '__main__':
    main()
