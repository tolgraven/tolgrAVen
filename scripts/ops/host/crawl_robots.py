#!/usr/bin/env python3
"""Private server-zone robots responder; no application or external dependencies."""
import argparse
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BODY = b'User-agent: *\nDisallow: /\n'
HOST_PATTERN = r'^(?:[a-z0-9-]+\.)*(?:bux|box)\.tolgraven\.se$'


def private_host(host):
    return bool(re.fullmatch(HOST_PATTERN, host.lower().split(':', 1)[0]))


class RobotsHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.split('?', 1)[0] != '/robots.txt' or not private_host(self.headers.get('Host', '')):
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Cache-Control', 'public, max-age=300')
        self.send_header('Content-Length', str(len(BODY)))
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(BODY)

    do_HEAD = do_GET

    def log_message(self, *_args):
        pass


class RobotsServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 16

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(5)
        return connection, address


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bind', required=True, help='Private proxy-network gateway address')
    parser.add_argument('--port', type=int, default=18082)
    args = parser.parse_args()
    RobotsServer((args.bind, args.port), RobotsHandler).serve_forever()
