#!/usr/bin/env python3
"""Serve Shadow browser tests with the app's image/CSS fixtures."""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit
import argparse

public = Path(__file__).resolve().parents[1] / "resources/public"
tests = public / "js/tests"

class Handler(SimpleHTTPRequestHandler):
    def translate_path(self, path):
        path = unquote(urlsplit(path).path).lstrip("/")
        if ".." in Path(path).parts:
            return str(public / "__invalid_test_path__")
        if not path:
            return str(tests / "index.html")
        if path.startswith("fixtures/"):
            return str(public.parents[1] / "test/browser" / path.removeprefix("fixtures/"))
        # Exercise the real picture component's converted-avatar URL contract
        # with local image fixtures; this is a component test, not live Storage.
        avatar = "storage/v1/object/public/avatars/test/" + "0" * 64
        if path in {avatar + suffix for suffix in (".png", ".webp", ".avif")}:
            return str(public / "img" / ("tolgrav" + Path(path).suffix))
        root = tests if path.startswith("js/") else public
        return str(root / path)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=4002)
    args = parser.parse_args()
    print(f"Browser tests: http://127.0.0.1:{args.port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
