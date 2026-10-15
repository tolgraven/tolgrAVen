#!/usr/bin/env python3
"""Inspect or explicitly migrate bux's Docker/Coolify storage to its attached SSD."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

DEVICE = '/dev/disk/by-id/scsi-0HC_Volume_107155672'
UUID = '75c692b4-7894-4822-83c1-09250c94e267'
VOLUME = Path('/mnt/bux-extra')
STATE = Path('/var/lib/tolgraven/storage-migration.json')
SOURCES = ['/var/lib/docker', '/data/coolify']


def run(*args, capture=False):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE if capture else None).stdout


def save(state):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    temporary = STATE.with_suffix('.tmp')
    temporary.write_text(json.dumps(state, indent=2) + '\n')
    temporary.chmod(0o600)
    temporary.replace(STATE)


def destination(source):
    return VOLUME / ('docker' if source == '/var/lib/docker' else 'coolify')


def mounted(path):
    return subprocess.run(['mountpoint', '-q', str(path)]).returncode == 0


def verify_volume():
    if run('blkid', '-s', 'UUID', '-o', 'value', DEVICE, capture=True).strip() != UUID:
        raise RuntimeError('Unexpected SSD identity; never format storage automatically')
    if not mounted(VOLUME):
        raise RuntimeError('SSD must be mounted before copying or starting Docker')
    actual = run('findmnt', '-n', '-o', 'UUID', '--target', str(VOLUME), capture=True).strip()
    if actual != UUID:
        raise RuntimeError('Mount does not belong to the expected SSD')


def prepare():
    if run('blkid', '-s', 'UUID', '-o', 'value', DEVICE, capture=True).strip() != UUID:
        raise RuntimeError('Unexpected volume; formatting is never implicit')
    fstab = Path('/etc/fstab')
    text = fstab.read_text()
    old = Path('/mnt/HC_Volume_107155672')
    VOLUME.mkdir(exist_ok=True)
    if mounted(old) and not mounted(VOLUME):
        run('umount', str(old))
    if not mounted(VOLUME):
        run('mount', '-o', 'discard,defaults', DEVICE, str(VOLUME))
    verify_volume()
    if not STATE.exists():
        backup = Path('/etc/fstab.before-tolgraven-storage')
        if backup.exists():
            raise RuntimeError('Existing recovery file requires manual inspection')
        shutil.copy2(fstab, backup)
        save({'phase': 'prepared', 'device': DEVICE, 'uuid': UUID, 'sources': SOURCES})
    lines = [line for line in text.splitlines() if not (line.strip() and not line.lstrip().startswith('#') and (DEVICE in line or UUID in line))]
    lines.append(f'UUID={UUID} {VOLUME} ext4 discard,nofail,defaults 0 2')
    fstab.write_text('\n'.join(lines) + '\n')
    run('systemctl', 'daemon-reload')
    for source in SOURCES:
        destination(source).mkdir(exist_ok=True)


def copy_data(final=False):
    verify_volume()
    for source in SOURCES:
        args = ['rsync', '-aHAXx', '--numeric-ids']
        if final:
            args.extend(['--delete', '--checksum'])
        result = subprocess.run([*args, source + '/', str(destination(source)) + '/'])
        if result.returncode and not (not final and result.returncode == 24):
            raise RuntimeError(f'Copy failed for {source}; exit {result.returncode}')


def resume_admission():
    # Container restart order can briefly precede database/Redis readiness.
    deadline = time.monotonic() + 120
    while True:
        try:
            run('docker', 'exec', 'coolify', 'php', 'artisan', 'up')
            run('docker', 'exec', 'coolify', 'php', 'artisan', 'horizon:continue')
            return
        except subprocess.CalledProcessError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(2)


def check_admission():
    # Refuse preexisting maintenance/paused workers rather than overwrite them.
    run('docker', 'exec', 'coolify', 'test', '!', '-e', '/var/www/html/storage/framework/down')
    status = run('docker', 'exec', 'coolify', 'php', 'artisan', 'horizon:status', capture=True)
    if 'Horizon is running' not in status:
        raise RuntimeError('Coolify workers must be running before migration')


def quiesce_admission():
    run('docker', 'exec', 'coolify', 'php', 'artisan', 'down', '--retry=60')
    run('docker', 'exec', 'coolify', 'php', 'artisan', 'horizon:pause')
    deadline = time.monotonic() + 30
    while True:
        status = subprocess.run(['docker', 'exec', 'coolify', 'php', 'artisan', 'horizon:status'], text=True, capture_output=True)
        if status.returncode == 1 and 'Horizon is paused' in status.stdout:
            return
        if time.monotonic() >= deadline:
            raise RuntimeError('Coolify workers did not pause')
        time.sleep(1)


def migrate():
    verify_volume()
    state = json.loads(STATE.read_text())
    if state['phase'] != 'prepared':
        raise RuntimeError('Migration already started; inspect recovery state before proceeding')
    # Block webhook admission and new jobs before checking the drained queue.
    probe = r"""<?php
