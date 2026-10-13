#!/usr/bin/env python3
"""Serve renderer-generated SSR image probes with a shared paced image link."""
import argparse
import hashlib
import json
import mimetypes
import statistics
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit, parse_qs

ROOT = Path(__file__).resolve().parents[2]

class ImageLink:
    def __init__(self, kibps, latency_ms):
        self.rate = kibps * 1024
        self.latency = latency_ms / 1000
        self.next_slot = 0
        self.lock = threading.Condition()
        self.active = {}
        self.origin = time.monotonic()

    def begin(self, key):
        with self.lock:
            others = [run for run in self.active if run != key]
            self.active[key] = self.active.get(key, 0) + 1
            return {'startedMs': (time.monotonic() - self.origin) * 1000,
                    'otherRunsActiveAtStart': others}

    def end(self, key):
        with self.lock:
            self.active[key] -= 1
            if not self.active[key]:
                del self.active[key]
            self.lock.notify_all()

    def idle(self):
        start = time.monotonic()
        with self.lock:
            active = dict(self.active)
            while self.active:
                self.lock.wait(timeout=1)
            return {'activeAtCheck': active, 'drainedMs': (time.monotonic() - start) * 1000}

    def write(self, output, body, record):
        time.sleep(self.latency)
        for offset in range(0, len(body), 1024):
            chunk = body[offset:offset + 1024]
            with self.lock:
                slot = max(time.monotonic(), self.next_slot)
                self.next_slot = slot + len(chunk) / self.rate
            time.sleep(max(0, slot - time.monotonic()))
            output.write(chunk)
            output.flush()
            record['sentBytes'] += len(chunk)

