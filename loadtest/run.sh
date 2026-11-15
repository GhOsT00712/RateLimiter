#!/usr/bin/env bash
# Load-test runner.
#   loadtest/run.sh up                 build jars + images, start the stack (waits for healthy)
#   loadtest/run.sh run <scenario> [k6 -e args...]
#        scenario: overhead | ramp | accuracy   (e.g. run overhead -e RATE=3000)
#   loadtest/run.sh stats              one docker stats snapshot of the stack
#   loadtest/run.sh down               tear the stack down and delete its volumes
#   loadtest/run.sh all                up, then overhead, ramp, accuracy
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1   # keep Git Bash from rewriting /scripts/... into a Windows path

COMPOSE=(docker compose -p rl-loadtest -f docker-compose.loadtest.yml)
mkdir -p loadtest/results

up() {
  mvn -B -q -DskipTests package
  "${COMPOSE[@]}" build
  "${COMPOSE[@]}" up -d --wait
}

run() {
  local name="$1"; shift
  [ -f "loadtest/k6/$name.js" ] || { echo "no such scenario: $name" >&2; exit 1; }
  # summary goes to loadtest/results/ (written by handleSummary); stdout is also kept
  "${COMPOSE[@]}" --profile k6 run --rm k6 run "$@" "/scripts/$name.js" \
    | tee "loadtest/results/$name-$(date +%Y%m%d-%H%M%S).txt"
}

case "${1:-}" in
  up) up ;;
  run) shift; run "$@" ;;
  stats) docker stats --no-stream $("${COMPOSE[@]}" ps -q) ;;
  down) "${COMPOSE[@]}" --profile k6 down -v ;;
  all) up; run overhead; run ramp; run accuracy ;;
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac
