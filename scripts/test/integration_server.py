#!/usr/bin/env python3
"""Serve the live browser checks and proxy an unchanged running application.

No fixtures, app-db seeding, or upstream credentials. An optional audited bundle
directory serves actual production JS against the unchanged live HTTP/data routes.
A separate browser origin keeps test navigation/persistence out of user sessions.
"""
import argparse
import http.client
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
HOP = {'connection', 'transfer-encoding', 'keep-alive', 'upgrade', 'proxy-authenticate',
       'proxy-authorization', 'te', 'trailer'}

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlsplit(self.path).path
        assets = {'/__tests/': 'live.html', '/__tests/live.js': 'live.js',
                  '/__tests/scroll.html': 'scroll.html', '/__tests/scroll.js': 'scroll.js',
                  '/__tests/motion.html': 'motion.html', '/__tests/motion.js': 'motion.js',
                  '/__tests/away.html': 'away.html'}
        if path in assets:
            body = (ROOT / 'test/browser' / assets[path]).read_bytes()
            self.send_response(200)
            self.send_header('Content-Type', 'text/html' if not path.endswith('.js') else 'text/javascript')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.server.bundle_dir and path.startswith('/js/compiled/out/'):
            name = path.removeprefix('/js/compiled/out/')
            if name not in self.server.bundle_names:
                self.send_error(404, 'Not in the audited production bundle manifest')
                return
            body = (self.server.bundle_dir / name).read_bytes()
            self.send_response(200)
            self.send_header('Content-Type', 'text/javascript; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.proxy()

    def do_POST(self):
        # The application uses POST for read batches too. Tests never submit writes.
        self.proxy()

    def proxy(self):
        # Optional real transport latency, without fixtures or substituted data.
        # This exposes navigation that accidentally waits for code/content.
        if self.server.delay_path and self.server.delay_path in self.path:
            time.sleep(self.server.delay_seconds)
        upstream = self.server.upstream
        connection = http.client.HTTPConnection(upstream.hostname, upstream.port or 80, timeout=60)
        try:
            length = int(self.headers.get('Content-Length', 0))
            headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP | {'host'}}
            if self.server.forwarded_proto:
                # Match a TLS-terminating deployment proxy when testing a prod image.
                headers['X-Forwarded-Proto'] = self.server.forwarded_proto
            connection.request(self.command, self.path, self.rfile.read(length) if length else None, headers)
            response = connection.getresponse()
            if self.server.verbose:
                print(self.command, urlsplit(self.path).path, response.status, flush=True)
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in HOP:
                    self.send_header(key, value)
            self.send_header('Connection', 'close')
            self.end_headers()
            while chunk := response.read1(65536):
                self.wfile.write(chunk)
                self.wfile.flush()  # Preserve the application's early skeleton flush.
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            connection.close()
            self.close_connection = True

    def log_message(self, *_):
        pass

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', default='http://127.0.0.1:4000')
    parser.add_argument('--port', type=int, default=4003)
    parser.add_argument('--delay-path', default='')
    parser.add_argument('--delay-seconds', type=float, default=0)
    parser.add_argument('--forwarded-proto', choices=['http', 'https'])
    parser.add_argument('--bundle-dir', type=Path, help='Serve JS from an isolated production bundle audit')
    parser.add_argument('--verbose', action='store_true', help='Log request paths and response statuses')
    args = parser.parse_args()
    upstream = urlsplit(args.app)
    if upstream.scheme != 'http' or upstream.hostname not in {'localhost', '127.0.0.1', '::1'}:
        parser.error('--app must be a local HTTP application')
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    server.upstream = upstream
    server.delay_path = args.delay_path
    server.delay_seconds = max(0, args.delay_seconds)
    server.forwarded_proto = args.forwarded_proto
    server.verbose = args.verbose
    server.bundle_dir = args.bundle_dir.resolve() if args.bundle_dir else None
    server.bundle_names = set()
    if server.bundle_dir:
        server.bundle_names = set(json.loads((server.bundle_dir / 'bundles.json').read_text()))
        if any(Path(name).name != name or not name.endswith('.js') for name in server.bundle_names):
            parser.error('Invalid production bundle manifest')
    print(f'Live integration checks: http://127.0.0.1:{args.port}/__tests/', flush=True)
    server.serve_forever()
