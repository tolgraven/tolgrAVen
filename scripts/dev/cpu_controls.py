#!/usr/bin/env python3
"""Bounded macOS CPU ownership adapter and isolated, capped prefab build launcher."""
import argparse
from contextlib import contextmanager
import ctypes
import fcntl
import hashlib
import json
import os
from pathlib import Path
import select
import signal
import socket
import struct
import subprocess
import sys
import plistlib
import shutil
import shlex
import time
import uuid

STATE = Path.home() / 'Library/Application Support/tolgraven/cpu-controls'
BUILD_SLOTS = 2
BUILD_CPUS = 1.5  # Two kernel-capped workers share a maximum of three Docker cores.
JVM_POOLS = '-XX:ActiveProcessorCount=1 -XX:CICompilerCount=2 -XX:ParallelGCThreads=1 -XX:ConcGCThreads=1'


class BSDInfo(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint32) for n in ('flags', 'status', 'xstatus', 'pid', 'ppid', 'uid',
                 'gid', 'ruid', 'rgid', 'svuid', 'svgid', 'reserved')] + [
                 ('comm', ctypes.c_char * 16), ('name', ctypes.c_char * 32)] + [
                 (n, ctypes.c_uint32) for n in ('nfiles', 'pgid', 'jobc', 'tdev', 'tpgid')] + [
                 ('nice', ctypes.c_int32), ('start_sec', ctypes.c_uint64), ('start_usec', ctypes.c_uint64)]


class TaskInfo(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint64) for n in ('virtual', 'resident', 'user', 'system',
                                             'threads_user', 'threads_system')] + [
                 (n, ctypes.c_int32) for n in ('policy', 'faults', 'pageins', 'cow', 'sent', 'received',
                                              'mach', 'unix', 'switches', 'threads', 'running', 'priority')]


class VStat(ctypes.Structure):
    _fields_ = [('dev', ctypes.c_uint32), ('mode', ctypes.c_uint16), ('nlink', ctypes.c_uint16),
                ('ino', ctypes.c_uint64), ('uid', ctypes.c_uint32), ('gid', ctypes.c_uint32)] + [
                (n, ctypes.c_int64) for n in ('atime', 'atimensec', 'mtime', 'mtimensec', 'ctime',
                                             'ctimensec', 'birthtime', 'birthtimensec', 'size', 'blocks')] + [
                ('blksize', ctypes.c_int32), ('flags', ctypes.c_uint32), ('gen', ctypes.c_uint32),
                ('rdev', ctypes.c_uint32), ('spare', ctypes.c_int64 * 2)]


class VnodePath(ctypes.Structure):
    _fields_ = [('stat', VStat), ('type', ctypes.c_int32), ('pad', ctypes.c_int32),
                ('fsid', ctypes.c_int32 * 2), ('path', ctypes.c_char * 1024)]


class VnodeInfo(ctypes.Structure):
    _fields_ = [('cwd', VnodePath), ('root', VnodePath)]


class MacProcesses:
    """libproc supplies microsecond birth identity and native cumulative CPU time."""
    def __init__(self):
        if sys.platform != 'darwin':
            raise RuntimeError('Native controls require macOS; use docker for Linux quotas.')
        self.lib = ctypes.CDLL('/usr/lib/libproc.dylib', use_errno=True)
        class Timebase(ctypes.Structure):
            _fields_ = [('numer', ctypes.c_uint32), ('denom', ctypes.c_uint32)]
        clock = ctypes.CDLL('/usr/lib/libSystem.B.dylib')
        self.system = clock
        basis = Timebase()
        if clock.mach_timebase_info(ctypes.byref(basis)) != 0 or not basis.denom:
            raise RuntimeError('Cannot resolve the native CPU clock timebase.')
        self.seconds_per_tick = basis.numer / basis.denom / 1e9

    def info(self, pid, flavor, kind):
        data = kind()
        size = ctypes.sizeof(data)
        n = self.lib.proc_pidinfo(pid, flavor, 0, ctypes.byref(data), size)
        return data if n == size else None

    def identity(self, pid):
        b = self.info(pid, 3, BSDInfo)
        if b is None or b.uid != os.getuid() or b.flags & 1:
            return None
        buf = ctypes.create_string_buffer(4096)
        if not self.lib.proc_pidpath(pid, buf, len(buf)):
            return None
        return {'pid': pid, 'start': [b.start_sec, b.start_usec], 'uid': b.uid,
                'ppid': b.ppid, 'pgid': b.pgid, 'status': b.status, 'path': os.fsdecode(buf.value)}

    def same(self, record):
        now = self.identity(record['pid'])
        return bool(now and all(now[k] == record[k] for k in ('pid', 'start', 'uid', 'path')))

    def argv(self, pid):
        mib = (ctypes.c_int * 3)(1, 49, pid)  # CTL_KERN / KERN_PROCARGS2, installed SDK.
        size = ctypes.c_size_t()
        if self.system.sysctl(mib, 3, None, ctypes.byref(size), None, 0) != 0:
            raise RuntimeError('Cannot read the owned guardian argument vector.')
        buffer = ctypes.create_string_buffer(size.value)
        if self.system.sysctl(mib, 3, buffer, ctypes.byref(size), None, 0) != 0:
            raise RuntimeError('Cannot read the owned guardian argument vector.')
        raw = buffer.raw[:size.value]
        argc = struct.unpack_from('i', raw)[0]
        offset = raw.index(b'\0', 4) + 1
        while offset < len(raw) and raw[offset] == 0:
            offset += 1
        return [os.fsdecode(arg) for arg in raw[offset:].split(b'\0')[:argc]]

    def cwd(self, pid):
        info = self.info(pid, 9, VnodeInfo)
        return Path(os.fsdecode(info.cwd.path)) if info and info.cwd.path else None

    def cpu(self, pid):
        t = self.info(pid, 4, TaskInfo)
        return (t.user + t.system) * self.seconds_per_tick if t else None

    def all(self):
        ids = ctypes.create_string_buffer(4 * 65536)
        n = self.lib.proc_listallpids(ids, len(ids))
        return {pid: record for pid in ctypes.cast(ids, ctypes.POINTER(ctypes.c_int))[:n]
                if pid > 1 and (record := self.identity(pid))}


def ancestry(pid, processes):
    chain = set()
    while pid in processes and pid not in chain:
        chain.add(pid)
        pid = processes[pid]['ppid']
    return chain


def cwd(pid):
    return MacProcesses().cwd(pid)


def within(path, roots):
    return path is not None and any(path == r or r in path.parents for r in roots)


def host_registration_path(token):
    if len(token) != 32 or any(char not in '0123456789abcdef' for char in token):
        raise RuntimeError('Invalid owned host registration token.')
    return STATE / 'host-active' / (token + '.json')


