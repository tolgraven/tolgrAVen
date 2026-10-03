# Supabase memory for small sites

Measured on bux on 2026-10-03 before the staging suspension/resume test. These
are Docker resident-memory snapshots, not maximum requirements; some pages
were swapped out. Database size is not the main idle cost of this stack.

| Staging component | Resident MiB |
| --- | ---: |
| Postgres | 87 |
| Auth | 15 |
| REST | 29 |
| Storage API | 147 |
| Realtime | 180 |
| Kong gateway | 160 |
| MinIO | 83 |
| imgproxy | 40 |
| Supavisor | 95 |
| Studio | 232 |
| postgres-meta | 78 |
| Analytics / Logflare | 235 |
| Vector log collector | 50 |
| Edge Functions | 58 |

## Implemented saving: stop idle staging entirely

`staging_supabase.py` stops the complete tolgraven staging stack after the last
web runtime/deployment disappears, retaining all data. It resumes on demand.
This removes the whole idle container footprint, instead of merely shrinking
Postgres buffers. See [site provisioning](site-provisioning.md).

## Further reductions investigated

1. **Make Analytics and Vector optional.** Together they used about 285 MiB
   resident in this sample. Current upstream Supabase already makes them opt-in;
   Auth, REST, Storage, Realtime and Studio work without them. The tradeoff is
   losing Studio's aggregated Logs Explorer, not ordinary Docker/Coolify logs.
   The installed Coolify template still includes these services and dependencies,
   so simply stopping their containers is not a persistent configuration change.
2. **Start Studio and postgres-meta only for administration.** About 310 MiB
   combined in this sample. Site browser traffic uses Auth/REST/Storage/Realtime,
   not Studio. This would require a separate admin-start command and dependency
   checks; leave them enabled if always-available Studio is preferred.
3. **Omit unused Edge Functions and optionally imgproxy.** About 58 MiB and
   40 MiB respectively in the sample. No Edge Function invocations or Storage
   image-transformation calls were found in the current app source. Removing
   these features must remain a per-site choice, since another site may use them.
4. **Tune gateway workers and connection pools for measured concurrency.** Each
   Kong worker and each database connection adds overhead. These are preferable
   tuning targets to blindly imposing low memory limits, which can create OOM
   restarts instead of reducing demand. Keep Auth/REST/Realtime/Storage working
   and test concurrent requests before adopting smaller pools. The installed
   template currently allows 20 backend connections per Supavisor tenant,
   100 client connections and a separate five-connection metadata pool; these
   are concurrency allowances, not proof that all those connections are open.

The first two options account for about 595 MiB of the measured resident
footprint. That is potential removal of currently resident services, not a
verified reduction or a capacity guarantee. No optional services have been
removed from existing production or staging as part of this investigation.
The installed images and database version remain unchanged. Upstream's
experimental `slim-services` images target local development/CI; they are not
being substituted into these production-oriented stacks.

Sources:
- [Supabase: Analytics and Vector becoming opt-in](https://supabase.com/changelog/46084-self-hosted-supabase-making-analytics-and-vector-opt-in)
- [Supabase Docker self-hosting and resource requirements](https://supabase.com/docs/guides/self-hosting/docker)
- [Kong gateway configuration](https://developer.konghq.com/gateway/configuration/)
- [Supabase connection pooler configuration](https://supabase.com/docs/guides/self-hosting/accessing-postgres)
- [Experimental slim-services scope](https://github.com/supabase/slim-services)
