#!/usr/bin/env bash
# Load-test runner.
#   loadtest/run.sh up                      build jars + images, start the stack (waits for healthy)
#   loadtest/run.sh run <k6-script> [k6 args...]
#        round 1 style: plain k6 run of loadtest/k6/<script>.js, output loadtest/results/<script>-<time>.txt
#   loadtest/run.sh r2 <scenario> [k6 -e args...]
#        round 2: every scenario by name, with samplers, log scan and fault injection where needed.
#        Result files are loadtest/results/r2-<scenario>*.{txt,json}; an existing file is never overwritten.
#        scenarios:
#          overhead-cold overhead-hot      scenarios 1+2 (RATE=3000 per scenario, 15 s warm-up + 60 s)
#          ramp-cold ramp-hot              scenario 3, gateway-1 only (START/STEP/STEPS/STEP_SEC env, see below)
#          accuracy                        scenario 4 (4a, 4b, 4c)
#          burst-cold burst-hot            scenario 5 (CAP=<per-node rps> required)
#          cardinality                     scenario 6 (100k + 1M ids, Redis/gateway memory snapshots, expiry wait)
#          hotkey                          scenario 7 (RATE=<rps> required: ~70% of per-node capacity)
#          fail-a fail-b fail-c            scenario 8 (Redis pause / control plane stop / rule change)
#          soak                            scenario 9 (PER_NODE=<rps per gateway> required, DURATION default 1200)
#   loadtest/run.sh stats                   one docker stats snapshot of the stack
#   loadtest/run.sh down                    tear the stack down and delete its volumes
#   loadtest/run.sh all                     up, then round 1 scenarios (overhead, ramp, accuracy)
# Environment knobs for r2: START STEP STEPS STEP_SEC (ramp), CAP, RATE, PER_NODE, DURATION, TAG (suffix for result names).
set -uo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1   # keep Git Bash from rewriting /scripts/... into a Windows path

COMPOSE=(docker compose -p rl-loadtest -f docker-compose.loadtest.yml)
RES=loadtest/results
mkdir -p "$RES"
PIDS=()
K6NAMES=()

# Nothing started here may outlive this script: samplers are killed, k6 containers removed.
cleanup() {
  local p n
  for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done
  for n in "${K6NAMES[@]:-}"; do [ -n "$n" ] && docker rm -f "$n" >/dev/null 2>&1; done
  docker unpause rl-loadtest-redis-1 >/dev/null 2>&1   # never leave Redis paused
  docker start rl-loadtest-control-plane-1 >/dev/null 2>&1
  return 0
}
trap cleanup EXIT INT TERM

fresh() { # refuse to write into an existing result file
  local f
  for f in "$@"; do [ -e "$f" ] && { echo "refusing to overwrite $f" >&2; exit 1; }; done
  return 0
}

ms() { date +%s%3N; }
iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }

up() {
  mvn -B -q -Dmaven.test.skip=true package || exit 1
  "${COMPOSE[@]}" build || exit 1
  "${COMPOSE[@]}" up -d --wait
}

# ---- samplers (bounded: max seconds is the last line of defence)
sampler() { # name interval max what
  bash loadtest/sample-stats.sh "$RES/r2-$1" "$2" "$3" "$4" &
  PIDS+=($!)
}

# ---- k6
k6_fg() { # tag script [args...]: foreground
  local tag="$1" script="$2"; shift 2
  fresh "$RES/r2-$tag.txt" "$RES/r2-$tag.json"
  local name="rl-k6-$tag"; K6NAMES+=("$name")
  "${COMPOSE[@]}" --profile k6 run --rm -T --name "$name" k6 run --quiet -e "OUT=/results/r2-$tag.json" "$@" "/scripts/$script.js" 2>&1 | tee "$RES/r2-$tag.txt"
  return "${PIPESTATUS[0]}"
}

