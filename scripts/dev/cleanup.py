#!/usr/bin/env python3
"""Inspect task resources; explicitly clean one verified, merged worktree."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import time

import cpu_controls as cpu


def git(checkout, *args):
    return subprocess.check_output(['git', '-C', str(checkout), *args], text=True).strip()


def registered_worktree(checkout, requested):
    target = Path(requested).resolve()
    records = git(checkout, 'worktree', 'list', '--porcelain').split('\n\n')
    for block in records:
        entries = dict(line.split(' ', 1) for line in block.splitlines() if ' ' in line)
        if Path(entries['worktree']).resolve() == target:
            if target == cpu.repository_root(checkout):
                raise RuntimeError('The primary checkout is never a cleanup target.')
            if 'branch' not in entries:
                raise RuntimeError('Detached worktree: inspect its originating task manually.')
            return target, entries['branch'].removeprefix('refs/heads/'), entries['HEAD']
    raise RuntimeError('The target is not a registered worktree of this repository.')


def merged_task(checkout, branch, head):
    prs = json.loads(subprocess.check_output(
        ['gh', 'pr', 'list', '--repo', 'tolgraven/tolgrAVen', '--head', branch,
         '--state', 'all', '--limit', '100', '--json', 'number,state,headRefOid,mergeCommit'], text=True))
    if any(pr['state'] == 'OPEN' for pr in prs):
        raise RuntimeError('The branch still has an open PR.')
    for pr in prs:
        if pr['state'] == 'MERGED' and pr['headRefOid'] == head and pr.get('mergeCommit'):
            result = subprocess.run(['git', '-C', str(checkout), 'merge-base', '--is-ancestor',
                                     pr['mergeCommit']['oid'], 'origin/master'], capture_output=True)
            if result.returncode == 0:
                return pr['number']
    raise RuntimeError('No merged PR matches this exact worktree head in origin/master; preserve it.')


def active_processes(target):
    proc = cpu.MacProcesses()
    active = []
    for pid, record in proc.all().items():
        if pid == os.getpid():
            continue
        directory = proc.cwd(pid)
        requested = False
        if Path(record['path']).name.lower().startswith('python'):
            try:
                args = proc.argv(pid)
                marker = next((i for i, arg in enumerate(args) if Path(arg).name == 'cpu_controls.py'), None)
                if marker is not None and args[marker + 1:marker + 2] in [['run'], ['runtime']]:
                    tail = args[marker + 2:]
                    # Only launcher options before the command delimiter own checkout.
                    options = tail[:tail.index('--')] if '--' in tail else tail
                    supplied = next((arg.split('=', 1)[1] for arg in options if arg.startswith('--checkout=')), None)
                    if '--checkout' in options:
                        index = options.index('--checkout')
                        supplied = options[index + 1]
                    if supplied is not None:
                        requested = cpu.within(Path(supplied).resolve(), [target])
            except (OSError, RuntimeError, ValueError, IndexError):
                pass
        if requested or cpu.within(directory, [target]):
            active.append({'pid': pid, 'command': record['path'], 'cwd': str(directory)})
    return active


def active_docker_builds(target):
    """Check reservations and exact original container mounts, including old launchers."""
    proc = cpu.MacProcesses()
    active = []
    for _, record in cpu.build_records():
        owner_live = proc.same(record.get('owner', {}))
        guardian_live = bool(record.get('guardian') and proc.same(record['guardian']))
        output = Path(record['output']).resolve() if isinstance(record.get('output'), str) else None
        if cpu.within(output, [target]) and (owner_live or guardian_live):
            active.append({'name': record['name'], 'output': str(output)})
            continue
        endpoint = record.get('endpoint', '')
        name = record.get('name', '')
        if not endpoint.startswith('unix://') or not name or not record.get('token'):
            raise RuntimeError('Cannot verify a build reservation; use the supported recovery path.')
        inspected = subprocess.run(
            ['docker', '--host', endpoint, 'inspect', '--format',
             '{{json .Config.Labels}}\n{{json .Mounts}}\n{{json .State.Running}}', name],
            capture_output=True, text=True, timeout=15, env=cpu.docker_environment())
        if inspected.returncode:
            error = inspected.stderr.lower()
            absent = name.lower() in error and ('no such object:' in error or 'no such container:' in error)
            if not absent:
                raise RuntimeError('Cannot verify an owned build container; preserve the worktree.')
            if (owner_live or guardian_live) and output is None:
                raise RuntimeError('An older live build has not exposed its output mount yet; preserve the worktree.')
            continue
        try:
            labels, mounts, running = [json.loads(line) for line in inspected.stdout.splitlines()]
            if labels.get('tolgraven.cpu.token') != record['token']:
                raise RuntimeError('Build container ownership changed; preserve the worktree.')
            for mount in mounts:
                source = Path(mount['Source']).resolve()
                if cpu.within(source, [target]) and (running or owner_live or guardian_live):
                    active.append({'name': name, 'output': str(source)})
        except (ValueError, TypeError, KeyError):
            raise RuntimeError('Invalid build container inventory; preserve the worktree.')
    return active


def active_cache_writers(target):
    writers = []
    for metadata in cpu.shadow_cache_root(target).glob('*/metadata.json'):
        try:
            record = json.loads(metadata.read_text())
            if record.get('checkout') != str(target):
                continue
            key = record.get('key', '')
            if len(key) != 24 or any(char not in '0123456789abcdef' for char in key):
                continue
            handle = cpu.cache_lock(key, blocking=False)
            if handle is None:
                writers.append(key)
            else:
                handle.close()
        except (OSError, ValueError, TypeError):
            raise RuntimeError('Cannot verify the target compiler cache ownership.')
    return writers


def worktree_plan(checkout, requested):
    target, branch, head = registered_worktree(checkout, requested)
    if cpu.within(Path.cwd().resolve(), [target]):
        raise RuntimeError('Run cleanup from another checkout, not the target worktree.')
    if git(target, 'status', '--porcelain', '--untracked-files=all'):
        raise RuntimeError('Tracked changes or untracked source remain; preserve the worktree.')
    pr = merged_task(checkout, branch, head)
    builds = active_docker_builds(target)
    if builds:
        raise RuntimeError('Docker builds still use the worktree: ' + json.dumps(builds))
    writers = active_cache_writers(target)
    if writers:
        raise RuntimeError('Compiler writers still use the worktree: ' + json.dumps(writers))
    active = active_processes(target)
    if active:
        raise RuntimeError('Processes still use the worktree: ' + json.dumps(active))
    return {'path': str(target), 'branch': branch, 'head': head, 'merged_pr': pr}


# Ignored source/configuration and review artifacts are archived before removal.
# Only these reproducible dependency/compiler/output trees are omitted.
GENERATED = ('node_modules/', '.shadow-cljs/', '.local-wip/cpu-builds/',
             '.local-wip/shadow-caches/', 'target/', 'classes/', '.clj-kondo/.cache/',
             'resources/public/js/', 'resources/public/css/')


def stream_digest(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
    return digest.digest()


def archive_ignored(checkout, target, head):
    raw = subprocess.check_output(['git', '-C', str(target), 'ls-files', '--others',
                                   '--ignored', '--exclude-standard', '-z'])
    names = [os.fsdecode(name) for name in raw.split(b'\0') if name]
    expanded = set()
    def collect(path):
        name = path.relative_to(target).as_posix().rstrip('/')
        if any(name == prefix.rstrip('/') or name.startswith(prefix) for prefix in GENERATED):
            return
        expanded.add(name)
        if path.is_dir() and not path.is_symlink():
            for child in path.iterdir():
                collect(child)
    for name in names:
        collect(target / name)
    keep = sorted(expanded)
    if not keep:
        return None
    root = cpu.repository_root(checkout) / '.local-wip/task-archives'
    if root.is_symlink() or not root.resolve().is_relative_to(cpu.repository_root(checkout)):
        raise RuntimeError('Refusing an archive directory outside the repository.')
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    root.chmod(0o700)
    destination = root / (target.parent.name + '-' + head[:12] + '-' + str(time.time_ns()) + '.tar.gz')
    fd = os.open(destination, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'wb') as output, tarfile.open(fileobj=output, mode='w:gz', dereference=False) as archive:
        for name in keep:
            path = target / name
            if not path.is_symlink() and not path.resolve().is_relative_to(target):
                raise RuntimeError('Ignored file escapes the target worktree.')
            archive.add(path, arcname=name, recursive=False)
    with tarfile.open(destination) as archive:
        if sorted(archive.getnames()) != sorted(keep):
            raise RuntimeError('Archive verification failed; preserve the worktree.')
        for name in keep:
            path = target / name
            if path.is_file() and not path.is_symlink():
                with path.open('rb') as source, archive.extractfile(name) as recovered:
                    if stream_digest(source) != stream_digest(recovered):
                        raise RuntimeError('Archived data differs from source; preserve the worktree.')
    return str(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkout', default=os.getcwd())
    parser.add_argument('--worktree', help='Exact completed task checkout; never inferred from age.')
    parser.add_argument('--apply', action='store_true', help='Apply cache pruning; otherwise report only.')
    parser.add_argument('--remove-worktree', action='store_true', help='Also archive ignored task data and remove the verified merged worktree.')
    parser.add_argument('--legacy-caches', action='store_true', help='Prune the former Application Support cache root.')
    parser.add_argument('--all-unused-caches', action='store_true', help='Discard all unlocked target-worktree caches (all legacy identities with --legacy-caches).')
    args = parser.parse_args()
    if args.remove_worktree and not args.worktree:
        parser.error('--remove-worktree requires an exact --worktree')
    checkout = Path(args.checkout).resolve()
    target = Path(args.worktree).resolve() if args.worktree else checkout
    plan = worktree_plan(checkout, target) if args.worktree else None
    result = {'apply': args.apply, 'worktree': plan,
              'caches': cpu.prune_shadow_caches(checkout=target, apply=args.apply,
                                                legacy=args.legacy_caches,
                                                all_unused=args.all_unused_caches or args.remove_worktree)}
    if args.apply and args.remove_worktree:
        # Repeat lifecycle/head/dirty checks immediately before removing source.
        if worktree_plan(checkout, target) != plan:
            raise RuntimeError('Worktree changed during cleanup; preserve it.')
        result['archive'] = archive_ignored(checkout, target, plan['head'])
        if worktree_plan(checkout, target) != plan:
            raise RuntimeError('Worktree changed while archiving; preserve it.')
        subprocess.run(['git', '-C', str(checkout), 'worktree', 'remove', str(target)], check=True)
        result['removed_worktree'] = str(target)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error))
