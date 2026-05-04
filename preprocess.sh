#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# Remove history files older than 1 day that have already been analyzed
# (i.e. their stats.json sentinel exists in local_history/).
# Unanalyzed files are kept regardless of age so postprocess.sh can still process them.
shopt -s nullglob
for JSONL in history/*.jsonl; do
    GAME_ID=$(basename "$JSONL" .jsonl)
    SENTINEL="local_history/${GAME_ID}.stats.json"
    if [[ -f "$SENTINEL" ]] && [[ $(find "$JSONL" -mtime +0) ]]; then
        echo "preprocess: removing old analyzed game $GAME_ID"
        rm -f history/"${GAME_ID}".jsonl history/"${GAME_ID}".log history/"${GAME_ID}".md
    fi
done
shopt -u nullglob

# Remove local_history files whose source game no longer exists in history/.
for F in local_history/*; do
    GAME_ID=$(basename "$F" | sed 's/\..*//')
    if [[ ! -f "history/${GAME_ID}.jsonl" ]]; then
        echo "preprocess: removing orphaned local_history file $(basename "$F")"
        rm -f "$F"
    fi
done
