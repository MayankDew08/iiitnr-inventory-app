#!/usr/bin/env bash
#
# Local quality gate. Same coverage as CI's pull-request jobs, run concurrently.
#
# Output is quiet by default: a single line when everything passes, and the log
# of only the steps that failed otherwise. Use -v to see every step.
#
#   ./bin/check.sh --fast   # every CI gate except the DB-backed backend tests
#   ./bin/check.sh          # the full pre-push gate
#   ./bin/check.sh --full   # + the WasmJs bundle that deploy-pages ships
#   ./bin/check.sh -v       # show per-step timings and output
#
# Concurrency is capped at CHECK_JOBS units (default 2). Each unit is a whole
# toolchain process, and these machines are usually memory-bound long before
# they are core-bound: Gradle alone is allowed -Xmx6g, so fanning out to five
# concurrent JVM/Node processes swaps the box and a 9s run becomes a 5min run.

set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

MODE="check"
VERBOSE=${CHECK_VERBOSE:-0}
JOBS=${CHECK_JOBS:-2}
TEST_DB_URL="postgresql://postgres:postgres@localhost:5432/iiitnr_inventory_test"

usage() {
    # Print the header block: comment lines after the shebang, stop at code.
    awk 'NR > 2 && /^#/ { sub(/^# ?/, ""); print; next } NR > 2 { exit }' "${BASH_SOURCE[0]}"
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --fast) MODE="check-fast" ;;
        --full) MODE="check-full" ;;
        -v | --verbose) VERBOSE=1 ;;
        -h | --help) usage; exit 0 ;;
        *)
            printf 'unknown option: %s\n\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

((JOBS < 1)) && JOBS=1

if [[ -t 1 && -z ${NO_COLOR:-} ]]; then
    C_OK=$'\033[32m' C_BAD=$'\033[31m' C_DIM=$'\033[2m' C_OFF=$'\033[0m'
else
    C_OK="" C_BAD="" C_DIM="" C_OFF=""
fi

now_ms() {
    if [[ -n ${EPOCHREALTIME:-} ]]; then
        local t=${EPOCHREALTIME/[.,]/}
        printf '%s' $((t / 1000))
    else
        # bash < 5: second granularity is close enough for a summary line.
        printf '%s' $(($(date +%s) * 1000))
    fi
}

fmt_ms() {
    local ms=$1
    if ((ms < 60000)); then
        printf '%d.%01ds' $((ms / 1000)) $(((ms % 1000) / 100))
    else
        printf '%dm%02ds' $((ms / 60000)) $(((ms % 60000) / 1000))
    fi
}

# --- individual checks -------------------------------------------------------

check_typecheck() {
    (cd backend && bun run typecheck)
}

check_eslint() {
    (cd backend && bun run lint)
}

check_prettier() {
    (cd backend && bun run format:check)
}

check_schema() {
    (cd backend && bun run scripts/generate-schema-docs.ts --check)
}

# --- units -------------------------------------------------------------------
# A unit is one process group. Units run concurrently (up to $JOBS); checks
# inside a unit run one after another, so only one Node process exists at a time.

unit_backend-static() {
    local rc=0
    : >"$LOGDIR/sub.backend-static"
    for c in typecheck eslint prettier schema; do
        local t0
        t0=$(now_ms)
        printf '\n── %s\n' "$c"
        "check_${c}" || rc=1
        printf '%s %s\n' "$c" $(($(now_ms) - t0)) >>"$LOGDIR/sub.backend-static"
    done
    return $rc
}

unit_gradle() {
    local tasks=(ktlintCheck detekt :shared:jvmTest :android:assembleDebug)
    if [[ $MODE == "check-full" ]]; then
        tasks+=(":web:wasmJsBrowserDistribution" "-Pkotlin.native.ignoreDisabledTargets=true")
    fi
    # One Gradle invocation: separate concurrent ./gradlew processes contend on
    # the same cache locks and come out slower than this. Cross-project
    # --parallel is opt-in, because with -Xmx6g it is the fastest way to swap a
    # 8GB machine.
    local extra=()
    [[ ${CHECK_GRADLE_PARALLEL:-0} == 1 ]] && extra+=(--parallel)
    (cd app && ./gradlew "${tasks[@]}" "${extra[@]}" --console=plain)
}

