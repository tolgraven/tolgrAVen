<?php
// Input is supplied over authenticated SSH; no credentials leave Coolify.
require '/var/www/html/vendor/autoload.php';
$app = require '/var/www/html/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();
function cmsRequire($ok,$message) { if (!$ok) throw new RuntimeException($message); }
function cmsEnv($resource,$key,$value,$preview=false) {
    $relation=$resource instanceof App\Models\Application && $preview ? $resource->environment_variables_preview() : $resource->environment_variables();
    $fields=['value'=>$value,'is_literal'=>true,'is_multiline'=>false];
    if ($resource instanceof App\Models\Application) $fields+=['is_buildtime'=>false,'is_runtime'=>true];
    $relation->updateOrCreate(['key'=>$key,'is_preview'=>$preview],$fields);
}
try {
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
        $compose=Symfony\Component\Yaml\Yaml::parse($service->docker_compose_raw);
        $compose['services']['cms']['image']=$image;
        $service->docker_compose_raw=Symfony\Component\Yaml\Yaml::dump($compose,20,2);
        $service->save();
        foreach(['APP_KEYS','ADMIN_JWT_SECRET','API_TOKEN_SALT','TRANSFER_TOKEN_SALT','ENCRYPTION_KEY','JWT_SECRET','CMS_READ_TOKEN','CMS_ADMIN_PASSWORD'] as $key) {
            if (!$service->environment_variables()->where('key',$key)->first()?->value) cmsEnv($service,$key,bin2hex(random_bytes(32)));
        }
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
        foreach([false,true] as $preview) {
            cmsEnv($web,'STRAPI_URL',$url,$preview);
            cmsEnv($web,'STRAPI_READ_TOKEN',$token,$preview);
        }
    }
    if ($input['action']==='start') App\Actions\Service\StartService::dispatch($service);
    if ($input['action']==='verify') {
        $response=Illuminate\Support\Facades\Http::withToken($token)->timeout(15)->get($url.'/api/site-content');
        cmsRequire($response->successful() && $response->json('version')===1 && count($response->json('content')??[])===16,'CMS content verification failed');
        cmsRequire(Illuminate\Support\Facades\Http::timeout(10)->get($url.'/api/site-content')->status()===401,'CMS endpoint must require a server token');
    }
    echo json_encode(['uuid'=>$service->uuid,'url'=>$url,'action'=>$input['action'],'status'=>$service->status,'environment'=>$environment->name]).PHP_EOL;
} catch (Throwable $e) { fwrite(STDERR,get_class($e).': '.$e->getMessage().PHP_EOL); exit(1); }
