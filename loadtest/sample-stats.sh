#!/usr/bin/env bash
# Sample the load-test stack while a scenario runs. Bounded and self-terminating by design:
#   loadtest/sample-stats.sh <outfile> [interval-sec=5] [max-sec=3600] [what=docker]
# what is a comma list of: docker (docker stats), redis (commandstats/memory/dbsize), jvm (gateway heap via actuator)
# Exits when max-sec has elapsed, when its parent shell is gone, or on TERM/INT. Never appends to an existing
# file (round 1 result files are never touched).
#
# Line formats (epoch in ms, host clock):
#   D <ms> <container> cpu=<pct> mem=<usage>
#   R <ms> evalsha=<calls> eval=<calls> used=<bytes> rss=<bytes> keys=<n>     (NA when Redis does not answer)
#   J <ms> gw1_heap=<bytes> gw1_live=<bytes> gw2_heap=<bytes> gw2_live=<bytes>
out="${1:?outfile}"; every="${2:-5}"; max="${3:-3600}"; what="${4:-docker}"
[ -e "$out" ] && { echo "refusing to overwrite $out" >&2; exit 1; }
parent=$PPID
end=$(( $(date +%s) + max ))
trap 'exit 0' TERM INT HUP

ms() { date +%s%3N; }

sample_docker() {
  local t; t=$(ms)
  docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' \
    $(docker ps -q --filter label=com.docker.compose.project=rl-loadtest) 2>/dev/null | sed "s/^/D $t /" >> "$out"
}

sample_redis() {
  local t; t=$(ms)
  local raw
  raw=$(docker exec rl-loadtest-redis-1 sh -c 'redis-cli info commandstats; redis-cli info memory; echo "keys:$(redis-cli dbsize)"' 2>/dev/null | tr -d '\r') || raw=""
  if [ -z "$raw" ]; then echo "R $t NA" >> "$out"; return; fi
  echo "$raw" | awk -v t="$t" '
    /^cmdstat_evalsha:/ { split($0, a, "[:=,]"); es = a[3] }
    /^cmdstat_eval:/    { split($0, a, "[:=,]"); ev = a[3] }
    /^used_memory:/     { split($0, a, ":"); used = a[2] }
    /^used_memory_rss:/ { split($0, a, ":"); rss = a[2] }
    /^keys:/            { split($0, a, ":"); keys = a[2] }
    END { printf "R %s evalsha=%d eval=%d used=%d rss=%d keys=%d\n", t, es, ev, used, rss, keys }' >> "$out"
}

metric() { # port, metric path
  curl -s -m 2 "http://localhost:$1/actuator/metrics/$2" | sed -n 's/.*"value":\([0-9.E+-]*\).*/\1/p'
}

sample_jvm() {
  local t; t=$(ms)
  echo "J $t gw1_heap=$(metric 18080 'jvm.memory.used?tag=area:heap') gw1_live=$(metric 18080 jvm.gc.live.data.size) gw2_heap=$(metric 18090 'jvm.memory.used?tag=area:heap') gw2_live=$(metric 18090 jvm.gc.live.data.size)" >> "$out"
}

: > "$out"
while [ "$(date +%s)" -lt "$end" ]; do
  kill -0 "$parent" 2>/dev/null || exit 0
  case ",$what," in *,redis,*) sample_redis ;; esac
  case ",$what," in *,jvm,*) sample_jvm ;; esac
  case ",$what," in *,docker,*) sample_docker ;; esac
  sleep "$every"
done