unit_backend-test() {
    (
        cd backend || exit 1
        podman compose -f compose.db.yaml --profile test up -d || exit 1
        local rc=0
        TEST_DATABASE_URL="$TEST_DB_URL" bun test --parallel=1 || rc=$?
        podman compose -f compose.db.yaml --profile test down >/dev/null 2>&1
        exit $rc
    )
}

UNITS=(backend-static gradle)
[[ $MODE != "check-fast" ]] && UNITS+=(backend-test)

# --- dependency install ------------------------------------------------------
# Before the parallel section, so bun is not writing node_modules while tsc and
# eslint are reading it.

LOGDIR=$(mktemp -d)
trap 'rm -rf "$LOGDIR"' EXIT

if ! (cd backend && bun install) >"$LOGDIR/install.log" 2>&1; then
    printf '%s✗%s bun install failed\n\n' "$C_BAD" "$C_OFF"
    cat "$LOGDIR/install.log"
    exit 1
fi

# --- run ---------------------------------------------------------------------

declare -A START_MS
declare -a LAUNCHED

for unit in "${UNITS[@]}"; do
    while (($(jobs -rp | wc -l) >= JOBS)); do
        wait -n 2>/dev/null || true
    done
    START_MS[$unit]=$(now_ms)
    LAUNCHED+=("$unit")
    (
        "unit_${unit}" >"$LOGDIR/${unit}.log" 2>&1
        printf '%s' $? >"$LOGDIR/${unit}.rc"
        printf '%s' "$(now_ms)" >"$LOGDIR/${unit}.end"
    ) &
done

wait

# --- report ------------------------------------------------------------------

unit_start() {
    printf '%s' "${START_MS[$1]}"
}

unit_end() {
    cat "$LOGDIR/$1.end" 2>/dev/null || now_ms
}

unit_elapsed() {
    fmt_ms $(($(unit_end "$1") - $(unit_start "$1")))
}

unit_rc() {
    cat "$LOGDIR/$1.rc" 2>/dev/null || echo 1
}

# Wall clock: what the user actually waited for, not the sum of the steps.
total_start=0
latest_end=0
for unit in "${UNITS[@]}"; do
    start=$(unit_start "$unit")
    end=$(unit_end "$unit")
    ((total_start == 0 || start < total_start)) && total_start=$start
    ((end > latest_end)) && latest_end=$end
done

failed=0
for unit in "${UNITS[@]}"; do
    [[ $(unit_rc "$unit") == "0" ]] || ((failed++))
done

elapsed=$((latest_end - total_start))
count=${#UNITS[@]}

print_unit_detail() {
    local unit=$1
    printf '  %s✓%s %-15s %s\n' "$C_OK" "$C_OFF" "$unit" "$(unit_elapsed "$unit")"
    if [[ -f $LOGDIR/sub.${unit} ]]; then
        while read -r name ms; do
            printf '     %s%-24s %s%s\n' "$C_DIM" "$name" "$(fmt_ms "$ms")" "$C_OFF"
        done <"$LOGDIR/sub.${unit}"
    fi
}

if ((failed == 0)); then
    if ((VERBOSE)); then
        for unit in "${UNITS[@]}"; do print_unit_detail "$unit"; done
        printf '\n'
    fi
    printf '%s✓%s %s passed (%d steps, %s)\n' \
        "$C_OK" "$C_OFF" "$MODE" "$count" "$(fmt_ms "$elapsed")"
    exit 0
fi

for unit in "${UNITS[@]}"; do
    if [[ $(unit_rc "$unit") != "0" ]]; then
        printf '%s✗%s %s (%s)\n' "$C_BAD" "$C_OFF" "$unit" "$(unit_elapsed "$unit")"
        printf '%s\n' "────────────────────────────────────────────────────────────"
        cat "$LOGDIR/${unit}.log"
        printf '\n'
    elif ((VERBOSE)); then
        print_unit_detail "$unit"
    fi
done

printf '%s✗%s %d of %d steps failed (%s)\n' \
    "$C_BAD" "$C_OFF" "$failed" "$count" "$(fmt_ms "$elapsed")"
exit 1
