#!/usr/bin/env bash
set -euo pipefail
# Reuse Coolify's existing Hetzner credentials without sending secrets through the UI.
# Registry listens only on loopback; S3 objects remain private.
if docker inspect tolgraven-build-registry >/dev/null 2>&1; then
  docker start tolgraven-build-registry >/dev/null
  exit 0
fi
umask 077
registry_env=$(mktemp)
trap 'rm -f "$registry_env"' EXIT
docker exec coolify php artisan tinker --execute='$s = App\Models\S3Storage::where("name", "hetzner")->firstOrFail(); if ($s->bucket !== "tolgraven" || rtrim($s->endpoint, "/") !== "https://hel1.your-objectstorage.com") { throw new Exception("Unexpected S3 storage"); } foreach (["REGISTRY_STORAGE"=>"s3", "REGISTRY_STORAGE_S3_REGION"=>"us-east-1", "REGISTRY_STORAGE_S3_REGIONENDPOINT"=>$s->endpoint, "REGISTRY_STORAGE_S3_FORCEPATHSTYLE"=>"true", "REGISTRY_STORAGE_S3_BUCKET"=>$s->bucket, "REGISTRY_STORAGE_S3_ROOTDIRECTORY"=>"/docker-registry", "REGISTRY_STORAGE_S3_ACCESSKEY"=>$s->key, "REGISTRY_STORAGE_S3_SECRETKEY"=>$s->secret, "REGISTRY_STORAGE_REDIRECT_DISABLE"=>"true", "REGISTRY_HTTP_ADDR"=>":5000", "REGISTRY_LOG_LEVEL"=>"warn"] as $k=>$v) { if (str_contains($v, "\n")) {throw new Exception("Multiline config");} echo $k."=".$v."\n"; }' > "$registry_env"
docker run -d --name tolgraven-build-registry --restart unless-stopped \
  -p 127.0.0.1:5005:5000 --env-file "$registry_env" \
  registry:3@sha256:ddf754342cfc8acc51a56d5d0ab6af06826461864460636d8bd5c546dab2a7b8
