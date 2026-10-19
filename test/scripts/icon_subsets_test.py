"""Backend subsets contain exactly the requested glyphs, including dynamic additions."""
import importlib.util
import io
import json
from pathlib import Path
import unittest
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("icon_subset", ROOT / "resources/fonts/subset.py")
codec = importlib.util.module_from_spec(spec)
spec.loader.exec_module(codec)


class RuntimeSubsetTest(unittest.TestCase):
    def test_exact_coverage_metrics_and_repeated_input(self):
        declared = json.loads((ROOT / "resources/icon-subsets.json").read_text())
        for kind, weight in [("solid", 900), ("brands", 400)]:
            source = ROOT / f"resources/public/webfonts/fa-{kind}-{weight}.woff2"
            original = TTFont(source)
            baseline = set(declared[kind])
            additional = next(code for code in original.getBestCmap() if code not in baseline)
            for points in [baseline, baseline | {additional}]:
                data = codec.subset_font(source, points, kind)
                subset = TTFont(io.BytesIO(data))
                self.assertEqual(points, set(subset.getBestCmap()))
                self.assertEqual(data, codec.subset_font(source, reversed(sorted(points)), kind))
                self.assertLess(len(data), source.stat().st_size / 4)
                self.assertEqual(original["hhea"].ascent, subset["hhea"].ascent)
                self.assertEqual(original["hhea"].descent, subset["hhea"].descent)
                self.assertTrue(any(n.nameID == 0 for n in subset["name"].names))

    def test_invalid_family_glyph_and_empty_selection_fail(self):
        source = ROOT / "resources/public/webfonts/fa-solid-900.woff2"
        for points in [[], [1], [0xF09B]]:
            with self.assertRaises(ValueError):
                codec.subset_font(source, points, "solid")


if __name__ == "__main__":
    unittest.main()
