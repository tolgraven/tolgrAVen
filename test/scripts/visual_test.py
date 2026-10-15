"""The visual gate must reject a real one-pixel/padding/missing-element change."""
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

SPEC = importlib.util.spec_from_file_location("visual", Path(__file__).parents[2] / "scripts/test/visual.py")
visual = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(visual)


class VisualTest(unittest.TestCase):
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
