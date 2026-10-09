#!/usr/bin/env bash
# Convert bundled assets, or only the explicit JPG/PNG paths supplied by a caller.
set -euo pipefail

force=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --force) force=true; shift ;;
        --) shift; break ;;
        -*) echo "Usage: $0 [--force] [--] [image ...]" >&2; exit 2 ;;
        *) break ;;
    esac
done

converted=0
skipped=0
convert_image() {
    local img="$1" stem tmp
    case "$img" in
        *favicon*|*android-chrome*|*apple-touch-icon*|*mstile*) return ;;
    esac
    case "$img" in
        *.[jJ][pP][gG]|*.[jJ][pP][eE][gG]|*.[pP][nN][gG]) ;;
        *) echo "Unsupported image: $img" >&2; return 2 ;;
    esac
    [[ -f "$img" ]] || { echo "Missing image: $img" >&2; return 2; }
    stem="${img%.*}"
    for format in webp avif; do
        local target="$stem.$format"
        if [[ -f "$target" && "$force" == false && ! "$img" -nt "$target" ]]; then
            skipped=$((skipped + 1))
            continue
        fi
        # Publish only successful encodes, retaining a previous variant on failure.
        tmp=$(mktemp "$target.XXXXXX")
        if [[ "$format" == webp ]]; then
            if ! cwebp -quiet -q 85 -m 6 "$img" -o "$tmp"; then
                rm -f "$tmp"; return 1
            fi
        else
            # Ubuntu's packaged ImageMagick 6 uses `convert`; macOS uses `magick`.
            local imagemagick=magick
            command -v magick >/dev/null 2>&1 || imagemagick=convert
            if ! "$imagemagick" "$img" -quality 80 "avif:$tmp"; then
                rm -f "$tmp"; return 1
            fi
        fi
        chmod 644 "$tmp"
        mv -f "$tmp" "$target"
        converted=$((converted + 1))
    done
}

if [[ $# -gt 0 ]]; then
    for img in "$@"; do convert_image "$img"; done
else
    while IFS= read -r -d '' img; do
        convert_image "$img"
    done < <(find resources/public -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' \) -print0)
fi
echo "Image conversion: $converted variants created, $skipped current variants skipped."
