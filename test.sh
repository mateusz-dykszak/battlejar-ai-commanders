#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env"

if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
else
    echo "Warning: $ENV_FILE not found, relying on environment variables already set" >&2
fi

export BATTLEJAR_HISTORY_DIR="./history"
export BATTLEJAR_API_URL="https://api.battlejar.it"

cd "$SCRIPT_DIR"
./gradlew test --tests "it.battlejar.commander.ai.AIAgentTest"