class Handler(BaseHTTPRequestHandler):
    def send(self, body, content_type, headers=None):
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == '/':
            self.send((ROOT / 'test/browser/image-benchmark.html').read_bytes(), 'text/html')
        elif path == '/image-benchmark.js':
            self.send((ROOT / 'test/browser/image-benchmark.js').read_bytes(), 'text/javascript')
        elif path == '/idle':
            self.send(json.dumps(self.server.link.idle()).encode(), 'application/json')
        elif path == '/saved-results':
            body = self.server.output.read_bytes() if self.server.output.exists() else b'{"runs":[]}'
            self.send(body, 'application/json')
        elif path == '/metadata':
            self.send(json.dumps(self.server.metadata).encode(), 'application/json')
        elif path.startswith('/report/'):
            key = path.removeprefix('/report/')
            with self.server.records_lock:
                records = self.server.records.get(key, [])
                self.send(json.dumps(records).encode(), 'application/json')
        elif path.startswith('/probe/'):
            _, _, version, key = path.split('/')
            tree = self.server.trees[version]
            markup = self.server.fixtures[version].replace('img/foggy-shit-small', f'/image/{version}/{key}/foggy-shit-small')
            # Match layout's native AVIF preloads, including its sizes declaration.
            sources = self.server.preloads[version]
            preloads = ''.join(f'<link rel="preload" as="image" type="image/avif" fetchpriority="high" href="/image/{version}/{key}/{href}"'+
                (f' imagesrcset="{srcset.replace("img/", f"/image/{version}/{key}/")}" imagesizes="{sizes}"' if srcset else '')+'>'
                for href, srcset, sizes in sources)
            gate_file = tree / 'resources/public/js/document/entrance.js'
            if not gate_file.exists():
                gate_file = tree / 'resources/public/js/document-entrance.js'
            policy_file = tree / 'resources/public/js/document/head.js'
            policy = f'<script src="/assets/{version}/js/document/head.js"></script>' if policy_file.exists() else ''
            gate = (f'<script src="/assets/{version}/js/document/entrance.js"></script>'
                    if gate_file.name == 'entrance.js' else f'<script>{gate_file.read_text()}</script>')
            probe = (ROOT / 'test/browser/image-benchmark.js').read_text().split('// CONTROLLER')[0]
            options = parse_qs(urlsplit(self.path).query)
            returns = options.get('returns', [None])[0]
            held = options.get('hold', [None])[0]
            if held in ('all', 'final'):
                # Screenshot-only pacing; benchmark cases never use this option.
                self.server.holds[key] = (held, time.monotonic() + 15)

            unload = '<script>addEventListener("unload",()=>{});</script>' if returns == 'network' else ''
            driver = ''
            if returns:
                driver = f'''<aside style="position:fixed;inset:1rem 1rem auto;z-index:999;background:#fff;color:#111;padding:1rem;max-height:25vh;overflow:auto"><a href="http://127.0.0.1:4052/fixtures/away.html">Leave for external page</a> <button id="report">Show request report</button><pre id="return-report"></pre></aside><script>
const output=document.querySelector('#return-report');
const visits=[];addEventListener('pageshow',event=>{{visits.push({{persisted:event.persisted,type:performance.getEntriesByType('navigation')[0]?.type,direct:document.documentElement.hasAttribute('data-image-direct')}});output.textContent=JSON.stringify(visits);}});
document.querySelector('#report').onclick=async()=>{{output.textContent=JSON.stringify({{visits,requests:await(await fetch('/report/{key}')).json(),resources:performance.getEntriesByType('resource').filter(e=>e.name.includes('/image/')).map(e=>({{name:e.name.split('/').pop(),start:e.startTime,end:e.responseEnd}})),visibleLayers:[...document.querySelectorAll('#top-banner img')].filter(e=>{{let n=e;while(n&&n!==document.body){{const s=getComputedStyle(n);if(s.display==='none'||s.visibility==='hidden'||Number(s.opacity)===0)return false;n=n.parentElement;}}return true;}}).length}});}};</script>'''
            snapshot = ''
            if options.get('snapshot') == ['midfade']:
                # Screenshot-only fixture: pause native CSS transitions at 75ms.
                # The adapter's bounded 200ms fallback is postponed while paused;
                # real benchmark cases never modify timers or animation progress.
                snapshot = '''<script>
const originalTimeout=window.setTimeout;
window.setTimeout=(fn,ms,...args)=>originalTimeout(fn,ms===200?600000:ms,...args);
const pose=()=>{const hero=document.querySelector('#top-banner');const final=hero?.querySelector('[data-image-final]');
 const opacity=final?.getAnimations().find(a=>a.transitionProperty==='opacity');
 if(opacity){for(const image of hero.querySelectorAll('img,.progressive-image__preview-layer'))for(const animation of image.getAnimations()){animation.pause();animation.currentTime=75;}document.documentElement.dataset.snapshotPose='midfade';}
 else requestAnimationFrame(pose);};requestAnimationFrame(pose);
</script>'''
            document = f'''<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<base href="/assets/{version}/">{unload}<script>{probe}</script>{snapshot}{policy}{preloads}
<link rel="stylesheet" href="css/tolgraven/main.min.css"><link rel="stylesheet" href="css/tolgraven/modules/home.min.css">
</head><body class="container themable framing-shadow sticky-footer-container"><div id="app" data-document-pending>{markup}</div>{gate}{driver}</body></html>'''
            self.send(document.encode(), 'text/html; charset=utf-8',
                      {'Permissions-Policy': 'unload=(self)'} if returns == 'network' else None)
        elif path.startswith('/image/'):
            _, _, version, key, name = path.split('/')
            if not name.startswith('foggy-shit-small') or Path(name).name != name:
                self.send_error(404)
                return
            source = self.server.trees[version] / 'resources/public/img' / name
            body = source.read_bytes()
            record = {'name': name, 'encodedBytes': len(body), 'sentBytes': 0, **self.server.link.begin(key)}
            with self.server.records_lock:
                self.server.records.setdefault(key, []).append(record)
            self.send_response(200)
            self.send_header('Content-Type', mimetypes.guess_type(name)[0] or 'application/octet-stream')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            try:
                held, until = self.server.holds.get(key, (None, 0))
                if held == 'all' or (held == 'final' and '-400w.' not in name):
                    time.sleep(max(0, until - time.monotonic()))
                self.server.link.write(self.wfile, body, record)
            except (BrokenPipeError, ConnectionResetError):
                record['aborted'] = True
            finally:
                record['endedMs'] = (time.monotonic() - self.server.link.origin) * 1000
                self.server.link.end(key)
        elif path.startswith('/assets/'):
            _, _, version, relative = path.split('/', 3)
            public = self.server.trees[version] / 'resources/public'
            source = (public / relative).resolve()
            if not source.is_relative_to(public.resolve()) or not source.is_file():
                self.send_error(404)
                return
            self.send(source.read_bytes(), mimetypes.guess_type(source)[0] or 'application/octet-stream')
        else:
            self.send_error(404)

    def do_POST(self):
        if self.path != '/results':
            self.send_error(404)
            return
        results = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.server.output.parent.mkdir(parents=True, exist_ok=True)
        self.server.output.write_text(json.dumps(results, indent=2) + '\n')
        self.send(b'{"saved":true}', 'application/json')

    def log_message(self, *_):
        pass

