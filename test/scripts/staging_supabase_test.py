import importlib.util
from contextlib import nullcontext
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('staging_supabase', ROOT / 'scripts/ops/host/staging_supabase.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class StagingLifecycle(unittest.TestCase):
    def test_runtime_scope_excludes_production_helpers_and_similar_names(self):
        for name in [m.STAGING, m.STAGING+'-123', m.STAGING+'-pr-45']:
            self.assertTrue(m.web_running([name]))
        for name in ['bsok8o8csgso8c00g00k0csg', m.STAGING+'-builder', 'other-'+m.STAGING]:
            self.assertFalse(m.web_running([name]))

    def test_queue_or_manual_deployment_keeps_database_awake(self):
        with patch.object(m, 'pending', return_value=True), patch.object(m, 'run', return_value=''):
            self.assertTrue(m.observation())
        with tempfile.TemporaryDirectory() as temp:
            state = Path(temp)/'manual'; state.write_text('{}')
            with patch.object(m, 'MANUAL', state), patch.object(m, 'pending', return_value=False), patch.object(m, 'run', return_value=''):
                self.assertTrue(m.observation())

    def exercise(self, state, active, now=100):
        with patch.object(m, 'locked', return_value=nullcontext()), patch.object(m, 'load_state', return_value=state), \
             patch.object(m, 'observation', side_effect=active), patch.object(m.time, 'time', return_value=now), \
             patch.object(m, 'save_state') as save, patch.object(m, 'start_and_wait') as start, \
             patch.object(m, 'stack_running', return_value=True), patch.object(m, 'compose') as compose:
            m.reconcile()
            return start, compose, save

    def test_first_idle_observation_only_starts_grace_period(self):
        start, compose, save = self.exercise({}, [False])
        start.assert_not_called(); compose.assert_not_called()
        self.assertEqual(save.call_args.args[0]['idle_since'], 100)

    def test_expired_idle_stops_without_deleting_volumes(self):
        start, compose, _ = self.exercise({'idle_since': 30}, [False, False])
        start.assert_not_called()
        compose.assert_called_once_with('stop', '--timeout', '30')

    def test_deployment_arriving_during_idle_check_prevents_stop(self):
        _, compose, save = self.exercise({'idle_since': 30}, [False, True])
        compose.assert_not_called()
        self.assertNotIn('idle_since', save.call_args.args[0])

    def test_active_runtime_wakes_database_and_clears_idle_timer(self):
        start, compose, save = self.exercise({'idle_since': 30}, [True])
        start.assert_called_once(); compose.assert_not_called()
        self.assertNotIn('idle_since', save.call_args.args[0])

    def test_manual_start_lease_prevents_idle_shutdown(self):
        start, compose, _ = self.exercise({'lease_until': 200}, [False])
        start.assert_called_once(); compose.assert_not_called()

    def test_failed_coolify_observation_never_stops_database(self):
        with patch.object(m, 'locked', return_value=nullcontext()), patch.object(m, 'load_state', return_value={'idle_since':0}), \
             patch.object(m, 'observation', side_effect=RuntimeError('Coolify unavailable')), patch.object(m, 'compose') as compose:
            with self.assertRaises(RuntimeError): m.reconcile()
            compose.assert_not_called()

    def test_warm_database_does_not_run_compose(self):
        with patch.object(m, 'ready', return_value=True), patch.object(m, 'compose') as compose:
            m.start_and_wait(); compose.assert_not_called()

    def test_cold_start_preserves_existing_images_containers_and_volumes(self):
        with patch.object(m, 'ready', side_effect=[False, False, True]), patch.object(m, 'compose') as compose, patch.object(m.time, 'sleep'):
            m.start_and_wait()
            compose.assert_called_once_with('up', '-d', '--no-recreate', '--pull', 'never')


class Entrypoint(unittest.TestCase):
    def test_production_does_not_wait(self):
        result = subprocess.run(['sh', str(ROOT/'docker-entrypoint.sh'), 'echo', 'started'],
                                env={'PATH':'/usr/bin:/bin'}, capture_output=True, text=True, check=True)
        self.assertEqual(result.stdout, 'started\n')

    def test_staging_waits_for_both_auth_and_rest_before_exec(self):
        with tempfile.TemporaryDirectory() as temp:
            fake = Path(temp)/'curl'
            fake.write_text('#!/bin/sh\necho "$*" >> "$CALL_LOG"\n')
            fake.chmod(0o755)
            log = Path(temp)/'calls'
            result = subprocess.run(['sh', str(ROOT/'docker-entrypoint.sh'), 'echo', 'started'],
                env={'PATH':temp+':/usr/bin:/bin', 'CALL_LOG':str(log), 'SUPABASE_WAIT_FOR_READY':'true',
                     'SUPABASE_PUBLIC_URL':'https://example.invalid', 'SUPABASE_ANON_KEY':'test-only'},
                capture_output=True, text=True, check=True)
            self.assertIn('/auth/v1/health', log.read_text())
            self.assertIn('/rest/v1/blog_posts', log.read_text())
            self.assertTrue(result.stdout.endswith('Staging Supabase ready.\nstarted\n'))

    def test_unready_database_times_out_without_starting_web(self):
        with tempfile.TemporaryDirectory() as temp:
            for name, body in {
                'curl': '#!/bin/sh\nexit 1\n',
                'date': '#!/bin/sh\nif [ -f "$CLOCK_MARK" ]; then echo 301; else touch "$CLOCK_MARK"; echo 0; fi\n',
            }.items():
                path = Path(temp)/name; path.write_text(body); path.chmod(0o755)
            result = subprocess.run(['sh', str(ROOT/'docker-entrypoint.sh'), 'echo', 'started'],
                env={'PATH':temp+':/usr/bin:/bin', 'CLOCK_MARK':str(Path(temp)/'clock'),
                     'SUPABASE_WAIT_FOR_READY':'true', 'SUPABASE_PUBLIC_URL':'https://example.invalid',
                     'SUPABASE_ANON_KEY':'test-only'}, capture_output=True, text=True, timeout=5)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('started', result.stdout)
            self.assertIn('refusing to serve', result.stderr)

    def test_missing_staging_configuration_fails_before_starting(self):
        result = subprocess.run(['sh', str(ROOT/'docker-entrypoint.sh'), 'echo', 'started'],
            env={'PATH':'/usr/bin:/bin','SUPABASE_WAIT_FOR_READY':'true'}, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('started', result.stdout)


if __name__ == '__main__': unittest.main()
