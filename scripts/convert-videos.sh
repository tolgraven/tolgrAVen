#!/bin/bash

# Convert videos to modern WebM formats (VP9 and AV1)
# Usage: ./scripts/convert-videos.sh [--force]

set -e

FORCE=false
if [[ "$1" == "--force" ]]; then
    FORCE=true
fi

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Counters
CONVERTED_VP9=0
CONVERTED_AV1=0
SKIPPED=0

echo -e "${BLUE}Starting video conversion to WebM (VP9 and AV1)...${NC}"
echo "=================================================================="

# Check if ffmpeg is available
if ! command -v ffmpeg &> /dev/null; then
    echo -e "${RED}Error: ffmpeg not found${NC}"
    echo "Install with: brew install ffmpeg"
    exit 1
fi

# Find all MP4 files
find resources/public -type f -iname "*.mp4" | while read -r video; do

    filename="${video%.*}"
    extension="${video##*.}"
    vp9_file="${filename}-vp9.webm"
    av1_file="${filename}-av1.webm"

    echo ""
    echo -e "${BLUE}Processing:${NC} $(basename "$video")"

    # Get video duration for progress estimation
    DURATION=$(ffprobe -v error -show_entries format=duration \
               -of default=noprint_wrappers=1:nokey=1 "$video" 2>/dev/null || echo "unknown")

    # Convert to VP9 WebM
    if [[ ! -f "$vp9_file" ]] || [[ "$FORCE" == true ]]; then
        echo -e "${GREEN}  → VP9 WebM${NC} (good compression, 95%+ browser support)"

        ffmpeg -i "$video" \
            -c:v libvpx-vp9 \
            -crf 30 \
            -b:v 0 \
            -cpu-used 2 \
            -row-mt 1 \
            -an \
            -y \
            "$vp9_file" 2>&1 | grep -E "(Duration|time=|size=)" | tail -1 || true

        if [[ -f "$vp9_file" ]]; then
            VP9_SIZE=$(ls -lh "$vp9_file" | awk '{print $5}')
            echo -e "${GREEN}  ✓ Created:${NC} $vp9_file (${VP9_SIZE})"
            ((CONVERTED_VP9++)) || true
        fi
    else
        echo -e "${YELLOW}  ⊘ Skipping VP9${NC} (exists): $vp9_file"
        ((SKIPPED++)) || true
    fi

    # Convert to AV1 WebM (slower but better compression)
    if [[ ! -f "$av1_file" ]] || [[ "$FORCE" == true ]]; then
        echo -e "${GREEN}  → AV1 WebM${NC} (best compression, 73%+ browser support)"
        echo -e "${YELLOW}    (This may take a while - AV1 encoding is slow)${NC}"

        # Using SVT-AV1 for faster encoding than libaom
        ffmpeg -i "$video" \
            -c:v libsvtav1 \
            -crf 35 \
            -preset 6 \
            -svtav1-params "fast-decode=1:tune=0" \
            -an \
            -y \
            "$av1_file" 2>&1 | grep -E "(Duration|time=|size=)" | tail -1 || true

        if [[ -f "$av1_file" ]]; then
            AV1_SIZE=$(ls -lh "$av1_file" | awk '{print $5}')
            echo -e "${GREEN}  ✓ Created:${NC} $av1_file (${AV1_SIZE})"
            ((CONVERTED_AV1++)) || true
        fi
    else
        echo -e "${YELLOW}  ⊘ Skipping AV1${NC} (exists): $av1_file"
        ((SKIPPED++)) || true
    fi

    # Show size comparison
    if [[ -f "$video" ]] && [[ -f "$vp9_file" ]] && [[ -f "$av1_file" ]]; then
        ORIG_SIZE=$(stat -f%z "$video" 2>/dev/null || stat -c%s "$video" 2>/dev/null)
        VP9_SIZE=$(stat -f%z "$vp9_file" 2>/dev/null || stat -c%s "$vp9_file" 2>/dev/null)
        AV1_SIZE=$(stat -f%z "$av1_file" 2>/dev/null || stat -c%s "$av1_file" 2>/dev/null)

        ORIG_MB=$(echo "scale=2; $ORIG_SIZE / 1048576" | bc)
        VP9_MB=$(echo "scale=2; $VP9_SIZE / 1048576" | bc)
        AV1_MB=$(echo "scale=2; $AV1_SIZE / 1048576" | bc)

        VP9_PERCENT=$(echo "scale=1; ($ORIG_SIZE - $VP9_SIZE) * 100 / $ORIG_SIZE" | bc)
        AV1_PERCENT=$(echo "scale=1; ($ORIG_SIZE - $AV1_SIZE) * 100 / $ORIG_SIZE" | bc)

        echo ""
        echo -e "${BLUE}  Size Comparison:${NC}"
        echo "    Original (H.264): ${ORIG_MB}MB"
        echo "    VP9:              ${VP9_MB}MB (${VP9_PERCENT}% smaller)"
        echo "    AV1:              ${AV1_MB}MB (${AV1_PERCENT}% smaller)"
    fi
done

echo ""
echo "=================================================================="
echo -e "${GREEN}Conversion complete!${NC}"
echo "VP9 files created: $CONVERTED_VP9"
echo "AV1 files created: $CONVERTED_AV1"
echo "Files skipped: $SKIPPED"
echo ""
echo -e "${BLUE}Browser Support:${NC}"
echo "  • VP9 WebM:  Chrome, Firefox, Edge, Safari 14.1+, Opera"
echo "  • AV1 WebM:  Chrome 90+, Firefox 93+, Edge 90+, Opera 76+"
echo "  • H.264 MP4: Universal fallback"
