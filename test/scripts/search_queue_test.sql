-- Run only in an isolated empty PostgreSQL fixture database, never production.
create role anon;
create role authenticated;
create role service_role bypassrls;
create table public.blog_posts(id bigint primary key, title text, text text, ts bigint, user_id text, tags text);
create table public.blog_comments(id text primary key, title text, text text, ts bigint, user_id text, parent_post bigint);
create table public.github_repositories(owner text,repo text,is_public boolean);
create table public.github_commits(owner text,repo text,sha text,message text,committed_at timestamptz,
                                 author_name text,author_login text,indexed_at timestamptz);
-- The test runner inserts the selected module SQL here before the assertions.
