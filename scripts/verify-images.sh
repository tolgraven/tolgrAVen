#!/bin/bash

# Verify image conversion and show statistics

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}Image Optimization Verification${NC}"
echo "=================================="
echo ""

# Count files
ORIG_COUNT=$(find resources/public -type f \( -iname "*.jpg" -o -iname "*.jpeg" -o -iname "*.png" \) ! -name "*favicon*" ! -name "*chrome*" ! -name "*apple*" ! -name "*mstile*" | wc -l | tr -d ' ')
WEBP_COUNT=$(find resources/public -name "*.webp" | wc -l | tr -d ' ')
AVIF_COUNT=$(find resources/public -name "*.avif" | wc -l | tr -d ' ')

echo -e "${GREEN}File Counts:${NC}"
echo "  Original images: $ORIG_COUNT"
echo "  WebP files:      $WEBP_COUNT"
echo "  AVIF files:      $AVIF_COUNT"
echo ""

# Check for missing conversions
MISSING=0
find resources/public -type f \( -iname "*.jpg" -o -iname "*.jpeg" -o -iname "*.png" \) \
    ! -name "*favicon*" ! -name "*chrome*" ! -name "*apple*" ! -name "*mstile*" | while read -r img; do
    base="${img%.*}"
    if [[ ! -f "${base}.webp" ]] || [[ ! -f "${base}.avif" ]]; then
        if [[ $MISSING -eq 0 ]]; then
            echo -e "${YELLOW}Missing conversions:${NC}"
        fi
        echo "  - $img"
        MISSING=$((MISSING + 1))
    fi
done

if [[ $MISSING -eq 0 ]]; then
    echo -e "${GREEN}✓ All images have been converted!${NC}"
    echo ""
fi

# Calculate size savings
echo -e "${BLUE}Size Analysis:${NC}"
echo "=================================="

TOTAL_ORIG=0
TOTAL_WEBP=0
TOTAL_AVIF=0

for img in $(find resources/public -type f \( -iname "*.jpg" -o -iname "*.png" \) ! -name "*favicon*" ! -name "*chrome*" ! -name "*apple*" ! -name "*mstile*" | head -10); do
    base="${img%.*}"

    if [[ -f "$img" ]] && [[ -f "${base}.webp" ]] && [[ -f "${base}.avif" ]]; then
        SIZE_ORIG=$(stat -f%z "$img" 2>/dev/null || stat -c%s "$img" 2>/dev/null)
        SIZE_WEBP=$(stat -f%z "${base}.webp" 2>/dev/null || stat -c%s "${base}.webp" 2>/dev/null)
        SIZE_AVIF=$(stat -f%z "${base}.avif" 2>/dev/null || stat -c%s "${base}.avif" 2>/dev/null)

        TOTAL_ORIG=$((TOTAL_ORIG + SIZE_ORIG))
        TOTAL_WEBP=$((TOTAL_WEBP + SIZE_WEBP))
        TOTAL_AVIF=$((TOTAL_AVIF + SIZE_AVIF))

        # Calculate percentages
        WEBP_PERCENT=$(echo "scale=1; ($SIZE_ORIG - $SIZE_WEBP) * 100 / $SIZE_ORIG" | bc)
        AVIF_PERCENT=$(echo "scale=1; ($SIZE_ORIG - $SIZE_AVIF) * 100 / $SIZE_ORIG" | bc)

        BASENAME=$(basename "$img")
        SIZE_ORIG_KB=$((SIZE_ORIG / 1024))
        SIZE_WEBP_KB=$((SIZE_WEBP / 1024))
        SIZE_AVIF_KB=$((SIZE_AVIF / 1024))

        echo ""
        echo "  $BASENAME"
        echo "    Original: ${SIZE_ORIG_KB}KB"
        echo "    WebP:     ${SIZE_WEBP_KB}KB (${WEBP_PERCENT}% smaller)"
        echo "    AVIF:     ${SIZE_AVIF_KB}KB (${AVIF_PERCENT}% smaller)"
    fi
done

if [[ $TOTAL_ORIG -gt 0 ]]; then
    TOTAL_WEBP_PERCENT=$(echo "scale=1; ($TOTAL_ORIG - $TOTAL_WEBP) * 100 / $TOTAL_ORIG" | bc)
    TOTAL_AVIF_PERCENT=$(echo "scale=1; ($TOTAL_ORIG - $TOTAL_AVIF) * 100 / $TOTAL_AVIF" | bc)
    TOTAL_ORIG_MB=$(echo "scale=2; $TOTAL_ORIG / 1048576" | bc)
    TOTAL_WEBP_MB=$(echo "scale=2; $TOTAL_WEBP / 1048576" | bc)
    TOTAL_AVIF_MB=$(echo "scale=2; $TOTAL_AVIF / 1048576" | bc)

    echo ""
    echo "=================================="
    echo -e "${GREEN}Total Savings (sample):${NC}"
    echo "  Original: ${TOTAL_ORIG_MB}MB"
    echo "  WebP:     ${TOTAL_WEBP_MB}MB (${TOTAL_WEBP_PERCENT}% smaller)"
    echo "  AVIF:     ${TOTAL_AVIF_MB}MB (${TOTAL_AVIF_PERCENT}% smaller)"
fi

echo ""
echo "=================================="
echo -e "${GREEN}✓ Verification complete!${NC}"
