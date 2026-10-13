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
            return {'pid': pid, 'path': path, 'ppid': parent, 'status': status, 'start': [1, pid], 'uid': os.getuid()}
        bundle = '/Applications/ChatGPT.app/Contents/'
        items = {2:p(2, bundle+'MacOS/ChatGPT'), 3:p(3,bundle+'Helpers/Codex (Renderer)',2),
                 4:p(4,bundle+'Resources/codex'), 5:p(5,'/usr/bin/java'), 6:p(6,'/usr/bin/java'),
                 7:p(7,bundle+'Helpers/Codex (Renderer)',2,4), 8:p(8,bundle+'Helpers/Codex (Renderer)',1)}
        selected = cpu.select_targets(items,[Path('/project')],{4},lambda pid:Path('/project') if pid==5 else Path('/other'))
        self.assertEqual(set(selected), {3,5})

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
