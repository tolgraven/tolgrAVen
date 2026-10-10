"""Validate exported geometry against the original font and module ownership."""
import importlib.util
import unittest
from unittest.mock import patch
from xml.etree import ElementTree
from fontTools.pens.boundsPen import BoundsPen
from fontTools.svgLib.path import parse_path
from fontTools.ttLib import TTFont
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("icon_fonts", ROOT / "scripts/media/icon_fonts.py")
icons = importlib.util.module_from_spec(spec)
spec.loader.exec_module(icons)


class SvgIconsTest(unittest.TestCase):
    def test_geometry_and_baseline_match_original_glyphs(self):
        font = TTFont(icons.PUBLIC / "webfonts/fa-brands-400.woff2")
        glyphs = font.getGlyphSet()
        for code in (0xF09B, 0xF1A0):  # GitHub and Google: asymmetric outlines
            name = font.getBestCmap()[code]
            svg, ratio, baseline = icons.svg_icon(font, name)
            root = ElementTree.fromstring(svg)
            width, height = map(float, root.attrib["viewBox"].split()[2:])
            original = BoundsPen(glyphs)
            glyphs[name].draw(original)
            exported = BoundsPen(None)
            parse_path(root[0].attrib["d"], exported)
            left, bottom, right, top = original.bounds
            expected = (left, font["hhea"].ascent - top, right, font["hhea"].ascent - bottom)
            for actual, wanted in zip(exported.bounds, expected):
                self.assertAlmostEqual(actual, wanted, places=3)
            self.assertEqual(width, font["hmtx"][name][0])
            self.assertEqual(ratio, width / height)
            self.assertEqual(baseline, font["hhea"].descent / height)

    def test_module_masks_do_not_need_font_or_svg_requests(self):
        with patch.object(icons, "icon_definitions", return_value={"main": ["brands/github"], "user": ["brands/google"]}):
            outputs = icons.svg_outputs({"github": 0xF09B, "google": 0xF1A0})
        common = outputs[ROOT / "resources/scss/generated/icons/_main.scss"].decode()
        feature = outputs[ROOT / "resources/scss/generated/icons/_user.scss"].decode()
        self.assertIn(".fab.fa-github::before", common)
        self.assertNotIn("fa-google", common)
        self.assertIn(".fab.fa-google::before", feature)
        self.assertIn('content: ""', feature)
        self.assertIn("font-family: inherit", feature)
        self.assertIn("data:image/svg+xml,", feature)
        self.assertNotRegex(feature, r"url\([^)]*(?:woff|/img/)")
        self.assertNotIn("px", common + feature)

    def test_wrong_font_family_fails(self):
        with patch.object(icons, "icon_definitions", return_value={"main": ["solid/github"]}):
            with self.assertRaisesRegex(ValueError, "declared font family"):
                icons.svg_outputs({"github": 0xF09B})


if __name__ == "__main__":
    unittest.main()
