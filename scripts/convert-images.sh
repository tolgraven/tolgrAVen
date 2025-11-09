#!/bin/bash

# Convert images to WebP and AVIF formats
# Usage: ./scripts/convert-images.sh [--force]

set -e

FORCE=false
if [[ "$1" == "--force" ]]; then
    FORCE=true
fi

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Counters
CONVERTED_WEBP=0
CONVERTED_AVIF=0
SKIPPED=0

echo "Starting image conversion to WebP and AVIF..."
echo "=============================================="

# Find all JPG and PNG files, excluding already converted ones
find resources/public -type f \( -iname "*.jpg" -o -iname "*.jpeg" -o -iname "*.png" \) \
    ! -name "*.webp" ! -name "*.avif" | while read -r img; do

    # Skip favicons and tiny icons (they're already optimized)
    if [[ "$img" =~ (favicon|android-chrome|apple-touch-icon|mstile) ]]; then
        echo -e "${YELLOW}Skipping${NC} $img (favicon/icon)"
        ((SKIPPED++)) || true
        continue
    fi

    # Get file info
    filename="${img%.*}"
    extension="${img##*.}"
    webp_file="${filename}.webp"
    avif_file="${filename}.avif"

    # Convert to WebP
    if [[ ! -f "$webp_file" ]] || [[ "$FORCE" == true ]]; then
        echo -e "${GREEN}Converting to WebP:${NC} $img"
        cwebp -q 85 -m 6 "$img" -o "$webp_file" 2>/dev/null
        ((CONVERTED_WEBP++)) || true
    else
        echo -e "${YELLOW}Skipping WebP${NC} (exists): $webp_file"
        ((SKIPPED++)) || true
    fi

    # Convert to AVIF
    if [[ ! -f "$avif_file" ]] || [[ "$FORCE" == true ]]; then
        echo -e "${GREEN}Converting to AVIF:${NC} $img"
        magick "$img" -quality 80 "$avif_file" 2>/dev/null
        ((CONVERTED_AVIF++)) || true
    else
        echo -e "${YELLOW}Skipping AVIF${NC} (exists): $avif_file"
        ((SKIPPED++)) || true
    fi

done

echo ""
echo "=============================================="
echo -e "${GREEN}Conversion complete!${NC}"
echo "WebP files created: $CONVERTED_WEBP"
echo "AVIF files created: $CONVERTED_AVIF"
echo "Files skipped: $SKIPPED"
