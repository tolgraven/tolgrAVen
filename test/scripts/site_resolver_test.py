"""Real HTTP fixture for the fixed-origin build resolver; uses dummy credentials."""
import json, os, subprocess, tempfile, threading, unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

class ResolverTest(unittest.TestCase):
    def test_override_snapshot_and_fail_closed_requests(self):
        calls=[]
        class Handler(BaseHTTPRequestHandler):
            status=200
            payload={'version':1,'config':{'modules':{'blog':{'comments':False}},'pages':{'cv':False}}}
            def log_message(self,*args): pass
            def do_GET(self):
                calls.append((self.path,self.headers.get('Authorization')))
                self.send_response(self.status)
                if self.status==302: self.send_header('Location', '/credential-sink')
                self.end_headers(); self.wfile.write(json.dumps(self.payload).encode())
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                Path(tmp,'resources').mkdir()
                env={**os.environ,'STRAPI_URL':f'http://127.0.0.1:{server.server_port}','STRAPI_READ_TOKEN':'fixture-secret'}
                command=['bb','-cp',f'{ROOT}/scripts:{ROOT}/src/cljc:{ROOT}/resources','-m','build.resolve-site']
                def run(): return subprocess.run(command,cwd=tmp,env=env,text=True,capture_output=True)
                result=run(); self.assertEqual(result.returncode,0,result.stderr)
                snapshot=Path(tmp,'resources/site.overrides.edn').read_text()
                self.assertIn(':comments false',snapshot); self.assertIn(':cv false',snapshot)
                self.assertNotIn('fixture-secret',snapshot)
                self.assertEqual(calls,[('/api/site-config','Bearer fixture-secret')])
                Handler.status=302; calls.clear(); result=run()
                self.assertNotEqual(result.returncode,0)
                self.assertEqual(len(calls),1,'Credentials must never follow a redirect')
                self.assertNotIn('fixture-secret',result.stderr)
                self.assertEqual(snapshot,Path(tmp,'resources/site.overrides.edn').read_text(),'Failed fetch preserves prior snapshot and fails the build')
                Handler.status=401; result=run(); self.assertNotEqual(result.returncode,0)
                Handler.status=404; result=run(); self.assertEqual(result.returncode,0)
                self.assertEqual(Path(tmp,'resources/site.overrides.edn').read_text(),'{:modules {}}')
                Handler.status=200; Handler.payload={'version':1,'config':{'modules':{},'token':'unexpected'}}
                self.assertNotEqual(run().returncode,0)
        finally: server.shutdown(); server.server_close()

if __name__=='__main__': unittest.main()
