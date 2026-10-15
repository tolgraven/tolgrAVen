# Bux SSD storage and maintenance

The attached Hetzner volume `scsi-0HC_Volume_107155672` already has ext4 UUID
`75c692b4-7894-4822-83c1-09250c94e267`. Do not format it. Its persistent mount is
`/mnt/bux-extra`; `/var/lib/docker` and `/data/coolify` bind to its `docker` and
`coolify` directories. Existing container bind paths, databases, uploads and
Coolify's generated files keep their original absolute paths. Containerd's tiny
independent directory remains on the root disk.

The Docker systemd drop-in requires these mounts. If the SSD is absent, Docker
must not start against empty root-disk directories. The root filesystem can still
boot because the volume entry uses `nofail`.

## Explicit migration

Install `scripts/ops/host/coolify-storage.py` under `/usr/local/lib/tolgraven/`.
It defaults to no action without an explicit command; `plan` only reports.

1. `coolify-storage.py prepare` verifies the existing filesystem, relocates its
   mount, backs up fstab and installs the persistent SSD entry. It never formats.
2. `coolify-storage.py precopy` copies current Docker/Coolify data while services
   remain available. This copy is not authoritative until the offline sync.
3. Wait for Coolify's running deployments to finish. `coolify-storage.py migrate`
   quiesces webhook admission and Horizon workers, waits up to 30 minutes for active
   deployments to finish (resuming admission on failure); queued jobs remain persisted
   and resume after maintenance. It records running containers and the scoped
   runtime policy, gracefully stops them, stops Docker, syncs again and verifies
   file checksums before switching the bind mounts. It then restarts the same
   containers and runtime policy, then resumes admission/workers. Preexisting
   maintenance/paused workers require manual inspection. This step requires a
   short maintenance window.
4. Verify mounts, all previously healthy services, web/CMS/provider endpoints,
   container volumes, Docker root directory, and queue operation. Then explicitly
   run `coolify-storage.py retire-originals` to reclaim the old root-disk copies.

Private recovery metadata is `/var/lib/tolgraven/storage-migration.json`;
originals remain at `/var/lib/docker.before-ssd` and `/data/coolify.before-ssd`
until verification. Do not rerun a partially completed cutover blindly. On error,
inspect its phase, current mounts and containers first. Before retiring originals,
rollback requires Docker stopped, new bind mounts unmounted, original directories
restored, original fstab restored from `/etc/fstab.before-tolgraven-storage`, and
the managed Docker drop-in removed. Then reload systemd/start Docker and the
recorded container/service identities. Run `docker exec coolify php artisan up`
and `docker exec coolify php artisan horizon:continue` to restore admission/jobs.
Never operate on mounted backup paths.
After retirement, restore data from the SSD or normal backups; old root copies
are no longer a recovery source. This volume is persistent storage, not a backup.

## Automatic cleanup

Install `coolify-storage-cleanup.py` beside the migration tool and the matching
service/timer from `scripts/ops/host/` into `/etc/systemd/system/`, then run
`systemctl daemon-reload` and `systemctl enable --now coolify-storage-cleanup.timer`.

The timer runs every six hours at 00:15, 06:15, 12:15 and 18:15 UTC,
with up to ten minutes of randomized delay,
catching missed runs after boot. A shared lock prevents cleanup during migration;
cleanup also refuses an unverified migration phase. Run the Python tool without
`--apply` to inspect its plan. Applied maintenance:

- Removes only immutable tolgrAVen production/staging/CMS image references older
  than 24 hours, retaining the newest three images per repository and every
  image referenced by any container, including stopped containers.
- Preserves `latest`, infrastructure images and unrelated repositories. Image
  deletion never uses force; container ownership is checked again before each.
- Prunes unused build cache older than six hours with an 8 GB target, plus dangling
  images older than 24 hours. In-use records are protected by Docker.
- Never prunes containers, networks or data volumes. Existing bounded Docker log
  rotation stays in place. Registry/S3 rollback artifacts are outside this policy.

Inspect `systemctl list-timers coolify-storage-cleanup.timer` and
`journalctl -u coolify-storage-cleanup.service` for its schedule and results.
