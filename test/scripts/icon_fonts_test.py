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
        for family, weight, codes in [("brands", 400, (0xF09B, 0xF1A0)),
                                     ("solid", 900, (0xF102, 0xF5AC))]:
            font = TTFont(icons.PUBLIC / "webfonts" / f"fa-{family}-{weight}.woff2")
            glyphs = font.getGlyphSet()
            for code in codes:
                name = font.getBestCmap()[code]
                svg, layout = icons.svg_icon(font, name)
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
                top, right, bottom, left = layout["margins"]
                font_height = font["hhea"].ascent - font["hhea"].descent
                self.assertAlmostEqual(layout["width"] + left + right,
                                       font["hmtx"][name][0] / font_height)
                self.assertAlmostEqual(layout["height"] + top + bottom, 1)
                self.assertEqual(layout["baseline"], font["hhea"].descent / font_height)

    def test_every_declared_outline_fits_its_viewport(self):
        css = (icons.PUBLIC / "css/fontawesome.css").read_text()
        mapping = {name: int(code, 16) for name, code in icons.re.findall(
            r'\.fa-([a-z0-9-]+):before\s*\{\s*content:\s*["\']\\([0-9a-f]+)', css
        )}
        outputs = icons.svg_outputs(mapping)
        checked = []
        for file, data in outputs.items():
            if file.suffix != ".svg":
                continue
            root = ElementTree.fromstring(data)
            x, y, width, height = map(float, root.attrib["viewBox"].split())
            pen = BoundsPen(None)
            parse_path(root[0].attrib["d"], pen)
            left, top, right, bottom = pen.bounds
            self.assertGreaterEqual(left, x - 0.001, file.name)
            self.assertGreaterEqual(top, y - 0.001, file.name)
            self.assertLessEqual(right, x + width + 0.001, file.name)
            self.assertLessEqual(bottom, y + height + 0.001, file.name)
            checked.append(file.stem)
        self.assertIn("angle-double-up", checked)
        self.assertIn("pen-fancy", checked)

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
