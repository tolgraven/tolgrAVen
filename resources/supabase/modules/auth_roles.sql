create table if not exists auth_roles (
  role text not null,
  user_id text not null references site_users(id) on delete cascade,
  primary key (role, user_id)
);

-- Private bridge/configuration tables must never be directly exposed.
alter table auth_roles enable row level security;

revoke all on auth_roles from anon, authenticated;

grant all on auth_roles to service_role;
