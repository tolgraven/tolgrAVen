<?php
// Run inside installed Coolify: php cms_actions_test.php <helper> <cms UUID> <web UUID>.
// Every write is rolled back; no deploy action or container restart is called.
if (!function_exists('cmsWireActions')) {
    define('PROVISION_STRAPI_FUNCTIONS_ONLY', true);
    require $argv[1];
}
function checkCms($ok, $message) { if (!$ok) throw new RuntimeException($message); }
function rejectedCms($f) {
    try { $f(); } catch (CmsProvisionException $e) { return; }
    throw new RuntimeException('Invalid CMS action configuration was accepted');
}
Illuminate\Support\Facades\DB::beginTransaction();
try {
    $service = App\Models\Service::where('uuid', $argv[2])->firstOrFail();
    $web = App\Models\Application::where('uuid', $argv[3])->firstOrFail();
    $team = $service->environment->project->team;
    $settings = App\Models\InstanceSettings::get();
    $settings->is_api_enabled = false; $settings->save();
    rejectedCms(fn() => cmsWireActions($service, $web, $team, []));
    $settings->is_api_enabled = true; $settings->save();
    foreach (['rebuild', 'reprovision'] as $action) {
        App\Models\PersonalAccessToken::where('team_id', $team->id)->where('name', 'site-admin:'.$service->uuid.':'.$action)->delete();
        cmsEnv($service, 'CMS_'.strtoupper($action).'_WEBHOOK_TOKEN', 'fixture-not-a-token');
    }
    $initialCount = App\Models\PersonalAccessToken::count();
    cmsWireActions($service, $web, $team, []);
    $read = fn($key) => $service->environment_variables()->where('key', $key)->firstOrFail()->value;
    $first = $read('CMS_REBUILD_WEBHOOK_TOKEN');
    $editor = $read('CMS_REPROVISION_WEBHOOK_TOKEN');
    checkCms($first !== $editor, 'Actions must have separate credentials');
    foreach (['REBUILD' => $web->uuid, 'REPROVISION' => $service->uuid] as $action => $uuid) {
        $token = App\Models\PersonalAccessToken::findToken($read('CMS_'.$action.'_WEBHOOK_TOKEN'));
        checkCms($token && (string) $token->team_id === (string) $team->id && $token->abilities === ['deploy'], 'Wrong token team or permissions');
        checkCms($token->tokenable->isAdminOfTeam($team->id), 'Token owner cannot deploy');
        checkCms(str_contains($read('CMS_'.$action.'_WEBHOOK_URL'), '/api/v1/deploy?uuid='.$uuid.'&force=true'), 'Wrong deploy resource');
        $request = Illuminate\Http\Request::create('/api/v1/deploy', 'POST');
        $request->setUserResolver(fn() => $token->tokenable->withAccessToken($token));
        $middleware = new App\Http\Middleware\ApiAbility();
        checkCms($middleware->handle($request, fn() => response('', 202), 'deploy')->getStatusCode() === 202, 'Deploy ability denied');
        checkCms($middleware->handle($request, fn() => response('', 202), 'read')->getStatusCode() === 403, 'Token unexpectedly permits read');
    }
    cmsWireActions($service, $web, $team, []);
    checkCms($first === $read('CMS_REBUILD_WEBHOOK_TOKEN') && $editor === $read('CMS_REPROVISION_WEBHOOK_TOKEN'), 'Retry rotated valid credentials');
    checkCms(App\Models\PersonalAccessToken::count() === $initialCount + 2, 'Retry created duplicate tokens');
    App\Models\PersonalAccessToken::findToken($first)->update(['expires_at' => now()->subDay()]);
    cmsWireActions($service, $web, $team, []);
    checkCms($first !== $read('CMS_REBUILD_WEBHOOK_TOKEN') && App\Models\PersonalAccessToken::findToken($first) === null, 'Expired credential not replaced');
    $first = $read('CMS_REBUILD_WEBHOOK_TOKEN');
    App\Models\PersonalAccessToken::findToken($first)->update(['abilities' => ['root']]);
    cmsWireActions($service, $web, $team, []);
    checkCms($first !== $read('CMS_REBUILD_WEBHOOK_TOKEN'), 'Broad credential reused');
    $first = $read('CMS_REBUILD_WEBHOOK_TOKEN');
    App\Models\PersonalAccessToken::findToken($first)->delete();
    cmsWireActions($service, $web, $team, []);
    checkCms($first !== $read('CMS_REBUILD_WEBHOOK_TOKEN'), 'Revoked credential not recreated');
    $first = $read('CMS_REBUILD_WEBHOOK_TOKEN');
    // A token from another team cannot be adopted, modified or revoked.
    $foreignTeam = App\Models\Team::create(['name' => 'cms-action-credential-test']);
    App\Models\PersonalAccessToken::findToken($first)->update(['team_id' => $foreignTeam->id]);
    cmsWireActions($service, $web, $team, []);
    rejectedCms(fn() => cmsWireActions($service, $web, $foreignTeam, []));
    checkCms($first !== $read('CMS_REBUILD_WEBHOOK_TOKEN') && (string) App\Models\PersonalAccessToken::findToken($first)->team_id === (string) $foreignTeam->id, 'Foreign team credential was reused or modified');
    $first = $read('CMS_REBUILD_WEBHOOK_TOKEN');
    rejectedCms(fn() => cmsWireActions($service, $web, $team, ['rebuild' => ['url' => 'https://user:password@example.invalid', 'token' => 'fixture']]));
    rejectedCms(fn() => cmsWireActions($service, $web, $team, ['reprovision' => ['url' => 'http://example.invalid', 'token' => 'fixture']]));
    rejectedCms(fn() => cmsWireActions($service, $web, $team, ['unknown' => ['url' => 'https://example.invalid', 'token' => 'fixture']]));
    checkCms($first === $read('CMS_REBUILD_WEBHOOK_TOKEN'), 'Invalid overrides changed the credential');
    cmsWireActions($service, $web, $team, ['rebuild' => ['url' => $read('CMS_REBUILD_WEBHOOK_URL'), 'token' => $first]]);
    checkCms(App\Models\PersonalAccessToken::findToken($first) !== null, 'Explicitly supplied managed token was revoked');
    $settings->fqdn = 'http://unsafe.invalid'; $settings->save();
    rejectedCms(fn() => cmsWireActions($service, $web, $team, []));
    // Both explicit overrides work without instance API access or an origin.
    $settings->is_api_enabled = false; $settings->save();
    $override = ['url' => 'https://runner.example.invalid/action', 'token' => 'fixture-only'];
    cmsWireActions($service, $web, $team, ['rebuild' => $override, 'reprovision' => $override]);
    checkCms($read('CMS_REBUILD_WEBHOOK_TOKEN') === 'fixture-only' && $read('CMS_REPROVISION_WEBHOOK_URL') === $override['url'], 'Explicit runner overrides lost');
    checkCms(App\Models\PersonalAccessToken::findToken($first) === null, 'Unused managed credential not revoked');
    echo "CMS actions: deploy-only authorization, team/resource ownership, retries, rotation, validation and overrides passed.\n";
} finally {
    Illuminate\Support\Facades\DB::rollBack();
    echo "All test writes rolled back; no deployment triggered.\n";
}
