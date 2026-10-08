import importlib.util
from unittest.mock import patch
from pathlib import Path
import tempfile
import unittest

source = Path(__file__).resolve().parents[2] / 'scripts/ops/host' / 'deploy-image.py'
spec = importlib.util.spec_from_file_location('deploy_image', source)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class DeploymentSafety(unittest.TestCase):
    def test_scopes_cleanup_to_staging_only(self):
        names = [m.STAGING, m.STAGING+'-pr-45', m.STAGING+'-123', m.STAGING+'-builder',
                 'bsok8o8csgso8c00g00k0csg-pr-45', 'supabase-db-example']
        import json
        with patch.object(m,'run',side_effect=['ids',json.dumps([{'Name':'/'+n} for n in names])]):
            self.assertEqual([c['Name'][1:] for c in m.staging_containers()], names[:3])

    def test_rejects_unrelated_images_before_any_action(self):
        with patch.object(m,'run') as run:
            with self.assertRaises(SystemExit): m.deploy('evil.example/site:tag',45)
            run.assert_not_called()

    def test_pull_failure_leaves_runtime_untouched(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(m,'STATE',Path(temp)/'state'), \
             patch.object(m,'run',side_effect=RuntimeError('pull failed')), patch.object(m,'stop') as stop, \
             patch.object(m,'php') as php:
            with self.assertRaises(RuntimeError): m.deploy(m.IMAGE_PREFIX+'test',45)
            stop.assert_not_called()
            php.assert_not_called()

    def test_unfinished_deployment_blocks_reentry(self):
        with tempfile.TemporaryDirectory() as temp:
            state=Path(temp)/'state';state.write_text('{}')
            with patch.object(m,'STATE',state),patch.object(m,'run') as run:
                with self.assertRaises(SystemExit):m.deploy(m.IMAGE_PREFIX+'test',45)
                run.assert_not_called()

    def test_disables_restart_before_graceful_stop(self):
        with patch.object(m,'run') as run:
            m.stop([{'Name':'/'+m.STAGING,'Id':'test-id'}])
            self.assertEqual(run.call_args_list[0].args,('docker','update','--restart=no','test-id'))
            self.assertEqual(run.call_args_list[1].args,('docker','stop','--time','30','test-id'))

class DeploymentRecovery(unittest.TestCase):
    def exercise(self, queue_result, status, expect_error=False):
        state = {'fields': {'build_pack': 'dockerfile'}, 'auto': True}
        old = {'Id': 'old', 'Name': '/' + m.STAGING, 'Created': '2026-01-01',
               'HostConfig': {'RestartPolicy': {'Name': 'unless-stopped'}}}
        new = {'Id': 'new', 'Image': 'new-image', 'Name': '/' + m.STAGING}
        def command(*args, **kwargs):
            if args[:3] == ('docker', 'image', 'inspect'):
                return '[{"Id":"new-image"}]'
            return ''
        results = [state, True, queue_result, status, True]
        with tempfile.TemporaryDirectory() as temp, patch.object(m, 'STATE', Path(temp)/'state'), \
             patch.object(m, 'php', side_effect=results) as php, \
             patch.object(m, 'run', side_effect=command) as run, \
             patch.object(m, 'staging_containers', side_effect=[[old], [new], [new]]), \
             patch.object(m, 'stop') as stop:
            if expect_error:
                with self.assertRaises(RuntimeError):
                    m.deploy(m.IMAGE_PREFIX+'test', 45)
            else:
                m.deploy(m.IMAGE_PREFIX+'test', 45)
            return m.STATE.exists(), php.call_args_list, run.call_args_list, stop.call_args_list

    def test_success_restores_configuration_and_removes_recovery_file(self):
        exists, calls, commands, stops = self.exercise({'status':'queued'}, 'finished')
        self.assertFalse(exists)
        self.assertIn('forceFill', calls[-1].args[0])
        self.assertEqual(calls[-1].args[1]['fields']['build_pack'], 'dockerfile')
        self.assertEqual(len(stops), 1)

    def test_terminal_failure_rolls_back_previous_runtime(self):
        exists, calls, commands, stops = self.exercise({'status':'queued'}, 'failed', True)
        self.assertFalse(exists)
        self.assertIn(('docker', 'start', 'old'), [c.args for c in commands])
        self.assertEqual(len(stops), 2)

    def test_uncertain_queue_result_retains_recovery_state(self):
        exists, calls, commands, stops = self.exercise(RuntimeError('connection lost'), None, True)
        self.assertTrue(exists)
        self.assertNotIn(('docker', 'start', 'old'), [c.args for c in commands])
        self.assertEqual(len(stops), 1)

if __name__=='__main__':unittest.main()
