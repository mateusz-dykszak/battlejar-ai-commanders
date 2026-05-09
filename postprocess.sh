#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

shopt -s nullglob
jsonl_files=(history/*.jsonl)
shopt -u nullglob

if [[ ${#jsonl_files[@]} -eq 0 ]]; then
    echo "postprocess: no JSONL found in history/, skipping"
    exit 0
fi

for JSONL in "${jsonl_files[@]}"; do
    GAME_ID=$(basename "$JSONL" .jsonl)
    SENTINEL="local_history/${GAME_ID}.stats.json"

    if [[ -f "$SENTINEL" ]]; then
        echo "postprocess: $GAME_ID already processed, skipping"
        continue
    fi

    if ! head -1 "$JSONL" | grep -q '"Klaudiusz"'; then
        echo "postprocess: $GAME_ID skipping — Klaudiusz not in this game"
        continue
    fi

    echo "postprocess: analyzing $GAME_ID"
    python3 scripts/stats.py        "$JSONL"
    python3 scripts/deployment.py   "$JSONL"
    python3 scripts/threats.py      "$JSONL"
    python3 scripts/all_carriers.py "$JSONL"
    python3 scripts/phases.py       "$JSONL"
done
