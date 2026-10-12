<?php
// Input is supplied over authenticated SSH; no credentials leave Coolify.
require '/var/www/html/vendor/autoload.php';
$app = require '/var/www/html/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();
class CmsProvisionException extends RuntimeException {}
function cmsRequire($ok,$message) { if (!$ok) throw new CmsProvisionException($message); }
function cmsEnv($resource,$key,$value,$preview=false) {
    $relation=$resource instanceof App\Models\Application && $preview ? $resource->environment_variables_preview() : $resource->environment_variables();
    $fields=['value'=>$value,'is_literal'=>true,'is_multiline'=>false];
    if ($resource instanceof App\Models\Application) $fields+=['is_buildtime'=>in_array($key,['STRAPI_URL','STRAPI_READ_TOKEN'],true),'is_runtime'=>true];
    $relation->updateOrCreate(['key'=>$key,'is_preview'=>$preview],$fields);
}
// Coolify's interactive User::createToken reads the session's current team.
// This SSH adapter uses the same Sanctum relation/format with an explicit team.
function cmsDeployCredential($service, $team, $actionName) {
    $name = 'site-admin:'.$service->uuid.':'.$actionName;
    $key = 'CMS_'.strtoupper($actionName).'_WEBHOOK_TOKEN';
    $plain = $service->environment_variables()->where('key', $key)->first()?->value;
    $token = is_string($plain) && $plain !== '' ? App\Models\PersonalAccessToken::findToken($plain) : null;
    $expiration = config('sanctum.expiration');
    if ($token && $token->name === $name && (string) $token->team_id === (string) $team->id
        && $token->abilities === ['deploy'] && $token->tokenable instanceof App\Models\User
        && $token->tokenable->isAdminOfTeam($team->id)
        && (!$token->expires_at || $token->expires_at->isFuture())
        && (!$expiration || $token->created_at->gt(now()->subMinutes($expiration)))) return $plain;

    $owner = $team->members()->wherePivotIn('role', ['owner', 'admin'])->orderBy('users.id')->first();
    cmsRequire($owner !== null, 'Coolify team needs an owner or admin for site deploy credentials');
    // Only replace credentials owned by this managed action, never a supplied token.
    App\Models\PersonalAccessToken::where('team_id', $team->id)->where('name', $name)->delete();
    $secret = $owner->generateTokenString();
    $token = $owner->tokens()->create([
        'name' => $name,
        'token' => hash('sha256', $secret),
        'abilities' => ['deploy'],
        'team_id' => $team->id,
        'expires_at' => null,
    ]);
    return $token->getKey().'|'.$secret;
}
function cmsWebhook($webhook) {
    cmsRequire(is_array($webhook), 'Webhook must contain a URL and credential');
    $url = $webhook['url'] ?? null;
    $destination = is_string($url) ? parse_url($url) : false;
    cmsRequire(($destination['scheme'] ?? null) === 'https' && !empty($destination['host'])
        && !isset($destination['user']) && !isset($destination['pass']) && !isset($destination['fragment']),
        'Invalid HTTPS webhook destination');
    cmsRequire(is_string($webhook['token'] ?? null) && strlen($webhook['token']) > 0, 'Webhook credential required');
    return $webhook;
}
function cmsWireActions($service, $web, $team, $overrides) {
    cmsRequire($service->environment_id === $web->environment_id
        && $service->environment->project->team_id === $team->id, 'CMS actions must belong to the resource team and environment');
    cmsRequire(is_array($overrides) && !array_diff(array_keys($overrides), ['rebuild', 'reprovision']), 'Invalid CMS webhook actions');
    // Validate every override before storing credentials or replacing managed tokens.
    $hooks = array_map('cmsWebhook', $overrides);
    if (count($hooks) < 2) {
        $settings = App\Models\InstanceSettings::get();
        cmsRequire($settings->is_api_enabled, 'Enable Coolify API access before provisioning CMS deploy actions');
        $origin = rtrim((string) $settings->fqdn, '/');
        cmsRequire(preg_match('#^https://[a-z0-9.-]+(?::[0-9]+)?$#', $origin), 'Coolify needs a public HTTPS origin for CMS deploy actions');
    }
    foreach (['rebuild' => $web->uuid, 'reprovision' => $service->uuid] as $actionName => $resourceUuid) {
        $name = 'site-admin:'.$service->uuid.':'.$actionName;
        if (!isset($hooks[$actionName])) {
            $hooks[$actionName] = [
                'url' => $origin.'/api/v1/deploy?'.http_build_query(['uuid' => $resourceUuid, 'force' => 'true']),
                'token' => cmsDeployCredential($service, $team, $actionName),
            ];
        } else {
            $managed = App\Models\PersonalAccessToken::where('team_id', $team->id)->where('name', $name);
            $supplied = App\Models\PersonalAccessToken::findToken($hooks[$actionName]['token']);
            if ($supplied) $managed->where('id', '!=', $supplied->id);
            $managed->delete();
        }
        cmsEnv($service, 'CMS_'.strtoupper($actionName).'_WEBHOOK_URL', $hooks[$actionName]['url']);
        cmsEnv($service, 'CMS_'.strtoupper($actionName).'_WEBHOOK_TOKEN', $hooks[$actionName]['token']);
    }
}

