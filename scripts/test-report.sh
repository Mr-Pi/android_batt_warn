#!/usr/bin/env bash
# Prints a per-test PASS/FAIL summary from the connected-test XML reports.
# Exits 0 silently when no report exists (e.g. compile failure).
set -euo pipefail

dir=app/build/outputs/androidTest-results/connected/debug
if [ ! -d "$dir" ]; then
    exit 0
fi

for f in "$dir"/*.xml; do
    [ -f "$f" ] || continue
    echo "--- $(basename "$f") ---"
    awk 'BEGIN { RS = "<testcase " } NR > 1 {
        n = ""; c = ""
        if (match($0, /name="[^"]*"/, m)) { n = substr(m[0], 7, length(m[0]) - 7) }
        if (match($0, /classname="[^"]*"/, m)) { c = substr(m[0], 12, length(m[0]) - 12) }
        s = ($0 ~ /<failure|<error|<skipped/) ? "FAIL" : "PASS";
        printf "  [%s] %s.%s\n", s, c, n;
    }' "$f" | sort
done

echo "--- totals ---"
grep -hoE 'tests="[0-9]+" failures="[0-9]+" errors="[0-9]+" skipped="[0-9]+"' "$dir"/*.xml
