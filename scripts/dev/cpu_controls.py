#!/usr/bin/env python3
"""Bounded macOS CPU ownership adapter and isolated, capped prefab build launcher."""
import argparse
import ctypes
import fcntl
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


def select_targets(processes, roots, excluded, cwd_fn=cwd, allow_apps=True):
    """Only project Java and renderer descendants of exact app bundle roots qualify."""
    apps = {pid for pid, p in processes.items() if p['path'] in (
        '/Applications/ChatGPT.app/Contents/MacOS/ChatGPT',
        '/Applications/Codex.app/Contents/MacOS/Codex')}
    targets = {}
    for pid, p in processes.items():
        if pid in excluded or p['status'] == 4 or p['uid'] != os.getuid():
            continue
        path = p['path']
        if Path(path).name == 'java' and within(cwd_fn(pid), roots):
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
                targets = select_targets(processes, roots, excluded, proc.cwd, not args.java_only)
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
                    'excluded_controller_ancestors': sorted(excluded)})
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


def active_docker_quota(proc):
    try:
        record = json.loads((STATE / 'docker-active.json').read_text())
        return record['cpus'] if proc.same(record['owner']) or (record.get('guardian') and proc.same(record['guardian'])) else 0.0
    except (OSError, ValueError, KeyError):
        return 0.0


def stop_container(name, token):
    result = subprocess.run(['docker', 'inspect', '--format',
                             '{{.Id}} {{index .Config.Labels "tolgraven.cpu.token"}} {{.State.Running}}', name],
                            capture_output=True, text=True, timeout=10)
    if result.returncode != 0:
        return 'No such' in result.stderr
    values = result.stdout.strip().split()
    if len(values) != 3:
        return False
    if values[1] != token or values[2] == 'false':
        return True  # This name is no longer our running container; never stop a foreign label.
    stopped = subprocess.run(['docker', 'stop', '--time', '3', values[0]], capture_output=True, timeout=15)
    return stopped.returncode == 0


def docker_watchdog(owner, name, token):
    proc = MacProcesses()
    while proc.same(owner):
        time.sleep(0.5)
    while True:
        try:
            if stop_container(name, token):
                try:
                    record = json.loads((STATE / 'docker-active.json').read_text())
                    if record.get('name') == name:
                        (STATE / 'docker-active.json').unlink(missing_ok=True)
                except (OSError, ValueError):
                    pass
                return
        except subprocess.SubprocessError:
            pass
        # Retain the inherited build lock and reservation until cleanup is confirmed.
        time.sleep(1)


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


def host_watchdog(owner, root):
    proc = MacProcesses()
    while proc.same(owner):
        time.sleep(0.5)
    while not stop_host_family(root):
        time.sleep(1)


def host_run(args, command, lock):
    env = dict(os.environ)
    env.setdefault('JAVA_TOOL_OPTIONS', JVM_POOLS + ' -Xmx1024m')
    child = subprocess.Popen(['nice', '-n', '10', *command], cwd=args.checkout, env=env,
                             start_new_session=True)
    proc = MacProcesses()
    owner = proc.identity(os.getpid())
    root = proc.identity(child.pid)
    if root is None:
        return child.wait()
    guard = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '_host-watchdog',
                              json.dumps(owner), json.dumps(root)], start_new_session=True,
                             pass_fds=(lock.fileno(),) if lock else ())
    try:
        return child.wait()
    finally:
        if stop_host_family(root):
            guard.terminate()
            guard.wait(timeout=3)
        else:
            print('Host family cleanup pending; watchdog retains ownership and any build lock.', file=sys.stderr)


def build_lock():
    STATE.mkdir(parents=True, exist_ok=True)
    handle = (STATE / 'build.lock').open('w')
    print('Waiting for the shared agent build slot…', flush=True)
    fcntl.flock(handle, fcntl.LOCK_EX)
    return handle