if (defined('PROVISION_STRAPI_FUNCTIONS_ONLY')) return;

try {
    if (($input['action'] ?? null) === 'prepare') Illuminate\Support\Facades\DB::beginTransaction();
    cmsRequire(in_array($input['action'],['prepare','start','verify','wire','status'],true),'Invalid action');
    $server=App\Models\Server::where('uuid',$input['server_uuid'])->firstOrFail();
    $environment=App\Models\Environment::where('uuid',$input['environment_uuid'])->firstOrFail();
    cmsRequire($environment->project->team_id === $server->team_id,'Environment and server must share a team');
    $destination=$server->destinations()->where('uuid',$input['destination_uuid'])->firstOrFail();
    $web=$environment->applications()->where('uuid',$input['application_uuid'])->firstOrFail();
    $name=$input['name']; $url=$input['url']; $image=$input['image'];
    cmsRequire(preg_match('/^[a-z][a-z0-9-]{1,60}$/',$name),'Invalid CMS name');
    cmsRequire(preg_match('#^https://[a-z0-9.-]+$#',$url),'CMS URL must be an HTTPS origin');
    cmsRequire(preg_match('#^(?:127\.0\.0\.1:5005|registry\.bux\.tolgraven\.se)/tolgraven/strapi:[A-Za-z0-9_.-]+$#',$image),'Unexpected CMS registry');
    $marker='Managed by scripts/provision-strapi.php';
    $service=$environment->services()->where('name',$name)->first();
    if (!$service) {
        cmsRequire($input['action']==='prepare','Prepare the CMS first');
        $check=checkIfDomainIsAlreadyUsedViaAPI(collect([$url]),$server->team_id);
        cmsRequire(!($check['hasConflicts']??false),'CMS domain is already in use');
        $compose=['services'=>['cms'=>[
            'image'=>$image,'environment'=>[
                'SERVICE_URL_CMS_1337','PUBLIC_URL=${SERVICE_URL_CMS}',
                'APP_KEYS=${APP_KEYS}','ADMIN_JWT_SECRET=${ADMIN_JWT_SECRET}',
                'API_TOKEN_SALT=${API_TOKEN_SALT}','TRANSFER_TOKEN_SALT=${TRANSFER_TOKEN_SALT}',
                'ENCRYPTION_KEY=${ENCRYPTION_KEY}','JWT_SECRET=${JWT_SECRET}',
                'CMS_READ_TOKEN=${CMS_READ_TOKEN}','CMS_ADMIN_EMAIL=${CMS_ADMIN_EMAIL}',
                'CMS_ADMIN_PASSWORD=${CMS_ADMIN_PASSWORD}','CMS_SEED_CONTENT=${CMS_SEED_CONTENT}',
                'DATABASE_CLIENT=sqlite','DATABASE_FILENAME=/opt/app/data/data.db',
                'STRAPI_TELEMETRY_DISABLED=true','NODE_OPTIONS=--max-old-space-size=384'],
            'volumes'=>['cms-data:/opt/app/data','cms-uploads:/opt/app/public/uploads'],
            'mem_limit'=>'768m','restart'=>'unless-stopped',
            'healthcheck'=>['test'=>['CMD','node','-e',"fetch('http://127.0.0.1:1337/_health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"], 'interval'=>'15s','timeout'=>'5s','retries'=>8,'start_period'=>'90s']
        ]],'volumes'=>['cms-data'=>null,'cms-uploads'=>null]];
        $raw=Symfony\Component\Yaml\Yaml::dump($compose,20,2);
        $service=App\Models\Service::create(['name'=>$name,'description'=>$marker,'docker_compose_raw'=>$raw,
            'environment_id'=>$environment->id,'server_id'=>$server->id,
            'destination_id'=>$destination->id,'destination_type'=>$destination->getMorphClass()]);
        $service->parse(isNew:true); applyServiceApplicationPrerequisites($service);
    }
    cmsRequire($service->description===$marker && $service->server_id===$server->id,'Refusing unrelated CMS');
    if ($input['action']==='prepare') {
        $service=App\Models\Service::whereKey($service->id)->lockForUpdate()->firstOrFail();
        $compose=Symfony\Component\Yaml\Yaml::parse($service->docker_compose_raw);
        $compose['services']['cms']['image']=$image;
        $keys=['CMS_SITE_MANIFEST','CMS_REBUILD_WEBHOOK_URL','CMS_REBUILD_WEBHOOK_TOKEN',
               'CMS_REPROVISION_WEBHOOK_URL','CMS_REPROVISION_WEBHOOK_TOKEN'];
        foreach ($keys as $key) {
            $declaration=$key.'=${'.$key.'}';
            if (!in_array($declaration,$compose['services']['cms']['environment'],true))
                $compose['services']['cms']['environment'][]=$declaration;
        }

        $service->docker_compose_raw=Symfony\Component\Yaml\Yaml::dump($compose,20,2);
        $service->save();
        foreach(['APP_KEYS','ADMIN_JWT_SECRET','API_TOKEN_SALT','TRANSFER_TOKEN_SALT','ENCRYPTION_KEY','JWT_SECRET','CMS_READ_TOKEN','CMS_ADMIN_PASSWORD'] as $key) {
            if (!$service->environment_variables()->where('key',$key)->first()?->value) cmsEnv($service,$key,bin2hex(random_bytes(32)));
        }
        if (isset($input['site'])) {
            $site=$input['site'];
            cmsEnv($service,'CMS_SITE_MANIFEST',json_encode(['config'=>$site['config'],
                'availableModules'=>$site['available-modules'],'pages'=>$site['pages']]));
        }
        cmsWireActions($service, $web, $environment->project->team, $input['webhooks'] ?? []);
        cmsEnv($service,'CMS_ADMIN_EMAIL',$input['admin_email']);
        cmsEnv($service,'CMS_SEED_CONTENT',($input['seed']??false)?'true':'false');
        cmsEnv($service,'SERVICE_URL_CMS',$url);
        cmsEnv($service,'SERVICE_FQDN_CMS',parse_url($url,PHP_URL_HOST));
        $cms=$service->applications()->where('name','cms')->firstOrFail();
        $cms->fqdn=$url.':1337'; $cms->save();
        $service->parse();
    }
    $token=$service->environment_variables()->where('key','CMS_READ_TOKEN')->firstOrFail()->value;
    if ($input['action']==='wire') {
        $web->settings()->update(['use_build_secrets'=>true,'disable_build_cache'=>true]);
        foreach([false,true] as $preview) {
            cmsEnv($web,'STRAPI_URL',$url,$preview);
            cmsEnv($web,'STRAPI_READ_TOKEN',$token,$preview);
        }
    }
    if ($input['action']==='start') App\Actions\Service\StartService::dispatch($service);
    if ($input['action']==='verify') {
        $response=Illuminate\Support\Facades\Http::withToken($token)->timeout(15)->get($url.'/api/site-content');
        cmsRequire($response->successful() && $response->json('version')===1 && !array_diff($input['site']['content']??[],array_keys($response->json('content')??[])) && !array_diff(array_keys($response->json('content')??[]),$input['site']['content']??[]),'CMS content verification failed');
        cmsRequire(Illuminate\Support\Facades\Http::timeout(10)->get($url.'/api/site-content')->status()===401,'CMS endpoint must require a server token');
    }
    if ($input['action']==='prepare') Illuminate\Support\Facades\DB::commit();
    echo json_encode(['uuid'=>$service->uuid,'url'=>$url,'action'=>$input['action'],'status'=>$service->status,'environment'=>$environment->name]).PHP_EOL;
} catch (Throwable $e) {
    if (Illuminate\Support\Facades\DB::transactionLevel() > 0) Illuminate\Support\Facades\DB::rollBack();
    // Database errors can include secret bindings; only our validation messages are public.
    fwrite(STDERR,($e instanceof CmsProvisionException ? $e->getMessage() : 'CMS provisioning failed ('.get_class($e).')').PHP_EOL);
    exit(1);
}
