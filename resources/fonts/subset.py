"""Backend Font Awesome subset codec. Inputs are private files supplied by the JVM."""
import io
import json
import sys
from pathlib import Path
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import DecomposingRecordingPen


def subset_font(source_file, points, kind):
    font = TTFont(source_file, recalcTimestamp=False, recalcBBoxes=False)
    cmap = dict(font.getBestCmap())
    core = set(points)
    target = f"fa-{kind}-subset.woff2"
    if not core or not core <= cmap.keys():
        raise ValueError("Requested icons are absent from their font family")
    options = subset.Options()
    options.name_IDs = ["*"]
    options.glyph_names = True
    options.recalc_timestamp = False
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=core)
    subsetter.subset(font)
    # These are modified fonts; preserve copyright/license names and give
    # the derivative its own internal family/PostScript/unique identifiers.
    for record in font["name"].names:
        if record.nameID in {1, 3, 4, 6, 16}:
            value = f"tolgrAVen UI {kind.title()}"
            if record.nameID == 6:
                value = value.replace(" ", "")
            record.string = value.encode(record.getEncoding())
    font.flavor = "woff2"
    data = io.BytesIO()
    font.save(data)
    data = data.getvalue()
    generated = TTFont(io.BytesIO(data))
    if set(generated.getBestCmap()) != core:
        raise ValueError(f"Unexpected glyph coverage in {target}")
    source = TTFont(source_file)
    before, after = source.getGlyphSet(), generated.getGlyphSet()
    for code, name in generated.getBestCmap().items():
        old_name = cmap[code]
        old_pen, new_pen = DecomposingRecordingPen(before), DecomposingRecordingPen(after)
        before[old_name].draw(old_pen)
        after[name].draw(new_pen)
        if (old_pen.value != new_pen.value
                or source["hmtx"][old_name] != generated["hmtx"][name]):
            raise ValueError(f"Glyph geometry or metrics changed: {target}, U+{code:X}")
    return data


def main():
    directory = Path(sys.argv[1])
    plan = json.loads((directory / "selection.json").read_text())
    for kind, weight in [("solid", 900), ("brands", 400)]:
        data = subset_font(directory / f"fa-{kind}-{weight}.woff2", plan[kind], kind)
        (directory / f"{kind}.woff2").write_bytes(data)


if __name__ == "__main__":
    main()
