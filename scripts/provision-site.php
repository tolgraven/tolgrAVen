<?php
// Run inside the existing Coolify container over an authenticated SSH session.
// $input is supplied by provision-site.py. Secrets never leave Coolify's database.
require '/var/www/html/vendor/autoload.php';
$app = require '/var/www/html/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();

function provisionCompose($raw) {
    $compose = Symfony\Component\Yaml\Yaml::parse($raw);
    if (isset($compose['services']['minio-createbucket'])) {
        // Docker Hub removed minio/mc. Coolify's MinIO image includes /usr/bin/mc.
        $compose['services']['minio-createbucket']['image'] = $compose['services']['supabase-minio']['image'];
    }
    return Symfony\Component\Yaml\Yaml::dump($compose, 20, 2, Symfony\Component\Yaml\Yaml::DUMP_MULTI_LINE_LITERAL_BLOCK);
}
function requireThat($condition, $message) {
    if (!$condition) throw new RuntimeException($message);
}
function envValue($resource, $key) {
    $value = $resource->environment_variables()->where('key', $key)->first()?->value;
    requireThat(is_string($value) && strlen($value) > 0, "Missing generated environment variable: $key");
    return $value;
}
function setEnv($resource, $key, $value, $preview = false, $build = false) {
    $fields = ['value' => $value, 'is_literal' => true, 'is_multiline' => false];
    if ($resource instanceof App\Models\Application) {
        $fields += ['is_buildtime' => $build, 'is_runtime' => !$build];
    }
    $relation = $resource instanceof App\Models\Application && $preview
        ? $resource->environment_variables_preview() : $resource->environment_variables();
    $record = $relation->updateOrCreate(['key' => $key, 'is_preview' => $preview], $fields);
    // Older provisioning used the normal-only relation for previews and could
    // create duplicate preview keys on retries. Keep one authoritative record.
    $relation->where('key', $key)->where('is_preview', $preview)->where('id', '!=', $record->id)->delete();
}

function webSupabaseEnv($service, $config, $name) {
    $url = rtrim(envValue($service, 'SERVICE_URL_SUPABASEKONG'), '/');
    requireThat($url === rtrim($config['supabase_url'], '/'), 'Supabase service URL differs from the environment manifest');
    return [
        'SUPABASE_PUBLIC_URL' => $url,
        'SUPABASE_ANON_KEY' => envValue($service, 'SERVICE_SUPABASEANON_KEY'),
        'SUPABASE_SERVICE_KEY' => envValue($service, 'SERVICE_SUPABASESERVICE_KEY'),
        'SUPABASE_WAIT_FOR_READY' => $name === 'staging' ? 'true' : 'false',
    ];
}
function verifyWebSupabaseEnv($application, $values) {
    // A stale higher-priority alias could silently override a freshly copied key.
    $aliases = ['SUPABASE_PUBLISHABLE_KEY' => 'SUPABASE_ANON_KEY',
        'NEXT_PUBLIC_SUPABASE_ANON_KEY' => 'SUPABASE_ANON_KEY',
        'SUPABASE_URL' => 'SUPABASE_PUBLIC_URL', 'NEXT_PUBLIC_SUPABASE_URL' => 'SUPABASE_PUBLIC_URL',
        'SUPABASE_SERVICE_ROLE_KEY' => 'SUPABASE_SERVICE_KEY'];
    foreach ([false, true] as $preview) {
        foreach ($values as $key => $value) {
            $env = ($preview ? $application->environment_variables_preview() : $application->environment_variables())->where('key', $key)->first();
            requireThat($env && hash_equals($value, (string) $env->value), "Web runtime variable mismatch: $key");
            requireThat($env->is_runtime && !$env->is_buildtime, "Web variable must be runtime-only: $key");
        }
        foreach ($aliases as $alias => $canonical) {
            $env = ($preview ? $application->environment_variables_preview() : $application->environment_variables())->where('key', $alias)->first();
            requireThat(!$env || hash_equals($values[$canonical], (string) $env->value), "Conflicting Supabase variable alias: $alias");
        }
    }
}

if (defined('PROVISION_SITE_FUNCTIONS_ONLY')) return;

