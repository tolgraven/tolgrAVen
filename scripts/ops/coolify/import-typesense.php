<?php
// Import only into the explicitly selected managed service. Never print keys/documents.
require '/var/www/html/vendor/autoload.php';
$app = require '/var/www/html/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();
class SearchMigrationException extends RuntimeException {}
function migrationRequire($ok, $message) {
    if (!$ok) throw new SearchMigrationException($message);
}

try {
    $server = App\Models\Server::where('uuid', $input['server_uuid'])->firstOrFail();
    $environment = App\Models\Environment::where('uuid', $input['environment_uuid'])->firstOrFail();
    $web = $environment->applications()->where('uuid', $input['application_uuid'])->firstOrFail();
    $destination = $server->destinations()->where('uuid', $input['destination_uuid'])->firstOrFail();
    $service = $environment->services()->where('name', $input['name'])->firstOrFail();
    migrationRequire($environment->project->team_id === $server->team_id
        && $service->description === 'Managed by scripts/ops/provision-typesense.py'
        && $service->server_id === $server->id
        && $service->destination_id === $destination->id
        && $service->destination_type === $destination->getMorphClass()
        && $web->destination_id === $destination->id
        && $web->destination_type === $destination->getMorphClass(), 'Migration target ownership does not match');
    $directory = $input['archive_directory'] ?? '';
    migrationRequire(preg_match('#^/tmp/typesense-migration-[0-9-]+$#', $directory)
        && realpath($directory) === $directory, 'Invalid private migration directory');
    migrationRequire(hash_file('sha256', $directory.'/manifest.json') === $input['manifest_sha256'],
                     'Source migration manifest checksum does not match');
    $manifest = json_decode(file_get_contents($directory.'/manifest.json'), true, flags: JSON_THROW_ON_ERROR);
    $key = $service->environment_variables()->where('key', 'TYPESENSE_API_KEY')->firstOrFail()->value;
    $url = 'http://typesense-'.$service->uuid.':8108';
    $client = Illuminate\Support\Facades\Http::withHeaders(['X-TYPESENSE-API-KEY' => $key])->timeout(60);
    $request = function ($method, $path, $body = null) use ($client, $url) {
        $options = $body === null ? [] : ['body' => $body, 'headers' => ['Content-Type' => 'application/json']];
        $result = $client->send($method, $url.$path, $options);
        migrationRequire($result->successful(), 'Typesense migration request failed');
        return $result;
    };
    $result = [];
    $targetDirectory = $directory.'/target';
    if (!is_dir($targetDirectory)) mkdir($targetDirectory, 0700);
    foreach ($manifest['collections'] as $collection) {
        $schema = $collection['schema'];
        $name = $schema['name'];
        migrationRequire(preg_match('/^[A-Za-z0-9_-]{1,100}$/', $name), 'Invalid source collection name');
        $path = '/collections/'.rawurlencode($name);
        $existing = $client->get($url.$path);
        if ($existing->status() === 404) {
            unset($schema['num_documents'], $schema['created_at']);
            $request('POST', '/collections', json_encode($schema, JSON_THROW_ON_ERROR));
        } else migrationRequire($existing->successful(), 'Cannot inspect target collection');
        $file = fopen($directory.'/'.$name.'.jsonl', 'r');
        migrationRequire($file !== false, 'Source document export is missing');
        $batch = [];
        $expectedIds = [];
        $import = function () use (&$batch, &$expectedIds, $request, $path) {
            if (!$batch) return;
            $reply = $request('POST', $path.'/documents/import?action=upsert&return_id=true', implode("\n", $batch));
            $rows = preg_split('/\r?\n/', trim($reply->body()));
            migrationRequire(count($rows) === count($batch), 'Incomplete document import response');
            foreach ($rows as $index => $line) {
                $row = json_decode($line, true, flags: JSON_THROW_ON_ERROR);
                migrationRequire(($row['success'] ?? false) === true && ($row['id'] ?? null) === $expectedIds[$index],
                                 'A source document failed to import');
            }
            $batch = []; $expectedIds = [];
        };
        while (($line = fgets($file)) !== false) {
            if (trim($line) === '') continue;
            $document = json_decode($line, true, flags: JSON_THROW_ON_ERROR);
            $batch[] = trim($line); $expectedIds[] = $document['id'];
            if (count($batch) === 40) $import();
        }
        fclose($file); $import();
        foreach ($collection['overrides'] as $override)
            $request('PUT', $path.'/overrides/'.rawurlencode($override['id']), json_encode($override, JSON_THROW_ON_ERROR));
        foreach ($collection['synonyms'] as $synonym)
            $request('PUT', $path.'/synonyms/'.rawurlencode($synonym['id']), json_encode($synonym, JSON_THROW_ON_ERROR));
        $target = $request('GET', $path)->json();
        migrationRequire($target['num_documents'] === $collection['count'], 'Target document count does not match source');
        file_put_contents($targetDirectory.'/'.$name.'.jsonl', $request('GET', $path.'/documents/export')->body());
        $result[] = ['name' => $name, 'count' => $target['num_documents']];
    }
    foreach ($manifest['aliases'] as $alias)
        $request('PUT', '/aliases/'.rawurlencode($alias['name']), json_encode(['collection_name' => $alias['collection_name']], JSON_THROW_ON_ERROR));
    echo json_encode(['uuid' => $service->uuid, 'collections' => $result, 'target_export' => $targetDirectory]).PHP_EOL;
} catch (Throwable $error) {
    fwrite(STDERR, ($error instanceof SearchMigrationException ? $error->getMessage()
        : 'Typesense migration failed ('.get_class($error).' at line '.$error->getLine().')').PHP_EOL);
    exit(1);
}
