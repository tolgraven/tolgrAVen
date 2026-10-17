<?php
// Bootstrap/update the Coolify-managed gate. No credentials leave the server.
require '/var/www/html/vendor/autoload.php';
$app = require '/var/www/html/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();
try {
    $production = App\Models\Application::where('uuid', 'bsok8o8csgso8c00g00k0csg')->firstOrFail();
    $preview = App\Models\Application::where('uuid', 'o84wgo08wcs048ss8sokgkgw')->firstOrFail();
    $source = $production->source;
    if ($source->app_id != 2257592 || $source->id != 1) throw new RuntimeException('Unexpected GitHub App');
    $marker = 'Managed by scripts/ops/provision-prefab-gate.py';
    $environment = $production->environment;
    $service = $environment->services()->where('name', 'tolgraven-prefab-gate')->first();
    if ($service && $service->description !== $marker) throw new RuntimeException('Unmanaged service');
    if ($input['action'] === 'prepare') {
        if (!preg_match('#^registry\.bux\.tolgraven\.se/tolgraven/prefab-gate:[a-f0-9]{16}$#D', $input['image']))
            throw new RuntimeException('Invalid gate image');
        $compose = ['services' => ['gate' => [
            'image' => $input['image'], 'restart' => 'unless-stopped', 'expose' => ['8080'],
            'environment' => ['WEBHOOK_SECRET=${WEBHOOK_SECRET}', 'PREVIEW_SECRET=${PREVIEW_SECRET}',
                              'GITHUB_APP_ID=2257592'],
            'volumes' => ['/var/run/docker.sock:/var/run/docker.sock',
                          '/root/.docker/config.json:/root/.docker/config.json:ro',
                          '/data/coolify/prefab-gate/state:/state'],
            'mem_limit' => '256m', 'cpus' => '0.25',
            'healthcheck' => ['test' => ['CMD', 'python3', '-c',
                'import urllib.request; urllib.request.urlopen("http://127.0.0.1:8080/health", timeout=3)'],
                'interval' => '10s', 'timeout' => '5s', 'retries' => 6],
        ]]];
        Illuminate\Support\Facades\DB::transaction(function () use (&$service, $environment, $production, $source, $preview, $marker, $compose) {
            if (!$service) {
                $service = App\Models\Service::create([
                    'name' => 'tolgraven-prefab-gate', 'description' => $marker,
                    'docker_compose_raw' => Symfony\Component\Yaml\Yaml::dump($compose, 20, 2),
                    'environment_id' => $environment->id, 'server_id' => $production->destination->server_id,
                    'destination_id' => $production->destination_id, 'destination_type' => $production->destination_type,
                    'connect_to_docker_network' => true,
                ]);
                $service->parse(isNew: true);
                applyServiceApplicationPrerequisites($service);
            } else {
                $service->docker_compose_raw = Symfony\Component\Yaml\Yaml::dump($compose, 20, 2);
                $service->save();
            }
            foreach (['WEBHOOK_SECRET' => $source->webhook_secret,
                      'PREVIEW_SECRET' => $preview->manual_webhook_secret_github] as $key => $value) {
                if (!is_string($value) || strlen($value) < 20) throw new RuntimeException('Missing webhook secret');
                $service->environment_variables()->updateOrCreate(['key' => $key, 'is_preview' => false],
                    ['value' => $value, 'is_literal' => true, 'is_multiline' => false]);
            }
            $service->parse();
            $web = $service->applications()->where('name', 'gate')->firstOrFail();
            $web->fqdn = 'https://prefab.bux.tolgraven.se:8080';
            $web->save();
        });
    } elseif (!$service) throw new RuntimeException('Prepare the gate first');
    if ($input['action'] === 'start') App\Actions\Service\StartService::dispatch($service);
    if ($input['action'] === 'wire') {
        $health = Illuminate\Support\Facades\Http::timeout(10)->get('https://prefab.bux.tolgraven.se/health');
        if (!$health->successful()) throw new RuntimeException('Gate health check failed');
        $http = Illuminate\Support\Facades\Http::timeout(30)->withToken(generateGithubJwt($source))->acceptJson();
        $before = $http->get('https://api.github.com/app/hook/config');
        if (!$before->successful()) throw new RuntimeException('Cannot inspect App webhook');
        // Private rollback record excludes credentials, and is never printed.
        $path = '/tmp/tolgraven-prefab-webhook-before.json';
        if (!file_exists($path)) { file_put_contents($path, $before->body()); chmod($path, 0600); }
        $response = $http->patch('https://api.github.com/app/hook/config', ['url' => 'https://prefab.bux.tolgraven.se/github']);
        if (!$response->successful()) throw new RuntimeException('Cannot update App webhook');
    }
    echo json_encode(['uuid' => $service->uuid, 'status' => $service->status, 'action' => $input['action']]).PHP_EOL;
} catch (Throwable $error) {
    fwrite(STDERR, 'Prefab gate provisioning failed: '.$error->getMessage().PHP_EOL);
    exit(1);
}
