#!/usr/bin/env bash
# Converts every sample through the running service and prints the result per file.
# Usage: scripts/convert-samples.sh [base-url] [output] [out-dir]
#   base-url  default http://localhost:8080
#   output    pdf, pdfa-1b, pdfa-2b, pdfa-2u or pdfa-3b (default pdfa-2b)
#   out-dir   where the PDFs are written (default ./converted)
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
OUTPUT="${2:-pdfa-2b}"
OUT_DIR="${3:-converted}"
SAMPLES="$(cd "$(dirname "$0")/../samples" && pwd)"
mkdir -p "$OUT_DIR"
HEADERS="$(mktemp)"
trap 'rm -f "$HEADERS"' EXIT

header() { grep -i "^$1:" "$HEADERS" | cut -d' ' -f2- | tr -d '\r'; }

printf "%-34s %-6s %6s %10s %10s %s\n" "Input" "HTTP" "Pages" "SDK ms" "Total ms" "PDF/A valid"
for file in quarterly-facilities-report.docx maintenance-budget.xlsx project-kickoff.pptx floor-plan.png visitor-notice.html; do
  target="$OUT_DIR/${file%.*}.pdf"
  result=$(curl -s -o "$target" -D "$HEADERS" -w "%{http_code} %{time_total}" \
    -F "files=@$SAMPLES/$file" -F "output=$OUTPUT" "$BASE_URL/api/convert")
  code=${result% *}
  total=$(awk -v t="${result#* }" 'BEGIN { printf "%d", t * 1000 }')
  printf "%-34s %-6s %6s %10s %10s %s\n" "$file" "$code" "$(header X-Page-Count)" \
    "$(header X-Conversion-Time-Ms)" "$total" "$(header X-PdfA-Valid)"
done

result=$(curl -s -o "$OUT_DIR/merged.pdf" -D "$HEADERS" -w "%{http_code} %{time_total}" \
  -F "files=@$SAMPLES/quarterly-facilities-report.docx" -F "files=@$SAMPLES/maintenance-budget.xlsx" \
  -F "files=@$SAMPLES/project-kickoff.pptx" -F "files=@$SAMPLES/floor-plan.png" \
  -F "files=@$SAMPLES/visitor-notice.html" -F "merge=true" -F "output=$OUTPUT" "$BASE_URL/api/convert")
total=$(awk -v t="${result#* }" 'BEGIN { printf "%d", t * 1000 }')
printf "%-34s %-6s %6s %10s %10s %s\n" "all five, merged" "${result% *}" "$(header X-Page-Count)" \
  "$(header X-Conversion-Time-Ms)" "$total" "$(header X-PdfA-Valid)"
echo "PDFs written to $OUT_DIR"