k6_bg() { # tag script [args...]: background, output file polled by the caller
  local tag="$1" script="$2"; shift 2
  fresh "$RES/r2-$tag.txt" "$RES/r2-$tag.json"
  local name="rl-k6-$tag"; K6NAMES+=("$name")
  "${COMPOSE[@]}" --profile k6 run --rm -T --name "$name" k6 run --quiet -e "OUT=/results/r2-$tag.json" "$@" "/scripts/$script.js" > "$RES/r2-$tag.txt" 2>&1 &
  K6PID=$!
  PIDS+=($K6PID)
}

wait_t0() { # tag: waits for the "T0 <ms>" line; sets T0 (k6 clock, ms)
  local i line
  for i in $(seq 1 240); do
    line=$(grep -a -o 'T0 [0-9]*' "$RES/r2-$1.txt" 2>/dev/null | head -1)
    [ -n "$line" ] && { T0=${line#T0 }; return 0; }
    sleep 1
  done
  echo "k6 did not print T0" >&2; return 1
}

skew_ms() { # VM clock minus host clock (ms), measured around a docker exec
  local a v b
  a=$(ms); v=$(docker exec rl-loadtest-mongo-1 date +%s%3N); b=$(ms)
  echo $(( v - (a + b) / 2 ))
}

sleep_until_rel() { # seconds after T0 (k6 clock), using the measured skew
  local target=$(( T0 + $1 * 1000 - SKEW )) now
  now=$(ms)
  [ "$target" -gt "$now" ] && sleep "$(awk -v d=$(( target - now )) 'BEGIN{printf "%.3f", d/1000}')"
  return 0
}

event() { # tag name: append the event time (seconds after T0, k6 clock) to r2-<tag>-events.txt
  local a b
  a=$(ms); b=$(ms)
  echo "$2 t_rel_s=$(awk -v t=$(( (a + b) / 2 + SKEW - T0 )) 'BEGIN{printf "%.2f", t/1000}')" >> "$RES/r2-$1-events.txt"
}

# ---- gateway log scan
logscan() { # tag since-iso
  local out="$RES/r2-$1-logs.txt" g
  fresh "$out"
  for g in gateway-1 gateway-2; do
    local c="rl-loadtest-$g-1" l
    l=$(docker logs --since "$2" "$c" 2>&1)
    {
      echo "$g: redis_unavailable=$(echo "$l" | grep -c 'Redis unavailable') redis_recovered=$(echo "$l" | grep -c 'Redis recovered') leak=$(echo "$l" | grep -c 'LEAK:') oom=$(echo "$l" | grep -c 'OutOfMemoryError') errors=$(echo "$l" | grep -c ' ERROR ') restarts=$(docker inspect --format '{{.RestartCount}}' "$c") state=$(docker inspect --format '{{.State.Status}}' "$c")"
      echo "$l" | grep -E 'Redis unavailable|Redis recovered|LEAK:|OutOfMemoryError| ERROR ' | cut -c1-300 | head -8
    } >> "$out"
  done
  cat "$out"
}

# ---- snapshots (scenario 6)
snap() { # label file
  local t used rss keys line g
  line=$(docker exec rl-loadtest-redis-1 sh -c 'redis-cli info memory; echo "keys:$(redis-cli dbsize)"' | tr -d '\r' | awk -F: '/^used_memory:/{u=$2} /^used_memory_rss:/{r=$2} /^keys:/{k=$2} END{printf "used=%d rss=%d keys=%d", u, r, k}')
  line="$line"
  for g in 1 2; do
    local port=$((g == 1 ? 18080 : 18090))
    local heap live rssg
    heap=$(curl -s -m 3 "http://localhost:$port/actuator/metrics/jvm.memory.used?tag=area:heap" | sed -n 's/.*"value":\([0-9.E+-]*\).*/\1/p')
    live=$(curl -s -m 3 "http://localhost:$port/actuator/metrics/jvm.gc.live.data.size" | sed -n 's/.*"value":\([0-9.E+-]*\).*/\1/p')
    rssg=$(docker stats --no-stream --format '{{.MemUsage}}' "rl-loadtest-gateway-$g-1" | awk '{print $1}')
    line="$line gw${g}_heap=$heap gw${g}_live=$live gw${g}_rss=$rssg"
  done
  echo "$1 t=$(ms) $line" | tee -a "$2"
}

# ---- scenarios
r2() {
  local sc="${1:?scenario}"; shift
  local suffix="${TAG:+-$TAG}"
  local since; since=$(iso)
  case "$sc" in
    overhead-cold|overhead-hot)
      local v=${sc#overhead-} tag="overhead-${sc#overhead-}${suffix}"
      sampler "$tag-docker.txt" 5 300 docker
      k6_fg "$tag" overhead -e "VARIANT=$v" -e RATE="${RATE:-3000}" -e DURATION=60 "$@"
      ;;
    ramp-cold|ramp-hot)
      local v=${sc#ramp-} tag="${sc}${suffix}"
      local steps="${STEPS:-10}" sec="${STEP_SEC:-30}"
      sampler "$tag-docker.txt" 5 $(( 120 + steps * sec + 120 )) docker
      sampler "$tag-redis.txt" 1 $(( 120 + steps * sec + 120 )) redis
      k6_fg "$tag" ramp -e "VARIANT=$v" -e START="${START:-5000}" -e STEP="${STEP:-500}" -e STEPS="$steps" -e STEP_SEC="$sec" "$@"
      ;;
    accuracy)
      local tag="accuracy${suffix}"
      k6_fg "$tag" accuracy "$@"
      ;;
    burst-cold|burst-hot)
      : "${CAP:?CAP=<per-node capacity rps> required}"
      local tag="${sc}${suffix}"
      sampler "$tag-docker.txt" 2 300 docker
      sampler "$tag-redis.txt" 1 300 redis
      k6_fg "$tag" burst -e "MIX=${sc#burst-}" -e CAP="$CAP" "$@"
      ;;
    hotkey)
      : "${RATE:?RATE=<rps, about 70% of per-node capacity> required}"
      local tag="hotkey${suffix}"
      sampler "$tag-docker.txt" 5 300 docker
      sampler "$tag-redis.txt" 1 300 redis
      k6_fg "$tag" hotkey -e RATE="$RATE" "$@"
      ;;
    cardinality) cardinality "$@" ;;
    fail-a|fail-b|fail-c) failure "$sc" "$@" ;;
    soak) soak "$@" ;;
    *) echo "unknown scenario: $sc" >&2; return 1 ;;
  esac
  local rc=$?
  case "$sc" in cardinality|fail-*|soak) ;; *) logscan "${sc}${suffix}" "$since" ;; esac
  return $rc
}

