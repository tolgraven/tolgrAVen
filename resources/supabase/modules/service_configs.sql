create table if not exists service_configs (
  service text primary key,
  collection text not null,
  doc_id text not null,
  config jsonb not null default '{}'::jsonb
);

alter table service_configs enable row level security;

revoke all on service_configs from anon, authenticated;

grant all on service_configs to service_role;