def release_host_registration(token, owner, root):
    if token is None:
        return
    path = host_registration_path(token)
    try:
        record = json.loads(path.read_text())
        if record.get('owner') == owner and record.get('root') == root:
            path.unlink(missing_ok=True)
    except (OSError, ValueError):
        pass


def registered_host_roots(proc, roots):
    """Only a live explicit launcher registration admits a native Java family."""
    admitted = []
    for path in (STATE / 'host-active').glob('*.json'):
        try:
            if path.stat().st_uid != os.getuid():
                continue
            record = json.loads(path.read_text())
            root = record['root']
            current = proc.identity(root['pid'])
            # A launcher root legitimately execs nice/shell/Java. Its birth and
            # dedicated process group remain stable; each target path is checked
            # afresh by Controller before STOP/CONT.
            if (proc.same(record['owner']) and same_birth(current, root)
                    and current['pgid'] == root['pid']
                    and within(Path(record['checkout']).resolve(), roots)):
                admitted.append(current)
        except (OSError, ValueError, KeyError, TypeError):
            continue
    return admitted


def select_targets(processes, roots, excluded, cwd_fn=cwd, allow_apps=True, owned_roots=()):
    """Only registered agent Java and renderer descendants of exact app roots qualify."""
    apps = {pid for pid, p in processes.items() if p['path'] in (
        '/Applications/ChatGPT.app/Contents/MacOS/ChatGPT',
        '/Applications/Codex.app/Contents/MacOS/Codex')}
    targets = {}
    for pid, p in processes.items():
        if pid in excluded or p['status'] == 4 or p['uid'] != os.getuid():
            continue
        path = p['path']
        if (Path(path).name == 'java' and within(cwd_fn(pid), roots)
                and any(p.get('pgid') == root['pid'] and p['start'] >= root['start']
                        and root['pid'] in ancestry(pid, processes) for root in owned_roots)):
            targets[pid] = dict(p, family='java')
        elif allow_apps and ('/Applications/ChatGPT.app/Contents/' in path or
              '/Applications/Codex.app/Contents/' in path) and '(Renderer)' in Path(path).name:
            if ancestry(pid, processes) & apps:
                targets[pid] = dict(p, family='renderers')
    return targets


def duty_plan(demand, caps, total):
    duties = {k: min(1.0, caps[k] / max(v, 0.001)) for k, v in demand.items()}
    admitted = sum(demand[k] * duties[k] for k in demand)
    scale = min(1.0, total / max(admitted, 0.001))
    return {k: max(0.01, v * scale) for k, v in duties.items()}


def send_cont(records, processes):
    for p in records:
        if processes.same(p):
            try:
                os.kill(p['pid'], signal.SIGCONT)
            except ProcessLookupError:
                pass


def packet_send(channel, data):
    payload = json.dumps(data).encode()
    channel.sendall(struct.pack('!I', len(payload)) + payload)


def packet_receive(channel):
    def read(size):
        data = bytearray()
        while len(data) < size:
            part = channel.recv(size - len(data))
            if not part:
                raise EOFError
            data.extend(part)
        return bytes(data)
    size = struct.unpack('!I', read(4))[0]
    if size > 65536:
        raise ValueError('Oversized watchdog packet')
    return json.loads(read(size))


def watchdog(fd):
    """Runs independently: a stalled/dead controller cannot leave an armed PID stopped."""
    proc = MacProcesses()
    channel = socket.socket(fileno=fd)
    channel.settimeout(1)
    armed, deadline = [], 0.0
    packet_send(channel, {'ready': True})
    while True:
        try:
            ready, _, _ = select.select([channel], [], [], 0.05 if armed else None)
            if not ready:
                if armed and time.monotonic() > deadline:
                    send_cont(armed, proc)
                continue
            command = packet_receive(channel)
            if command['action'] == 'arm':
                armed = command['records']
                deadline = time.monotonic() + 0.25
                packet_send(channel, {'ack': True})
            elif command['action'] == 'release':
                send_cont(armed, proc)
                armed = []
            elif command['action'] == 'close':
                send_cont(armed, proc)
                return
        except socket.timeout:
            if armed and time.monotonic() > deadline:
                # Repeat CONT after lease expiry, covering a delayed STOP after acknowledgement.
                send_cont(armed, proc)
        except (OSError, ValueError, EOFError, KeyboardInterrupt):
            send_cont(armed, proc)
            return


class Controller:
    def __init__(self, processes):
        self.processes = processes
        self.stopped = []
        self.channel, child = socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM)
        self.channel.settimeout(1)
        self.helper = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '_watchdog',
                                       str(child.fileno())], pass_fds=(child.fileno(),), start_new_session=True)
        child.close()
        self.channel.settimeout(10)
        if packet_receive(self.channel) != {'ready': True}:
            raise RuntimeError('Watchdog failed to initialize.')
        self.channel.settimeout(1)

    def pause(self, records):
        owned = {p['pid'] for p in self.stopped}
        eligible = []
        for p in records:
            current = self.processes.identity(p['pid'])
            if current and self.processes.same(p) and (current['status'] != 4 or p['pid'] in owned):
                eligible.append(p)
        records = eligible
        packet_send(self.channel, {'action': 'arm', 'records': records})
        started = time.monotonic()
        if packet_receive(self.channel) != {'ack': True} or time.monotonic() - started > 0.15:
            raise RuntimeError('Watchdog acknowledgement expired; refusing to STOP.')
        for p in records:
            if self.helper.poll() is not None:
                raise RuntimeError('Watchdog exited; refusing to STOP.')
            current = self.processes.identity(p['pid'])
            if current and self.processes.same(p) and current['status'] != 4:
                self.stopped.append(p)  # Record before STOP, so signal/error cleanup owns it.
                os.kill(p['pid'], signal.SIGSTOP)

    def resume(self):
        send_cont(self.stopped, self.processes)
        self.stopped = []
        packet_send(self.channel, {'action': 'release'})

    def close(self):
        send_cont(self.stopped, self.processes)
        self.stopped = []
        try:
            packet_send(self.channel, {'action': 'close'})
        except OSError:
            pass
        self.channel.close()
        self.helper.wait(timeout=3)


def stop_signal(_sig, _frame):
    raise KeyboardInterrupt


def save_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(data, indent=2) + '\n')
    temp.replace(path)


