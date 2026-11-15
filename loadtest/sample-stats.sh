#!/usr/bin/env bash
# Sample docker stats for the load-test stack every few seconds (run alongside a scenario).
#   loadtest/sample-stats.sh <outfile> [interval-sec]   (stop with Ctrl-C / kill)
out="${1:?outfile}"; every="${2:-5}"
while true; do
  echo "# $(date +%H:%M:%S)" >> "$out"
  docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' $(docker ps -q --filter label=com.docker.compose.project=rl-loadtest) >> "$out"
  sleep "$every"
done