try {
    $spec = $input['spec'];
    if (in_array($input['action'], ['prepare','probe','wire'])) Illuminate\Support\Facades\DB::beginTransaction();
    $rollback = $input['action'] === 'probe';
    $action = $rollback ? 'prepare' : $input['action'];
    requireThat(in_array($action, ['prepare', 'start', 'wire', 'deploy', 'status', 'ready', 'verify']), 'Unknown action');
    requireThat(preg_match('/^[a-z][a-z0-9-]{1,48}$/', $spec['name']), 'Invalid site name');
    $server = App\Models\Server::where('uuid', $spec['server_uuid'])->firstOrFail();
    $destination = $server->destinations()->where('uuid', $spec['destination_uuid'])->first();
    requireThat($destination !== null, 'Destination must belong to the selected server');
    $marker = 'Managed by scripts/provision-site.py: '.$spec['name'];
    $project = isset($spec['project_uuid'])
        ? App\Models\Project::where('uuid', $spec['project_uuid'])->firstOrFail()
        : App\Models\Project::where('team_id', $server->team_id)->where('name', $spec['name'])->first();
    if (!$project) {
        requireThat($action === 'prepare', 'Run prepare first');
        $project = App\Models\Project::create(['name' => $spec['name'], 'description' => $marker, 'team_id' => $server->team_id]);
    }
    requireThat($project->team_id === $server->team_id, 'Project and server must belong to the same team');
    if (!isset($spec['project_uuid'])) requireThat($project->description === $marker, 'Project name is already used by an unmanaged project');
    $result = ['project_uuid' => $project->uuid, 'environments' => []];
    foreach ($spec['environments'] as $name => $config) {
        requireThat(in_array($name, ['production', 'staging']), 'Unsupported environment');
        $environment = $project->environments()->where('name', $name)->first();
        if (!$environment) {
            requireThat($action === 'prepare', 'Run prepare first');
            $environment = $project->environments()->create(['name' => $name]);
        }
        $resourceMarker = "$marker/$name";
        $serviceName = $spec['name'].'-'.$name.'-supabase';
        $service = $environment->services()->where('name', $serviceName)->first();
        if (!$service) {
            requireThat($action === 'prepare', 'Run prepare first');
            $domainCheck = checkIfDomainIsAlreadyUsedViaAPI(collect([$config['supabase_url']]), $server->team_id);
            requireThat(!($domainCheck['hasConflicts'] ?? false), 'Supabase domain is already used');
            $template = data_get(get_service_templates(), 'supabase.compose');
            requireThat(is_string($template) && strlen($template) > 0, 'Installed Supabase template not found');
            // Fresh template, UUID, credentials and UUID-scoped volumes. Never clone a live stack.
            $service = App\Models\Service::create([
                'name' => $serviceName, 'description' => $resourceMarker,
                'docker_compose_raw' => provisionCompose(base64_decode($template)), 'service_type' => 'supabase',
                'environment_id' => $environment->id, 'server_id' => $server->id,
                'destination_id' => $destination->id, 'destination_type' => $destination->getMorphClass(),
            ]);
            $service->parse(isNew: true);
            applyServiceApplicationPrerequisites($service);
        }
        requireThat($service->description === $resourceMarker && $service->server_id === $server->id, 'Refusing unrelated Supabase resource');
        $application = isset($config['application_uuid'])
            ? $environment->applications()->where('uuid', $config['application_uuid'])->firstOrFail()
            : $environment->applications()->where('name', $spec['name'].'-'.$name.'-web')->first();
        if ($action === 'prepare') {
            // Keep configuration stable across retries. Regenerating JWTs would invalidate users.
            $kong = $service->applications()->where('name', 'supabase-kong')->firstOrFail();
            $kong->fqdn = $config['supabase_url'].':8000';
            $kong->save();
            setEnv($service, 'SERVICE_URL_SUPABASEKONG', $config['supabase_url']);
            setEnv($service, 'SERVICE_FQDN_SUPABASEKONG', parse_url($config['supabase_url'], PHP_URL_HOST));
            setEnv($service, 'GOTRUE_SITE_URL', $config['web_url']);
            setEnv($service, 'ADDITIONAL_REDIRECT_URLS', $config['web_url'].'/**');
            setEnv($service, 'DISABLE_SIGNUP', 'false');
            foreach (($config['supabase_env'] ?? []) as $key => $value) {
                requireThat(preg_match('/^(SMTP_|MAILER_|ENABLE_EMAIL_|GOTRUE_EXTERNAL_)[A-Z0-9_]+$/', $key), 'Unsupported Supabase configuration override');
                setEnv($service, $key, $value);
            }
            if (!$application) {
                $domainCheck = checkIfDomainIsAlreadyUsedViaAPI(collect([$config['web_url']]), $server->team_id);
                requireThat(!($domainCheck['hasConflicts'] ?? false), 'Web domain is already used');
                $application = new App\Models\Application;
                $application->fill([
                    'name' => $spec['name'].'-'.$name.'-web', 'description' => $resourceMarker,
                    'git_repository' => $spec['repository'], 'git_branch' => $config['branch'],
                    'build_pack' => 'dockerfile', 'dockerfile_location' => '/Dockerfile',
                    'base_directory' => '/', 'ports_exposes' => '3000', 'fqdn' => $config['web_url'],
                    'destination_id' => $destination->id, 'destination_type' => $destination->getMorphClass(),
                    'environment_id' => $environment->id, 'limits_memory' => '1g', 'limits_memory_swap' => '1536m',
                    'health_check_enabled' => true, 'health_check_path' => '/',
                    'health_check_port' => 3000, 'health_check_start_period' => 60,
                ]);
                if (preg_match('#^https://github.com/([^/]+/[^/]+?)(?:\.git)?$#', $spec['repository'], $match)) {
                    $application->git_repository = $match[1];
                    $application->source_type = App\Models\GithubApp::class;
                    $application->source_id = 0;
                }
                $application->save();
                $application->settings->is_auto_deploy_enabled = false;
                $application->settings->is_preview_deployments_enabled = false;
                $application->settings->is_force_https_enabled = true;
                $application->settings->save();
                if (isset($spec['builder_image'])) setEnv($application, 'BUILDER_IMAGE', $spec['builder_image'], false, true);
            }
        }
        requireThat($application !== null, 'Web application missing');
        if (!isset($config['application_uuid'])) requireThat($application->description === $resourceMarker, 'Refusing unrelated web application');
        if ($action === 'start') App\Actions\Service\StartService::dispatch($service);
        if (in_array($action, ['wire', 'ready', 'verify'])) {
            // Check the new endpoint before replacing an existing app's runtime configuration.
            $response = Illuminate\Support\Facades\Http::timeout(15)->withHeaders([
                'apikey' => envValue($service, 'SERVICE_SUPABASEANON_KEY'),
            ])->get($config['supabase_url'].'/rest/v1/blog_posts?select=id&limit=1');
            requireThat($response->successful(), 'Supabase REST/schema check failed: HTTP '.$response->status());
            $health = Illuminate\Support\Facades\Http::timeout(15)->withHeaders([
                'apikey' => envValue($service, 'SERVICE_SUPABASEANON_KEY'),
            ])->get($config['supabase_url'].'/auth/v1/health');
            requireThat($health->successful(), 'Supabase Auth check failed');
        }
        if (in_array($action, ['wire', 'verify', 'deploy'])) $runtimeEnv = webSupabaseEnv($service, $config, $name);
        if (in_array($action, ['verify', 'deploy'])) verifyWebSupabaseEnv($application, $runtimeEnv);
        if ($action === 'verify') {
            $settings = Illuminate\Support\Facades\Http::timeout(15)->get($config['web_url'].'/api/supabase/settings');
            requireThat($settings->successful(), 'Web Supabase settings check failed');
            requireThat($settings->json('url') === $runtimeEnv['SUPABASE_PUBLIC_URL'], 'Web app still points to a different Supabase instance');
            requireThat(hash_equals($runtimeEnv['SUPABASE_ANON_KEY'], (string) $settings->json('anon-key')), 'Web frontend key does not match its Supabase instance');
            requireThat(!str_contains($settings->body(), $runtimeEnv['SUPABASE_SERVICE_KEY']), 'Server-only Supabase key leaked into frontend settings');
            requireThat(Illuminate\Support\Facades\Http::timeout(15)->get($config['web_url'])->successful(), 'Web homepage check failed');
        }
        if ($action === 'wire') {
            if ($name === 'staging') { $application->health_check_start_period = 360; $application->save(); }
            foreach ([false, true] as $preview) {
                foreach ($runtimeEnv as $key => $value) setEnv($application, $key, $value, $preview);
            }
            verifyWebSupabaseEnv($application, $runtimeEnv);
        }
        $entry = ['environment_uuid' => $environment->uuid, 'application_uuid' => $application->uuid,
            'supabase_uuid' => $service->uuid, 'supabase_url' => $config['supabase_url'],
            'database_container' => 'supabase-db-'.$service->uuid, 'status' => $service->status,
            'latest_deployment' => $application->deployment_queue()->latest()->first()?->only(['deployment_uuid','status'])];
        if ($action === 'deploy') {
            requireThat(!$application->deployment_queue()->whereIn('status', ['queued','in_progress'])->exists(), 'Application already has a deployment in progress');
            $uuid = (string) Illuminate\Support\Str::uuid();
            $entry['deployment'] = queue_application_deployment(application: $application,
                deployment_uuid: $uuid, pull_request_id: $config['pull_request_id'] ?? 0);
        }
        $result['environments'][$name] = $entry;
    }
    if (in_array($action, ['prepare','wire'])) {
        if ($rollback) { Illuminate\Support\Facades\DB::rollBack(); $result['rolled_back'] = true; }
        else Illuminate\Support\Facades\DB::commit();
    }
    echo json_encode($result, JSON_THROW_ON_ERROR)."\n";
} catch (Throwable $error) {
    if (Illuminate\Support\Facades\DB::transactionLevel()) Illuminate\Support\Facades\DB::rollBack();
    // Never dump request data or models: those may contain runtime credentials.
    fwrite(STDERR, get_class($error).': '.$error->getMessage()."\n");
    exit(1);
}