def monitor(args):
    proc = MacProcesses()
    roots = [Path(r).resolve() for r in args.root]
    if args.project:
        inventory = subprocess.check_output(['git', '-C', args.project, 'worktree', 'list', '--porcelain'], text=True, env={**os.environ, 'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_CONFIG_SYSTEM': '/dev/null'})
        roots.extend(Path(line[9:]).resolve() for line in inventory.splitlines() if line.startswith('worktree '))
    if not roots:
        raise RuntimeError('Specify --project checkout or an explicit --root.')
    processes = proc.all()
    excluded = ancestry(os.getpid(), processes)
    STATE.mkdir(parents=True, exist_ok=True)
    lock = (STATE / 'monitor.lock').open('w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    controller = Controller(proc) if args.enforce else None
    if controller:
        excluded.add(controller.helper.pid)
    identity = proc.identity(os.getpid())
    save_json(STATE / 'monitor.json', identity)
    root_inventory_time = time.monotonic()
    samples, duties, last, next_scan = {}, {}, time.monotonic(), 0.0
    targets, demand, rates = {}, {}, {}
    caps = {'java': args.java_cpus, 'renderers': args.app_cpus}
    try:
        while True:
            now = time.monotonic()
            if now >= next_scan:
                processes = proc.all()
                if args.project and now - root_inventory_time >= 60:
                    # Discover worktrees asynchronously, never blocking the PWM cycle on Git.
                    if 'inventory_child' not in locals():
                        inventory_child = subprocess.Popen(['git', '-C', args.project, 'worktree', 'list', '--porcelain'], stdout=subprocess.PIPE, env={**os.environ, 'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_CONFIG_SYSTEM': '/dev/null'})
                    elif inventory_child.poll() is not None:
                        text = inventory_child.stdout.read().decode();inventory_child.stdout.close()
                        roots = [Path(line[9:]).resolve() for line in text.splitlines() if line.startswith('worktree ')] or roots
                        del inventory_child
                        root_inventory_time = now
                owned_roots = registered_host_roots(proc, roots)
                targets = select_targets(processes, roots, excluded, proc.cwd, not args.java_only, owned_roots)
                rates = {'java': 0.0, 'renderers': 0.0}
                fresh = {}
                for pid, p in targets.items():
                    cpu = proc.cpu(pid)
                    old = samples.get(pid)
                    fresh[pid] = (p['start'], cpu)
                    if old and old[0] == p['start'] and cpu is not None and old[1] is not None:
                        rates[p['family']] += max(0, cpu - old[1]) / max(now - last, 0.001)
                samples = fresh
                demand = {k: v / max(duties.get(k, 1), 0.01) for k, v in rates.items()}
                docker_quota = active_docker_quota(proc)
                native_budget = min(args.cpus, max(0.25, args.cpus - docker_quota))
                duties = duty_plan(demand, caps, native_budget)
                thread_info = {pid: proc.info(pid, 4, TaskInfo) for pid in targets}
                save_json(STATE / 'status.json', {'time': time.time(), 'enforce': args.enforce,
                    'budget_cores': args.cpus, 'native_budget_cores': native_budget,
                    'docker_quota_cores': docker_quota, 'actual_cores': rates, 'duty': duties,
                    'targets': [{'pid': p['pid'], 'start': p['start'], 'family': p['family'],
                                 'executable': p['path']} for p in targets.values()],
                    'threads': {k: {'total': sum(thread_info[p['pid']].threads for p in targets.values() if p['family']==k and thread_info[p['pid']]),
                                    'runnable': sum(thread_info[p['pid']].running for p in targets.values() if p['family']==k and thread_info[p['pid']])} for k in caps},
                    'load_averages_1_5_15': list(os.getloadavg()),
                    'scan_ms': round((time.monotonic()-now)*1000,3),
                    'excluded_controller_ancestors': sorted(excluded),
                    'java_scope': 'registered-agent-host-families',
                    'registered_host_roots': [{'pid': p['pid'], 'start': p['start']} for p in owned_roots]})
                last, next_scan = now, now + 1
            if args.once:
                print((STATE / 'status.json').read_text())
                return
            if controller and not any(duties.get(p['family'], 1) < 0.99 for p in targets.values()):
                if controller.stopped:
                    controller.resume()
                time.sleep(max(0, next_scan - time.monotonic()))
            elif controller:
                # Each family resumes together, then pauses for its remaining 100ms PWM cycle.
                start = time.monotonic()
                steps = sorted({min(0.1, duties.get(p['family'], 1) * 0.1) for p in targets.values()})
                stopped = []
                for at in steps:
                    time.sleep(max(0, start + at - time.monotonic()))
                    additions = [p for p in targets.values() if duties.get(p['family'], 1) * 0.1 <= at and at < 0.099]
                    known = {(p['pid'], tuple(p['start'])) for p in stopped}
                    stopped.extend(p for p in additions if (p['pid'], tuple(p['start'])) not in known)
                    if additions:
                        controller.pause(stopped)
                time.sleep(max(0, start + 0.1 - time.monotonic()))
                controller.resume()
            else:
                time.sleep(1)
    finally:
        if controller:
            controller.close()
        (STATE / 'monitor.json').unlink(missing_ok=True)


def build_records():
    """Read independent reservations, including an already-running legacy build."""
    for path in STATE.glob('docker-active*.json'):
        try:
            yield path, json.loads(path.read_text())
        except (OSError, ValueError):
            continue


def reservation_path(name):
    return STATE / ('docker-active-' + name + '.json')


def release_reservation(name):
    for path, record in build_records():
        if record.get('name') == name:
            path.unlink(missing_ok=True)


def active_docker_quota(proc):
    total = 0.0
    for _, record in build_records():
        try:
            if proc.same(record['owner']) or (record.get('guardian') and proc.same(record['guardian'])):
                total += record['cpus']
        except (KeyError, TypeError):
            continue
    return total


def docker_environment():
    return {k: v for k, v in os.environ.items() if k not in ('DOCKER_CONTEXT', 'DOCKER_HOST')}


def docker_endpoint():
    endpoint = os.environ.get('DOCKER_HOST')
    if not endpoint:
        context = os.environ.get('DOCKER_CONTEXT') or subprocess.check_output(['docker', 'context', 'show'], text=True).strip()
        endpoint = subprocess.check_output(['docker', 'context', 'inspect', context, '--format', '{{.Endpoints.docker.Host}}'], text=True).strip()
    if not endpoint.startswith('unix://'):
        raise RuntimeError('CPU builds require an explicit local Unix Docker endpoint on this Mac.')
    return endpoint


def stop_container(name, token, endpoint=None):
    endpoint = endpoint or docker_endpoint()
    prefix = ['docker', '--host', endpoint]
    result = subprocess.run(prefix + ['inspect', '--format',
                             '{{.Id}} {{index .Config.Labels "tolgraven.cpu.token"}} {{.State.Running}}', name],
                            capture_output=True, text=True, timeout=10, env=docker_environment())
    if result.returncode != 0:
        error = result.stderr.lower()
        return name.lower() in error and ('no such object:' in error or 'no such container:' in error)
    values = result.stdout.strip().split()
    if len(values) != 3:
        return False
    if values[1] != token or values[2] == 'false':
        return True
    stopped = subprocess.run(prefix + ['stop', '--time', '3', values[0]], capture_output=True,
                             timeout=15, env=docker_environment())
    return stopped.returncode == 0


def guardian_protocol(argv, record):
    try:
        marker = argv.index('_docker-watchdog')
        if marker < 2 or Path(argv[marker - 1]).name != 'cpu_controls.py':
            raise ValueError
        arguments = argv[marker + 1:]
        if len(arguments) < 3 or arguments[1] != record['name']:
            raise ValueError
        owner = json.loads(arguments[0])
        if not same_birth(owner, record['owner']):
            raise ValueError
        token = arguments[2]
        if record.get('token', token) != token:
            raise ValueError
        options = arguments[3:]
        seen = {}
        if len(options) % 2:
            raise ValueError
        for key, value in zip(options[::2], options[1::2]):
            if key not in ('--endpoint', '--ready-fd', '--label-key') or key in seen:
                raise ValueError
            if key == '--ready-fd' and int(value) < 0:
                raise ValueError
            if key == '--label-key' and value != 'tolgraven.cpu.token':
                raise ValueError
            seen[key] = value
        endpoint = seen.get('--endpoint')
        if endpoint and record.get('endpoint', endpoint) != endpoint:
            raise ValueError
        return token, endpoint
    except (ValueError, KeyError, TypeError) as error:
        raise RuntimeError('Guardian argv does not own the recorded build protocol.') from error


def recover_build(args):
    proc = MacProcesses()
    records = [(path, record) for path, record in build_records()
               if not args.name or record.get('name') == args.name]
    if len(records) != 1:
        names = ', '.join(record.get('name', '<unknown>') for _, record in records)
        raise RuntimeError('Select exactly one recorded build with --name. Recorded: ' + names)
    _, record = records[0]
    if same_birth(proc.identity(record['owner']['pid']), record['owner']):
        raise RuntimeError('Build owner is alive; refusing to recover its slot.')
    guardian = proc.identity(record['guardian']['pid'])
    if not same_birth(guardian, record['guardian']) or guardian['path'] != record['owner']['path']:
        raise RuntimeError('Guardian identity does not match the recorded orphan.')
    token, protocol_endpoint = guardian_protocol(proc.argv(guardian['pid']), record)
    endpoint = record.get('endpoint') or protocol_endpoint or args.endpoint
    if not endpoint:
        raise RuntimeError('Legacy reservation requires the original explicit --endpoint.')
    if not stop_container(record['name'], token, endpoint):
        raise RuntimeError('Owned container cleanup remains unconfirmed; keeping the slot.')
    if same_birth(proc.identity(guardian['pid']), guardian):
        os.kill(guardian['pid'], signal.SIGTERM)
    deadline = time.monotonic() + 3
    while same_birth(proc.identity(guardian['pid']), guardian) and time.monotonic() < deadline:
        time.sleep(0.05)
    if same_birth(proc.identity(guardian['pid']), guardian):
        raise RuntimeError('Guardian has not exited; slot remains owned.')
    release_reservation(record['name'])
    print('Recovered confirmed orphan:', record['name'], 'guardian', guardian['pid'])


def repair_legacy_reservation(proc, owner, name, token, endpoint):
    for path, record in build_records():
        guardian = record.get('guardian')
        if record.get('name') == name and guardian and guardian['pid'] == os.getpid() and same_birth(record['owner'], owner):
            stable = proc.identity(os.getpid())
            if record.get('endpoint') != endpoint or guardian != stable:
                record.update(guardian=stable, endpoint=endpoint, token=token)
                save_json(path, record)


def docker_watchdog(owner, name, token, endpoint=None, ready_fd=None):
    proc = MacProcesses()
    endpoint = endpoint or docker_endpoint()
    if ready_fd is not None:
        ready = socket.socket(fileno=ready_fd)
        try:
            packet_send(ready, {'guardian': proc.identity(os.getpid())})
        except OSError:
            pass  # Parent death still falls through to owned container cleanup.
        finally:
            ready.close()
    while proc.same(owner):
        if ready_fd is None:
            # Already-queued older parents register before the interpreter's exec settles.
            # Their new guardian repairs only its own same-birth named reservation.
            repair_legacy_reservation(proc, owner, name, token, endpoint)
        time.sleep(0.5)
    while True:
        try:
            if stop_container(name, token, endpoint):
                release_reservation(name)
                return
        except subprocess.SubprocessError:
            pass
        time.sleep(1)


def start_docker_guardian(owner, name, token, endpoint, lock, extra_locks=()):
    parent, child = socket.socketpair()
    parent.settimeout(10)
    guard = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '_docker-watchdog',
                              json.dumps(owner), name, token, '--endpoint', endpoint,
                              '--ready-fd', str(child.fileno())], start_new_session=True,
                              pass_fds=(lock.fileno(), child.fileno(), *(item.fileno() for item in extra_locks)))
    child.close()
    try:
        identity = packet_receive(parent)['guardian']
        if not identity or identity['pid'] != guard.pid or identity['path'] != owner['path'] or not MacProcesses().same(identity):
            raise RuntimeError('Docker guardian did not acknowledge its stable interpreter identity.')
        return guard, identity
    finally:
        parent.close()