def snapshot(checkout, destination):
    raw = subprocess.check_output(['git', '-C', str(checkout), 'ls-files', '-z', '--cached',
                                   '--others', '--exclude-standard'])
    import shutil
    for name in dict.fromkeys(os.fsdecode(raw).split('\0')):
        if not name or name.startswith(('config/local.', '.local-wip/')):
            continue
        source = checkout / name
        if not source.is_file() or source.is_symlink():
            continue
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def run(args):
    command = args.command
    if command and command[0] == '--':
        command = command[1:]
    if not command:
        raise RuntimeError('Pass a command after --.')
    if args.action == 'runtime':
        return host_run(args, command, None)
    with build_lock() as lock:
        if args.mode == 'host':
            return host_run(args, command, lock)
        output = Path(args.checkout).resolve() / '.local-wip/cpu-builds' / uuid.uuid4().hex[:12]
        output.mkdir(parents=True)
        snapshot(Path(args.checkout).resolve(), output)
        shadow = output / 'shadow-cljs.edn'
        if shadow.exists():
            import re
            shadow.write_text(re.sub(r':parallel-build\s+true', ':parallel-build false', shadow.read_text()))
        name = 'tolgraven-cpu-' + output.name
        token = uuid.uuid4().hex
        owner = MacProcesses().identity(os.getpid())
        save_json(STATE / 'docker-active.json', {'owner': owner, 'cpus': args.cpus, 'name': name})
        guardian = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '_docker-watchdog',
                                     json.dumps(owner), name, token], start_new_session=True, pass_fds=(lock.fileno(),))
        save_json(STATE / 'docker-active.json', {'owner': owner, 'guardian': MacProcesses().identity(guardian.pid), 'cpus': args.cpus, 'name': name})
        bootstrap = ('if cmp -s package-lock.json /opt/prefab-package-lock.json; then '
                     'ln -s /usr/src/app/node_modules node_modules; else npm ci --no-audit --no-fund; fi; '
                     'exec "$@"')
        cmd = ['docker', 'run', '--rm', '--init', '--name', name, '--cpus', str(args.cpus),
               '--memory', args.memory, '--memory-swap', args.memory, '--pids-limit', '512',
               '--label', 'tolgraven.cpu.token=' + token,
               '--mount', f'type=bind,source={output},target=/workspace', '-w', '/workspace',
               '-e', f'JAVA_TOOL_OPTIONS={JVM_POOLS} -Xmx{args.java_heap}',
               args.image, 'sh', '-c', bootstrap, 'cpu-build', *command]
        print('Isolated output:', output, '\nDocker budget:', args.cpus, 'cores /', args.memory, flush=True)
        child = subprocess.Popen(cmd)
        try:
            return child.wait()
        finally:
            # Exact owned container name; do not touch unrelated build/watch containers.
            try:
                confirmed = stop_container(name, token)
            except subprocess.SubprocessError:
                confirmed = False
            if confirmed:
                (STATE / 'docker-active.json').unlink(missing_ok=True)
                guardian.terminate()
                guardian.wait(timeout=3)
            else:
                print('Docker cleanup pending; watchdog retains the build lock and quota reservation.', file=sys.stderr)


def positive(value):
    number = float(value)
    if not 0 < number <= 64:
        raise argparse.ArgumentTypeError('CPU budget must be greater than 0 and no more than 64 cores.')
    return number


def install(args):
    STATE.mkdir(parents=True, exist_ok=True)
    label = 'se.tolgraven.cpu-controls'
    domain = f'gui/{os.getuid()}'
    subprocess.run(['launchctl', 'bootout', f'{domain}/{label}'], capture_output=True)
    installed = STATE / 'cpu_controls.py'
    if Path(__file__).resolve() != installed.resolve():
        shutil.copy2(__file__, installed)
    binary = Path.home() / '.local/bin/tolgraven-cpu'
    binary.parent.mkdir(parents=True, exist_ok=True)
    binary.write_text('#!/bin/sh\nexec ' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(installed)) + ' "$@"\n')
    binary.chmod(0o755)
    agent = Path.home() / 'Library/LaunchAgents' / (label + '.plist')
    agent.parent.mkdir(parents=True, exist_ok=True)
    config = {'Label': label,
        'ProgramArguments': [sys.executable, str(installed), 'monitor', '--project',
                             str(Path(args.project).resolve()), '--enforce', '--cpus', str(args.cpus)],
        'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 10,
        'StandardOutPath': str(STATE / 'monitor.log'), 'StandardErrorPath': str(STATE / 'monitor-error.log')}
    agent.write_bytes(plistlib.dumps(config))
    subprocess.run(['launchctl', 'bootstrap', domain, str(agent)], check=True)
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
    runner = commands.add_parser('run', help='Serialize one isolated Docker build or low-priority host command.')
    runner.add_argument('--mode', choices=['docker', 'host'], default='docker')
    runner.add_argument('--checkout', default=os.getcwd())
    runner.add_argument('--java-heap', default='1024m')
    runner.add_argument('--cpus', type=positive, default=1.0)
    runner.add_argument('--memory', default='2g')
    runner.add_argument('--image', default='registry.bux.tolgraven.se/tolgraven/builder:java21-node22-v1')
    runner.add_argument('command', nargs=argparse.REMAINDER)
    runtime = commands.add_parser('runtime', help='Supervised low-priority host runtime; does not hold the agent build queue.')
    runtime.add_argument('--checkout', default=os.getcwd())
    runtime.add_argument('command', nargs=argparse.REMAINDER)
    installer = commands.add_parser('install', help='Explicitly install/enable the scoped user LaunchAgent and launcher.')
    installer.add_argument('--project', required=True)
    installer.add_argument('--cpus', type=positive, default=1.5)
    commands.add_parser('status')
    commands.add_parser('stop', help='Stop only the recorded monitor; watchdog resumes its owned paused targets.')
    wd = commands.add_parser('_watchdog')
    wd.add_argument('fd', type=int)
    dw = commands.add_parser('_docker-watchdog')
    dw.add_argument('owner')
    dw.add_argument('name')
    dw.add_argument('token')
    hw = commands.add_parser('_host-watchdog')
    hw.add_argument('owner')
    hw.add_argument('root')
    args = parser.parse_args()
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, stop_signal)
    if args.action == '_host-watchdog':
        host_watchdog(json.loads(args.owner), json.loads(args.root))
    elif args.action == '_docker-watchdog':
        docker_watchdog(json.loads(args.owner), args.name, args.token)
    elif args.action == '_watchdog':
        watchdog(args.fd)
    elif args.action == 'monitor':
        monitor(args)
    elif args.action in ('run', 'runtime'):
        return run(args)
    elif args.action == 'install':
        install(args)
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
