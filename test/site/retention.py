"""Fresh disposable PostgreSQL fixture: disabling preserves data and revokes access."""
import json, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
NAME = 'site_retention_fixture'
def run(args, **kw):
    return subprocess.run(args, cwd=ROOT, text=True, check=True, **kw)
def sql(statement):
    return run(['psql','-X','-qAt','-v','ON_ERROR_STOP=1',NAME],input=statement,capture_output=True).stdout.strip()
def manifest(modules):
    expression = "(require '[build.site :as site] '[cheshire.core :as json]) (println (json/generate-string (site/manifest-for {:modules "+modules+"})))"
    return json.loads(run(['bb','-e',expression],capture_output=True).stdout)
def main():
    run(['createdb', NAME])
    try:
        sql("create schema auth; create function auth.uid() returns uuid language sql as $$select null::uuid$$; create function auth.jwt() returns jsonb language sql as $$select '{}'::jsonb$$; create schema storage; create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]); create publication supabase_realtime;")
        full = manifest('{}')
        sql('BEGIN;'+full['sql']+'COMMIT;')
        sql("insert into site_users(id,name) values('u','Retained'); insert into blog_posts(id,doc_id,title,text,ts,user_id) values(42,'42','Retained','Post',1,'u'); insert into blog_comments(id,parent_post,text,ts,user_id) values('c',42,'Retained',1,'u'); insert into comment_votes(user_id,comment_id,vote) values('u','c',1);")
        assert sql("select has_column_privilege('anon','blog_comments','text','SELECT');") == 't'
        sql('BEGIN;'+manifest('{:blog {:comments false}}')['sql']+'COMMIT;')
        assert sql("select has_column_privilege('anon','blog_comments','text','SELECT');") == 'f'
        assert sql("select count(*) from blog_comments;") == '1'
        assert sql("select count(*) from comment_votes;") == '1'
        assert sql("select has_function_privilege('service_role','public.tolgraven_create_comment(text,bigint,text,text,text)','EXECUTE');") == 'f'
        assert sql("select count(*) from pg_publication_tables where pubname='supabase_realtime' and tablename='blog_comments';") == '0'
        sql('BEGIN;'+manifest('{:blog {:enabled false} :search {:enabled false}}')['sql']+'COMMIT;')
        assert sql("select has_column_privilege('anon','blog_posts','title','SELECT');") == 'f'
        assert sql("select count(*) from blog_posts;") == '1'
        no_storage = '{:main {:services false :documents false} '+' '.join(':'+m['id']+' {:enabled false}' for m in full['available-modules'] if m['id'] not in ('main','home','carousel','coercion'))+'}'
        empty = manifest(no_storage)
        assert not empty['tables']
        sql('BEGIN;'+empty['sql']+'COMMIT;')
        assert sql("select public from storage.buckets where id='avatars';") == 'f'
        assert sql("select count(*) from site_users;") == '1'
        for table in full['tables']:
            assert sql("select has_table_privilege('authenticated','"+table+"','SELECT');") == 'f'
        sql('BEGIN;'+full['sql']+'COMMIT;')
        assert sql("select public from storage.buckets where id='avatars';") == 't'
        assert sql("select has_column_privilege('anon','blog_comments','text','SELECT');") == 't'
        assert sql("select has_function_privilege('service_role','public.tolgraven_create_comment(text,bigint,text,text,text)','EXECUTE');") == 't'
        assert sql("select count(*) from pg_publication_tables where pubname='supabase_realtime' and tablename='blog_comments';") == '1'
        assert sql("select text from blog_comments where id='c';") == 'Retained'
        print('PASS: disable comments/blog/all storage revokes grants, RPCs, realtime and public avatars; re-enable restores access and original rows')
    finally:
        run(['dropdb',NAME])
if __name__ == '__main__': main()
