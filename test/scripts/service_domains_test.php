<?php
// Run inside Coolify: php service_domains_test.php <provision-site.php|provision-strapi.php>.
// Exercise native parser normalization; every write rolls back, no restart runs.
define('PROVISION_SITE_FUNCTIONS_ONLY', true);
define('PROVISION_STRAPI_FUNCTIONS_ONLY', true);
require $argv[1];
$cms = function_exists('cmsDomains');
$targets = $cms ? [
    [13, 'https://strapi.staging.tolgraven.se'],
    [14, 'https://strapi.tolgraven.se'],
] : [
    [5, 'https://supabase.tolgraven.se'],
    [10, 'https://supabase.staging.tolgraven.se'],
];
$domainList = $cms ? 'cmsDomains' : 'supabaseDomains';
$name = $cms ? 'cms' : 'supabase-kong';
$key = $cms ? 'SERVICE_URL_CMS' : 'SERVICE_URL_SUPABASEKONG';
Illuminate\Support\Facades\DB::beginTransaction();
try {
    foreach ($targets as [$id, $url]) {
        $service = App\Models\Service::findOrFail($id);
        $application = $service->applications()->where('name', $name)->firstOrFail();
        $original = array_map('getFqdnWithoutPort', explode(',', $application->fqdn));
        for ($attempt = 0; $attempt < 2; $attempt++) {
            $application->fqdn = $domainList($application->fqdn, $url);
            $application->save();
            $service->parse();
            $application->refresh();
            $domains = array_map('getFqdnWithoutPort', explode(',', $application->fqdn));
            if (array_diff($original, $domains)) throw new RuntimeException('Legacy proxy alias removed');
            if (count($domains) !== count(array_unique($domains))) throw new RuntimeException('Duplicate alias after retry');
            $canonical = $service->environment_variables()->where('key', $key)->firstOrFail()->value;
            if ($canonical !== $url) throw new RuntimeException('Canonical service URL differs');
        }
        echo $id.": canonical URL, legacy aliases and retries passed\n";
    }
} finally {
    Illuminate\Support\Facades\DB::rollBack();
    echo "All writes rolled back; no restart triggered.\n";
}
