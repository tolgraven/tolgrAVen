<?php
// Authenticated SSH adapter. Keys remain in Coolify's encrypted environment store.
require '/var/www/html/vendor/autoload.php';
$app = require '/var/www/html/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();

class SearchProvisionException extends RuntimeException {}
function searchRequire($ok, $message) {
    if (!$ok) throw new SearchProvisionException($message);
}
function searchEnv($resource, $key, $value, $preview = false) {
    $relation = $resource instanceof App\Models\Application && $preview
        ? $resource->environment_variables_preview() : $resource->environment_variables();
    $fields = ['value' => $value, 'is_literal' => true, 'is_multiline' => false];
    if ($resource instanceof App\Models\Application)
        $fields += ['is_buildtime' => false, 'is_runtime' => true];
    $relation->updateOrCreate(['key' => $key, 'is_preview' => $preview], $fields);
}
function searchCompose() {
    return ['services' => ['typesense' => [
        'image' => 'typesense/typesense:30.2',
        'environment' => ['TYPESENSE_API_KEY=${TYPESENSE_API_KEY}', 'TYPESENSE_DATA_DIR=/data',
                          'TYPESENSE_THREAD_POOL_SIZE=4', 'TYPESENSE_NUM_COLLECTIONS_PARALLEL_LOAD=1',
                          'TYPESENSE_MAX_INDEXING_CONCURRENCY=2'],
        'expose' => ['8108'],
        'volumes' => ['typesense-data:/data'],
        'mem_limit' => '1g',
        'cpus' => '1.0',
        'restart' => 'unless-stopped',
        'healthcheck' => [
            'test' => ['CMD', '/usr/bin/timeout', '3', '/bin/bash', '-c',
                       'exec 3<>/dev/tcp/127.0.0.1/8108; printf "GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; grep -q \'"ok":true\' <&3'],
            'interval' => '15s', 'timeout' => '5s', 'retries' => 8, 'start_period' => '30s']]],
        'volumes' => ['typesense-data' => null]];
}

if (defined('PROVISION_TYPESENSE_FUNCTIONS_ONLY')) return;

try {
    searchRequire(in_array($input['action'] ?? null, ['prepare', 'start', 'verify', 'wire', 'status'], true), 'Invalid action');
    $server = App\Models\Server::where('uuid', $input['server_uuid'])->firstOrFail();
    $environment = App\Models\Environment::where('uuid', $input['environment_uuid'])->firstOrFail();
    searchRequire($environment->project->team_id === $server->team_id, 'Environment and server must share a team');
    $destination = $server->destinations()->where('uuid', $input['destination_uuid'])->firstOrFail();
    $web = $environment->applications()->where('uuid', $input['application_uuid'])->firstOrFail();
    searchRequire($web->destination_id === $destination->id
        && $web->destination_type === $destination->getMorphClass(), 'Application must use the selected destination');
    $name = $input['name'] ?? '';
    $collection = $input['collection'] ?? 'website_v1';
    searchRequire(preg_match('/^[a-z][a-z0-9-]{1,60}$/', $name), 'Invalid search service name');
    searchRequire(preg_match('/^[a-zA-Z0-9_-]{1,80}$/', $collection), 'Invalid search collection');
    $marker = 'Managed by scripts/ops/provision-typesense.py';
    if ($input['action'] === 'prepare') Illuminate\Support\Facades\DB::beginTransaction();
    $service = $environment->services()->where('name', $name)->first();
    if (!$service) {
        searchRequire($input['action'] === 'prepare', 'Prepare Typesense first');
        $service = App\Models\Service::create([
            'name' => $name, 'description' => $marker,
            'docker_compose_raw' => Symfony\Component\Yaml\Yaml::dump(searchCompose(), 20, 2),
            'environment_id' => $environment->id, 'server_id' => $server->id,
            'destination_id' => $destination->id, 'destination_type' => $destination->getMorphClass(),
            'connect_to_docker_network' => true]);
        $service->parse(isNew: true);
        applyServiceApplicationPrerequisites($service);
    }
    searchRequire($service->description === $marker && $service->server_id === $server->id
        && $service->destination_id === $destination->id
        && $service->destination_type === $destination->getMorphClass(), 'Refusing an unrelated search service');
    if ($input['action'] === 'prepare') {
        $service = App\Models\Service::whereKey($service->id)->lockForUpdate()->firstOrFail();
        $service->connect_to_docker_network = true;
        $service->save();
        if (!$service->environment_variables()->where('key', 'TYPESENSE_API_KEY')->first()?->value)
            searchEnv($service, 'TYPESENSE_API_KEY', bin2hex(random_bytes(32)));
        $service->parse();
        if ($input['dry_run'] ?? false) Illuminate\Support\Facades\DB::rollBack();
        else Illuminate\Support\Facades\DB::commit();
    }
    $url = 'http://typesense-'.$service->uuid.':8108';
    if ($input['action'] === 'start') App\Actions\Service\StartService::dispatch($service);
    if (in_array($input['action'], ['verify', 'wire'], true)) {
        $key = $service->environment_variables()->where('key', 'TYPESENSE_API_KEY')->firstOrFail()->value;
        $health = Illuminate\Support\Facades\Http::timeout(10)->get($url.'/health');
        searchRequire($health->successful() && $health->json('ok') === true, 'Private Typesense health check failed');
        searchRequire(Illuminate\Support\Facades\Http::timeout(10)->get($url.'/collections')->status() === 401,
                      'Typesense must reject unauthenticated collection access');
        searchRequire(Illuminate\Support\Facades\Http::withHeaders(['X-TYPESENSE-API-KEY' => $key])
            ->timeout(10)->get($url.'/collections')->successful(), 'Typesense private authentication failed');
        if ($input['action'] === 'wire') {
            Illuminate\Support\Facades\DB::transaction(function () use ($web, $url, $key, $collection) {
                $web->settings()->update(['connect_to_docker_network' => true]);
                // Wire only this deployment. Previews need a separate sink and
                // must never inherit production indexing credentials/jobs.
                // Runtime-only: no key enters frontend builds or public settings.
                searchEnv($web, 'TYPESENSE_URL', $url);
                searchEnv($web, 'TYPESENSE_API_KEY', $key);
                searchEnv($web, 'TYPESENSE_COLLECTION', $collection);
                $owned = ['TYPESENSE_URL' => $url, 'TYPESENSE_API_KEY' => $key,
                          'TYPESENSE_COLLECTION' => $collection];
                $preview = $web->environment_variables_preview()->whereIn('key', array_keys($owned))->get();
                // Repair earlier wiring only when all three values identify
                // this exact managed production sink; retain unrelated previews.
                if ($preview->count() === 3 && $preview->every(
                    fn ($entry) => hash_equals($owned[$entry->key], (string) $entry->value)))
                    $web->environment_variables_preview()->whereIn('id', $preview->modelKeys())->delete();
            });
        }
    }
    echo json_encode(['uuid' => $service->uuid, 'url' => $url, 'action' => $input['action'],
                      'status' => $service->status, 'environment' => $environment->name,
                      'dry_run' => $input['dry_run'] ?? false]).PHP_EOL;
} catch (Throwable $error) {
    if (Illuminate\Support\Facades\DB::transactionLevel() > 0) Illuminate\Support\Facades\DB::rollBack();
    fwrite(STDERR, ($error instanceof SearchProvisionException ? $error->getMessage()
        : 'Search provisioning failed ('.get_class($error).')').PHP_EOL);
    exit(1);
}
