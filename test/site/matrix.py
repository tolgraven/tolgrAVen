#!/usr/bin/env python3
"""Exercise isolated site builds and PostgreSQL installs; always restore site.edn.
Run in an idle disposable worktree: python3 test/site/matrix.py [--compile].
PGPORT optionally enables real fresh PostgreSQL fixtures for each configuration.
"""
import argparse, json, os, re, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
SITE = ROOT / 'resources/site.edn'
CONFIGS = {
    'default': '{}',
    'posts-only': '{:blog {:comments false}}',
    'no-blog': '{:blog {:enabled false} :search {:enabled false}}',
    'anonymous': '{:blog {:enabled false} :search {:enabled false} :user {:enabled false} :chat {:enabled false} :gpt {:enabled false}}',
    'home-only': None,
    'no-home': '{:home {:enabled false}}',
    'page-selection': '{}',
}

def run(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, **kwargs)

def database_check(label, manifest):
    name = 'site_matrix_' + label.replace('-', '_')
    run(['createdb', name])
    try:
        bootstrap = """
create schema auth;
create function auth.uid() returns uuid language sql as $$select null::uuid$$;
create function auth.jwt() returns jsonb language sql as $$select '{}'::jsonb$$;
create schema storage;
create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
create publication supabase_realtime;
"""
        run(['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-q', name], input=bootstrap)
        run(['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-q', name], input='BEGIN;'+manifest['sql']+'COMMIT;')
        tables = run(['psql', '-X', '-At', name, '-c', "select tablename from pg_tables where schemaname='public' order by tablename"], capture_output=True).stdout.splitlines()
        assert tables == sorted(manifest['tables']), (label, tables)
        if 'blog_comments' not in tables:
            functions = run(['psql', '-X', '-At', name, '-c', "select proname from pg_proc where pronamespace='public'::regnamespace"], capture_output=True).stdout
            assert 'tolgraven_create_comment' not in functions
            assert 'tolgraven_set_comment_vote' not in functions
        print(f'PASS: {label} SQL installs exactly {len(tables)} tables and selected RPCs', flush=True)
    finally:
        run(['dropdb', name])

def main():
    args = argparse.ArgumentParser()
    args.add_argument('--only', nargs='+', choices=list(CONFIGS))
    args.add_argument('--compile', action='store_true')
    args.add_argument('--release', action='store_true')
    options = args.parse_args()
    original = SITE.read_bytes()
    try:
        for label, config in CONFIGS.items():
            if options.only and label not in options.only:
                continue
            if config is None:
                modules = sorted(m['id'] for m in json.loads(run(['bb','site:manifest'], capture_output=True).stdout)['available-modules'])
                config = '{:main {:services false :documents false} '+' '.join(f':{id} {{:enabled false}}' for id in modules if id not in ('main','home','carousel','coercion'))+'}'
            pages = ' :pages {:cv false :about false :blog-post false}' if label == 'page-selection' else ''
            SITE.write_text('{:modules '+config+pages+'}\n')
            manifest = json.loads(run(['bb','site:manifest'], capture_output=True).stdout)
            directory = ROOT/'target/site-matrix'/label
            directory.mkdir(parents=True, exist_ok=True)
            (directory/'manifest.json').write_text(json.dumps(manifest, indent=2))
            with (directory/'backend.log').open('w') as log:
                run(['lein','with-profile','+project/test','run','-m','clojure.main','test/site/check.clj'], stdout=log, stderr=subprocess.STDOUT)
            if os.environ.get('PGPORT'):
                database_check(label, manifest)
            if options.compile or options.release:
                command = 'release' if options.release else 'compile'
                target = 'app' if options.release else 'app-dev'
                with (directory/'build.log').open('w') as log:
                    run(['lein','with-profile','+project/test','run','-m','shadow.cljs.devtools.cli',command,'ssr',target,'return-worker'], stdout=log, stderr=subprocess.STDOUT)
                build_log = (directory/'build.log').read_text()
                assert '0 warnings' in build_log, label
                # Phoenix 0.4.5 uses TypeScript-style @template defaults in JSDoc.
                # Shadow reports these two npm comment parse notices separately
                # from CLJS warnings; executable SDK code still compiles normally.
                notices = re.findall(r'Resource: ([^\n]+)\n ([^\n]+)', build_log)
                assert all('node_modules/@supabase/phoenix/priv/static/phoenix.cjs.js:' in source
                           and message == 'Parse error. name not recognized due to syntax error.'
                           for source, message in notices), notices
                emitted = (ROOT/'resources/public/js/compiled/out/manifest.edn').read_text()
                (directory/'browser.edn').write_text(emitted)
                assert ('website.modules.blog.module' in emitted) == ('blog' in manifest['modules']), label
                assert ('website.modules.supabase.module' in emitted) == ('supabase' in manifest['modules']), label
                if 'supabase' not in manifest['modules']:
                    assert '@supabase/' not in emitted and 'supabase/client.cljs' not in emitted, label
                else:
                    ownership = run(['bb', '-e', '(require (quote clojure.edn)) (let [modules (clojure.edn/read-string (slurp "resources/public/js/compiled/out/manifest.edn"))] (assert (every? #(= :supabase (:module-id %)) (filter #(some (fn [source] (.contains source "@supabase/")) (:sources %)) modules))))'],capture_output=True)

            print(f'PASS: {label}: {len(manifest["modules"])} modules, {len(manifest["tables"])} tables', flush=True)
    finally:
        SITE.write_bytes(original)

if __name__ == '__main__':
    main()