cardinality() {
  local tag="cardinality${TAG:+-$TAG}" since; since=$(iso)
  local snaps="$RES/r2-$tag-snaps.txt"
  fresh "$snaps"
  sampler "$tag-redis.txt" 5 2400 redis
  sampler "$tag-docker.txt" 5 2400 docker
  sleep 6
  snap start "$snaps"
  k6_fg "$tag-100k-long" cardinality -e IDS=100000 -e RULE=long -e RATE=8000
  snap after_100k_long "$snaps"
  sleep 15
  snap after_100k_long_plus15s "$snaps"
  k6_fg "$tag-1m-long" cardinality -e IDS=1000000 -e RULE=long -e RATE=8000
  snap after_1m_long "$snaps"
  sleep 15
  snap after_1m_long_plus15s "$snaps"
  k6_fg "$tag-100k-short" cardinality -e IDS=100000 -e RULE=short -e RATE=8000
  snap after_100k_short "$snaps"
  sleep 15
  snap after_100k_short_plus15s "$snaps"
  # long-TTL keys (300 s) must now expire by themselves: wait until Redis is empty (max 12 min)
  local i k
  for i in $(seq 1 72); do
    k=$(docker exec rl-loadtest-redis-1 redis-cli dbsize | tr -d '\r')
    echo "waiting for expiry: dbsize=$k"
    [ "$k" -le 100 ] && break
    sleep 10
  done
  sleep 20
  snap final_after_expiry "$snaps"
  logscan "$tag" "$since"
}

