#!/usr/bin/env python3
"""Exercise the real Node worker and write its output for browser hydration tests.

Use the existing SSR watch worker, or `make ssr` if none is running, first.
Then run this script and the Shadow app-test browser suite. This is a renderer
contract check with fixture snapshots, not live service integration.
"""
import argparse
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--worker", default="target/ssr/site.js", help="Compiled Node renderer to exercise")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
snapshot = json.loads((root / "test/browser/blog-ssr-input.json").read_text())
other = {**snapshot, "posts": [{"id": 99, "title": "Second request", "text": "Isolated", "tags": []}]}
other["summaries"] = [{k: v for k, v in row.items() if k != "text"} for row in other["posts"]]
result = subprocess.run(["node", args.worker], cwd=root, text=True,
                        input="\n".join(map(json.dumps, [snapshot, other])) + "\n",
                        capture_output=True, timeout=15, check=True)
assert "Subscribe was called outside" not in result.stderr, result.stderr
assert "localStorage is not available" not in result.stderr, result.stderr
first, second = map(json.loads, result.stdout.splitlines())
assert "html" in first and "html" in second, result.stderr
assert "<strong>article</strong>" in first["html"]
assert "Server comment 0" in first["html"]
assert "Server visible reply" in first["html"]
assert "Server comment 4" in first["html"]
assert "5 comments" in first["html"]
assert "<script>" not in first["html"]
assert "javascript:" not in first["html"]
assert "Server-rendered blog" not in second["html"]
assert "Second request" in second["html"]
output = root / "resources/public/js/tests/js/blog-ssr.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({"snapshot": snapshot, "html": first["html"]}))
print("Node SSR: Markdown, escaping, request isolation passed; hydration fixture generated.")

# Deterministic test content only; real pages use fresh Strapi bundles.
landing = {"renderer-version": 3, "kind": "landing", "path": "/", "posts": [],
           "content": json.loads((root / "resources/content-seed.json").read_text())}
result = subprocess.run(["node", args.worker], cwd=root, text=True,
                        input="\n".join(map(json.dumps, [landing, snapshot, landing])) + "\n",
                        capture_output=True, timeout=15, check=True)
home, blog, again = map(json.loads, result.stdout.splitlines())
assert all("html" in response for response in [home, blog, again]), result.stderr
assert home["html"] == again["html"]
assert "h-intro" in home["html"]
assert "intro-letter" not in home["html"]  # React keys never leak into DOM
assert "Server-rendered blog" not in home["html"]
assert 'id="section-services"' in home["html"]
assert 'id="about"' in home["html"]
assert 'id="gallery"' in home["html"]
assert 'id="cljs"' not in home["html"]
assert 'id="story-image-cljs"' in home["html"]
assert "Server-rendered blog" in blog["html"]
assert "h-intro" not in blog["html"]
(root / "resources/public/js/tests/js/landing-ssr.json").write_text(
    json.dumps({"snapshot": landing, "html": home["html"]}))
print("Landing SSR: full content, deterministic markup, blog/landing isolation passed.")

# Additional ordinary module pages exercise the same renderer and bootstrap.
cv = {**landing, "kind": "cv", "path": "/cv"}
docs = {**landing, "kind": "docs", "path": "/docs",
        "app-db-edn": '{:docs {"index" "<h1>API reference</h1><p>Shared documentation view.</p>"} :state {:docs {:current-page "index"}}}'}
result = subprocess.run(["node", args.worker], cwd=root, text=True,
                        input="\n".join(map(json.dumps, [cv, docs, cv])) + "\n",
                        capture_output=True, timeout=15, check=True)
cv_html, docs_html, cv_again = map(json.loads, result.stdout.splitlines())
assert "cv-skills" in cv_html["html"], result.stderr
assert "Shared documentation view." in docs_html["html"], result.stderr
assert "cv-skills" not in docs_html["html"]
assert cv_html["html"] == cv_again["html"]
for name, data, response in [("cv", cv, cv_html), ("docs", docs, docs_html)]:
    (root / f"resources/public/js/tests/js/{name}-ssr.json").write_text(
        json.dumps({"snapshot": data, "html": response["html"]}))
print("CV/docs SSR: ordinary module views, public state and request isolation passed.")

missing = {**snapshot, "path": "/blog/post/missing-999999", "post-id": 999999,
           "missing?": True, "posts": [], "comments": []}
result = subprocess.run(["node", args.worker], cwd=root, text=True,
                        input=json.dumps(missing) + "\n", capture_output=True,
                        timeout=15, check=True)
assert "not found" in json.loads(result.stdout)["html"].lower(), result.stdout
print("Missing permalink SSR: completed empty read renders not-found.")
(root / "resources/public/js/tests/js/missing-ssr.json").write_text(
    json.dumps({"snapshot": missing, "html": json.loads(result.stdout)["html"]}))

# The initial shell calls the ordinary views with skeleton sample inputs. Its
# schemas must validate those as well as complete SSR and hydration inputs.
shells = [{**data, "shell?": True, "posts": [], "query-params": {}}
          for data in [landing, {**snapshot, "path": "/blog/post/42"}, cv]]
result = subprocess.run(["node", args.worker], cwd=root, text=True,
                        input="\n".join(map(json.dumps, shells)) + "\n",
                        capture_output=True, timeout=15, check=True)
responses = list(map(json.loads, result.stdout.splitlines()))
assert len(responses) == len(shells)
assert all("html" in response for response in responses), result.stderr
assert all("component-error" not in response["html"] for response in responses)
print("Initial shells: landing, blog post and CV sample inputs pass shared contracts.")
