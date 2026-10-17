<?php
// Private control adapter. Never return the GitHub token or private key.
require '/var/www/html/vendor/autoload.php';
$app = require '/var/www/html/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();
try {
    $source = App\Models\GithubApp::findOrFail(1);
    if ($source->app_id != 2257592) throw new RuntimeException('Unexpected GitHub App');
    $sha = $input['sha'] ?? '';
    if (!preg_match('/^[a-f0-9]{40}$/D', $sha)) throw new RuntimeException('Invalid commit');
    $http = Illuminate\Support\Facades\Http::timeout(30)
        ->withToken(generateGithubInstallationToken($source))->acceptJson();
    $base = 'https://api.github.com/repos/tolgraven/tolgrAVen';
    if ($input['action'] === 'current') {
        $pr = $input['pr'] ?? 0;
        if (!is_int($pr) || $pr < 0) throw new RuntimeException('Invalid PR');
        $response = $http->get($base.($pr ? '/pulls/'.$pr : '/git/ref/heads/master'));
        if (!$response->successful()) throw new RuntimeException('GitHub current-head request failed');
        $current = $pr
            ? $response->json('state') === 'open' && $response->json('head.sha') === $sha
                && $response->json('head.repo.full_name') === 'tolgraven/tolgrAVen'
                && $response->json('base.ref') === 'master'
            : $response->json('object.sha') === $sha;
        echo json_encode(['current' => $current]);
    } elseif ($input['action'] === 'status') {
        if (!in_array($input['state'] ?? '', ['pending', 'success', 'failure'], true))
            throw new RuntimeException('Invalid status');
        $response = $http->post($base.'/statuses/'.$sha, [
            'state' => $input['state'], 'context' => 'prefab-ready',
            'description' => substr((string)($input['description'] ?? ''), 0, 140),
            'target_url' => 'https://bux.tolgraven.se',
        ]);
        if (!$response->successful()) throw new RuntimeException('GitHub status write failed: HTTP '.$response->status());
        echo json_encode(['status' => $response->status()]);
    } else throw new RuntimeException('Unsupported control operation');
    echo PHP_EOL;
} catch (Throwable $error) {
    fwrite(STDERR, 'Prefab control failed: '.$error->getMessage().PHP_EOL);
    exit(1);
}
