"""Audit actual production jars in an idle worktree; restore site defaults."""
import json, shutil, subprocess, zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
SITE = ROOT / 'resources/site.edn'
def run(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, **kwargs)
def main():
    original = SITE.read_bytes()
    try:
        catalog = json.loads(run(['bb','site:manifest'],capture_output=True).stdout)
        ids = [m['id'] for m in catalog['available-modules']]
        minimal = '{:main {:services false :documents false} '+' '.join(f':{id} {{:enabled false}}' for id in ids if id not in ('main','home','carousel','coercion'))+'}'
        for label, modules in [('posts-only','{:blog {:comments false}}'),('home-only',minimal)]:
            SITE.write_text('{:modules '+modules+'}\n')
            target = 'target/site-package/'+label
            prefix = ['lein','with-profile','uberjar,provided','update-in',':','assoc',':target-path','"'+target+'"',':compile-path','"'+target+'/classes"',':prep-tasks','^:replace []',':auto-clean','false','--']
            shutil.rmtree(ROOT/target/'classes', ignore_errors=True)
            Path(ROOT/target).mkdir(parents=True,exist_ok=True)
            with (ROOT/target/'build.log').open('w') as log:
                for task in ('compile','docs','jar'):
                    run(prefix+[task],stdout=log,stderr=subprocess.STDOUT)
            log = (ROOT/target/'build.log').read_text()
            assert 'Could not generate' not in log, log[-3000:]
            jars = list((ROOT/target).glob('*.jar'))
            assert len(jars)==1, jars
            with zipfile.ZipFile(jars[0]) as jar:
                names = jar.namelist()
                assert 'tolgraven/core.class' in names, 'Executable backend class missing'
                assert 'supabase/schema.sql' not in names
                assert 'site.overrides.edn' not in names
                assert not any(n.startswith('tolgraven/modules/blog/comments') for n in names)
                assert 'supabase/modules/blog_comments.sql' not in names
                if label == 'home-only':
                    for helper in ('auth$profile_', 'auth$ensure_profile_', 'auth$save_profile_', 'storage$png_bytes_', 'storage$save_avatar_', 'integrations$strapi_', 'integrations$image_response_'):
                        assert not any(n.startswith('tolgraven/supabase/'+helper) for n in names), helper
                    for owner in ('blog','user','chat','gpt','search','strava','instagram'):
                        assert not any(n.startswith('tolgraven/modules/'+owner+'/') for n in names), owner
                        assert not any(n.startswith('docs/codox/tolgraven.modules.'+owner+'.') for n in names), owner
                    assert not any(n.startswith('supabase/modules/') and not n.endswith('/') for n in names)
                print(f'PASS: {label}: actual AOT jar omits disabled code, SQL and docs ({len(names)} entries)',flush=True)
    finally:
        SITE.write_bytes(original)
if __name__=='__main__': main()