def same_birth(current, record):
    return bool(current and all(current[k] == record[k] for k in ('pid', 'start', 'uid')))


def stop_host_family(root):
    proc = MacProcesses()
    current = proc.identity(root['pid'])
    if current and not same_birth(current, root):
        return True
    processes = proc.all()
    # This launcher created a new session/group. Enumerate individual identities,
    # including reparented members; never send a negative/group-wide signal.
    owned = [p for p in processes.values() if p['pgid'] == root['pid'] and p['start'] >= root['start']]
    for p in reversed(owned):
        if proc.same(p):
            try:
                os.kill(p['pid'], signal.SIGCONT)
                os.kill(p['pid'], signal.SIGTERM)
            except ProcessLookupError:
                pass
    end = time.monotonic() + 5
    while time.monotonic() < end:
        if not any(proc.same(p) for p in owned):
            return True
        time.sleep(0.1)
    return False


def host_watchdog(owner, root, registration=None):
    proc = MacProcesses()
    while proc.same(owner):
        time.sleep(0.5)
    while not stop_host_family(root):
        time.sleep(1)
    release_host_registration(registration, owner, root)


def host_environment(environment, heap='1024m'):
    import re
    env = dict(environment)
    existing = env.get('JAVA_TOOL_OPTIONS', '')
    # HotSpot processes later repeated flags last; preserve unrelated properties
    # and explicit heap sizing, while always enforcing the owned worker bounds.
    options = existing.strip() + ' ' + JVM_POOLS
    if not re.search(r'(?:^|\s)(?:-Xmx\S+|-XX:MaxRAM(?:Percentage|Fraction)?=\S+)', existing):
        options += ' -Xmx' + heap
    env['JAVA_TOOL_OPTIONS'] = options.strip()
    return env


