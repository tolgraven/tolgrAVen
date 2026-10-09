import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('provision_site', ROOT / 'scripts/ops/provision-site.py')
provision = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provision)


class ManifestSafetyTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((ROOT / 'deploy/coolify/site.example.json').read_text())

    def test_separate_environments_and_no_data_by_default(self):
        plan = provision.plan(provision.validate(self.manifest))
        self.assertEqual(set(plan['environments']), {'production', 'staging'})
        self.assertIn('no production data', plan['data'])
        self.assertNotEqual(plan['environments']['production']['supabase'], plan['environments']['staging']['supabase'])

    def test_rejects_shared_supabase_endpoint(self):
        self.manifest['environments']['staging']['supabase_url'] = self.manifest['environments']['production']['supabase_url']
        with self.assertRaisesRegex(ValueError, 'distinct origin'):
            provision.validate(self.manifest)

    def test_existing_application_requires_project_scope(self):
        self.manifest['environments']['staging']['application_uuid'] = 'existing'
        with self.assertRaisesRegex(ValueError, 'project_uuid'):
            provision.validate(self.manifest)

    def test_rejects_ssh_option_injection(self):
        self.manifest['ssh_host'] = '-oProxyCommand=evil'
        with self.assertRaises(ValueError):
            provision.validate(self.manifest)

    def test_rejects_credentials_in_origins(self):
        self.manifest['environments']['staging']['supabase_url'] = 'https://user:secret@example.com'
        with self.assertRaisesRegex(ValueError, 'HTTPS origin'):
            provision.validate(self.manifest)

    def test_separate_controller_and_resource_hosts(self):
        self.manifest['coolify_ssh_host'] = 'controller'
        self.manifest['ssh_host'] = 'resource-host'
        provision.validate(self.manifest)
        with patch.object(provision, 'ssh', return_value='{}') as remote:
            provision.remote(self.manifest, 'status')
            self.assertEqual(remote.call_args.kwargs['host'], 'controller')

    def test_capacity_counts_new_stacks_and_existing_runtimes(self):
        state = {'environments': {'production': {'database_container': 'supabase-db-prod', 'application_uuid': 'webprod'},
                                  'staging': {'database_container': 'supabase-db-stage', 'application_uuid': 'webstage'}}}
        self.assertEqual(provision.capacity_budget(state, []), 6144)
        self.assertEqual(provision.capacity_budget(state, ['supabase-db-prod', 'webprod-123']), 3072)
        self.assertEqual(provision.capacity_budget(state, ['supabase-db-prod', 'webprod-123', 'supabase-db-stage']), 1024)
        self.assertEqual(provision.capacity_budget(state, ['supabase-db-prod', 'webprod-123', 'supabase-db-stage', 'webstage-pr-45']), 0)

    def test_php_input_is_encoded_not_interpolated(self):
        self.manifest['name'] = "x');system('evil');"
        rendered = provision.php_script(self.manifest, 'status')
        self.assertNotIn(self.manifest['name'], rendered)

    def test_cms_requires_separate_origin_and_an_administrator(self):
        self.manifest['environments']['staging']['cms_url'] = self.manifest['environments']['production']['cms_url']
        with self.assertRaisesRegex(ValueError, 'distinct origin'):
            provision.validate(self.manifest)
        self.manifest['environments']['staging']['cms_url'] = 'https://cms-stage.example.com'
        self.manifest['environments']['staging']['cms_admin_email'] = ''
        with self.assertRaisesRegex(ValueError, 'cms_admin_email'):
            provision.validate(self.manifest)

    def test_cms_memory_is_included_when_not_running(self):
        state = {'environments': {'staging': {'database_container': 'supabase-db-stage',
                 'application_uuid': 'webstage', 'cms_container': 'cms-isolated'}}}
        self.assertEqual(provision.capacity_budget(state, []), 3840)
        self.assertEqual(provision.capacity_budget(state, ['supabase-db-stage', 'webstage', 'cms-isolated']), 0)

    def test_cms_uses_each_environments_own_web_application(self):
        import base64
        import re
        payloads = []
        state = {'environments': {name: {'environment_uuid': name, 'application_uuid': name+'-web'}
                                  for name in ('production','staging')}}
        def fake_ssh(spec, args, source, **kwargs):
            encoded = re.search(r"base64_decode\('([^']+)'", source).group(1)
            payloads.append(json.loads(base64.b64decode(encoded)))
            return json.dumps({'uuid': payloads[-1]['environment_uuid']+'-cms'})
        with patch.object(provision, 'ssh', side_effect=fake_ssh):
            provision.provision_cms(self.manifest, state, 'wire')
        self.assertEqual([p['application_uuid'] for p in payloads], ['production-web','staging-web'])
        self.assertEqual(state['environments']['staging']['cms_container'], 'cms-staging-cms')


if __name__ == '__main__':
    unittest.main()
