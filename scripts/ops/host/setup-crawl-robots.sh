#!/usr/bin/env bash
set -euo pipefail
# Inspect by default; install only with explicit apply. No application restart.
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
action=${1:-plan}
[[ "$action" == plan || "$action" == apply ]] || { printf 'Usage: %s [plan|apply]\n' "$0" >&2; exit 1; }
proxy_image=$(docker inspect coolify-proxy --format '{{.Config.Image}}')
[[ "$proxy_image" == traefik:v3.* ]] || { printf 'Expected the existing Traefik v3 proxy\n' >&2; exit 1; }
proxy_gateway=$(docker inspect coolify-proxy --format '{{(index .NetworkSettings.Networks "coolify").Gateway}}')
python3 - "$proxy_gateway" <<'PY'
import ipaddress, sys
address = ipaddress.ip_address(sys.argv[1])
assert address.version == 4 and address.is_private and not address.is_loopback
PY
if [[ "$action" == plan ]]; then
  printf 'Private bux/box zones: Traefik robots route via %s:18082; apply installs a systemd responder.\n' "$proxy_gateway"
  exit 0
fi
[[ $EUID == 0 ]] || { printf 'Apply requires root on the host\n' >&2; exit 1; }
exec 9>/run/tolgraven-crawl-robots.lock
flock -n 9
install -d -m 700 /var/lib/tolgraven-crawl-policy
recovery_dir=$(mktemp -d /var/lib/tolgraven-crawl-policy/install-XXXXXXXX)
install -d -m 755 /usr/local/lib/tolgraven
was_active=false
was_enabled=false
systemctl is-active --quiet tolgraven-crawl-robots.service && was_active=true
systemctl is-enabled --quiet tolgraven-crawl-robots.service && was_enabled=true
for path in /usr/local/lib/tolgraven/crawl_robots.py /etc/systemd/system/tolgraven-crawl-robots.service /data/coolify/proxy/dynamic/tolgraven-crawl-robots.yaml; do
  if [ -f "$path" ]; then cp -a "$path" "$recovery_dir/$(basename "$path")"; fi
done
printf 'active=%s\nenabled=%s\n' "$was_active" "$was_enabled" > "$recovery_dir/service-state"
rollback() {
  status=$?
  trap - EXIT
  if ((status != 0)); then
    set +e
    for path in /usr/local/lib/tolgraven/crawl_robots.py /etc/systemd/system/tolgraven-crawl-robots.service /data/coolify/proxy/dynamic/tolgraven-crawl-robots.yaml; do
      saved="$recovery_dir/$(basename "$path")"
      if [ -f "$saved" ]; then cp -a "$saved" "$path"; else rm -f "$path"; fi
    done
    systemctl daemon-reload
    if "$was_active"; then systemctl restart tolgraven-crawl-robots.service; else systemctl stop tolgraven-crawl-robots.service; fi
    if ! "$was_enabled"; then systemctl disable tolgraven-crawl-robots.service >/dev/null; fi
    printf 'Installation failed; restored prior files and service state. Recovery: %s\n' "$recovery_dir" >&2
  fi
  exit "$status"
}
trap rollback EXIT
install -m 644 "$script_dir/crawl_robots.py" /usr/local/lib/tolgraven/crawl_robots.py
python3 - "$proxy_gateway" <<'PY'
from pathlib import Path
import sys
Path('/etc/systemd/system/tolgraven-crawl-robots.service').write_text(f'''[Unit]
Description=Robots exclusions for private server hostnames
Requires=docker.service
After=docker.service

[Service]
Type=simple
ExecStart=/usr/bin/python3 /usr/local/lib/tolgraven/crawl_robots.py --bind {sys.argv[1]}
Restart=on-failure
DynamicUser=yes
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
RestrictAddressFamilies=AF_INET AF_UNIX

[Install]
WantedBy=multi-user.target
''')
PY
systemctl daemon-reload
systemctl enable tolgraven-crawl-robots.service >/dev/null
systemctl restart tolgraven-crawl-robots.service
curl --fail --silent --show-error --retry 5 --retry-delay 1 --retry-connrefused \
  -H 'Host: web.bux.tolgraven.se' "http://$proxy_gateway:18082/robots.txt" | \
  python3 -c 'import sys; assert sys.stdin.read() == "User-agent: *\nDisallow: /\n"'
python3 - "$proxy_gateway" <<'PY'
import json, sys
from pathlib import Path
rule = r'HostRegexp(`^([a-z0-9-]+\.)*(bux|box)\.tolgraven\.se$`) && Path(`/robots.txt`)'
routers = {}
for name, entry in [('http', 'http'), ('https', 'https')]:
    router = {'entryPoints': [entry], 'rule': rule, 'priority': 100000,
              'service': 'tolgraven-crawl-robots'}
    if name == 'https': router['tls'] = {}
    routers['tolgraven-crawl-robots-' + name] = router
config = {'http': {'routers': routers, 'services': {'tolgraven-crawl-robots': {
    'loadBalancer': {'servers': [{'url': f'http://{sys.argv[1]}:18082'}]}}}}}
path = Path('/data/coolify/proxy/dynamic/tolgraven-crawl-robots.yaml')
temporary = path.with_suffix('.pending')
temporary.write_text(json.dumps(config, indent=2) + '\n')
temporary.chmod(0o644)
temporary.replace(path)
PY
printf 'Installed private-zone crawl policy; recovery directory: %s\n' "$recovery_dir"