def host_run(args, command, lock, extra_env=None):
    env = host_environment(os.environ, getattr(args, 'java_heap', '1024m'))
    if extra_env:
        for key, value in extra_env.items():
            if value is None:
                env.pop(key, None)
            else:
                env[key] = value
    child = subprocess.Popen(['nice', '-n', '10', *command], cwd=args.checkout, env=env,
                             start_new_session=True)
    proc = MacProcesses()
    owner = proc.identity(os.getpid())
    root = proc.identity(child.pid)
    if root is None:
        return child.wait()
    registration = uuid.uuid4().hex
    guard = None
    try:
        record_path = host_registration_path(registration)
        record_path.parent.mkdir(parents=True, exist_ok=True)
        save_json(record_path, {'owner': owner, 'root': root, 'checkout': str(Path(args.checkout).resolve())})
        guard = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '_host-watchdog',
                                  json.dumps(owner), json.dumps(root), '--registration', registration],
                                 start_new_session=True, pass_fds=(lock.fileno(),) if lock else ())
        return child.wait()
    finally:
        if stop_host_family(root):
            release_host_registration(registration, owner, root)
            if guard is not None:
                guard.terminate()
                guard.wait(timeout=3)
        else:
            print('Host family cleanup pending; watchdog retains ownership and any build lock.', file=sys.stderr)


def try_build_lock():
    """Acquire one of two OS locks; guardians inherit the selected descriptor."""
    STATE.mkdir(parents=True, exist_ok=True)
    for index in range(BUILD_SLOTS):
        filename = 'build.lock' if index == 0 else 'build-' + str(index) + '.lock'
        handle = (STATE / filename).open('a')
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return handle
        except BlockingIOError:
            handle.close()
    return None


def build_lock():
    print('Waiting for one of two shared agent build slots…', flush=True)
    while True:
        handle = try_build_lock()
        if handle:
            return handle
        time.sleep(0.25)


CONFIG_EXAMPLES = (('config/dev.example.edn', 'env/dev/resources/config.edn'),
                   ('config/test.example.edn', 'env/test/resources/config.edn'))


def snapshot(checkout, destination):
    raw = subprocess.check_output(['git', '-C', str(checkout), 'ls-files', '-z', '--cached',
                                   '--others', '--exclude-standard'])
    private_configs = {target for _, target in CONFIG_EXAMPLES}
    for name in dict.fromkeys(os.fsdecode(raw).split('\0')):
        if not name or name in private_configs or name.startswith(('config/local.', '.local-wip/')):
            continue
        source = checkout / name
        if not source.is_file() or source.is_symlink():
            continue
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    tracked = set(os.fsdecode(subprocess.check_output(
        ['git', '-C', str(checkout), 'ls-files', '-z', '--cached'])).split('\0'))
    for example, runtime in CONFIG_EXAMPLES:
        source = destination / example
        target = destination / runtime
        if example in tracked and source.is_file() and not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)


SHADOW_CACHE_VERSION = 1
SHADOW_CACHE_BYTES = 2 * 1024 ** 3
SHADOW_CACHE_AGE = 7 * 86400


def repository_root(checkout=None):
    inventory = subprocess.check_output(
        ['git', '-C', str(checkout or Path.cwd()), 'worktree', 'list', '--porcelain'], text=True)
    first = inventory.splitlines()[0]
    if not first.startswith('worktree '):
        raise RuntimeError('Cannot resolve the primary repository checkout.')
    return Path(first.removeprefix('worktree ')).resolve()


def shadow_cache_root(checkout=None):
    repository = repository_root(checkout).resolve()
    root = repository / '.local-wip/shadow-caches'
    if not root.resolve().is_relative_to(repository):
        raise RuntimeError('Compiler cache directory escapes the repository.')
    return root


def shadow_cache_key(checkout, output, image_id, command):
    import re
    # Helper/reporting text and source revisions are deliberately not identities.
    # Shadow owns build ID/mode/options and validates changed source/macro keys.
    declarations = [' '.join(command)]
    for argument in command:
        if len(argument) > 1024 or not argument.endswith(('.sh', '.clj', '.py')):
            continue
        path = output / argument
        if path.resolve().is_relative_to(output.resolve()) and path.is_file():
            declarations.append(path.read_text())
    profiles = sorted(set(re.findall(r'with-profile[\"\']?\s+[\"\']?([+\w,./-]+)', '\n'.join(declarations))))
    fingerprints = {}
    for name in ('project.clj', 'package.json', 'package-lock.json', 'resources/site.edn', 'resources/site.overrides.edn'):
        path = output / name
        fingerprints[name] = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
    policy = [SHADOW_CACHE_VERSION, str(checkout.resolve()), image_id, profiles or ['default'], fingerprints]
    return hashlib.sha256(json.dumps(policy, sort_keys=True).encode()).hexdigest()[:24]


def cache_lock(key, blocking=True):
    # Keep the original lock inodes while older installed launchers may still run.
    locks = STATE / 'shadow-caches/locks'
    locks.mkdir(parents=True, exist_ok=True)
    handle = (locks / (key + '.lock')).open('a')
    try:
        fcntl.flock(handle, fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB))
        return handle
    except BlockingIOError:
        handle.close()
        return None


def cache_bytes(path):
    size = 0
    for item in path.rglob('*'):
        try:
            if item.is_file() and not item.is_symlink():
                size += item.stat().st_size
        except FileNotFoundError:
            pass  # An orphan guardian may still own a writer replacing a cache file.
    return size


