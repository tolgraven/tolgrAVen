#!/usr/bin/env bash
set -euo pipefail
# Reuse Coolify's existing Hetzner credentials without sending secrets through the UI.
# Registry listens on loopback and the proxy network; public access requires HTTPS/basic auth.
if docker inspect tolgraven-build-registry >/dev/null 2>&1; then
  docker start tolgraven-build-registry >/dev/null
else
umask 077
registry_env=$(mktemp)
trap 'rm -f "$registry_env"' EXIT
docker exec coolify php artisan tinker --execute='$s = App\Models\S3Storage::where("name", "hetzner")->firstOrFail(); if ($s->bucket !== "tolgraven" || rtrim($s->endpoint, "/") !== "https://hel1.your-objectstorage.com") { throw new Exception("Unexpected S3 storage"); } foreach (["REGISTRY_STORAGE"=>"s3", "REGISTRY_STORAGE_S3_REGION"=>"us-east-1", "REGISTRY_STORAGE_S3_REGIONENDPOINT"=>$s->endpoint, "REGISTRY_STORAGE_S3_FORCEPATHSTYLE"=>"true", "REGISTRY_STORAGE_S3_BUCKET"=>$s->bucket, "REGISTRY_STORAGE_S3_ROOTDIRECTORY"=>"/docker-registry", "REGISTRY_STORAGE_S3_ACCESSKEY"=>$s->key, "REGISTRY_STORAGE_S3_SECRETKEY"=>$s->secret, "REGISTRY_STORAGE_REDIRECT_DISABLE"=>"true", "REGISTRY_HTTP_ADDR"=>":5000", "REGISTRY_LOG_LEVEL"=>"warn"] as $k=>$v) { if (str_contains($v, "\n")) {throw new Exception("Multiline config");} echo $k."=".$v."\n"; }' > "$registry_env"
docker run -d --name tolgraven-build-registry --restart unless-stopped \
  -p 127.0.0.1:5005:5000 --env-file "$registry_env" \
  registry:3@sha256:ddf754342cfc8acc51a56d5d0ab6af06826461864460636d8bd5c546dab2a7b8
fi
if [ "$(docker inspect --format '{{if index .NetworkSettings.Networks "coolify"}}yes{{end}}' tolgraven-build-registry)" != yes ]; then
  docker network connect coolify tolgraven-build-registry
fi
# Optional public route provisioning. Supply a bcrypt htpasswd file, never plaintext.
if [ -n "${REGISTRY_HTPASSWD_FILE:-}" ]; then
  test -s "$REGISTRY_HTPASSWD_FILE"
  registry_script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
  install -d -m 700 /data/coolify/proxy/auth
  install -m 600 "$REGISTRY_HTPASSWD_FILE" /data/coolify/proxy/auth/tolgraven-registry.htpasswd
  install -m 600 "$registry_script_dir/../../../deploy/coolify/registry-proxy.yaml" /data/coolify/proxy/dynamic/tolgraven-registry.yaml
fi
