#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# Find the most recently modified JSONL in history/
JSONL=$(ls -t history/*.jsonl 2>/dev/null | head -1)

if [[ -z "$JSONL" ]]; then
    echo "postprocess: no JSONL found in history/, skipping"
    exit 0
fi

echo "postprocess: analyzing $JSONL"
python3 scripts/stats.py      "$JSONL"
python3 scripts/deployment.py "$JSONL"
python3 scripts/threats.py    "$JSONL"
