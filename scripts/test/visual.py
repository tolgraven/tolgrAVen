#!/usr/bin/env python3
"""Native Chrome captures and exact pixel/geometry comparisons. No browser launch."""
import argparse
import base64
import binascii
import hashlib
import html
import json
import os
from pathlib import Path
import queue
import re
import shlex
import struct
import subprocess
import sys
import threading
import time
import zlib

ROOT = Path(__file__).resolve().parents[2]
SIGNATURE = b"\x89PNG\r\n\x1a\n"


def read_png(path):
    """Decode Chrome's noninterlaced 8-bit RGB/RGBA PNGs, including all filters."""
    data = Path(path).read_bytes()
    if not data.startswith(SIGNATURE):
        raise ValueError(f"Not PNG: {path}")
    pos, compressed, header = 8, bytearray(), None
    while pos < len(data):
        length = struct.unpack_from(">I", data, pos)[0]
        kind = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + length]
        checksum = struct.unpack_from(">I", data, pos + 8 + length)[0]
        if binascii.crc32(kind + payload) & 0xffffffff != checksum:
            raise ValueError(f"Corrupt PNG chunk: {path}")
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", payload)
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            break
        pos += length + 12
    if header is None:
        raise ValueError("Missing PNG header")
    width, height, depth, color, compression, filtering, interlace = header
    if depth != 8 or color not in (2, 6) or any((compression, filtering, interlace)):
        raise ValueError("Expected noninterlaced 8-bit RGB/RGBA PNG")
    if not width or not height or width * height > 16000000:
        raise ValueError("PNG dimensions exceed the bounded viewport capture budget")
    channels = 3 if color == 2 else 4
    stride = width * channels
    decoder = zlib.decompressobj()
    raw = decoder.decompress(compressed, height * (stride + 1) + 1)
    if not decoder.eof or decoder.unused_data or len(raw) != height * (stride + 1):
        raise ValueError("PNG data length does not match dimensions")
    pixels, previous = bytearray(), bytearray(stride)
    for row in range(height):
        start = row * (stride + 1)
        mode = raw[start]
        current = bytearray(raw[start + 1:start + 1 + stride])
        for i in range(stride):
            left = current[i - channels] if i >= channels else 0
            up = previous[i]
            corner = previous[i - channels] if i >= channels else 0
            if mode == 0:
                predictor = 0
            elif mode == 1:
                predictor = left
            elif mode == 2:
                predictor = up
            elif mode == 3:
                predictor = (left + up) // 2
            elif mode == 4:
                p = left + up - corner
                distances = [abs(p - left), abs(p - up), abs(p - corner)]
                predictor = (left, up, corner)[distances.index(min(distances))]
            else:
                raise ValueError(f"Unknown PNG filter {mode}")
            current[i] = (current[i] + predictor) & 255
        for i in range(0, stride, channels):
            pixels.extend(current[i:i + 3])
            pixels.append(current[i + 3] if channels == 4 else 255)
        previous = current
    return width, height, pixels


