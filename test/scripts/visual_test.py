"""The visual gate must reject a real one-pixel/padding/missing-element change."""
import importlib.util
import argparse
import base64
import hashlib
import gzip
import json
import re
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

SPEC = importlib.util.spec_from_file_location("visual", Path(__file__).parents[2] / "scripts/test/visual.py")
visual = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(visual)


def hover_case():
    return {'id': 'native-edit-hover', 'viewport': [400, 800], 'path': '/fixtures/visual.html?component=comments-auth',
            'native_hover': {'selector': '.native-edit', 'role': 'button', 'name': 'Edit comment',
                             'assert': "if (!document.querySelector('[role=tooltip]')) throw new Error('Missing native tooltip')"}}


class HoverClient:
    """Fake only the MCP transport; assertions exercise the real capture protocol."""
    def __init__(self, target_mode='normal', initially_hovered=False, hover_reached=True, gaps=None, wrong_viewport=False):
        self.calls = []
        self.target_mode = target_mode
        self.initially_hovered = initially_hovered
        self.hover_reached = hover_reached
        self.gaps = gaps or []
        self.wrong_viewport = wrong_viewport
        self.url = 'about:blank'
        self.viewport = [400, 800, 1]

    def call(self, name, **arguments):
        self.calls.append((name, arguments))
        if name == 'list_pages':
            return {'content': [{'type': 'text', 'text': '11: http://other-project.test/ [selected]'}]}
        if name == 'new_page':
            return {'content': [{'type': 'text', 'text': '11: http://other-project.test/ [selected]\n73: ' + arguments['url']}]}
        if name == 'navigate_page': self.url = arguments['url']
        if name == 'emulate': self.viewport = [int(value) for value in arguments['viewport'].split('x')]
        if name == 'take_snapshot':
            if self.url.split('?', 1)[0].endswith('visual-driver.html'):
                text = 'uid=observed-parking heading "Native pointer parking" level="1"'
            elif self.target_mode == 'missing': text = 'uid=observed-unrelated button "Another action"'
            else: text = 'uid=observed-decoy button "Edit comment"\n  uid=observed-edit button "Edit comment"'
            return {'content': [{'type': 'text', 'text': text}]}
        return {}

    def state(self, viewport=None):
        return {'viewport': viewport or ([401, 800, 1] if self.wrong_viewport else self.viewport), 'errors': [],
                'geometry': [], 'motion': {'version': visual.MOTION, 'properties': [], 'styles': [],
                                         'declarations': [], 'sourceRules': [], 'triggered': [], 'gaps': self.gaps}}

    def evaluate(self, function, **arguments):
        self.calls.append(('evaluate_script', {'function': function, **arguments}))
        if 'args' in arguments:
            uid = arguments['args'][0]
            if uid == 'observed-parking': return True
            return self.target_mode == 'ambiguous' or (uid == 'observed-edit' and self.target_mode != 'wrong-selector')
        if 'Native hover target missing' in function and self.initially_hovered:
            raise RuntimeError('Native hover target missing or already hovered')
        if 'Configured native hover did not reach' in function and not self.hover_reached:
            raise RuntimeError('Configured native hover did not reach its target')
        if 'visualDriver.capture' in function:
            cases = json.loads(function.split('capture(', 1)[1].rsplit(')', 1)[0])
            return [{'id': case['id'], 'state': self.state(case['viewport'] + [1])} for case in cases]
        if 'visualFixture.snapshot()' in function: return self.state()
        return True

    def close(self): self.calls.append(('disconnect', {}))


