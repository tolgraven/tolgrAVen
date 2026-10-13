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
            cpu.stop_container('owned-name','my-token')
            self.assertEqual(run.call_count,1)

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
