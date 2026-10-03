<?php
// Run inside the installed Coolify container with the helper path as argv[1].
// Every write is rolled back; existing runtime containers are never touched.
define('PROVISION_SITE_FUNCTIONS_ONLY', true);
require $argv[1];
Illuminate\Support\Facades\DB::beginTransaction();
try {
    foreach ([
        ['production', 'bsok8o8csgso8c00g00k0csg', 'e840kco0scs04gkcco44w088'],
        ['staging', 'o84wgo08wcs048ss8sokgkgw', 'fqaammdsestcbglokp8ewao0'],
    ] as [$name, $appUuid, $serviceUuid]) {
        $application = App\Models\Application::where('uuid', $appUuid)->firstOrFail();
        $service = App\Models\Service::where('uuid', $serviceUuid)->firstOrFail();
        $config = ['supabase_url' => envValue($service, 'SERVICE_URL_SUPABASEKONG')];
        $values = webSupabaseEnv($service, $config, $name);
        // Exercise retries, including Coolify's automatic preview creation.
        for ($attempt = 0; $attempt < 2; $attempt++) {
            foreach ([false, true] as $preview) {
                foreach ($values as $key => $value) setEnv($application, $key, $value, $preview);
            }
            verifyWebSupabaseEnv($application, $values);
        }
        foreach ([false, true] as $preview) {
            foreach ($values as $key => $value) {
                $relation = $preview ? $application->environment_variables_preview() : $application->environment_variables();
                requireThat($relation->where('key', $key)->count() === 1, 'Duplicate managed runtime key');
            }
        }
        $rejected = false;
        try { webSupabaseEnv($service, ['supabase_url'=>'https://wrong.example.invalid'], $name); }
        catch (RuntimeException $error) { $rejected = true; }
        requireThat($rejected, 'Mismatched service endpoint was accepted');
        $bad = $values; $bad['SUPABASE_ANON_KEY'] = 'wrong-environment-key';
        $rejected = false;
        try { verifyWebSupabaseEnv($application, $bad); }
        catch (RuntimeException $error) { $rejected = true; }
        requireThat($rejected, 'Mismatched frontend key was accepted');
        echo "$name: repeated wiring, unique keys, runtime scopes and mismatch rejection passed\n";
    }
} finally {
    Illuminate\Support\Facades\DB::rollBack();
    echo "All test writes rolled back.\n";
}
