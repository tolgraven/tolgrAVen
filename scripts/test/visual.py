#!/usr/bin/env python3
"""Configured shared Chrome captures and exact pixel/geometry comparisons."""
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
RASTER = "overlapping-viewports-v1"
MOTION = "native-css-motion-v2"


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
    """Attach through the configured shared Development Chrome launcher."""
    def __init__(self, command, stderr):
        if command != [str(Path.home() / ".local/bin/codex-chrome-devtools")]:
            raise ValueError("Use only ~/.local/bin/codex-chrome-devtools for the shared Development profile")
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
        try:
            self.rpc("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                                    "clientInfo": {"name": "website-visual-check", "version": "1"}})
            self.send({"jsonrpc": "2.0", "method": "notifications/initialized"})
            self.schemas = {tool["name"]: tool["inputSchema"].get("properties", {})
                            for tool in self.rpc("tools/list", {})["tools"]}
        except BaseException:
            self.close()
            raise

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

    def evaluate(self, function, **arguments):
        result = self.call("evaluate_script", function=function, **arguments)
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


def observed_target(client, selector, role, name):
    """Resolve only UIDs supplied by the latest native accessibility snapshot."""
    snapshot = client.call("take_snapshot", verbose=True)
    candidates = []
    for block in snapshot.get("content", []):
        if block.get("type") != "text":
            continue
        for match in re.finditer(r'^\s*uid=(\S+)\s+(\S+)\s+("(?:\\.|[^"\\])*")', block["text"], re.M):
            uid, observed_role, encoded_name = match.groups()
            if observed_role == role and json.loads(encoded_name) == name and uid not in candidates:
                candidates.append(uid)
    verified = []
    for uid in candidates:
        if client.evaluate("element => element.ownerDocument === document && element.isConnected && "
                           "element.matches(" + json.dumps(selector) + ")", args=[uid]) is True:
            verified.append(uid)
    if len(verified) != 1:
        raise RuntimeError(f"Expected one observed {role} {name!r} matching {selector}; found {len(verified)}")
    return verified[0]


def park_pointer(client, origin):
    """Known native pointer position in task-owned driver markup, never app DOM."""
    client.call("navigate_page", url=origin + "/fixtures/visual-driver.html")
    uid = observed_target(client, "body > h1", "heading", "Native pointer parking")
    client.call("hover", uid=uid)


def native_hover(client, case):
    target = case["native_hover"]
    selector = json.dumps(target["selector"])
    client.evaluate("async () => { await window.visualFixture.ready(); const element = document.querySelector(" + selector +
                    "); if (!element || element.matches(':hover')) throw new Error('Native hover target missing or already hovered'); return true; }")
    uid = observed_target(client, target["selector"], target["role"], target["name"])
    client.evaluate("() => { window.visualFixture.motion.phase('native-hover'); return true; }")
    client.call("hover", uid=uid)
    client.evaluate("async () => { await window.visualFixture.ready(); const element = document.querySelector(" + selector +
                    "); if (!element?.matches(':hover')) throw new Error('Configured native hover did not reach its target'); " +
                    target["assert"] + "; return true; }")


def hover_contract(case):
    return {"target": {key: case["native_hover"][key] for key in ("selector", "role", "name")},
            "pointer_start": "observed driver heading; target initially not hovered"}


def capture_tiles(client, case, output):
    """Paint the complete document through real, overlapping viewport captures."""
    states, tiles, target = [], [], 0
    for index in range(128):
        state = client.evaluate("async () => { window.visualFixture.motion.phase('tile-" + str(index) + "'); window.scrollTo({left:0,top:" + str(target) +
                                ",behavior:'instant'}); await window.visualFixture.ready(); return window.visualFixture.snapshot(); }")
        if case.get("native_hover"):
            state["native_hover"] = hover_contract(case)
        width, height = case["viewport"]
        motion_changes(state, state, case["id"])
        if state["viewport"] != [width, height, 1] or state.get("errors"):
            raise RuntimeError(f"{case['id']}: invalid viewport/DPR or fixture readiness errors")
        x, y, document_height = state["document"]
        maximum = max(0, document_height - height)
        if x != 0 or y != target:
            raise RuntimeError(f"{case['id']}: viewport did not reach requested scroll position {target}: {state['document']}")
        stem = case["id"] + (f".tile-{index}" if index else "")
        image = client.call("take_screenshot", format="png")
        block = next(block for block in image.get("content", []) if block.get("type") == "image")
        (output / (stem + ".png")).write_bytes(base64.b64decode(block["data"]))
        states.append(state)
        tiles.append({"stem": stem, "scroll": [x, y], "document_height": document_height})
        if y >= maximum:
            break
        target = min(maximum, y + max(1, int(height * .8)))
    else:
        raise RuntimeError(f"{case['id']}: document exceeds the bounded 128 viewport capture budget")
    # Keep the initial snapshot canonical; later snapshots detect sticky/scroll
    # dependent geometry too. A missing tile is a failed comparison.
    states[0]["tiles"] = tiles
    for tile, state in zip(tiles, states):
        (output / (tile["stem"] + ".json")).write_text(json.dumps(state, indent=2) + "\n")


def capture(args):
    from urllib.parse import urlparse
    if urlparse(args.origin).hostname not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError("Captures require an isolated loopback fixture server")
    suite_bytes = args.suite.read_bytes()
    suite_sha256 = hashlib.sha256(suite_bytes).hexdigest()
    git_head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    suite = json.loads(suite_bytes)
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
            script_cases = [case for case in cases if not case.get("native_hover") and not case.get("standalone")]
            if args.geometry_only and script_cases:
                # Keep the owned driver tab, but return one complete geometry at
                # a time: a single generated Docs surface can be tens of MB.
                driver_width = max(1440, *(case["viewport"][0] for case in script_cases))
                # The native parent gets a 100 CSS-pixel parking band below all
                # exact-size child viewports; driver bounds checks prove separation.
                driver_height = max(1000, *(case["viewport"][1] for case in script_cases)) + 100
                client.call("emulate", viewport=f"{driver_width}x{driver_height}x1")
                client.call("navigate_page", url=args.origin + "/fixtures/visual-driver.html?geometry-parking=1")
                uid = observed_target(client, "body > h1", "heading", "Native pointer parking")
                client.call("hover", uid=uid)
                failures = []
                for case in script_cases:
                    results = client.evaluate("async () => await window.visualDriver.capture(" + json.dumps([case]) + ")")
                    if len(results) != 1 or results[0].get("id") != case["id"]:
                        raise ValueError("Native layout driver omitted or reordered declared cases")
                    result = results[0]
                    if result.get("error"):
                        failures.append(case["id"] + ": " + result["error"])
                    else:
                        state = result["state"]
                        motion_changes(state, state, case["id"])
                        if state["viewport"] != case["viewport"] + [1] or state.get("errors"):
                            failures.append(case["id"] + ": invalid viewport or native errors")
                        (args.output / (case["id"] + ".json")).write_text(json.dumps(state, indent=2) + "\n")
                        print(f"Captured geometry {case['id']}", flush=True)
                if failures:
                    raise ValueError("; ".join(failures))
            standalone = [case for case in cases if case.get("native_hover") or case.get("standalone")] if args.geometry_only else cases
            for case in standalone:
                client.call("select_page", pageId=page)
                width, height = case["viewport"]
                client.call("emulate", viewport=f"{width}x{height}x1")
                if case.get("native_hover") or case.get("standalone"):
                    park_pointer(client, args.origin)
                try:
                    client.call("navigate_page", url=args.origin + case["path"])
                    if case.get("action"):
                        client.evaluate("async () => { await window.visualFixture.ready(); window.visualFixture.motion.phase('action'); " + case["action"] + "; return true; }")
                    if case.get("native_hover"):
                        native_hover(client, case)
                    if args.geometry_only:
                        state = client.evaluate("async () => { await window.visualFixture.ready(); return window.visualFixture.snapshot(); }")
                        if case.get("native_hover"):
                            state["native_hover"] = hover_contract(case)
                        motion_changes(state, state, case["id"])
                        if state["viewport"] != case["viewport"] + [1] or state.get("errors"):
                            raise RuntimeError(case["id"] + ": invalid viewport or native errors")
                        (args.output / (case["id"] + ".json")).write_text(json.dumps(state, indent=2) + "\n")
                    else:
                        capture_tiles(client, case, args.output)
                finally:
                    client.evaluate("async () => { try { await window.visualFixture?.dispose?.(); } finally { window.visualFixture?.motion?.stop(); } return true; }")
                print(f"Captured {case['id']}", flush=True)
            if args.suite.read_bytes() != suite_bytes:
                raise ValueError("Visual suite changed during capture; partial evidence has no completion metadata")
            metadata = {"schema": 1, "suite_sha256": suite_sha256,
                        "cases": [case["id"] for case in cases],
                        "git_head": git_head,
                        "pixels": not args.geometry_only, "raster": RASTER if not args.geometry_only else None,
                        "motion": MOTION,
                        "approval": None}
            (args.output / "capture.json").write_text(json.dumps(metadata, indent=2) + "\n")
        finally:
            if page is not None:
                try:
                    # Chrome may retain its last page instead of closing it.
                    # Release the owned frame/resources even in that case.
                    client.call("navigate_page", pageId=page, type="url", url="about:blank")
                    client.call("close_page", pageId=page)
                except Exception as error:
                    print(f"Owned task tab {page} needs closing: {error}", file=sys.stderr)
            client.close()


def motion_changes(expected, actual, label):
    for state in (expected, actual):
        motion = state.get("motion")
        if not isinstance(motion, dict) or motion.get("version") != MOTION:
            raise ValueError(label + ": missing native CSS motion observation")
        if motion.get("gaps"):
            raise ValueError(label + ": incomplete CSS motion observation: " + str(motion["gaps"]))
        if not all(key in motion for key in ("properties", "styles", "declarations", "sourceRules", "triggered")):
            raise ValueError(label + ": incomplete CSS declaration/runtime contract")
    return geometry_changes(expected["motion"], actual["motion"], label + ".motion")


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
    if metadata.get("pixels", True) and (metadata.get("raster") != RASTER or actual_metadata.get("raster") != RASTER):
        raise ValueError("Raster captures must include the current complete-document viewport tile strategy")
    motion_version = metadata.get("motion")
    if motion_version != actual_metadata.get("motion"):
        raise ValueError("Motion coverage differs: old layout baselines require separately reviewed motion candidates")
    if motion_version not in (None, MOTION):
        raise ValueError("Unsupported CSS motion capture contract")
    if getattr(args, "require_motion", False) and motion_version != MOTION:
        raise ValueError("Missing approved CSS motion coverage; settled layout is not a motion pass")
    for case in suite["cases"]:
        ident = case["id"]
        try:
            expected = json.loads((args.baseline / (ident + ".json")).read_text())
            actual = json.loads((args.actual / (ident + ".json")).read_text())
            if case.get("native_hover") and any(state.get("native_hover") != hover_contract(case)
                                                for state in (expected, actual)):
                raise ValueError("Missing or changed native hover interaction evidence")
            environment = geometry_changes(expected["environment"], actual["environment"], "environment")
            geometry = geometry_changes(expected["geometry"], actual["geometry"], "geometry")
            motion = motion_changes(expected, actual, ident) if motion_version else []
            tiles = []
            if metadata.get("pixels", True):
                if not expected.get("tiles") or expected["tiles"] != actual.get("tiles"):
                    raise ValueError("Missing or changed viewport tiles/scroll coverage")
                for index, tile in enumerate(expected["tiles"]):
                    stem = tile["stem"]
                    # Artifact names are derived from the declared case, never
                    # arbitrary paths supplied by capture metadata.
                    if stem != ident + (f".tile-{index}" if index else ""):
                        raise ValueError("Invalid viewport tile artifact name")
                    before = expected if not index else json.loads((args.baseline / (stem + ".json")).read_text())
                    after = actual if not index else json.loads((args.actual / (stem + ".json")).read_text())
                    if index:
                        environment.extend(geometry_changes(before["environment"], after["environment"], stem + ".environment"))
                        geometry.extend(geometry_changes(before["geometry"], after["geometry"], stem + ".geometry"))
                        if motion_version:
                            motion.extend(motion_changes(before, after, stem))
                    raster = compare_pixels(args.baseline / (stem + ".png"), args.actual / (stem + ".png"), args.output / (stem + ".diff.png"))
                    tiles.append({**tile, "pixels": raster})
                pixels = {"passed": all(tile["pixels"]["passed"] for tile in tiles),
                          "changed_pixels": sum(tile["pixels"].get("changed_pixels", 0) for tile in tiles),
                          "total_pixels": sum(tile["pixels"].get("total_pixels", 0) for tile in tiles)}
            else:
                pixels = {"passed": True, "mode": "geometry-only"}
            result = {"id": ident, "passed": pixels["passed"] and not geometry and not environment and not motion,
                      "pixels": pixels, "tiles": tiles, "geometry": geometry, "environment": environment,
                      "motion": motion, "motion_coverage": motion_version or "unmeasured"}
        except (OSError, ValueError, KeyError) as error:
            result = {"id": ident, "passed": False, "error": str(error)}
        results.append(result)
        if result.get("error"):
            detail = result["error"]
        elif result["pixels"].get("mode") == "geometry-only":
            detail = f"geometry-only; {len(result['geometry'])} geometry/style changes; {len(result['motion'])} motion changes"
        else:
            detail = f"{result['pixels'].get('changed_pixels', '?')} pixels; {len(result['geometry'])} geometry changes; {len(result['motion'])} motion changes"
        print(f"{'PASS' if result['passed'] else 'FAIL'} {ident}: {detail}")
    report = {"passed": all(result["passed"] for result in results),
              "motion_coverage": motion_version or "unmeasured", "cases": results}
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    rows = []
    for result in results:
        ident = result["id"]
        pictures = "".join(f'<figure><figcaption>{html.escape(stem)} — {label}</figcaption><img src="{html.escape(os.path.relpath(path, args.output))}"></figure>'
                           for tile in result.get("tiles", []) for stem in [tile["stem"]]
                           for label, path in [("Expected", args.baseline / (stem + ".png")),
                                               ("Actual", args.actual / (stem + ".png")),
                                               ("Changed pixels", args.output / (stem + ".diff.png"))] if path.exists())
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
    check.add_argument("--require-motion", action="store_true", help="Reject layout-only baselines; require reviewed native CSS motion contracts")
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