def summarize(path):
    """Summarize every retained pair; reject overlapping or truncated image runs."""
    data = json.loads(path.read_text())
    groups = {}
    for run in data['runs']:
        if run['isolation']['activeAtCheck']:
            raise ValueError('A previous image run was still active at the idle guard')
        for request in run['requests']:
            if request['otherRunsActiveAtStart'] or request.get('aborted'):
                raise ValueError('Image run overlapped another probe or was aborted')
            if request['sentBytes'] != request['encodedBytes']:
                raise ValueError('Image response body was truncated')
        key = f"{run['width']}/DPR{run['dpr']}/{run['version']}"
        groups.setdefault(key, []).append(run)
    metrics = ['gradientVisibleMs', 'previewVisibleMs', 'heroVisibleMs', 'finalLoadMs',
               'finalReadyMs', 'finalPaintMs', 'finalOpaqueMs', 'imageBytes', 'requestCount']
    summary = {}
    for key, runs in groups.items():
        summary[key] = {'runs': len(runs), 'finalSources': sorted({r['finalSource'] for r in runs})}
        for metric in metrics:
            values = [run[metric] for run in runs if run.get(metric) is not None]
            summary[key][metric] = ({'median': statistics.median(values),
                                     'min': min(values), 'max': max(values)} if values else None)
    print(json.dumps({'metadata': data['metadata'], 'summary': summary}, indent=2))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--summarize', type=Path, help='Summarize all retained runs without starting a server')
    parser.add_argument('--progressive', type=Path, default=ROOT)
    parser.add_argument('--port', type=int, default=4062)
    parser.add_argument('--image-kibps', type=float, default=64)
    parser.add_argument('--latency-ms', type=float, default=150)
    parser.add_argument('--output', type=Path, default=ROOT / 'target/image-benchmark-results.json')
    args = parser.parse_args()
    if args.summarize:
        summarize(args.summarize)
        return
    if not args.baseline:
        parser.error('--baseline is required when serving probes')
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    server.trees = {'baseline': args.baseline.resolve(), 'progressive': args.progressive.resolve()}
    server.fixtures = {}
    versions = {}
    for version, tree in server.trees.items():
        fixture = tree / 'resources/public/js/tests/js/landing-ssr.json'
        server.fixtures[version] = json.loads(fixture.read_text())['html']
        gate_file = tree / 'resources/public/js/document/entrance.js'
        if not gate_file.exists():
            gate_file = tree / 'resources/public/js/document-entrance.js'
        versions[version] = {'commit': subprocess.check_output(['git', '-C', str(tree), 'rev-parse', 'HEAD'], text=True).strip(),
                             'fixtureSha256': hashlib.sha256(fixture.read_bytes()).hexdigest(),
                             'adapterSha256': hashlib.sha256(gate_file.read_bytes()).hexdigest(),
                             'mainCssSha256': hashlib.sha256((tree / 'resources/public/css/tolgraven/main.min.css').read_bytes()).hexdigest(),
                             'trackedChanges': subprocess.call(['git', '-C', str(tree), 'diff', '--quiet']) != 0}
    class HeroPreloads(HTMLParser):
        def __init__(self):
            super().__init__()
            self.sources = []

        def handle_starttag(self, tag, attrs):
            attrs = dict(attrs)
            srcset = attrs.get('srcset', '')
            if tag == 'source' and attrs.get('type') == 'image/avif' and 'img/foggy-shit-small' in srcset and ' ' in srcset:
                href = srcset.split(',')[-1].strip().split()[0].removeprefix('img/')
                self.sources.append((href, srcset if ' ' in srcset else None, attrs.get('sizes', '100vw')))

    server.preloads = {}
    for version, markup in server.fixtures.items():
        parser = HeroPreloads()
        parser.feed(markup)
        server.preloads[version] = parser.sources
    server.link = ImageLink(args.image_kibps, args.latency_ms)
    server.metadata = {'protocolVersion': 5, 'versions': versions, 'profile': {'aggregateImageKiBps': args.image_kibps, 'imageBodyLatencyMs': args.latency_ms, 'chunkBytes': 1024},
                       'probeVisibility': 'Fixed full-viewport iframe; native image intersects viewport; all image ancestors have positive opacity and visible display; finalOpaque is the final layer alpha, independent of outer page entrance.',
                       'resourceControls': 'Build artifacts generated sequentially with Docker 1 CPU / 2 GiB / 1 GiB Java heap. Browser timing only after compilers and CPU probes finish. Native project-Java/Codex-renderer monitor active; Chrome excluded.',
                       'scope': 'Native images in renderer-generated SSR, application bootstrap withheld; non-hero assets unpaced; no backend/provider timing.'}
    server.holds = {}
    server.records = {}
    server.records_lock = threading.Lock()
    server.output = args.output
    print(f'Image benchmark: http://127.0.0.1:{args.port}/', flush=True)
    server.serve_forever()

if __name__ == '__main__':
    main()