class VisualTest(unittest.TestCase):
    def test_record_encoding_is_lossless_and_requires_one_unique_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = {"geometry": [{"x": 329.2421875, "text": "å\n```json"}],
                     "motion": {"triggered": [{"keyframes": [{"offset": 0}, {"offset": 1}]}]}}
            data = (json.dumps(state, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            plain, compressed = root / "case.json", root / "case.json.gz"
            with self.assertRaisesRegex(ValueError, "found 0"):
                visual.read_record(root, "case")
            plain.write_bytes(data)
            self.assertEqual(state, visual.read_record(root, "case"))
            compressed.write_bytes(gzip.compress(data, mtime=0))
            self.assertEqual(data, gzip.decompress(compressed.read_bytes()))
            with self.assertRaisesRegex(ValueError, "found 2"):
                visual.read_record(root, "case")
            plain.unlink()
            self.assertEqual(state, visual.read_record(root, "case"))

    def test_record_reader_rejects_corrupt_truncated_and_invalid_compressed_json(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            valid = gzip.compress(b'{"geometry": []}', mtime=0)
            bad_crc = bytearray(valid); bad_crc[-8] ^= 1
            for data in (b"not gzip", valid[:-5], bytes(bad_crc), gzip.compress(b"{not json", mtime=0)):
                with self.subTest(data=data):
                    (root / "case.json.gz").write_bytes(data)
                    with self.assertRaises((OSError, ValueError)):
                        visual.read_record(root, "case")

    def test_capture_preserves_existing_compressed_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            suite = root / "suite.json"
            suite.write_text(json.dumps({"cases": [{"id": "case"}]}))
            output = root / "capture"; output.mkdir()
            record = output / "case.json.gz"
            data = gzip.compress(b'{"geometry": []}', mtime=0)
            record.write_bytes(data)
            args = argparse.Namespace(origin="http://127.0.0.1:4098", suite=suite,
                                      output=output, case=[], geometry_only=True, mcp_command=None)
            with self.assertRaisesRegex(ValueError, "Capture output already exists"):
                visual.capture(args)
            self.assertEqual(data, record.read_bytes())

    def test_evaluation_preserves_literal_markdown_fences_inside_json(self):
        payload = {"summary": "", "body": "Code: ```clojure\n(+ 1 2)\n``` and ```json {not a second result}```",
                   "nested": ["quotes \" and slash \\"], "unicode": "å"}
        client = object.__new__(visual.MCP)
        client.call = lambda *args, **kwargs: {"content": [
            {"type": "text", "text": "Tool notice"},
            {"type": "text", "text": "Script ran on page and returned:\n```json\n" + json.dumps(payload) + "\n```"}]}
        self.assertEqual(payload, client.evaluate("() => document.body.innerText"))

    def test_evaluation_rejects_incomplete_json_or_missing_closing_fence(self):
        client = object.__new__(visual.MCP)
        for text in ['```json\n{"body":"truncated', '```json\n{"body":"valid"}',
                     '```json\n{"body":"valid"} {}\n```']:
            with self.subTest(text=text):
                client.call = lambda *args, **kwargs: {"content": [{"type": "text", "text": text}]}
                with self.assertRaises((ValueError, RuntimeError)):
                    client.evaluate("() => document.body.innerText")

    def test_owned_background_page_uses_observed_unique_url_and_new_id_never_selection(self):
        client = HoverClient()
        self.assertEqual(73, visual.owned_page(client))
        self.assertEqual('list_pages', client.calls[0][0])
        self.assertEqual('new_page', client.calls[1][0])
        self.assertTrue(client.calls[1][1]['background'])
        self.assertRegex(client.calls[1][1]['url'], r'^about:blank#tolgraven-visual-[0-9a-f]{32}$')
        another = HoverClient()
        self.assertEqual(73, visual.owned_page(another))
        self.assertNotEqual(client.calls[1][1]['url'], another.calls[1][1]['url'],
                            'Each capture has its own unpredictable task marker')
        for mode in ('missing', 'ambiguous', 'reused'):
            with self.subTest(mode=mode):
                class Client(HoverClient):
                    def call(self, name, **arguments):
                        result = super().call(name, **arguments)
                        if name == 'new_page':
                            url = arguments['url']
                            listing = {'missing': '11: http://other-project.test/ [selected]',
                                       'ambiguous': '73: ' + url + '\n74: ' + url,
                                       'reused': '11: ' + url}[mode]
                            return {'content': [{'type': 'text', 'text': listing}]}
                        return result
                rejected = Client()
                with self.assertRaisesRegex(RuntimeError, 'Cannot identify owned task tab'):
                    visual.owned_page(rejected)
                self.assertEqual(['list_pages', 'new_page'], [name for name, _ in rejected.calls],
                                 'No navigation, emulation or closing may touch an unproven page')

    def test_observed_hover_uid_requires_snapshot_role_name_and_native_selector_identity(self):
        client = HoverClient()
        uid = visual.observed_target(client, '.native-edit', 'button', 'Edit comment')
        self.assertEqual('observed-edit', uid)
        evaluations = [arguments['args'] for name, arguments in client.calls if name == 'evaluate_script' and 'args' in arguments]
        self.assertEqual([['observed-decoy'], ['observed-edit']], evaluations)
        self.assertFalse(any(name == 'hover' for name, _ in client.calls))
        for mode in ('missing', 'ambiguous', 'wrong-selector'):
            client = HoverClient(target_mode=mode)
            with self.assertRaisesRegex(RuntimeError, 'Expected one observed'):
                visual.observed_target(client, '.native-edit', 'button', 'Edit comment')
            self.assertFalse(any(name == 'hover' for name, _ in client.calls))

    def test_unnamed_paragraph_uid_retains_observed_identity_and_fail_closed_matching(self):
        class ParagraphClient(HoverClient):
            def call(self, name, **arguments):
                result = super().call(name, **arguments)
                if name == 'take_snapshot':
                    # Same native form as the archived Strava verbose snapshot:
                    # paragraph has no quoted name; its text child is separate.
                    text = ('uid=observed-decoy paragraph\n'
                            '  uid=observed-text "Harbour climb"\n'
                            '  uid=observed-whitespace " "\n'
                            'uid=observed-segment paragraph\n'
                            '  uid=observed-label "Harbour climb"\n'
                            'uid=observed-named paragraph "Another paragraph"')
                    return {'content': [{'type': 'text', 'text': text}]}
                return result

            def evaluate(self, function, **arguments):
                if 'args' in arguments:
                    self.calls.append(('evaluate_script', {'function': function, **arguments}))
                    return self.target_mode == 'ambiguous' or (arguments['args'] == ['observed-segment'] and self.target_mode != 'wrong-selector')
                return super().evaluate(function, **arguments)

        client = ParagraphClient()
        self.assertEqual('observed-segment', visual.observed_target(client, '.segment-name', 'paragraph', ''))
        observed = [arguments['args'] for name, arguments in client.calls
                    if name == 'evaluate_script' and 'args' in arguments]
        self.assertEqual([['observed-decoy'], ['observed-segment']], observed)
        self.assertFalse(any(name == 'hover' for name, _ in client.calls))
        for mode, role, name in [('ambiguous', 'paragraph', ''),
                                 ('wrong-selector', 'paragraph', ''),
                                 ('normal', 'paragraph', 'Harbour climb'),
                                 ('normal', 'button', '')]:
            with self.subTest(mode=mode, role=role, name=name):
                rejected = ParagraphClient(target_mode=mode)
                with self.assertRaisesRegex(RuntimeError, 'Expected one observed'):
                    visual.observed_target(rejected, '.segment-name', role, name)
                self.assertFalse(any(call == 'hover' for call, _ in rejected.calls))
        target = {'id': 'segment-hover', 'native_hover': {'selector': '.segment-name',
                  'role': 'paragraph', 'name': '', 'assert': "if (!element.matches(':hover')) throw Error('Missing hover')"}}
        visual.native_hover(client, target)
        self.assertEqual(['observed-segment'], [arguments['uid'] for name, arguments in client.calls if name == 'hover'])
        scripts = '\n'.join(arguments['function'] for name, arguments in client.calls if name == 'evaluate_script')
        self.assertIn('element.ownerDocument === document && element.isConnected', scripts)
        self.assertIn('element.matches(".segment-name")', scripts)
        self.assertIn("element.matches(':hover')", scripts)
        self.assertNotRegex(scripts, r'dispatchEvent|setAttribute|innerHTML|\.style\s*=|classList\.(add|remove)')

    def test_native_hover_parks_real_pointer_then_verifies_native_state_without_dom_marking(self):
        client = HoverClient()
        visual.park_pointer(client, 'http://127.0.0.1:4098')
        client.call('navigate_page', url='http://127.0.0.1:4098/fixtures/visual.html?component=comments-auth')
        visual.native_hover(client, hover_case())
        moves = [arguments['uid'] for name, arguments in client.calls if name == 'hover']
        self.assertEqual(['observed-parking', 'observed-edit'], moves)
        phases = [index for index, (name, arguments) in enumerate(client.calls)
                  if name == 'evaluate_script' and "phase('native-hover')" in arguments['function']]
        final_hover = max(index for index, (name, _) in enumerate(client.calls) if name == 'hover')
        self.assertLess(phases[0], final_hover)
        scripts = '\n'.join(arguments['function'] for name, arguments in client.calls if name == 'evaluate_script')
        self.assertIn("element.matches(':hover')", scripts)
        self.assertNotRegex(scripts, r'dispatchEvent|setAttribute|innerHTML|\.style\s*=|classList\.(add|remove)')
        for options, message in [({'initially_hovered': True}, 'already hovered'),
                                 ({'hover_reached': False}, 'did not reach')]:
            client = HoverClient(**options)
            with self.assertRaisesRegex(RuntimeError, message):
                visual.native_hover(client, hover_case())
            if options.get('initially_hovered'):
                self.assertFalse(any(name == 'hover' for name, _ in client.calls))

    def test_hover_cases_use_one_standalone_owned_tab_and_preserve_ordinary_iframe_capture(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            suite = root / 'suite.json'
            suite.write_text(json.dumps({'cases': [{'id': 'ordinary', 'viewport': [400, 800]}, hover_case()]}))
            args = argparse.Namespace(suite=suite, origin='http://127.0.0.1:4098', output=root / 'capture',
                                      case=[], geometry_only=True, mcp_command=None)
            client = HoverClient()
            with patch.object(visual, 'Relay', return_value=client):
                visual.capture(args)
            batches = [arguments['function'] for name, arguments in client.calls
                       if name == 'evaluate_script' and 'visualDriver.capture' in arguments['function']]
            self.assertEqual(1, len(batches)); self.assertIn('ordinary', batches[0]); self.assertNotIn('native-edit-hover', batches[0])
            self.assertEqual(1, sum(name == 'new_page' for name, _ in client.calls))
            foreground = client.calls.index(('select_page', {'pageId': 73, 'bringToFront': True}))
            first_measurement = next(index for index, (name, _) in enumerate(client.calls) if name == 'emulate')
            self.assertLess(foreground, first_measurement, 'Emulated focus cannot establish a foreground capture')
            self.assertEqual(1, sum(name == 'close_page' for name, _ in client.calls))
            state = json.loads((args.output / 'native-edit-hover.json').read_text())
            self.assertEqual([400, 800, 1], state['viewport'])
            self.assertEqual('.native-edit', state['native_hover']['target']['selector'])
            self.assertNotIn('observed-edit', json.dumps(state), 'Ephemeral snapshot IDs cannot change a repeat comparison')
            self.assertEqual(('navigate_page', {'pageId': 73, 'type': 'url', 'url': 'about:blank'}), client.calls[-3])
            self.assertTrue((args.output / 'ordinary.json').exists())

    def test_isolated_cases_stay_top_level_and_dispose_before_next_navigation(self):
        from unittest.mock import patch
        for fail in (False, True):
            with self.subTest(fail=fail), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                suite = root / 'suite.json'
                cases = [{'id': name, 'viewport': [400, 800], 'standalone': True,
                          'path': '/fixtures/neovim-visual.html?visual=' + name}
                         for name in ('patch', 'diff')]
                suite.write_text(json.dumps({'cases': cases}))
                args = argparse.Namespace(suite=suite, origin='http://127.0.0.1:4098',
                                          output=root / 'capture', case=[], geometry_only=True, mcp_command=None)
                class NativeClient(HoverClient):
                    def evaluate(self, function, **arguments):
                        if fail and 'visualFixture.snapshot()' in function:
                            self.calls.append(('evaluate_script', {'function': function}))
                            raise RuntimeError('Native startup failed')
                        return super().evaluate(function, **arguments)
                client = NativeClient()
                with patch.object(visual, 'Relay', return_value=client):
                    if fail:
                        with self.assertRaisesRegex(RuntimeError, 'Native startup failed'):
                            visual.capture(args)
                    else:
                        visual.capture(args)
                scripts = [(i, args['function']) for i, (name, args) in enumerate(client.calls)
                           if name == 'evaluate_script']
                self.assertFalse(any('visualDriver.capture' in script for _, script in scripts))
                disposals = [i for i, script in scripts if 'visualFixture?.dispose?.()' in script]
                self.assertEqual(1 if fail else 2, len(disposals))
                navigations = [i for i, (name, args) in enumerate(client.calls)
                               if name == 'navigate_page' and 'neovim-visual' in args['url']]
                self.assertLess(navigations[0], disposals[0])
                if not fail:
                    self.assertLess(disposals[0], navigations[1])
                    self.assertNotIn('native_hover', json.loads((args.output / 'patch.json').read_text()))
                self.assertEqual(1, sum(name == 'new_page' for name, _ in client.calls))
                self.assertEqual(1, sum(name == 'close_page' for name, _ in client.calls))
                self.assertEqual(not fail, (args.output / 'capture.json').exists())

    def test_geometry_pointer_parks_observed_heading_outside_all_fixture_viewports(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases = [{'id': 'wide', 'viewport': [1440, 1000]},
                     {'id': 'tall', 'viewport': [400, 1200]}]
            suite = root / 'suite.json'; suite.write_text(json.dumps({'cases': cases}))
            args = argparse.Namespace(suite=suite, origin='http://127.0.0.1:4098', output=root / 'capture',
                                      case=[], geometry_only=True, mcp_command=None)
            client = HoverClient()
            with patch.object(visual, 'Relay', return_value=client):
                visual.capture(args)
            self.assertIn(('emulate', {'viewport': '1440x1300x1'}), client.calls)
            self.assertIn(('navigate_page', {'url': args.origin + '/fixtures/visual-driver.html?geometry-parking=1'}), client.calls)
            moves = [(index, arguments) for index, (name, arguments) in enumerate(client.calls) if name == 'hover']
            self.assertEqual(['observed-parking'], [arguments['uid'] for _, arguments in moves])
            snapshots = [index for index, (name, _) in enumerate(client.calls) if name == 'take_snapshot']
            verifications = [index for index, (name, arguments) in enumerate(client.calls)
                             if name == 'evaluate_script' and arguments.get('args') == ['observed-parking']]
            captures = [index for index, (name, arguments) in enumerate(client.calls)
                        if name == 'evaluate_script' and 'visualDriver.capture' in arguments['function']]
            self.assertLess(snapshots[0], verifications[0]); self.assertLess(verifications[0], moves[0][0])
            self.assertLess(moves[0][0], captures[0])
            for case in cases:
                self.assertEqual(case['viewport'] + [1], json.loads((args.output / (case['id'] + '.json')).read_text())['viewport'])

    def test_large_geometry_cases_return_one_complete_fixture_per_request(self):
        from unittest.mock import patch

        class Client(HoverClient):
            def __init__(self):
                super().__init__()
                self.batches = []

            def evaluate(self, function, **arguments):
                if 'visualDriver.capture' in function:
                    batch = json.loads(function.split('capture(', 1)[1].rsplit(')', 1)[0])
                    self.batches.append([case['id'] for case in batch])
                    if len(batch) != 1:
                        raise RuntimeError('Large native geometries must return individually')
                    state = self.state(batch[0]['viewport'] + [1])
                    state['geometry'] = [{'text': 'x' * (1024 * 1024)}]
                    return [{'id': batch[0]['id'], 'state': state}]
                return super().evaluate(function, **arguments)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases = [{'id': f'large-docs-{index}', 'viewport': [1440, 1000]} for index in range(4)]
            suite = root / 'suite.json'; suite.write_text(json.dumps({'cases': cases}))
            args = argparse.Namespace(suite=suite, origin='http://127.0.0.1:4098', output=root / 'capture',
                                      case=[], geometry_only=True, mcp_command=None)
            client = Client()
            with patch.object(visual, 'Relay', return_value=client):
                visual.capture(args)
            self.assertEqual([[case['id']] for case in cases], client.batches)
            self.assertEqual(1, sum(name == 'new_page' for name, _ in client.calls))
            self.assertEqual(1, sum(name == 'close_page' for name, _ in client.calls))
            for case in cases:
                state = json.loads((args.output / (case['id'] + '.json')).read_text())
                self.assertEqual('x' * (1024 * 1024), state['geometry'][0]['text'])
            self.assertEqual([case['id'] for case in cases], json.loads((args.output / 'capture.json').read_text())['cases'])

    def test_capture_pins_suite_bytes_and_head_before_browser_work(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            suite = root / 'suite.json'
            original_bytes = b'{"cases": [{"id": "ordinary", "viewport": [400, 800]}]}\n'
            suite.write_bytes(original_bytes)
            args = argparse.Namespace(suite=suite, origin='http://127.0.0.1:4098', output=root / 'capture',
                                      case=[], geometry_only=True, mcp_command=None)
            client = HoverClient()
            original_call = client.call
            current_head = ['startup-head']
            def call(name, **arguments):
                self.assertEqual(1, head.call_count, 'Git head must be pinned before the browser is opened')
                current_head[0] = 'head-changed-while-capturing'
                return original_call(name, **arguments)
            client.call = call
            with patch.object(visual, 'Relay', return_value=client), patch.object(
                    visual.subprocess, 'check_output', side_effect=lambda *args, **kwargs: current_head[0]) as head:
                visual.capture(args)
            metadata = json.loads((args.output / 'capture.json').read_text())
            self.assertEqual('startup-head', metadata['git_head'])
            self.assertEqual(hashlib.sha256(original_bytes).hexdigest(), metadata['suite_sha256'])
            self.assertEqual(1, head.call_count)

    def test_midrun_suite_byte_change_rejects_completion_and_cleans_owned_page(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            suite = root / 'suite.json'
            original_bytes = b'{"cases": [{"id": "ordinary", "viewport": [400, 800]}]}'
            suite.write_bytes(original_bytes)
            args = argparse.Namespace(suite=suite, origin='http://127.0.0.1:4098', output=root / 'capture',
                                      case=[], geometry_only=True, mcp_command=None)
            client = HoverClient()
            original_evaluate = client.evaluate
            def evaluate(function, **arguments):
                result = original_evaluate(function, **arguments)
                # Semantically identical JSON is still different suite bytes.
                suite.write_bytes(original_bytes + b'\n')
                return result
            client.evaluate = evaluate
            with patch.object(visual, 'Relay', return_value=client):
                with self.assertRaisesRegex(ValueError, 'suite changed during capture'):
                    visual.capture(args)
            self.assertFalse((args.output / 'capture.json').exists())
            self.assertTrue((args.output / 'ordinary.json').exists(), 'Partial evidence must remain available')
            self.assertEqual(('navigate_page', {'pageId': 73, 'type': 'url', 'url': 'about:blank'}), client.calls[-3])
            self.assertEqual(('close_page', {'pageId': 73}), client.calls[-2])

    def test_native_hover_capture_keeps_strict_motion_gaps_and_viewport_failures(self):
        from unittest.mock import patch
        for options, message in [({'gaps': ['missing start']}, 'incomplete CSS'),
                                 ({'wrong_viewport': True}, 'invalid viewport')]:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                suite = root / 'suite.json'; suite.write_text(json.dumps({'cases': [hover_case()]}))
                args = argparse.Namespace(suite=suite, origin='http://127.0.0.1:4098', output=root / 'capture',
                                          case=[], geometry_only=True, mcp_command=None)
                client = HoverClient(**options)
                with patch.object(visual, 'Relay', return_value=client):
                    with self.assertRaisesRegex((ValueError, RuntimeError), message):
                        visual.capture(args)
                self.assertFalse((args.output / 'capture.json').exists())
                self.assertEqual('about:blank', client.calls[-3][1]['url'])

    def test_hover_comparison_requires_native_interaction_evidence_even_when_geometry_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case = hover_case()
            suite = root / 'suite.json'; suite.write_text(json.dumps({'cases': [case]}))
            baseline, actual = root / 'baseline', root / 'actual'
            state = {'environment': {}, 'geometry': [], 'native_hover': visual.hover_contract(case)}
            metadata = {'suite_sha256': hashlib.sha256(suite.read_bytes()).hexdigest(), 'pixels': False,
                        'approval': {'reviewer': 'test', 'reference': 'synthetic', 'reason': 'test'}}
            for folder in (baseline, actual):
                folder.mkdir(); (folder / 'capture.json').write_text(json.dumps(metadata))
                (folder / (case['id'] + '.json')).write_text(json.dumps(state))
            args = argparse.Namespace(suite=suite, baseline=baseline, actual=actual, output=root / 'report', require_motion=False)
            self.assertEqual(0, visual.compare(args))
            state.pop('native_hover')
            (actual / (case['id'] + '.json')).write_text(json.dumps(state))
            self.assertEqual(1, visual.compare(args))
            report = json.loads((args.output / 'report.json').read_text())
            self.assertIn('native hover interaction evidence', report['cases'][0]['error'])

    def test_evaluate_forwards_only_observed_uid_arguments_through_configured_mcp(self):
        client = object.__new__(visual.MCP)
        calls = []
        client.call = lambda name, **arguments: calls.append((name, arguments)) or {
            'content': [{'type': 'text', 'text': '```json\ntrue\n```'}]}
        self.assertTrue(client.evaluate('element => element.isConnected', args=['observed-uid']))
        self.assertEqual([('evaluate_script', {'function': 'element => element.isConnected',
                                              'args': ['observed-uid']})], calls)

    def test_failed_capture_releases_its_owned_page_before_chrome_refuses_last_close(self):
        from unittest.mock import patch
        calls = []

        class Client:
            def call(self, name, **arguments):
                calls.append((name, arguments))
                if name == "list_pages":
                    return {"content": [{"type": "text", "text": "11: http://other-project.test/ [selected]"}]}
                if name == "new_page":
                    return {"content": [{"type": "text", "text": "11: http://other-project.test/ [selected]\n73: " + arguments["url"]}]}
                if name == "close_page":
                    return {"content": [{"type": "text", "text": "The last open page cannot be closed."}]}
                if name == "take_snapshot":
                    return {"content": [{"type": "text", "text": 'uid=observed-parking heading "Native pointer parking"'}]}
                return {}

            def evaluate(self, _, **arguments):
                if arguments.get('args') == ['observed-parking']:
                    return True
                raise RuntimeError("Native fixture failed")

            def close(self):
                calls.append(("disconnect", {}))

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            suite = root / "suite.json"
            suite.write_text(json.dumps({"cases": [{"id": "comment", "viewport": [400, 800]}]}))
            args = argparse.Namespace(suite=suite, origin="http://127.0.0.1:4098",
                                      output=root / "capture", case=[], geometry_only=True, mcp_command=None)
            with patch.object(visual, "Relay", return_value=Client()):
                with self.assertRaisesRegex(RuntimeError, "Native fixture failed"):
                    visual.capture(args)
            self.assertEqual([
                ("navigate_page", {"pageId": 73, "type": "url", "url": "about:blank"}),
                ("close_page", {"pageId": 73}),
                ("disconnect", {})], calls[-3:])
            self.assertFalse((args.output / "capture.json").exists(), "A failed capture must remain unapproved and incomplete")

    def test_motion_contract_changes_fail_without_geometry_changes(self):
        import copy
        contract = {"version": visual.MOTION, "properties": ["animation-duration"],
                    "styles": [["2s"]], "declarations": [["article", "::before", 0]],
                    "sourceRules": [{"keyframes": "dormant", "frames": [{"offset": "100%", "properties": {"opacity": ["1", ""]}}]},
                                    {"property": "--logo-image-width", "syntax": "<percentage>",
                                     "inherits": True, "initialValue": "100%"}],
                    "triggered": [{"kind": "transition", "name": "height", "phase": "action",
                                   "timing": {"duration": 300, "easing": "ease-in"},
                                   "keyframes": [{"offset": 0, "height": "0px"},
                                                 {"offset": 1, "height": "100px"}]}], "gaps": []}
        expected = {"motion": contract}
        self.assertEqual([], visual.motion_changes(expected, copy.deepcopy(expected), "comment"))
        mutations = [lambda c: c["styles"][0].__setitem__(0, "3s"),
                     lambda c: c["declarations"][0].__setitem__(1, "::after"),
                     lambda c: c["triggered"][0]["timing"].__setitem__("duration", 0),
                     lambda c: c["triggered"][0]["timing"].__setitem__("easing", "linear"),
                     lambda c: c["triggered"][0].__setitem__("name", "opacity"),
                     lambda c: c["triggered"][0]["keyframes"][1].__setitem__("height", "101px"),
                     lambda c: c["triggered"].clear(),
                     lambda c: c["sourceRules"][0]["frames"][0]["properties"]["opacity"].__setitem__(0, "0.5"),
                     lambda c: c["sourceRules"][1].__setitem__("property", "--different-width"),
                     lambda c: c["sourceRules"][1].__setitem__("syntax", "*"),
                     lambda c: c["sourceRules"][1].__setitem__("inherits", False),
                     lambda c: c["sourceRules"][1].__setitem__("initialValue", "85%"),
                     lambda c: c["sourceRules"].pop()]
        for mutate in mutations:
            actual = copy.deepcopy(expected)
            mutate(actual["motion"])
            self.assertTrue(visual.motion_changes(expected, actual, "comment"))
        with self.assertRaisesRegex(ValueError, "missing native CSS"):
            visual.motion_changes(expected, {}, "comment")
        incomplete = copy.deepcopy(expected)
        incomplete["motion"]["gaps"].append("Unobservable start")
        with self.assertRaisesRegex(ValueError, "incomplete CSS"):
            visual.motion_changes(expected, incomplete, "comment")

    def test_motion_command_rejects_regression_restores_pass_and_requires_coverage(self):
        import copy
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            suite = root / "suite.json"
            suite.write_text(json.dumps({"cases": [{"id": "comment"}]}))
            before = {"environment": {"dpr": 1}, "geometry": [[329.2421875]], "motion": {
                "version": visual.MOTION, "properties": ["transition-duration"],
                "styles": [[".3s"]], "declarations": [["article", None, 0]],
                "sourceRules": [], "triggered": [], "gaps": []}}
            baseline, actual = root / "baseline", root / "actual"
            metadata = {"suite_sha256": hashlib.sha256(suite.read_bytes()).hexdigest(),
                        "pixels": False, "motion": visual.MOTION,
                        "approval": {"reviewer": "test", "reference": "synthetic", "reason": "test"}}
            for folder in (baseline, actual):
                folder.mkdir()
                (folder / "capture.json").write_text(json.dumps(metadata))
                (folder / "comment.json").write_text(json.dumps(before))
            record = baseline / "comment.json"
            (baseline / "comment.json.gz").write_bytes(gzip.compress(record.read_bytes(), mtime=0))
            record.unlink()
            args = argparse.Namespace(suite=suite, baseline=baseline, actual=actual,
                                      output=root / "report", require_motion=True)
            self.assertEqual(0, visual.compare(args))
            after = copy.deepcopy(before)
            after["motion"]["styles"][0][0] = "0s"
            (actual / "comment.json").write_text(json.dumps(after))
            self.assertEqual(1, visual.compare(args))
            report = json.loads((args.output / "report.json").read_text())
            self.assertEqual([], report["cases"][0]["geometry"])
            self.assertEqual("comment.motion.styles[0][0]", report["cases"][0]["motion"][0]["path"])
            (actual / "comment.json").write_text(json.dumps(before))
            self.assertEqual(0, visual.compare(args))
            for section, value in (("geometry", [[329.375]]), ("environment", {"dpr": 2})):
                after = copy.deepcopy(before); after[section] = value
                (actual / "comment.json").write_text(json.dumps(after))
                self.assertEqual(1, visual.compare(args))
                report = json.loads((args.output / "report.json").read_text())
                self.assertTrue(report["cases"][0][section])
            (actual / "comment.json").write_text(json.dumps(before))
            metadata.pop("motion")
            (baseline / "capture.json").write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, "Motion coverage differs"):
                visual.compare(args)
            (actual / "capture.json").write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, "Missing approved CSS motion"):
                visual.compare(args)

    def test_raster_settles_startup_before_first_scroll_and_still_rejects_displaced_tiles(self):
        class Client(HoverClient):
            def __init__(self, image, drift=None):
                super().__init__()
                self.image, self.drift = image, drift
                self.startup = False
                self.events = []
                self.scrolls = 0

            def evaluate(self, function, **arguments):
                self.assert_readiness(function)
                if 'window.scrollTo' not in function:
                    self.events.append('startup-ready')
                    self.startup = True
                    # Native startup focus moves the viewport before tiling.
                    self.y = 229
                    return True
                target = int(re.search(r'top:(\d+)', function).group(1))
                self.events.extend([('scroll', target), 'post-scroll-ready'])
                self.scrolls += 1
                self.y = target if self.startup else 229
                self.startup = True
                state = self.state()
                state['document'] = [0, self.y, 1610]
                if self.drift == 'scroll': state['document'][1] += 1
                if self.drift == 'viewport': state['viewport'][0] += 1
                return state

            @staticmethod
            def assert_readiness(function):
                if 'await window.visualFixture.ready()' not in function:
                    raise AssertionError('Startup must use the actual fixture readiness API')

            def call(self, name, **arguments):
                self.calls.append((name, arguments))
                if name != 'take_screenshot': raise AssertionError('Unexpected capture transport call')
                return {'content': [{'type': 'image', 'data': self.image}]}

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            png = root / 'native.png'
            visual.write_png(png, 1, 1, bytes([42, 23, 63, 255]))
            image = base64.b64encode(png.read_bytes()).decode()
            case = {'id': 'cv', 'viewport': [400, 800]}
            for drift in (None, 'scroll', 'viewport'):
                with self.subTest(drift=drift):
                    output = root / str(drift); output.mkdir()
                    client = Client(image, drift)
                    if drift:
                        message = 'requested scroll position' if drift == 'scroll' else 'invalid viewport'
                        with self.assertRaisesRegex(RuntimeError, message):
                            visual.capture_tiles(client, case, output)
                        self.assertEqual([], list(output.iterdir()), 'No invalid tile may be captured')
                        self.assertFalse(client.calls, 'Bounds must pass before taking a screenshot')
                    else:
                        visual.capture_tiles(client, case, output)
                        state = json.loads((output / 'cv.json').read_text())
                        self.assertEqual([[0, 0], [0, 640], [0, 810]], [tile['scroll'] for tile in state['tiles']])
                        self.assertEqual(3, len(client.calls))
                    self.assertEqual('startup-ready', client.events[0])
                    self.assertEqual(('scroll', 0), client.events[1])
                    self.assertEqual(client.scrolls, client.events.count('post-scroll-ready'))

    def test_raster_retains_the_interaction_before_document_scrolling(self):
        class Client(HoverClient):
            def __init__(self, image):
                super().__init__()
                self.image, self.y, self.popup = image, 289.5, True
                self.captured = []

            def evaluate(self, function, **arguments):
                if 'window.scrollTo' in function:
                    self.y = int(re.search(r'top:(\d+)', function).group(1))
                    self.popup = False  # Real tooltip scroll dismissal stays active.
                if 'window.visualFixture.snapshot()' in function:
                    state = self.state()
                    state['document'] = [0, self.y, 1089]
                    state['geometry'] = [{'popup': self.popup}]
                    return state
                return True

            def call(self, name, **arguments):
                if name != 'take_screenshot': raise AssertionError('Unexpected transport call')
                self.captured.append((self.y, self.popup))
                return {'content': [{'type': 'image', 'data': self.image}]}

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            png = root / 'native.png'
            visual.write_png(png, 1, 1, bytes([42, 23, 63, 255]))
            image = base64.b64encode(png.read_bytes()).decode()
            case = {'id': 'calendar', 'viewport': [400, 800], 'action': 'openNativeTooltip()'}
            output = root / 'capture'; output.mkdir()
            client = Client(image)
            visual.capture_tiles(client, case, output)
            state = json.loads((output / 'calendar.json').read_text())
            self.assertEqual((289.5, True), client.captured[0], 'Capture the actual open surface before scroll dismisses it')
            self.assertEqual([{'popup': True}], state['geometry'])
            self.assertEqual([[0, 289.5], [0, 0], [0, 289]], [tile['scroll'] for tile in state['tiles']],
                             'The interaction supplements complete top-to-bottom document coverage')
            self.assertEqual([(0, False), (289, False)], client.captured[1:],
                             'Later scrolling must keep the native dismissal behavior')

    def test_raster_rejects_interaction_lost_during_screenshot_and_preserves_failed_png(self):
        class Client(HoverClient):
            def __init__(self, image, failure):
                super().__init__()
                self.image, self.failure = image, failure
                self.hovered, self.surface, self.screenshots = True, True, 0

            def evaluate(self, function, **arguments):
                if 'Native hover was lost during capture' in function and not self.hovered:
                    raise RuntimeError('Native hover was lost during capture')
                if 'Missing native surface' in function and not self.surface:
                    raise RuntimeError('Missing native surface')
                if 'visualFixture.snapshot()' in function:
                    state = self.state()
                    state['document'] = [0, 0, 800]
                    return state
                return super().evaluate(function, **arguments)

            def call(self, name, **arguments):
                if name != 'take_screenshot':
                    raise AssertionError('Unexpected capture transport call')
                self.screenshots += 1
                if self.failure == 'hover': self.hovered = False
                if self.failure == 'surface': self.surface = False
                return {'content': [{'type': 'image', 'data': self.image}]}

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            png = root / 'native.png'
            visual.write_png(png, 1, 1, bytes([42, 23, 63, 255]))
            image = base64.b64encode(png.read_bytes()).decode()
            case = hover_case()
            case['capture_assert'] = "if (!document.querySelector('.native-surface')) throw new Error('Missing native surface')"
            for failure in (None, 'hover', 'surface'):
                with self.subTest(failure=failure):
                    output = root / str(failure); output.mkdir()
                    client = Client(image, failure)
                    if failure:
                        message = 'Native hover was lost' if failure == 'hover' else 'Missing native surface'
                        with self.assertRaisesRegex(RuntimeError, message):
                            visual.capture_tiles(client, case, output)
                        self.assertEqual([], list(output.glob('*.json')),
                                         'A dismissed interaction must not acquire completion records')
                    else:
                        visual.capture_tiles(client, case, output)
                        self.assertTrue((output / (case['id'] + '.json')).is_file())
                    self.assertEqual(png.read_bytes(), (output / (case['id'] + '.png')).read_bytes(),
                                     'Retain the actual PNG, including failed evidence')
                    self.assertEqual(1, client.screenshots, 'Never rehover or reshoot a dismissed interaction')

    def test_below_fold_pixel_and_missing_tile_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            suite = root / "suite.json"
            suite.write_text(json.dumps({"cases": [{"id": "comment"}]}))
            baseline, actual = root / "baseline", root / "actual"
            tiles = [{"stem": "comment", "scroll": [0, 0], "document_height": 3},
                     {"stem": "comment.tile-1", "scroll": [0, 1], "document_height": 3}]
            for folder in (baseline, actual):
                folder.mkdir()
                (folder / "capture.json").write_text(json.dumps({
                    "suite_sha256": hashlib.sha256(suite.read_bytes()).hexdigest(),
                    "pixels": True, "raster": visual.RASTER,
                    "approval": {"reviewer": "comparator test", "reference": "synthetic", "reason": "test"}}))
                for index, tile in enumerate(tiles):
                    state = {"environment": {}, "geometry": []}
                    if not index:
                        state["tiles"] = tiles
                    (folder / (tile["stem"] + ".json")).write_text(json.dumps(state))
                    visual.write_png(folder / (tile["stem"] + ".png"), 1, 2, bytes([42, 23, 63, 255] * 2))
            for record in baseline.glob("comment*.json"):
                record.with_suffix(".json.gz").write_bytes(gzip.compress(record.read_bytes(), mtime=0))
                record.unlink()
            args = argparse.Namespace(suite=suite, baseline=baseline, actual=actual, output=root / "report")
            self.assertEqual(0, visual.compare(args))
            duplicate = baseline / "comment.tile-1.json"
            duplicate.write_bytes(gzip.decompress((baseline / "comment.tile-1.json.gz").read_bytes()))
            self.assertEqual(1, visual.compare(args))
            self.assertIn("found 2", json.loads((args.output / "report.json").read_text())["cases"][0]["error"])
            duplicate.unlink()
            # Only the second, scrolled viewport changes; the initial image and
            # every measured element remain identical.
            visual.write_png(actual / "comment.tile-1.png", 1, 2, bytes([43, 23, 63, 255, 42, 23, 63, 255]))
            self.assertEqual(1, visual.compare(args))
            report = json.loads((args.output / "report.json").read_text())
            self.assertEqual(1, report["cases"][0]["pixels"]["changed_pixels"])
            self.assertIn("comment.tile-1.diff.png", (args.output / "index.html").read_text())
            (actual / "comment.tile-1.png").unlink()
            self.assertEqual(1, visual.compare(args))

    def test_identical_pixels_pass_but_one_changed_pixel_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = bytearray([42, 23, 63, 255] * 6)
            visual.write_png(root / "before.png", 3, 2, before)
            visual.write_png(root / "same.png", 3, 2, before)
            self.assertTrue(visual.compare_pixels(root / "before.png", root / "same.png", root / "diff.png")["passed"])
            before[16] = 43
            visual.write_png(root / "after.png", 3, 2, before)
            result = visual.compare_pixels(root / "same.png", root / "after.png", root / "diff.png")
            self.assertFalse(result["passed"])
            self.assertEqual(1, result["changed_pixels"])
            self.assertEqual([1, 1, 1, 1], result["changed_bounds"])
            self.assertEqual((3, 2), visual.read_png(root / "diff.png")[:2])
            self.assertTrue(visual.compare_pixels(root / "same.png", root / "same.png", root / "diff.png")["passed"])
            self.assertFalse((root / "diff.png").exists(), "A passing rerun must remove stale red difference imagery")

    def test_png_filters_and_crc(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "filtered.png"
            pixels = bytes([21, 65, 91, 255, 55, 69, 222, 128])
            for mode in range(5):
                # A single row still exercises left predictors and all filter tags.
                filtered = bytearray()
                for i, value in enumerate(pixels):
                    left = pixels[i - 4] if i >= 4 else 0
                    predictor = left if mode in (1, 4) else left // 2 if mode == 3 else 0
                    filtered.append((value - predictor) & 255)
                def chunk(kind, payload):
                    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", visual.binascii.crc32(kind + payload) & 0xffffffff)
                path.write_bytes(visual.SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 1, 8, 6, 0, 0, 0)) +
                                 chunk(b"IDAT", zlib.compress(bytes([mode]) + filtered)) + chunk(b"IEND", b""))
                self.assertEqual(pixels, visual.read_png(path)[2], f"filter {mode}")
            corrupt = bytearray(path.read_bytes())
            corrupt[45] ^= 1
            path.write_bytes(corrupt)
            with self.assertRaises(ValueError):
                visual.read_png(path)

    def test_geometry_rejects_subpixel_padding_and_missing_icons(self):
        before = {"elements": [{"padding": ["0px", "8px"], "box": [0, 0, 32, 32]}, {"class": "fa-briefcase"}]}
        after = {"elements": [{"padding": ["0px", "7px"], "box": [0, .125, 32, 32]}, {"class": ""}]}
        changes = visual.geometry_changes(before, after)
        self.assertEqual({"elements[0].padding[1]", "elements[0].box[1]", "elements[1].class"}, {change["path"] for change in changes})
        self.assertTrue(visual.geometry_changes(before, {"elements": before["elements"][:1]}))


if __name__ == "__main__":
    unittest.main()
