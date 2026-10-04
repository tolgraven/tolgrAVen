#!/usr/bin/env python3
"""Exercise the real Node worker and write its output for browser hydration tests.

Run `make ssr` first, then this script, then the Shadow app-test browser suite.
"""
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
snapshot = json.loads((root / "test/browser/blog-ssr-input.json").read_text())
other = {**snapshot, "posts": [{"id": 99, "title": "Second request", "text": "Isolated", "tags": []}]}
result = subprocess.run(["node", "target/ssr/blog.js"], cwd=root, text=True,
                        input="\n".join(map(json.dumps, [snapshot, other])) + "\n",
                        capture_output=True, timeout=15, check=True)
first, second = map(json.loads, result.stdout.splitlines())
assert "<strong>article</strong>" in first["html"]
assert "<script>" not in first["html"]
assert "javascript:" not in first["html"]
assert "Server-rendered blog" not in second["html"]
assert "Second request" in second["html"]
output = root / "resources/public/js/tests/js/blog-ssr.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({"snapshot": snapshot, "html": first["html"]}))
print("Node SSR: Markdown, escaping, request isolation passed; hydration fixture generated.")