def prune_shadow_caches(now=None, checkout=None, *, apply=True, legacy=False, all_unused=False):
    now = time.time() if now is None else now
    import re
    root = STATE / 'shadow-caches' if legacy else shadow_cache_root(checkout)
    if root.is_symlink():
        raise RuntimeError('Refusing a symlinked compiler cache root.')
    records = []
    for metadata in root.glob('*/metadata.json'):
        try:
            record = json.loads(metadata.read_text())
            if (record.get('version') == SHADOW_CACHE_VERSION and record.get('key') == metadata.parent.name
                    and re.fullmatch('[0-9a-f]{24}', metadata.parent.name)
                    and isinstance(record.get('used_at'), (float, int))
                    and isinstance(record.get('bytes'), int) and record['bytes'] >= 0
                    and not metadata.is_symlink() and not metadata.parent.is_symlink()):
                handle = cache_lock(record['key'], blocking=False)
                size = record['bytes']
                if handle is not None:
                    with handle:
                        size = cache_bytes(metadata.parent / 'builds')
                records.append((record, metadata.parent, size))
        except (OSError, ValueError, TypeError):
            continue
    total = sum(size for _, _, size in records)
    removed = []
    candidates = []
    errors = []
    for record, path, size in sorted(records, key=lambda pair: pair[0]['used_at']):
        owner = record.get('checkout')
        obsolete = isinstance(owner, str) and not Path(owner).exists()
        selected = owner is not None and checkout is not None and Path(owner).resolve() == Path(checkout).resolve()
        if not ((all_unused and (legacy or selected)) or obsolete
                or now - record['used_at'] >= SHADOW_CACHE_AGE or total > SHADOW_CACHE_BYTES):
            continue
        handle = cache_lock(record['key'], blocking=False)
        if handle is None:
            continue
        with handle:
            # Re-read after acquiring: a completed worker may have refreshed its lease.
            try:
                current = json.loads((path / 'metadata.json').read_text())
            except (OSError, ValueError):
                continue
            if current != record:
                continue
            candidates.append(record['key'])
            if apply:
                try:
                    shutil.rmtree(path)
                except OSError as error:
                    # Finder may recreate .DS_Store during traversal. Preserve the
                    # ownership marker if deletion was partial; retry on a later pass.
                    if path.exists():
                        save_json(path / 'metadata.json', record)
                    errors.append({'key': record['key'], 'error': str(error)})
                    continue
                removed.append(record['key'])
            total -= size
    return {'root': str(root), 'candidates': candidates, 'removed': removed, 'errors': errors,
            'retained_bytes': total if apply else sum(size for _, _, size in records),
            'planned_retained_bytes': total}


@contextmanager
def shadow_cache(args, output, command, endpoint):
    if args.shadow_cache == 'off' or not (output / 'shadow-cljs.edn').exists():
        yield None
        return
    import re
    config = (output / 'shadow-cljs.edn').read_text()
    if re.search(r':cache-root\s', config):
        print('Shadow cache: bypassed (custom cache-root)', flush=True)
        yield None
        return
    image = subprocess.run(['docker', '--host', endpoint, 'image', 'inspect', '--format', '{{.Id}}', args.image],
                           capture_output=True, text=True, timeout=30, env=docker_environment())
    if image.returncode or not image.stdout.strip().startswith('sha256:'):
        # Docker can pull the prefab normally; its next run can reuse a known immutable image.
        print('Shadow cache: bypassed (prefab image identity unavailable)', flush=True)
        yield None
        return
    prune_shadow_caches(checkout=args.checkout)
    key = shadow_cache_key(Path(args.checkout), output, image.stdout.strip(), command)
    with cache_lock(key) as handle:
        directory = shadow_cache_root(args.checkout) / key
        if directory.is_symlink():
            raise RuntimeError('Refusing a symlinked compiler cache directory.')
        if args.shadow_cache == 'refresh' and directory.exists():
            shutil.rmtree(directory)
        builds = directory / 'builds'
        builds.mkdir(parents=True, exist_ok=True)
        record = {'version': SHADOW_CACHE_VERSION, 'key': key, 'checkout': str(Path(args.checkout).resolve()),
                  'used_at': time.time(), 'bytes': cache_bytes(builds)}
        save_json(directory / 'metadata.json', record)
        print('Shadow cache:', key, '(worktree-scoped disk cache)', flush=True)
        try:
            yield builds, handle, image.stdout.strip()
        finally:
            # A surviving guardian retains the inherited lock while its container
            # can still write. No other invocation/pruner can enter that identity.
            record.update(used_at=time.time(), bytes=cache_bytes(builds))
            save_json(directory / 'metadata.json', record)
    prune_shadow_caches(checkout=args.checkout)


def buildkit_options(cpus, memory):
    return [f'cpu-quota={round(cpus * 100000)}', 'cpu-period=100000',
            f'memory={memory}', f'memory-swap={memory}', 'default-load=true',
            'restart-policy=no']


def validate_buildkit_container(container, builder, cpus, memory):
    host = container['HostConfig']
    if (container['Config'].get('Labels', {}).get('tolgraven.cpu.token') != builder
            or host.get('CpuQuota') != round(cpus * 100000)
            or host.get('CpuPeriod') != 100000
            or host.get('Memory') != memory
            or host.get('MemorySwap') != memory):
        raise RuntimeError('BuildKit container ownership or quota does not match; refusing the build.')


def buildkit_run(args, command, lock):
    endpoint = docker_endpoint()
    expected_memory = parse_memory(args.memory)
    if expected_memory < 6 * 1024 * 1024 or round(args.cpus * 100000) < 1000:
        raise RuntimeError('BuildKit requires at least 6 MiB memory and 0.01 CPU.')
    # Each admitted slot owns a separate reusable daemon and cache volume.
    policy = json.dumps([os.getuid(), endpoint, args.cpus, args.memory, str(lock.name)])
    builder = 'tolgraven-cpu-' + hashlib.sha256(policy.encode()).hexdigest()[:12]
    name = 'buildx_buildkit_' + builder + '0'
    prefix = ['docker', '--host', endpoint]
    owner = MacProcesses().identity(os.getpid())
    record = {'owner': owner, 'cpus': args.cpus, 'name': name, 'endpoint': endpoint,
              'token': builder}
    save_json(reservation_path(name), record)
    guardian, identity = start_docker_guardian(owner, name, builder, endpoint, lock)
    save_json(reservation_path(name), dict(record, guardian=identity))
    try:
        config = STATE / (builder + '.toml')
        config.write_text('[worker.oci]\n  max-parallelism = 1\n  gc = true\n'
                          '  reservedSpace = "256MB"\n  maxUsedSpace = "2GB"\n'
                          '  minFreeSpace = "20GB"\n')
        inspected = subprocess.run(prefix + ['buildx', 'inspect', builder], capture_output=True,
                                   text=True, env=docker_environment(), timeout=30)
        if inspected.returncode != 0:
            if 'no builder' not in inspected.stderr.lower():
                raise RuntimeError('Unable to inspect the owned BuildKit builder.')
            create = prefix + ['buildx', 'create', '--name', builder, '--driver', 'docker-container',
                               '--buildkitd-config', str(config)]
            for option in buildkit_options(args.cpus, args.memory):
                create.extend(['--driver-opt', option])
            subprocess.run(create + [endpoint], check=True, env=docker_environment())
        # Buildx does not label its daemon containers. Create our exact expected
        # daemon first with the ordinary launcher label, then let Buildx connect
        # to it. This keeps the existing guardian/recovery protocol unchanged.
        existing = subprocess.run(prefix + ['inspect', name], capture_output=True,
                                  text=True, env=docker_environment())
        if existing.returncode != 0:
            if not (name in existing.stderr and 'no such' in existing.stderr.lower()):
                raise RuntimeError('Unable to inspect the owned BuildKit container.')
            subprocess.run(prefix + ['create', '--name', name, '--privileged', '--init',
                '--label', 'tolgraven.cpu.token=' + builder,
                '--cpu-quota', str(round(args.cpus * 100000)), '--cpu-period', '100000',
                '--memory', args.memory, '--memory-swap', args.memory, '--restart', 'no',
                '--mount', f'type=volume,source={name}_state,target=/var/lib/buildkit',
                '--mount', f'type=bind,source={config},target=/etc/buildkit/buildkitd.toml,readonly',
                'moby/buildkit:buildx-stable-1', '--config', '/etc/buildkit/buildkitd.toml'],
                check=True, env=docker_environment())
        else:
            validate_buildkit_container(json.loads(existing.stdout)[0], builder, args.cpus, expected_memory)
        subprocess.run(prefix + ['buildx', 'inspect', '--bootstrap', builder], check=True,
                       env=docker_environment())
        inspected_container = subprocess.check_output(prefix + ['inspect', name], text=True,
                                                       env=docker_environment())
        container = json.loads(inspected_container)[0]
        validate_buildkit_container(container, builder, args.cpus, expected_memory)
        print('BuildKit budget:', args.cpus, 'cores /', args.memory, 'builder', builder, flush=True)
        environment = {'DOCKER_CONTEXT': None, 'DOCKER_HOST': endpoint,
                       'BUILDX_BUILDER': builder, 'DOCKER_BUILDKIT': '1'}
        return host_run(args, command, lock, environment)
    finally:
        confirmed = False
        try:
            confirmed = stop_container(name, builder, endpoint)
        except subprocess.SubprocessError:
            pass
        if confirmed:
            guardian.terminate()
            guardian.wait(timeout=3)
            release_reservation(name)
        else:
            print('BuildKit cleanup pending; guardian retains the quota and slot.', file=sys.stderr)


