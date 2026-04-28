#!/usr/bin/env bash
export BATTLEJAR_HISTORY_DIR="./history"
#export BATTLEJAR_API_URL="http://localhost:8888"
export BATTLEJAR_API_URL="https://api.battlejar.it"
set -euo pipefail
cd "$(dirname "$0")"
gradle shadowJar
java -jar build/commander.jar
./postprocess.sh