def write_png(path, width, height, pixels):
    def chunk(kind, value):
        return (struct.pack(">I", len(value)) + kind + value +
                struct.pack(">I", binascii.crc32(kind + value) & 0xffffffff))
    rows = b"".join(b"\0" + pixels[y * width * 4:(y + 1) * width * 4] for y in range(height))
    Path(path).write_bytes(SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) +
                           chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def compare_pixels(expected, actual, diff):
    Path(diff).unlink(missing_ok=True)
    ew, eh, before = read_png(expected)
    aw, ah, after = read_png(actual)
    if (ew, eh) != (aw, ah):
        return {"passed": False, "error": f"Image dimensions {(ew, eh)} -> {(aw, ah)}"}
    changed, bounds, output = 0, [ew, eh, -1, -1], bytearray()
    for index in range(0, len(before), 4):
        if before[index:index + 4] != after[index:index + 4]:
            changed += 1
            x, y = (index // 4) % ew, (index // 4) // ew
            bounds = [min(bounds[0], x), min(bounds[1], y), max(bounds[2], x), max(bounds[3], y)]
            output.extend((255, 0, 80, 255))
        else:
            shade = sum(after[index:index + 3]) // 3
            output.extend((shade, shade, shade, 90))
    if changed:
        write_png(diff, ew, eh, output)
    return {"passed": changed == 0, "changed_pixels": changed,
            "total_pixels": ew * eh, "changed_bounds": bounds if changed else None}


def geometry_changes(before, after, path=""):
    """Exact geometry equality; no rounding away subpixel layout changes."""
    if type(before) is not type(after):
        return [{"path": path, "before": before, "after": after}]
    if isinstance(before, dict):
        changes = []
        for key in sorted(before.keys() | after.keys()):
            child = f"{path}.{key}" if path else key
            if key not in before or key not in after:
                changes.append({"path": child, "before": before.get(key), "after": after.get(key)})
            else:
                changes.extend(geometry_changes(before[key], after[key], child))
        return changes
    if isinstance(before, list):
        if len(before) != len(after):
            return [{"path": path + ".count", "before": len(before), "after": len(after)}]
        return [change for i, (a, b) in enumerate(zip(before, after))
                for change in geometry_changes(a, b, f"{path}[{i}]")]
    return [] if before == after else [{"path": path, "before": before, "after": after}]


class MCP:
    """Attach to the installed browser using the configured autoConnect server."""
    def __init__(self, command, stderr):
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=stderr, text=True)
        self.messages, self.ident, self.page = queue.Queue(), 0, None
        def read():
            for line in self.process.stdout:
                try:
                    self.messages.put(json.loads(line))
                except ValueError:
                    pass
            self.messages.put(None)
        threading.Thread(target=read, daemon=True).start()
        self.rpc("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                                "clientInfo": {"name": "website-visual-check", "version": "1"}})
        self.send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.schemas = {tool["name"]: tool["inputSchema"].get("properties", {})
                        for tool in self.rpc("tools/list", {})["tools"]}

    def send(self, message):
        self.process.stdin.write(json.dumps(message) + "\n")
        self.process.stdin.flush()

    def rpc(self, method, params):
        self.ident += 1
        self.send({"jsonrpc": "2.0", "id": self.ident, "method": method, "params": params})
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            message = self.messages.get(timeout=max(.01, deadline - time.monotonic()))
            if message is None:
                raise RuntimeError("Chrome DevTools server disconnected; reconnect installed Chrome")
            if message.get("id") == self.ident:
                if "error" in message:
                    raise RuntimeError(str(message["error"]))
                result = message.get("result", {})
                if result.get("isError"):
                    raise RuntimeError(str(result))
                return result
        raise RuntimeError("Chrome DevTools response timed out")

    def call(self, name, **arguments):
        properties = self.schemas[name]
        if self.page is not None and "pageId" in properties and "pageId" not in arguments:
            arguments["pageId"] = self.page
        if "waitForStableDom" in properties:
            arguments["waitForStableDom"] = False
        return self.rpc("tools/call", {"name": name, "arguments": arguments})

    def evaluate(self, function):
        result = self.call("evaluate_script", function=function)
        for item in result.get("content", []):
            if item.get("type") == "text":
                match = re.search(r"```json\s*(.*?)\s*```", item["text"], re.S)
                if match:
                    return json.loads(match.group(1))
        raise RuntimeError(f"Missing evaluation result: {result}")

    def close(self):
        self.process.terminate()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()


class Relay(MCP):
    """JSON-lines tool bridge for the already configured extension MCP session.

    The caller forwards visual_tool requests to its existing tools and returns
    their unmodified MCP result. This does not start another browser/server.
    """
    def __init__(self):
        self.page = None
        if sys.stdin.isatty():
            # Only the task-owned relay PTY: screenshots exceed canonical line
            # limits and must not echo their base64 payload into the transcript.
            import termios
            settings = termios.tcgetattr(sys.stdin)
            settings[3] &= ~(termios.ECHO | termios.ICANON)
            termios.tcsetattr(sys.stdin, termios.TCSANOW, settings)

    def call(self, name, **arguments):
        if self.page is not None and name not in ("new_page", "list_pages", "close_page", "select_page"):
            arguments.setdefault("pageId", self.page)
        if name == "evaluate_script":
            arguments["waitForStableDom"] = False
        print(json.dumps({"visual_tool": name, "arguments": arguments}), flush=True)
        line = sys.stdin.readline()
        if not line:
            raise RuntimeError("Configured MCP tool relay disconnected")
        result = json.loads(line)
        if "response_file" in result:
            result = json.loads(Path(result["response_file"]).read_text())
        if result.get("isError"):
            raise RuntimeError(str(result))
        return result

    def close(self):
        pass


def capture(args):
    from urllib.parse import urlparse
    if urlparse(args.origin).hostname not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError("Captures require an isolated loopback fixture server")
    suite = json.loads(args.suite.read_text())
    cases = [case for case in suite["cases"] if not args.case or case["id"] in args.case]
    if args.case and set(args.case) - {case["id"] for case in cases}:
        raise ValueError("Unknown visual case")
    args.output.mkdir(parents=True, exist_ok=True)
    if any(args.output.glob("*.json")) or any(args.output.glob("*.png")):
        raise ValueError("Capture output already exists; use a fresh candidate directory, preserving previous evidence")
    page = None
    with (args.output / "chrome-mcp.log").open("w") as log:
        client = MCP(shlex.split(args.mcp_command), log) if args.mcp_command else Relay()
        try:
            result = client.call("new_page", url="about:blank", background=True)
            for item in result.get("content", []):
                if item.get("type") == "text":
                    match = re.search(r"(?:^|\n)(\d+):.*\[selected\]", item["text"])
                    if match:
                        page = int(match.group(1))
            if page is None:
                raise RuntimeError("Cannot identify owned task tab; no existing tab will be modified")
            client.page = page
            if args.geometry_only:
                # Avoid repeated extension navigation/snapshot overhead. Native
                # components still render in exact independent viewport realms.
                client.call("emulate", viewport="1440x1000x1")
                client.call("navigate_page", url=args.origin + "/fixtures/visual-driver.html")
                failures = []
                for start in range(0, len(cases), 4):
                    batch = cases[start:start + 4]
                    results = client.evaluate("async () => await window.visualDriver.capture(" + json.dumps(batch) + ")")
                    if [result.get("id") for result in results] != [case["id"] for case in batch]:
                        raise ValueError("Native layout driver omitted or reordered declared cases")
                    for result, case in zip(results, batch):
                        if result.get("error"):
                            failures.append(case["id"] + ": " + result["error"])
                        else:
                            state = result["state"]
                            if state["viewport"] != case["viewport"] + [1] or state.get("errors"):
                                failures.append(case["id"] + ": invalid viewport or native errors")
                            (args.output / (case["id"] + ".json")).write_text(json.dumps(state, indent=2) + "\n")
                            print(f"Captured geometry {case['id']}", flush=True)
                if failures:
                    raise ValueError("; ".join(failures))
            for case in ([] if args.geometry_only else cases):
                client.call("select_page", pageId=page)
                width, height = case["viewport"]
                client.call("emulate", viewport=f"{width}x{height}x1")
                client.call("navigate_page", url=args.origin + case["path"])
                if case.get("action"):
                    client.evaluate("async () => { await window.visualFixture.ready(); " + case["action"] + "; return true; }")
                state = client.evaluate("async () => { await window.visualFixture.ready(); return window.visualFixture.snapshot(); }")
                if state["viewport"] != [width, height, 1]:
                    raise RuntimeError(f"{case['id']}: expected viewport/DPR {[width, height, 1]}, got {state['viewport']}; restore this fixture origin's Chrome zoom to 100%")
                if state.get("errors"):
                    raise RuntimeError(f"{case['id']}: fixture readiness errors: {state['errors']}")
                if not args.geometry_only:
                    image = client.call("take_screenshot", format="png")
                    block = next(block for block in image.get("content", []) if block.get("type") == "image")
                    (args.output / (case["id"] + ".png")).write_bytes(base64.b64decode(block["data"]))
                (args.output / (case["id"] + ".json")).write_text(json.dumps(state, indent=2) + "\n")
                print(f"Captured {case['id']}", flush=True)
            metadata = {"schema": 1, "suite_sha256": hashlib.sha256(args.suite.read_bytes()).hexdigest(),
                        "cases": [case["id"] for case in cases],
                        "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                        "pixels": not args.geometry_only, "approval": None}
            (args.output / "capture.json").write_text(json.dumps(metadata, indent=2) + "\n")
        finally:
            if page is not None:
                try:
                    client.call("close_page", pageId=page)
                except Exception as error:
                    print(f"Owned task tab {page} needs closing: {error}", file=sys.stderr)
            client.close()


def compare(args):
    if args.output.resolve() in (args.baseline.resolve(), args.actual.resolve()):
        raise ValueError("Reports require a separate output directory; capture inputs stay read-only")
    suite = json.loads(args.suite.read_text())
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    metadata = json.loads((args.baseline / "capture.json").read_text())
    approval = metadata.get("approval")
    if not isinstance(approval, dict) or not all(isinstance(approval.get(key), str) and approval[key].strip()
                                                  for key in ("reviewer", "reference", "reason")):
        raise ValueError("Baseline is unapproved. Review against the reference design, then record approval; compare never updates goldens.")
    if metadata["suite_sha256"] != hashlib.sha256(args.suite.read_bytes()).hexdigest():
        raise ValueError("Baseline suite differs: review new/changed cases before approval")
    actual_metadata = json.loads((args.actual / "capture.json").read_text())
    if actual_metadata["suite_sha256"] != metadata["suite_sha256"]:
        raise ValueError("Actual captures used a different state/viewport suite")
    if actual_metadata.get("pixels", True) != metadata.get("pixels", True):
        raise ValueError("Actual capture mode differs from the approved baseline")
    for case in suite["cases"]:
        ident = case["id"]
        try:
            expected = json.loads((args.baseline / (ident + ".json")).read_text())
            actual = json.loads((args.actual / (ident + ".json")).read_text())
            environment = geometry_changes(expected["environment"], actual["environment"], "environment")
            geometry = geometry_changes(expected["geometry"], actual["geometry"], "geometry")
            pixels = (compare_pixels(args.baseline / (ident + ".png"), args.actual / (ident + ".png"),
                                     args.output / (ident + ".diff.png")) if metadata.get("pixels", True)
                      else {"passed": True, "mode": "geometry-only"})
            result = {"id": ident, "passed": pixels["passed"] and not geometry and not environment,
                      "pixels": pixels, "geometry": geometry, "environment": environment}
        except (OSError, ValueError, KeyError) as error:
            result = {"id": ident, "passed": False, "error": str(error)}
        results.append(result)
        print(f"{'PASS' if result['passed'] else 'FAIL'} {ident}: " +
              (result.get("error") or f"{result['pixels'].get('changed_pixels', '?')} pixels; {len(result['geometry'])} geometry changes"))
    report = {"passed": all(result["passed"] for result in results), "cases": results}
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    rows = []
    for result in results:
        ident = result["id"]
        pictures = "".join(f'<figure><figcaption>{label}</figcaption><img src="{html.escape(os.path.relpath(path, args.output))}"></figure>'
                           for label, path in [("Expected", args.baseline / (ident + ".png")),
                                               ("Actual", args.actual / (ident + ".png")),
                                               ("Changed pixels", args.output / (ident + ".diff.png"))] if path.exists())
        rows.append(f"<section><h2>{html.escape(ident)}: {'PASS' if result['passed'] else 'FAIL'}</h2><div class='images'>{pictures}</div><pre>{html.escape(json.dumps(result, indent=2))}</pre></section>")
    (args.output / "index.html").write_text("<!doctype html><meta charset='utf-8'><title>Visual regression report</title><style>body{font:1rem system-ui;margin:2rem} .images{display:flex;gap:1rem;overflow:auto}figure{margin:0;flex:0 0 30%}img{width:100%}pre{white-space:pre-wrap}section{border-bottom:.0625rem solid #888}</style><h1>Pixel and geometry comparison</h1>" + "".join(rows))
    return 0 if report["passed"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", type=Path, default=ROOT / "test/visual/suite.json")
    commands = parser.add_subparsers(dest="command", required=True)
    take = commands.add_parser("capture", help="Capture candidates; never updates an approved baseline")
    take.add_argument("--origin", required=True)
    take.add_argument("--output", type=Path, required=True)
    take.add_argument("--case", action="append")
    take.add_argument("--geometry-only", action="store_true", help="Explicit geometry/style gate without raster comparison; baseline must use the same mode")
    take.add_argument("--mcp-command", help="Optional configured MCP executable; default relays JSON-lines to the existing extension session")
    check = commands.add_parser("compare", help="Fail on any pixel/geometry/environment difference or missing case")
    check.add_argument("--baseline", type=Path, required=True)
    check.add_argument("--actual", type=Path, required=True)
    check.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        return capture(args) if args.command == "capture" else compare(args)
    except Exception as error:
        print(f"Visual check failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
