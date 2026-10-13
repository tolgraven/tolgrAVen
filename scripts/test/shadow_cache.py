#!/usr/bin/env python3
"""Measure isolated Shadow disk reuse through the installed, capped launcher."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time


def validate_results(results, target):
    if len({record['cache_key'] for record in results}) != 1:
        raise RuntimeError('Reporting/source changes incorrectly selected different caches.')
    # Both the project's loadable inventory and Shadow's browser test runner
    # deliberately use :dev/always; keep those two namespaces noncacheable.
    expected = 2 if target == 'app-test' else 0
    if any(record['compiled'] != expected for record in results[1:3]):
        raise RuntimeError('Unchanged source exceeded its intentional recompile count.')
    if target == 'document' and (not results[3]['compiled'] or not results[4]['compiled']):
        raise RuntimeError('Changed/restored source failed to invalidate its compiler cache.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkout', default='.')
    parser.add_argument('--report', required=True)
    parser.add_argument('--target', choices=['document', 'app-test'], default='document')
    parser.add_argument('--profiles')
    args = parser.parse_args()
    checkout = Path(args.checkout).resolve()
    launcher = Path.home() / '.local/bin/tolgraven-cpu'
    profiles = args.profiles or ('provided' if args.target == 'document' else '+project/test,+provided')
    compile_command = ['lein', 'with-profile', profiles, 'run', '-m',
                       'shadow.cljs.devtools.cli', 'compile', args.target]
    shell_compile = shlex.join(compile_command)
    samples = [
        ('cold-refresh', 'refresh', compile_command),
        ('unchanged', 'reuse', compile_command),
        ('reporting-variation', 'reuse', ['bash', '-c', 'echo cache-report-variation; ' + shell_compile]),
        ('changed-source', 'reuse', ['bash', '-c',
            "printf '\\n(def cache-validation-marker 42)\\n' >> src/bootstrap/bootstrap/entrance.cljs; " + shell_compile]),
        ('restored-source', 'reuse', compile_command)]
    if args.target == 'app-test':
        samples = samples[:3]
    results = []
    report = Path(args.report).resolve()
    report.parent.mkdir(parents=True, exist_ok=True)
    for label, mode, command in samples:
        status = subprocess.run([str(launcher), 'status'], capture_output=True, text=True)
        load = subprocess.check_output(['sysctl', '-n', 'vm.loadavg'], text=True).strip()
        started = time.monotonic()
        process = subprocess.run([str(launcher), 'run', '--checkout', str(checkout), '--shadow-cache', mode,
                                  '--', *command], capture_output=True, text=True)
        elapsed = time.monotonic() - started
        text = process.stdout + process.stderr
        log = report.with_name(report.stem + '-' + label + '.log')
        log.write_text(text)
        # Preserve measured runtime even if a later artifact/report assertion fails.
        log.with_suffix('.timing.json').write_text(json.dumps(
            {'wall_seconds_including_queue': round(elapsed, 3), 'exit_code': process.returncode}, indent=2) + '\n')
        output = re.search(r'Isolated output: (.+)', text)
        compiled = re.search(r'Build completed\. \((\d+) files, (\d+) compiled, (\d+) warnings, ([\d.]+)s\)', text)
        queue = re.search(r'Build slot wait: ([\d.]+)s', text)
        dependencies = re.search(r'Dependency setup: ([\d.]+)s', text)
        cache = re.search(r'Shadow cache: ([0-9a-f]{24})', text)
        if process.returncode or not output or not compiled or not cache:
            raise RuntimeError(label + ' did not complete successfully; inspect ' + str(log))
        snapshot = Path(output.group(1).strip())
        marker = False
        if args.target == 'document':
            javascript = snapshot / 'resources/public/js/document/cljs-runtime/bootstrap.entrance.js'
            # Missing output must be flushed from cached analysis, and source
            # restoration must replace the prior fixture's cached output.
            marker = 'cache_validation_marker' in javascript.read_text()
            if marker != (label == 'changed-source'):
                raise RuntimeError('Unexpected cached output for ' + label)
        else:
            javascript = snapshot / 'resources/public/js/tests/js/test.js'
            if not javascript.is_file() or javascript.stat().st_size == 0:
                raise RuntimeError('Fresh app-test output is missing for ' + label)
        record = {'label': label, 'wall_seconds_including_queue': round(elapsed, 3),
                  'target': args.target, 'queue_seconds': float(queue[1]) if queue else None,
                  'dependency_setup_seconds': float(dependencies[1]) if dependencies else None,
                  'load_average_before': load,
                  'files': int(compiled[1]), 'compiled': int(compiled[2]), 'warnings': int(compiled[3]),
                  'compiler_seconds': float(compiled[4]), 'cache_key': cache[1],
                  'snapshot': str(snapshot), 'log': str(log), 'marker_present': marker,
                  'budget': {'cpus': 1.5, 'memory': '4g', 'heap': '2048m'},
                  'status_before': json.loads(status.stdout) if status.returncode == 0 else None}
        results.append(record)
        report.write_text(json.dumps(results, indent=2) + '\n')
        print(json.dumps({key: value for key, value in record.items() if key != 'status_before'}), flush=True)
    validate_results(results, args.target)
    print('Verified fresh outputs and unchanged reuse' + (' with changed-source invalidation.' if args.target == 'document' else '.'))


if __name__ == '__main__':
    main()
