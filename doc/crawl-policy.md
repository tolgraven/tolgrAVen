# Server-host crawl policy

`bux.tolgraven.se`, `box.tolgraven.se` and every descendant hostname are server
aliases, staging or infrastructure endpoints. Their `/robots.txt` response is:

```text
User-agent: *
Disallow: /
```

The proxy selects this response by hostname and exact path, including requests
with a query string. Other application/API paths keep their existing routing.
`tolgraven.se`, `*.site.tolgraven.se`, `*.portfolio.tolgraven.se` and unrelated
hostnames keep their own robots policies. Each hostname needs its own response;
a parent hostname's robots file does not govern descendant hostnames. This is a
crawler instruction, so it does not restrict access to the services themselves.

## Coolify / Traefik on bux

Copy `scripts/ops/host/setup-crawl-robots.sh` and `crawl_robots.py` to the same
directory on the authorized host. The installer inspects the existing Traefik v3
proxy and its private `coolify` network gateway. Inspect first, then apply:

```sh
bash setup-crawl-robots.sh plan
sudo bash setup-crawl-robots.sh apply
```

Apply installs a dependency-free Python responder bound to the private Docker
gateway, a hardened systemd unit, and an atomic dynamic Traefik configuration.
It checks the responder before publishing the route. HTTP and HTTPS routes have
a higher priority than ordinary application routes and match only the server
hostname suffixes plus `/robots.txt`. Application containers are not restarted.

The installer serializes itself with a host lock. It stores prior files and
service enabled/active state under `/var/lib/tolgraven-crawl-policy/install-*`.
On failure it restores those files and the previous service state. Keep this
recovery directory until both private and public host checks have passed.

## CapRover / nginx on box

Copy `scripts/ops/host/crawl_policy.py` to the authorized host:

```sh
sudo python3 crawl_policy.py plan
sudo python3 crawl_policy.py apply
```

Plan reads installed CapRover templates/configuration and reports its scope
without writing. Apply verifies that the installed control plane supports the
persistent `server-block-conf-override.ejs` template. It adds shared includes to
the base HTTP template, root server template, custom app templates and future
app template, retaining unrelated configuration fields.

The generated nginx candidate is checked with `nginx -t` before persisted
CapRover configuration changes or nginx reload. Writes preserve generated-file
inodes because nginx bind mounts refer to them. A successful apply reloads nginx
and restarts the `captain-captain` control plane to acquire the stored templates;
application services are not restarted. Configuration edits by CapRover during
preparation abort apply and are retained.

Private recovery files live under
`/captain/data/tolgraven-crawl-policy-backups/<timestamp>/`. A manifest records
each original path, backup filename, mode and whether the file originally
existed. The directory is owner-only and files are mode `0600`. Validation or
persistence failures restore generated files, templates and shared includes;
if persistence began, nginx reloads the restored configuration. If a control-plane
restart was attempted, rollback restarts it again against the restored templates.
Recovery failures retain the original apply failure and report the recovery error
as its cause; inspect the backup manifest before any manual repair.

## Verification

Local checks exercise a real HTTP responder, suffix exclusions, GET/HEAD/query
handling, template idempotence, candidate validation ordering, bind-mount inode
preservation, partial-write rollback and concurrent configuration edits:

```sh
python3 -m unittest discover -s test/scripts -p crawl_policy_test.py
bash -n scripts/ops/host/setup-crawl-robots.sh
```

After an authorized installation, check both proxies and a descendant hostname
over HTTP and HTTPS. Confirm the exact private response, an ordinary application
path and a public site/portfolio hostname. Local fixtures establish script
behavior; live proxy installation and those external checks remain separate.