def parse_memory(value):
    import re
    match = re.fullmatch(r'(\d+)([bkmg]?)', value.lower())
    if not match:
        raise RuntimeError('Use an integer Docker memory budget, such as 2g.')
    return int(match[1]) * {'': 1, 'b': 1, 'k': 1024, 'm': 1024 ** 2, 'g': 1024 ** 3}[match[2]]


def docker_bootstrap(node_deps):
    if node_deps == 'skip':
        return 'exec "$@"'
    return ('cpu_deps_started=$(date +%s); if cmp -s package-lock.json /opt/prefab-package-lock.json; then '
            'ln -s /usr/src/app/node_modules node_modules; else npm ci --no-audit --no-fund; fi; '
            'printf "Dependency setup: %ss\\n" "$(( $(date +%s) - cpu_deps_started ))"; '
            'exec "$@"')


def run(args):
    command = args.command
    if command and command[0] == '--':
        command = command[1:]
    if not command:
        raise RuntimeError('Pass a command after --.')
    if args.action == 'runtime':
        return host_run(args, command, None)
    if args.cpus > BUILD_CPUS:
        raise RuntimeError('Each of two build slots permits at most 1.5 CPUs; the shared Docker ceiling is 3 CPUs.')
    queued = time.monotonic()
    with build_lock() as lock:
        print(f'Build slot wait: {time.monotonic() - queued:.3f}s', flush=True)
        if args.mode == 'host':
            return host_run(args, command, lock)
        if args.mode == 'buildkit':
            return buildkit_run(args, command, lock)
        output = Path(args.checkout).resolve() / '.local-wip/cpu-builds' / uuid.uuid4().hex[:12]
        output.mkdir(parents=True)
        snapshot(Path(args.checkout).resolve(), output)
        shadow = output / 'shadow-cljs.edn'
        if shadow.exists():
            import re
            shadow.write_text(re.sub(r':parallel-build\s+true', ':parallel-build false', shadow.read_text()))
        endpoint = docker_endpoint()
        cache_context = shadow_cache(args, output, command, endpoint)
        with cache_context as cache:
            return docker_run(args, command, lock, output, endpoint, cache)


def docker_run(args, command, lock, output, endpoint, cache):
    maven_repository = Path.home() / '.m2/repository'
    maven_repository.mkdir(parents=True, exist_ok=True)
    name = 'tolgraven-cpu-' + output.name
    token = uuid.uuid4().hex
    owner = MacProcesses().identity(os.getpid())
    save_json(reservation_path(name), {'owner': owner, 'cpus': args.cpus, 'name': name, 'endpoint': endpoint, 'token': token})
    guardian, guardian_identity = start_docker_guardian(owner, name, token, endpoint, lock, (cache[1],) if cache else ())
    save_json(reservation_path(name), {'owner': owner, 'guardian': guardian_identity, 'cpus': args.cpus, 'name': name, 'endpoint': endpoint, 'token': token})
    bootstrap = docker_bootstrap(args.node_deps)
    image = cache[2] if cache else args.image
    cache_mount = ['--mount', f'type=bind,source={cache[0]},target=/workspace/.shadow-cljs/builds'] if cache else []
    cmd = ['docker', '--host', endpoint, 'run', '--rm', '--init', '--name', name, '--cpus', str(args.cpus),
           '--memory', args.memory, '--memory-swap', args.memory, '--pids-limit', '512',
           '--label', 'tolgraven.cpu.token=' + token,
           '--mount', f'type=bind,source={output},target=/workspace', '-w', '/workspace',
           '--mount', f'type=bind,source={maven_repository},target=/root/.m2/repository',
           '-e', f'JAVA_TOOL_OPTIONS={JVM_POOLS} -Xmx{args.java_heap}',
           *cache_mount, image, 'sh', '-c', bootstrap, 'cpu-build', *command]
    print('Isolated output:', output, '\nDocker budget:', args.cpus, 'cores /', args.memory, flush=True)
    child = subprocess.Popen(cmd, env=docker_environment())
    try:
        return child.wait()
    finally:
        # Exact owned container name; do not touch unrelated build/watch containers.
        try:
            confirmed = stop_container(name, token, endpoint)
        except subprocess.SubprocessError:
            confirmed = False
        if confirmed:
            release_reservation(name)
            guardian.terminate()
            guardian.wait(timeout=3)
        else:
            print('Docker cleanup pending; watchdog retains the build lock and quota reservation.', file=sys.stderr)


def positive(value):
    number = float(value)
    if not 0 < number <= 64:
        raise argparse.ArgumentTypeError('CPU budget must be greater than 0 and no more than 64 cores.')
    return number


def install_launcher():
    STATE.mkdir(parents=True, exist_ok=True)
    installed = STATE / 'cpu_controls.py'
    if Path(__file__).resolve() != installed.resolve():
        temporary = STATE / ('cpu_controls-' + uuid.uuid4().hex + '.tmp')
        shutil.copy2(__file__, temporary)
        os.replace(temporary, installed)
    binary = Path.home() / '.local/bin/tolgraven-cpu'
    binary.parent.mkdir(parents=True, exist_ok=True)
    binary.write_text('#!/bin/sh\nexec ' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(installed)) + ' "$@"\n')
    binary.chmod(0o755)
    return binary