require '/var/www/html/vendor/autoload.php';
$app=require '/var/www/html/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();
if(App\Models\ApplicationDeploymentQueue::where('status','in_progress')->exists())exit(42);
"""
    check_admission()
    state['phase'] = 'quiescing'
    save(state)
    try:
        quiesce_admission()
        # Let existing jobs finish while paused workers retain queued work.
        # No running deployment is interrupted to obtain the maintenance window.
        deadline = time.monotonic() + 1800
        while True:
            result = subprocess.run(['docker', 'exec', '-i', 'coolify', 'php'], input=probe, text=True)
            if result.returncode == 0:
                break
            if result.returncode != 42:
                raise RuntimeError('Could not verify active deployment state')
            if time.monotonic() >= deadline:
                raise RuntimeError('Deployments did not drain before the maintenance deadline')
            time.sleep(5)
    except Exception:
        resume_admission()
        state['phase'] = 'prepared'
        save(state)
        raise
    state['services'] = [unit for unit in ['tolgraven-runtime-policy.service', 'coolify-site-runtime-policy.service']
                         if subprocess.run(['systemctl', 'is-active', '--quiet', unit]).returncode == 0]
    state.update(phase='stopping', running=run('docker', 'ps', '-q', capture=True).split())
    save(state)
    if state['running']:
        run('docker', 'stop', '--time', '60', *state['running'])
    run('systemctl', 'stop', 'docker.service', 'docker.socket')
    state['phase'] = 'copying'
    save(state)
    # On failure originals remain intact. Do not restart Docker against a partial copy.
    copy_data(final=True)
    for source in SOURCES:
        differences = run('rsync', '-aHAXxnc', '--numeric-ids', '--delete', '--itemize-changes', source + '/', str(destination(source)) + '/', capture=True)
        if differences.strip():
            raise RuntimeError('Offline checksum verification failed; originals retained')
    fstab = Path('/etc/fstab')
    text = fstab.read_text()
    for source in SOURCES:
        line = f'{destination(source)} {source} none bind,x-systemd.requires-mounts-for={VOLUME} 0 0'
        if line not in text:
            text += line + '\n'
    fstab.write_text(text)
    dropin = Path('/etc/systemd/system/docker.service.d/tolgraven-storage.conf')
    dropin.parent.mkdir(parents=True, exist_ok=True)
    dropin.write_text('[Unit]\nRequiresMountsFor=/mnt/bux-extra /var/lib/docker /data/coolify\n')
    run('systemctl', 'daemon-reload')
    state['phase'] = 'mounting'
    save(state)
    for source in SOURCES:
        original = Path(source)
        backup = Path(source + '.before-ssd')
        if backup.exists():
            raise RuntimeError('Original backup already exists; inspect recovery state')
        original.rename(backup)
        original.mkdir(mode=backup.stat().st_mode & 0o777)
        run('mount', source)
    state['phase'] = 'mounted'
    save(state)
    run('systemctl', 'start', 'docker.service')
    if state['running']:
        run('docker', 'start', *state['running'])
    resume_admission()
    for unit in state.get('services', []):
        run('systemctl', 'start', unit)
    state['phase'] = 'running-awaiting-verification'
    save(state)


def retire_originals():
    verify_volume()
    state = json.loads(STATE.read_text())
    if state['phase'] != 'running-awaiting-verification':
        raise RuntimeError('Verify the completed migration before retiring originals')
    for source in SOURCES:
        if not mounted(source) or os.stat(source).st_dev != os.stat(VOLUME).st_dev:
            raise RuntimeError('A managed path is not on the SSD')
        backup = Path(source + '.before-ssd')
        mount_tree = json.loads(run('findmnt', '--json', '--output', 'TARGET', capture=True))
        def targets(items):
            for item in items:
                yield item['target']
                yield from targets(item.get('children', []))
        nested_mount = any(path == str(backup) or path.startswith(str(backup) + '/')
                           for path in targets(mount_tree['filesystems']))
        if nested_mount or backup.is_symlink():
            raise RuntimeError('Refusing mounted or linked recovery directory')
    for source in SOURCES:
        shutil.rmtree(source + '.before-ssd')
    state.update(phase='verified', verified_at=int(time.time()))
    save(state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'prepare', 'precopy', 'migrate', 'retire-originals'])
    args = parser.parse_args()
    if args.action == 'plan':
        print(json.dumps({'device': DEVICE, 'mount': str(VOLUME), 'moves': {s: str(destination(s)) for s in SOURCES}, 'state': json.loads(STATE.read_text()) if STATE.exists() else None}, indent=2))
        return
    with open('/run/lock/tolgraven-storage.lock', 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        {'prepare': prepare, 'precopy': copy_data, 'migrate': migrate, 'retire-originals': retire_originals}[args.action]()


if __name__ == '__main__':
    main()
