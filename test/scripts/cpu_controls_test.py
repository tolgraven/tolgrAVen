import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

MODULE = Path(__file__).resolve().parents[2] / 'scripts/dev/cpu_controls.py'
spec = importlib.util.spec_from_file_location('cpu_controls', MODULE)
cpu = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cpu)


class PolicyTests(unittest.TestCase):
    def test_build_defaults_double_memory_without_increasing_cpu_or_slots(self):
        with mock.patch.object(sys, 'argv', ['cpu-controls', 'run', '--', 'true']), \
             mock.patch.object(cpu, 'run', return_value=0) as run:
            self.assertEqual(cpu.main(), 0)
            args = run.call_args.args[0]
            self.assertEqual(args.memory, '4g')
            self.assertEqual(args.java_heap, '2048m')
            self.assertEqual(args.cpus, 1.5)

    def test_two_locks_admit_two_builds_and_queue_a_third(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(cpu, 'STATE', Path(directory)):
            first = cpu.try_build_lock()
            second = cpu.try_build_lock()
            try:
                self.assertIsNotNone(first)
                self.assertIsNotNone(second)
                self.assertNotEqual(first.name, second.name)
                self.assertIsNone(cpu.try_build_lock())
                first.close()
                replacement = cpu.try_build_lock()
                try:
                    self.assertEqual(replacement.name, first.name)
                    self.assertIsNone(cpu.try_build_lock())
                finally:
                    replacement.close()
            finally:
                first.close()
                second.close()

    def test_legacy_lock_occupies_one_of_the_two_slots(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(cpu, 'STATE', Path(directory)):
            with (cpu.STATE / 'build.lock').open('w') as legacy:
                cpu.fcntl.flock(legacy, cpu.fcntl.LOCK_EX)
                with cpu.try_build_lock() as current:
                    self.assertEqual(Path(current.name).name, 'build-1.lock')
                    self.assertIsNone(cpu.try_build_lock())

    def test_inherited_lock_remains_owned_until_guardian_exits(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(cpu, 'STATE', Path(directory)):
            first = cpu.try_build_lock()
            second = cpu.try_build_lock()
            guardian = subprocess.Popen([sys.executable, '-c', 'import sys;sys.stdin.read()'],
                                        stdin=subprocess.PIPE, pass_fds=(first.fileno(),))
            first.close()
            try:
                self.assertIsNone(cpu.try_build_lock())
                guardian.stdin.close()
                guardian.wait(timeout=3)
                with cpu.try_build_lock() as replacement:
                    self.assertIsNotNone(replacement)
            finally:
                if guardian.poll() is None:
                    guardian.terminate()
                    guardian.wait(timeout=3)
                second.close()

    def test_cpu_reservations_sum_live_workers_and_cleanup_only_named_owner(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(cpu, 'STATE', Path(directory)):
            records = [('legacy', 1, 1), ('new', 2, 1.5), ('stale', 3, 1.5)]
            for name, pid, cpus in records:
                path = cpu.STATE / 'docker-active.json' if name == 'legacy' else cpu.reservation_path(name)
                path.write_text(json.dumps({'name': name, 'owner': {'pid': pid}, 'cpus': cpus}))
            proc = mock.Mock()
            proc.same.side_effect = lambda record: record['pid'] in (1, 2)
            self.assertEqual(cpu.active_docker_quota(proc), 2.5)
            cpu.release_reservation('new')
            self.assertEqual(cpu.active_docker_quota(proc), 1)
            self.assertEqual({record['name'] for _, record in cpu.build_records()}, {'legacy', 'stale'})

    def test_per_slot_cpu_override_cannot_exceed_combined_ceiling(self):
        args = mock.Mock(action='run', command=['true'], cpus=3)
        with mock.patch.object(cpu, 'build_lock') as acquire:
            with self.assertRaisesRegex(RuntimeError, 'shared Docker ceiling is 3'):
                cpu.run(args)
            acquire.assert_not_called()
        self.assertEqual(cpu.BUILD_SLOTS * cpu.BUILD_CPUS, 3)

    def test_buildkit_driver_options_bound_whole_worker_and_load_output(self):
        self.assertEqual(cpu.buildkit_options(1, '2g'),
                         ['cpu-quota=100000', 'cpu-period=100000', 'memory=2g',
                          'memory-swap=2g', 'default-load=true', 'restart-policy=no'])
        self.assertEqual(cpu.parse_memory('2g'), 2 * 1024 ** 3)
        for value in ('unlimited', '2.5g', '-1g'):
            with self.assertRaises(RuntimeError):
                cpu.parse_memory(value)

    def test_buildkit_rejects_foreign_or_uncapped_container_before_start(self):
        container = {'Config': {'Labels': {'tolgraven.cpu.token': 'owned'}},
                     'HostConfig': {'CpuQuota': 100000, 'CpuPeriod': 100000,
                                    'Memory': 2147483648, 'MemorySwap': 2147483648}}
        cpu.validate_buildkit_container(container, 'owned', 1, 2147483648)
        with self.assertRaises(RuntimeError):
            cpu.validate_buildkit_container(container, 'foreign', 1, 2147483648)
        container['HostConfig']['CpuQuota'] = 0
        with self.assertRaises(RuntimeError):
            cpu.validate_buildkit_container(container, 'owned', 1, 2147483648)

    def test_buildkit_host_environment_keeps_required_jvm_pools_and_pinned_endpoint(self):
        args = mock.Mock(checkout='/fixture', java_heap='1024m')
        child = mock.Mock(pid=123)
        child.wait.return_value = 0
        proc = mock.Mock()
        proc.identity.return_value = None
        with mock.patch.dict(cpu.os.environ, {'DOCKER_CONTEXT': 'foreign', 'JAVA_TOOL_OPTIONS': '-XX:ActiveProcessorCount=8'}), \
             mock.patch.object(cpu.subprocess, 'Popen', return_value=child) as popen, \
             mock.patch.object(cpu, 'MacProcesses', return_value=proc):
            cpu.host_run(args, ['make', 'docker-prefab'], None,
                         {'DOCKER_CONTEXT': None, 'DOCKER_HOST': 'unix:///original.sock', 'BUILDX_BUILDER': 'owned'})
            environment = popen.call_args.kwargs['env']
            self.assertNotIn('DOCKER_CONTEXT', environment)
            self.assertEqual(environment['DOCKER_HOST'], 'unix:///original.sock')
            self.assertIn(cpu.JVM_POOLS, environment['JAVA_TOOL_OPTIONS'])

    def test_custom_non_node_image_can_explicitly_skip_dependency_bootstrap(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(['sh', '-c', cpu.docker_bootstrap('skip'), 'cpu-build',
                                     'printf', '%s\\n', 'literal $value; argument with spaces'],
                                    cwd=directory, capture_output=True, text=True, check=True)
            self.assertEqual(result.stdout, 'literal $value; argument with spaces\n')
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_default_node_bootstrap_preserves_prefab_reuse_and_install(self):
        bootstrap = cpu.docker_bootstrap('auto')
        self.assertIn('cmp -s package-lock.json /opt/prefab-package-lock.json', bootstrap)
        self.assertIn('ln -s /usr/src/app/node_modules node_modules', bootstrap)
        self.assertIn('npm ci --no-audit --no-fund', bootstrap)
        self.assertTrue(bootstrap.endswith('exec "$@"'))

    def test_host_existing_options_preserve_heap_and_always_append_worker_bounds(self):
        options=cpu.host_environment({'JAVA_TOOL_OPTIONS':'-Dsome.property=keep -Xmx2g -XX:ActiveProcessorCount=8'})['JAVA_TOOL_OPTIONS']
        self.assertIn('-Dsome.property=keep',options)
        self.assertIn('-Xmx2g',options)
        self.assertNotIn('-Xmx1024m',options)
        self.assertTrue(options.endswith(cpu.JVM_POOLS))

    def test_host_default_heap_and_explicit_maxram(self):
        self.assertIn('-Xmx1024m',cpu.host_environment({})['JAVA_TOOL_OPTIONS'])
        self.assertNotIn('-Xmx',cpu.host_environment({'JAVA_TOOL_OPTIONS':'-XX:MaxRAMPercentage=40'})['JAVA_TOOL_OPTIONS'])

    def test_family_and_total_budgets(self):
        demand = {'java': 4, 'renderers': 2}
        duties = cpu.duty_plan(demand, {'java': 1.5, 'renderers': 1}, 1.5)
        self.assertAlmostEqual(sum(demand[k] * duties[k] for k in demand), 1.5)
        self.assertLessEqual(4 * duties['java'], 1.5)
        self.assertLessEqual(2 * duties['renderers'], 1)
        self.assertEqual(cpu.duty_plan({'java': .1}, {'java': 1.5}, 1.5), {'java': 1})

    def test_selection_excludes_other_users_helpers_pre_stopped_and_unrelated_java(self):
        def p(pid, path, parent=1, status=2):
            return {'pid': pid, 'path': path, 'ppid': parent, 'status': status, 'start': [1, pid], 'uid': os.getuid(), 'pgid': pid}
        bundle = '/Applications/ChatGPT.app/Contents/'
        items = {2:p(2, bundle+'MacOS/ChatGPT'), 3:p(3,bundle+'Helpers/Codex (Renderer)',2),
                 4:p(4,bundle+'Resources/codex'), 5:p(5,'/usr/bin/java'), 6:p(6,'/usr/bin/java'),
                 7:p(7,bundle+'Helpers/Codex (Renderer)',2,4), 8:p(8,bundle+'Helpers/Codex (Renderer)',1)}
        selected = cpu.select_targets(items,[Path('/project')],{4},lambda pid:Path('/project') if pid==5 else Path('/other'), owned_roots=[items[5]])
        self.assertEqual(set(selected), {3,5})

    def test_user_lein_tree_is_unthrottled_but_registered_backend_workers_are_selected(self):
        def record(pid, parent, group, path='/usr/bin/java'):
            return {'pid': pid, 'ppid': parent, 'pgid': group, 'path': path,
                    'start': [1, pid], 'uid': os.getuid(), 'status': 2}
        processes = {5: record(5, 1, 5), 6: record(6, 5, 5),
                     20: record(20, 1, 20, '/usr/bin/lein'),
                     21: record(21, 20, 20), 22: record(22, 21, 20)}
        project = lambda pid: Path('/project')
        self.assertEqual(cpu.select_targets(processes, [Path('/project')], set(), project), {})
        selected = cpu.select_targets(processes, [Path('/project')], set(), project,
                                      owned_roots=[processes[20]])
        self.assertEqual(set(selected), {21, 22})
        processes[22]['pgid'] = 5
        self.assertEqual(set(cpu.select_targets(processes, [Path('/project')], set(), project,
                                               owned_roots=[processes[20]])), {21})

    def test_host_registration_requires_live_owner_root_birth_and_project(self):
        owner = {'pid': 10, 'start': [1, 10], 'uid': os.getuid(), 'path': '/python'}
        root = {'pid': 20, 'start': [2, 20], 'uid': os.getuid(),
                'pgid': 20, 'path': '/usr/bin/nice'}
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(cpu, 'STATE', Path(directory)):
            token = 'a' * 32
            path = cpu.host_registration_path(token)
            path.parent.mkdir()
            cpu.save_json(path, {'owner': owner, 'root': root, 'checkout': '/project'})
            proc = mock.Mock()
            proc.same.return_value = True
            # Exec changes the path but never the root's birth identity/group.
            current = dict(root, path='/usr/bin/java')
            proc.identity.return_value = current
            self.assertEqual(cpu.registered_host_roots(proc, [Path('/project')]), [current])
            proc.identity.return_value = dict(current, start=[3, 20])
            self.assertEqual(cpu.registered_host_roots(proc, [Path('/project')]), [])
            proc.identity.return_value = current
            proc.same.return_value = False
            self.assertEqual(cpu.registered_host_roots(proc, [Path('/project')]), [])
            proc.same.return_value = True
            self.assertEqual(cpu.registered_host_roots(proc, [Path('/other')]), [])

    def test_host_launcher_registers_family_until_confirmed_cleanup(self):
        owner = {'pid': 10, 'start': [1, 10], 'uid': os.getuid(), 'path': '/python'}
        root = {'pid': 20, 'start': [2, 20], 'uid': os.getuid(), 'pgid': 20, 'path': '/nice'}
        args = mock.Mock(checkout='/project', java_heap='1024m')
        child, guardian = mock.Mock(pid=20), mock.Mock(pid=30)
        proc = mock.Mock()
        proc.identity.side_effect = [owner, root]
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(cpu, 'STATE', Path(directory)), \
             mock.patch.object(cpu, 'MacProcesses', return_value=proc), \
             mock.patch.object(cpu.subprocess, 'Popen', side_effect=[child, guardian]), \
             mock.patch.object(cpu, 'stop_host_family', return_value=True):
            def wait():
                records = list((cpu.STATE / 'host-active').glob('*.json'))
                self.assertEqual(len(records), 1)
                self.assertEqual(json.loads(records[0].read_text())['root'], root)
                return 0
            child.wait.side_effect = wait
            self.assertEqual(cpu.host_run(args, ['lein', 'run'], None), 0)
            self.assertEqual(list((cpu.STATE / 'host-active').glob('*.json')), [])
            guardian.terminate.assert_called_once()

    def test_host_registration_write_failure_still_cleans_up_child(self):
        owner = {'pid': 10, 'start': [1, 10], 'uid': os.getuid(), 'path': '/python'}
        root = {'pid': 20, 'start': [2, 20], 'uid': os.getuid(), 'pgid': 20, 'path': '/nice'}
        proc = mock.Mock()
        proc.identity.side_effect = [owner, root]
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(cpu, 'STATE', Path(directory)), \
             mock.patch.object(cpu, 'MacProcesses', return_value=proc), \
             mock.patch.object(cpu.subprocess, 'Popen', return_value=mock.Mock(pid=20)) as popen, \
             mock.patch.object(cpu, 'save_json', side_effect=OSError('fixture write failure')), \
             mock.patch.object(cpu, 'stop_host_family', return_value=True) as cleanup:
            with self.assertRaisesRegex(OSError, 'fixture write failure'):
                cpu.host_run(mock.Mock(checkout='/project', java_heap='1024m'), ['lein', 'run'], None)
            cleanup.assert_called_once_with(root)
            self.assertEqual(popen.call_count, 1)

    def test_pid_reuse_never_receives_continue(self):
        proc = mock.Mock()
        proc.same.return_value = False
        with mock.patch.object(cpu.os,'kill') as kill:
            cpu.send_cont([{'pid':123,'start':[1,2]}],proc)
            kill.assert_not_called()

    def test_stale_docker_reservation_does_not_reduce_native_budget(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(cpu,'STATE',Path(directory)):
            (cpu.STATE/'docker-active.json').write_text(json.dumps({'owner': {'pid':1},'cpus':1}))
            proc=mock.Mock();proc.same.return_value=False
            self.assertEqual(cpu.active_docker_quota(proc),0)

    def test_foreign_container_label_is_never_stopped(self):
        proc=mock.Mock(returncode=0,stdout='container-id other-token true')
        with mock.patch.object(cpu.subprocess,'run',return_value=proc) as run:
            cpu.stop_container('owned-name','my-token','unix:///fixture.sock')
            self.assertEqual(run.call_count,1)

    def test_lowercase_exact_docker_absence_releases_cleanup(self):
        result=mock.Mock(returncode=1,stdout='',stderr='error: no such object: owned-name')
        with mock.patch.object(cpu.subprocess,'run',return_value=result):
            self.assertTrue(cpu.stop_container('owned-name','token','unix:///fixture.sock'))

    def test_docker_socket_failure_or_other_name_never_confirms_absence(self):
        for message in ('error: cannot connect: no such file or directory /fixture.sock',
                        'error: no such object: another-container'):
            result=mock.Mock(returncode=1,stdout='',stderr=message)
            with mock.patch.object(cpu.subprocess,'run',return_value=result):
                self.assertFalse(cpu.stop_container('owned-name','token','unix:///fixture.sock'))

    def test_container_cleanup_uses_pinned_endpoint_and_immutable_id(self):
        inspected=mock.Mock(returncode=0,stdout='immutable-id token true',stderr='')
        stopped=mock.Mock(returncode=0)
        with mock.patch.object(cpu.subprocess,'run',side_effect=[inspected,stopped]) as run:
            self.assertTrue(cpu.stop_container('owned-name','token','unix:///original.sock'))
            self.assertEqual(run.call_args_list[0].args[0][:3],['docker','--host','unix:///original.sock'])
            self.assertEqual(run.call_args_list[1].args[0],['docker','--host','unix:///original.sock','stop','--time','3','immutable-id'])

    def test_recovery_accepts_legacy_and_ready_guardian_argv(self):
        owner={'pid':10,'start':[2,3],'uid':os.getuid()}
        record={'owner':owner,'name':'owned','token':'token','endpoint':'unix:///original.sock'}
        base=['python','/installed/cpu_controls.py','_docker-watchdog',json.dumps(owner),'owned','token']
        self.assertEqual(cpu.guardian_protocol(base,record),('token',None))
        self.assertEqual(cpu.guardian_protocol(base+['--endpoint','unix:///original.sock','--ready-fd','5'],record),('token','unix:///original.sock'))
        with self.assertRaises(RuntimeError):cpu.guardian_protocol(base+['--endpoint','unix:///different.sock'],record)
        with self.assertRaises(RuntimeError):cpu.guardian_protocol(base[:-1]+['foreign-token'],record)

    def test_guardian_missing_ready_identity_is_a_controlled_failure(self):
        fake=mock.Mock(pid=123)
        with tempfile.TemporaryFile() as lock, mock.patch.object(cpu.subprocess,'Popen',return_value=fake), mock.patch.object(cpu,'packet_receive',return_value={'guardian':None}):
            with self.assertRaises(RuntimeError):
                cpu.start_docker_guardian({'path':'/python'},'owned','token','unix:///fixture.sock',lock)

    def test_packet_boundaries(self):
        import socket
        a,b=socket.socketpair()
        try:
            cpu.packet_send(a,{'action':'arm','records':[]})
            cpu.packet_send(a,{'action':'release'})
            self.assertEqual(cpu.packet_receive(b)['action'],'arm')
            self.assertEqual(cpu.packet_receive(b)['action'],'release')
        finally:a.close();b.close()

    def test_snapshot_preserves_unstaged_source_but_excludes_private_config(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'repo';out=Path(directory)/'out';root.mkdir();out.mkdir()
            subprocess.run(['git','init','-q',str(root)],check=True)
            (root/'source.clj').write_text('original')
            subprocess.run(['git','-C',str(root),'add','source.clj'],check=True)
            (root/'source.clj').write_text('working edit')
            (root/'config').mkdir();(root/'config/local.dev.edn').write_text('private')
            (root/'new.txt').write_text('new source')
            cpu.snapshot(root,out)
            self.assertEqual((out/'source.clj').read_text(),'working edit')
            self.assertTrue((out/'new.txt').exists())
            self.assertFalse((out/'config/local.dev.edn').exists())

    def test_snapshot_seeds_public_dev_test_examples_without_private_configs(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'repo';out=Path(directory)/'out';root.mkdir();out.mkdir()
            subprocess.run(['git','init','-q',str(root)],check=True)
            files={'config/dev.example.edn':'{:port 4000}',
                   'config/test.example.edn':'{:port 3000}',
                   'env/prod/resources/config.edn':'{:prod true}',
                   '.gitignore':'/env/dev/resources/config.edn\n/env/test/resources/config.edn\n'}
            for name,text in files.items():
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
            subprocess.run(['git','-C',str(root),'add','.'],check=True)
            for name in ('env/dev/resources/config.edn','env/test/resources/config.edn'):
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('private marker')
            # Even accidental force-tracking must not copy a canonical private file.
            subprocess.run(['git','-C',str(root),'add','--force','env/dev/resources/config.edn'],check=True)
            cpu.snapshot(root,out)
            self.assertEqual((out/'env/dev/resources/config.edn').read_text(),files['config/dev.example.edn'])
            self.assertEqual((out/'env/test/resources/config.edn').read_text(),files['config/test.example.edn'])
            self.assertEqual((out/'env/prod/resources/config.edn').read_text(),files['env/prod/resources/config.edn'])
            self.assertNotIn('private marker',''.join(path.read_text() for path in out.rglob('*.edn')))

    def test_snapshot_seeding_preserves_existing_runtime_and_requires_tracked_example(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'repo';out=Path(directory)/'out';root.mkdir();out.mkdir()
            subprocess.run(['git','init','-q',str(root)],check=True)
            (root/'config').mkdir()
            (root/'config/dev.example.edn').write_text('public dev')
            (root/'config/test.example.edn').write_text('untracked test')
            subprocess.run(['git','-C',str(root),'add','config/dev.example.edn'],check=True)
            existing=out/'env/dev/resources/config.edn';existing.parent.mkdir(parents=True)
            existing.write_text('destination already configured')
            cpu.snapshot(root,out)
            self.assertEqual(existing.read_text(),'destination already configured')
            self.assertFalse((out/'env/test/resources/config.edn').exists())


class ShadowCacheTests(unittest.TestCase):
    def test_identity_retains_minor_command_changes_but_separates_worktree_profile_toolchain_site(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = root / 'source'; source.mkdir()
            (source / 'project.clj').write_text('deps')
            (source / 'package-lock.json').write_text('npm')
            (source / 'resources').mkdir(); (source / 'resources/site.edn').write_text('site')
            command = ['lein', 'with-profile', 'provided', 'run', '-m', 'shadow.cljs.devtools.cli', 'compile', 'document']
            key = cpu.shadow_cache_key(root / 'worktree', source, 'sha256:one', command)
            varied = ['bash', '-lc', 'echo report; lein with-profile provided run -m shadow.cljs.devtools.cli compile app-test; echo done']
            self.assertEqual(key, cpu.shadow_cache_key(root / 'worktree', source, 'sha256:one', varied))
            (source / 'source.cljs').write_text('changed source')
            self.assertEqual(key, cpu.shadow_cache_key(root / 'worktree', source, 'sha256:one', command))
            for checkout, image, args in [(root / 'other', 'sha256:one', command),
                                          (root / 'worktree', 'sha256:two', command),
                                          (root / 'worktree', 'sha256:one', ['lein', 'with-profile', 'prod,provided'])]:
                self.assertNotEqual(key, cpu.shadow_cache_key(checkout, source, image, args))
            (source / 'resources/site.edn').write_text('other site')
            self.assertNotEqual(key, cpu.shadow_cache_key(root / 'worktree', source, 'sha256:one', command))

    def test_helper_profile_inventory_does_not_hash_reporting_or_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); helper = root / 'build.sh'
            helper.write_text('lein with-profile +project/test run compile app-test')
            key = cpu.shadow_cache_key(root, root, 'image', ['bash', 'build.sh'])
            helper.write_text('echo report\nlein with-profile +project/test run compile app-test\necho done')
            self.assertEqual(key, cpu.shadow_cache_key(root, root, 'image', ['bash', 'build.sh']))
            helper.write_text('lein with-profile prod,provided run release app')
            self.assertNotEqual(key, cpu.shadow_cache_key(root, root, 'image', ['bash', 'build.sh']))

    def test_cache_guard_inherits_identity_lock_alongside_build_slot(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(cpu, 'STATE', Path(directory)):
            key = 'a' * 24
            handle = cpu.cache_lock(key)
            guard = subprocess.Popen([sys.executable, '-c', 'import sys;sys.stdin.read()'],
                                     stdin=subprocess.PIPE, pass_fds=(handle.fileno(),))
            handle.close()
            try:
                self.assertIsNone(cpu.cache_lock(key, blocking=False))
                guard.stdin.close(); guard.wait(timeout=3)
                with cpu.cache_lock(key, blocking=False) as acquired:
                    self.assertIsNotNone(acquired)
            finally:
                if guard.poll() is None:
                    guard.terminate(); guard.wait(timeout=3)

    def test_pruning_skips_active_and_unmanaged_caches_and_evicts_oldest_under_budget(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(cpu, 'STATE', Path(directory)), \
                mock.patch.object(cpu, 'SHADOW_CACHE_BYTES', 10):
            root = cpu.shadow_cache_root()
            for key, timestamp in [('a' * 24, 1), ('b' * 24, 2), ('c' * 24, 3)]:
                path = root / key; path.mkdir(parents=True)
                (path / 'builds').mkdir(); (path / 'builds' / 'cache').write_bytes(b'12345678')
                cpu.save_json(path / 'metadata.json', {'version': 1, 'key': key, 'used_at': timestamp, 'bytes': 8})
            unmanaged = root / 'unmanaged'; unmanaged.mkdir()
            with cpu.cache_lock('a' * 24):
                result = cpu.prune_shadow_caches(now=10)
                self.assertEqual(result['removed'], ['b' * 24, 'c' * 24])
                self.assertTrue((root / ('a' * 24)).is_dir())
                self.assertTrue(unmanaged.is_dir())
            self.assertEqual(cpu.prune_shadow_caches(now=cpu.SHADOW_CACHE_AGE + 10)['removed'], ['a' * 24])

    def test_reuse_refresh_and_off_keep_cache_outside_fresh_output(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(cpu, 'STATE', Path(directory) / 'state'):
            root = Path(directory); out = root / 'snapshot'; out.mkdir()
            (out / 'shadow-cljs.edn').write_text('{:builds {}}')
            args = SimpleNamespace(checkout=str(root / 'checkout'), image='prefab', shadow_cache='reuse')
            image = mock.Mock(returncode=0, stdout='sha256:fixture')
            with mock.patch.object(cpu.subprocess, 'run', return_value=image):
                with cpu.shadow_cache(args, out, ['lein'], 'unix:///fixture') as first:
                    (first[0] / 'cached').write_text('data')
                    self.assertFalse((out / '.shadow-cljs').exists())
                with cpu.shadow_cache(args, out, ['lein'], 'unix:///fixture') as second:
                    self.assertEqual((second[0] / 'cached').read_text(), 'data')
                args.shadow_cache = 'refresh'
                with cpu.shadow_cache(args, out, ['lein'], 'unix:///fixture') as fresh:
                    self.assertFalse((fresh[0] / 'cached').exists())
                args.shadow_cache = 'off'
                with cpu.shadow_cache(args, out, ['lein'], 'unix:///fixture') as absent:
                    self.assertIsNone(absent)

    def test_unknown_image_or_custom_cache_root_bypasses_without_touching_control_markers(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(cpu, 'STATE', Path(directory) / 'state'):
            out = Path(directory) / 'snapshot'; out.mkdir()
            config = out / 'shadow-cljs.edn'; config.write_text('{:cache-root "custom"}')
            args = SimpleNamespace(checkout=str(out), image='missing', shadow_cache='reuse')
            with mock.patch.object(cpu.subprocess, 'run', return_value=mock.Mock(returncode=1, stdout='')) as run:
                with cpu.shadow_cache(args, out, ['lein'], 'unix:///fixture') as absent:
                    self.assertIsNone(absent)
                run.assert_not_called()
                config.write_text('{:builds {}}')
                with cpu.shadow_cache(args, out, ['lein'], 'unix:///fixture') as absent:
                    self.assertIsNone(absent)
                self.assertFalse(cpu.shadow_cache_root().exists())


@unittest.skipUnless(sys.platform=='darwin','native guard uses macOS libproc')
class NativeTests(unittest.TestCase):
    def setUp(self):
        self.proc=cpu.MacProcesses()
        self.child=subprocess.Popen([sys.executable,'-c','while True: pass'])
        time.sleep(.1)
        self.record=self.proc.identity(self.child.pid)

    def tearDown(self):
        os.kill(self.child.pid,signal.SIGCONT)
        self.child.terminate();self.child.wait(timeout=3)

    def test_native_cwd_and_running_thread_layout(self):
        self.assertEqual(self.proc.cwd(os.getpid()),Path.cwd())
        self.assertEqual(self.proc.info(self.child.pid,4,cpu.TaskInfo).threads,1)
        self.assertGreaterEqual(self.proc.info(self.child.pid,4,cpu.TaskInfo).running,0)

    def test_cpu_accounting_and_microsecond_identity(self):
        before=self.proc.cpu(self.child.pid);time.sleep(.4)
        self.assertGreater(self.proc.cpu(self.child.pid)-before,.02)
        self.assertLess(self.proc.cpu(self.child.pid)-before,.8)
        self.assertTrue(self.proc.same(self.record))
        changed=dict(self.record,start=[0,0])
        self.assertFalse(self.proc.same(changed))

    def test_normal_cleanup_resumes_owned_fixture(self):
        controller=cpu.Controller(self.proc)
        try:
            controller.pause([self.record]);time.sleep(.03)
            self.assertEqual(self.proc.identity(self.child.pid)['status'],4)
        finally:controller.close()
        time.sleep(.05)
        self.assertNotEqual(self.proc.identity(self.child.pid)['status'],4)

    def test_watchdog_resumes_after_controller_sigkill(self):
        script=('import sys,time;sys.path.insert(0,'+repr(str(MODULE.parent))+');import cpu_controls as c;'
                'p=c.MacProcesses();x=c.Controller(p);x.pause([p.identity('+str(self.child.pid)+')]);'
                'print(x.helper.pid,flush=True);time.sleep(30)')
        owner=subprocess.Popen([sys.executable,'-c',script],stdout=subprocess.PIPE,text=True)
        watchdog_pid=int(owner.stdout.readline())
        owner.kill();owner.wait(timeout=3)
        deadline=time.monotonic()+2
        while self.proc.identity(self.child.pid)['status']==4 and time.monotonic()<deadline:time.sleep(.05)
        self.assertNotEqual(self.proc.identity(self.child.pid)['status'],4)
        time.sleep(.1)
        self.assertIsNone(self.proc.identity(watchdog_pid))
        owner.stdout.close()

    def test_docker_guardian_acknowledges_stable_postexec_identity(self):
        with tempfile.TemporaryFile() as lock:
            guard,identity=cpu.start_docker_guardian(self.proc.identity(os.getpid()),
                'no-created-fixture','token','unix:///fixture.sock',lock)
            try:
                self.assertEqual(identity['pid'],guard.pid)
                self.assertTrue(self.proc.same(identity))
                self.assertEqual(identity['path'],self.proc.identity(os.getpid())['path'])
            finally:guard.terminate();guard.wait(timeout=3)

    def test_preexisting_stop_is_preserved(self):
        os.kill(self.child.pid,signal.SIGSTOP)
        time.sleep(.05)
        controller=cpu.Controller(self.proc)
        try:
            controller.pause([self.record]);controller.resume()
        finally:controller.close()
        self.assertEqual(self.proc.identity(self.child.pid)['status'],4)

    def test_watchdog_resumes_after_controller_stall(self):
        controller=cpu.Controller(self.proc)
        try:
            controller.pause([self.record]);time.sleep(.45)
            self.assertNotEqual(self.proc.identity(self.child.pid)['status'],4)
        finally:controller.close()


if __name__=='__main__':unittest.main()