def install(args):
    if args.launcher_only:
        print('Updated launcher:', install_launcher(), '(monitor and active workers preserved)')
        return
    STATE.mkdir(parents=True, exist_ok=True)
    label = 'se.tolgraven.cpu-controls'
    domain = f'gui/{os.getuid()}'
    subprocess.run(['launchctl', 'bootout', f'{domain}/{label}'], capture_output=True)
    binary = install_launcher()
    agent = Path.home() / 'Library/LaunchAgents' / (label + '.plist')
    agent.parent.mkdir(parents=True, exist_ok=True)
    config = {'Label': label,
        'ProgramArguments': [sys.executable, str(installed), 'monitor', '--project',
                             str(Path(args.project).resolve()), '--enforce', '--cpus', str(args.cpus)],
        'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 10,
        'StandardOutPath': str(STATE / 'monitor.log'), 'StandardErrorPath': str(STATE / 'monitor-error.log')}
    agent.write_bytes(plistlib.dumps(config))
    for attempt in range(3):
        result = subprocess.run(['launchctl', 'bootstrap', domain, str(agent)], capture_output=True, text=True)
        if result.returncode == 0:
            break
        time.sleep(0.5 * (attempt + 1))
    else:
        raise RuntimeError('Cannot load the CPU LaunchAgent: ' + result.stderr.strip())
    print('Installed launcher:', binary, '\nEnabled LaunchAgent:', agent)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    mon = commands.add_parser('monitor', help='Observe, or explicitly enforce scoped Java/renderer budgets.')
    mon.add_argument('--root', action='append', default=[])
    mon.add_argument('--project', help='Discover only registered worktrees of this project, including new ones.')
    mon.add_argument('--enforce', action='store_true')
    mon.add_argument('--once', action='store_true')
    mon.add_argument('--java-only', action='store_true', help='Diagnostic scope: exclude all app renderers.')
    mon.add_argument('--cpus', type=positive, default=1.5)
    mon.add_argument('--java-cpus', type=positive, default=1.5)
    mon.add_argument('--app-cpus', type=positive, default=1.0)
    runner = commands.add_parser('run', help='Run in one of two bounded, isolated build slots (3 Docker CPUs combined).')
    runner.add_argument('--mode', choices=['docker', 'host', 'buildkit'], default='docker')
    runner.add_argument('--checkout', default=os.getcwd())
    runner.add_argument('--java-heap', default='2048m')
    runner.add_argument('--cpus', type=positive, default=BUILD_CPUS)
    runner.add_argument('--memory', default='4g')
    runner.add_argument('--image', default='registry.bux.tolgraven.se/tolgraven/builder:java21-node22-v1')
    runner.add_argument('--node-deps', choices=['auto', 'skip'], default='auto',
                        help='Use skip only for non-Node tasks in custom images, such as media codecs.')
    runner.add_argument('--shadow-cache', choices=['reuse', 'off', 'refresh'], default='reuse',
                        help='Reuse isolated worktree/compiler cache, bypass it, or reset this identity before building.')
    runner.add_argument('command', nargs=argparse.REMAINDER)
    runtime = commands.add_parser('runtime', help='Supervised low-priority host runtime; does not hold the agent build queue.')
    runtime.add_argument('--checkout', default=os.getcwd())
    runtime.add_argument('command', nargs=argparse.REMAINDER)
    installer = commands.add_parser('install', help='Explicitly install/enable the scoped user LaunchAgent and launcher.')
    installer.add_argument('--project', required=True)
    installer.add_argument('--cpus', type=positive, default=1.5)
    installer.add_argument('--launcher-only', action='store_true', help='Atomically update future launcher invocations without restarting the monitor or workers.')
    recovery = commands.add_parser('recover-build', help='Recover only a recorded orphan after exact endpoint/container/guardian confirmation.')
    recovery.add_argument('--name', help='Exact recorded build name when more than one reservation exists.')
    recovery.add_argument('--endpoint', help='Required original local Docker endpoint for legacy reservations.')
    cache_parser = commands.add_parser('cache-prune', help='Prune unlocked repo caches; disappeared worktrees, seven idle days, or the 2 GiB budget.')
    cache_parser.add_argument('--checkout', default=os.getcwd())
    cache_parser.add_argument('--dry-run', action='store_true')
    cache_parser.add_argument('--legacy', action='store_true', help='Inspect/prune the former Application Support cache directory.')
    cache_parser.add_argument('--all-unused', action='store_true', help='Discard every unlocked cache owned by --checkout (all managed legacy identities with --legacy).')
    commands.add_parser('status')
    commands.add_parser('stop', help='Stop only the recorded monitor; watchdog resumes its owned paused targets.')
    wd = commands.add_parser('_watchdog')
    wd.add_argument('fd', type=int)
    dw = commands.add_parser('_docker-watchdog')
    dw.add_argument('owner')
    dw.add_argument('name')
    dw.add_argument('token')
    dw.add_argument('--endpoint')
    dw.add_argument('--ready-fd', type=int)
    dw.add_argument('--label-key', choices=['tolgraven.cpu.token'], default='tolgraven.cpu.token')
    hw = commands.add_parser('_host-watchdog')
    hw.add_argument('owner')
    hw.add_argument('root')
    hw.add_argument('--registration')
    args = parser.parse_args()
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, stop_signal)
    if args.action == '_host-watchdog':
        host_watchdog(json.loads(args.owner), json.loads(args.root), args.registration)
    elif args.action == '_docker-watchdog':
        docker_watchdog(json.loads(args.owner), args.name, args.token, args.endpoint, args.ready_fd)
    elif args.action == '_watchdog':
        watchdog(args.fd)
    elif args.action == 'monitor':
        monitor(args)
    elif args.action in ('run', 'runtime'):
        return run(args)
    elif args.action == 'install':
        install(args)
    elif args.action == 'recover-build':
        recover_build(args)
    elif args.action == 'cache-prune':
        print(json.dumps(prune_shadow_caches(checkout=args.checkout, apply=not args.dry_run,
                                              legacy=args.legacy, all_unused=args.all_unused), indent=2))
    elif args.action == 'status':
        print((STATE / 'status.json').read_text())
    elif args.action == 'stop':
        subprocess.run(['launchctl', 'bootout', f'gui/{os.getuid()}/se.tolgraven.cpu-controls'], capture_output=True)
        if not (STATE / 'monitor.json').exists():
            return 0
        record = json.loads((STATE / 'monitor.json').read_text())
        if MacProcesses().same(record):
            os.kill(record['pid'], signal.SIGTERM)
        else:
            raise RuntimeError('Recorded monitor identity is stale; refusing to signal that PID.')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