failure() {
  local sc="$1"; shift
  local mode=${sc#fail-} tag="$sc${TAG:+-$TAG}" since; since=$(iso)
  fresh "$RES/r2-$tag-events.txt"
  local tf="${T_FAULT:-40}" fs
  case "$mode" in a) fs=${FAULT_SEC:-15} ;; b) fs=${FAULT_SEC:-20} ;; c) fs=0 ;; esac
  sampler "$tag-docker.txt" 5 400 docker
  sampler "$tag-redis.txt" 1 400 redis
  k6_bg "$tag" failures -e "MODE=$mode" -e T_FAULT="$tf" -e FAULT_SEC="$fs" "$@"
  wait_t0 "$tag" || return 1
  SKEW=$(skew_ms)
  echo "skew_ms(vm-host)=$SKEW T0=$T0" >> "$RES/r2-$tag-events.txt"
  sleep_until_rel "$tf"
  case "$mode" in
    a)
      event "$tag" pause_issued
      docker pause rl-loadtest-redis-1 >/dev/null
      event "$tag" pause_returned
      sleep_until_rel $(( tf + fs ))
      event "$tag" unpause_issued
      docker unpause rl-loadtest-redis-1 >/dev/null
      event "$tag" unpause_returned
      ;;
    b)
      event "$tag" cp_stop_issued
      docker stop -t 2 rl-loadtest-control-plane-1 >/dev/null
      event "$tag" cp_stop_returned
      sleep_until_rel $(( tf + fs ))
      event "$tag" cp_start_issued
      docker start rl-loadtest-control-plane-1 >/dev/null
      event "$tag" cp_start_returned
      ;;
    c) : ;; # the rule change is made by the k6 script itself (CHANGE_START/CHANGE_DONE in the log)
  esac
  wait "$K6PID"
  local rc=$?
  tail -n 30 "$RES/r2-$tag.txt" | grep -a -v '^$' | head -40
  cat "$RES/r2-$tag-events.txt"
  grep -a 'CHANGE_' "$RES/r2-$tag.txt" || true
  if [ "$mode" = a ]; then docker unpause rl-loadtest-redis-1 >/dev/null 2>&1; fi
  logscan "$tag" "$since"
  return $rc
}

soak() {
  local tag="soak${TAG:+-$TAG}" since; since=$(iso)
  : "${PER_NODE:?PER_NODE=<rps per gateway> required}"
  local d="${DURATION:-1200}"
  sampler "$tag-samples.txt" 30 $(( d + 300 )) docker,redis,jvm
  k6_fg "$tag" soak -e RATE="$PER_NODE" -e DURATION="$d" "$@"
  logscan "$tag" "$since"
}

# ---- round 1 style entry points
run() {
  local name="$1"; shift
  [ -f "loadtest/k6/$name.js" ] || { echo "no such scenario: $name" >&2; exit 1; }
  "${COMPOSE[@]}" --profile k6 run --rm k6 run "$@" "/scripts/$name.js" \
    | tee "$RES/$name-$(date +%Y%m%d-%H%M%S).txt"
}

case "${1:-}" in
  up) up ;;
  run) shift; run "$@" ;;
  r2) shift; r2 "$@" ;;
  stats) docker stats --no-stream $("${COMPOSE[@]}" ps -q) ;;
  down) "${COMPOSE[@]}" --profile k6 down -v ;;
  all) up; run overhead; run ramp; run accuracy ;;
  *) sed -n '2,24p' "$0"; exit 1 ;;
esac
