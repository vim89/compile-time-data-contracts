#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS_ROOT="$ROOT_DIR/benchmarks/results"
RUN_ID="${1:-$(date +%F-%H%M%S)}"
RUN_DIR="$RESULTS_ROOT/$RUN_ID"
TMP_DIR="$(mktemp -d)"

WARMUPS="${CTDC_BENCH_WARMUPS:-1}"
RUNS="${CTDC_BENCH_RUNS:-3}"
COMPILE_SIZES_RAW="${CTDC_COMPILE_SIZES:-10 25 50}"
RUNTIME_WARMUPS="${CTDC_RUNTIME_WARMUPS:-3}"
RUNTIME_RUNS="${CTDC_RUNTIME_RUNS:-8}"
RUNTIME_OPS="${CTDC_RUNTIME_OPS:-250000}"

mkdir -p "$RUN_DIR"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

log() {
  printf '[bench] %s\n' "$1" >&2
}

detect_cpu_model() {
  if [[ "$(uname -s)" == "Darwin" ]]; then
    sysctl -n machdep.cpu.brand_string 2>/dev/null || printf 'unknown'
  elif command -v lscpu >/dev/null 2>&1; then
    lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -n 1
  else
    printf 'unknown'
  fi
}

detect_memory_bytes() {
  if [[ "$(uname -s)" == "Darwin" ]]; then
    sysctl -n hw.memsize 2>/dev/null || printf 'unknown'
  elif [[ -r /proc/meminfo ]]; then
    awk '/^MemTotal:/ { printf "%s", $2 * 1024 }' /proc/meminfo
  else
    printf 'unknown'
  fi
}

collect_environment() {
  {
    printf 'run_id=%s\n' "$RUN_ID"
    printf 'timestamp_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'git_head=%s\n' "$(git -C "$ROOT_DIR" rev-parse HEAD)"
    printf 'git_branch=%s\n' "$(git -C "$ROOT_DIR" rev-parse --abbrev-ref HEAD)"
    printf 'repo_scala_version=%s\n' "$(awk -F'\"' '$1 ~ /^val scala3[[:space:]]*=/ { print $2; exit }' "$ROOT_DIR/build.sbt")"
    printf 'scalac_version=%s\n' "$(scalac -version 2>&1)"
    printf 'java_version=%s\n' "$(java -version 2>&1 | tr '\n' ' ' | sed 's/  */ /g')"
    printf 'uname=%s\n' "$(uname -a)"
    printf 'cpu_model=%s\n' "$(detect_cpu_model)"
    printf 'memory_bytes=%s\n' "$(detect_memory_bytes)"
    printf 'compile_warmups=%s\n' "$WARMUPS"
    printf 'compile_runs=%s\n' "$RUNS"
    printf 'compile_sizes=%s\n' "$COMPILE_SIZES_RAW"
    printf 'runtime_warmups=%s\n' "$RUNTIME_WARMUPS"
    printf 'runtime_runs=%s\n' "$RUNTIME_RUNS"
    printf 'runtime_ops=%s\n' "$RUNTIME_OPS"

    if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
      printf 'github_actions=%s\n' "$GITHUB_ACTIONS"
      printf 'github_repository=%s\n' "${GITHUB_REPOSITORY:-}"
      printf 'github_ref=%s\n' "${GITHUB_REF:-}"
      printf 'github_sha=%s\n' "${GITHUB_SHA:-}"
      printf 'github_workflow=%s\n' "${GITHUB_WORKFLOW:-}"
      printf 'github_run_id=%s\n' "${GITHUB_RUN_ID:-}"
      printf 'github_run_attempt=%s\n' "${GITHUB_RUN_ATTEMPT:-}"
      printf 'runner_os=%s\n' "${RUNNER_OS:-}"
      printf 'runner_arch=%s\n' "${RUNNER_ARCH:-}"
      printf 'runner_name=%s\n' "${RUNNER_NAME:-}"
    fi
  } > "$RUN_DIR/environment.txt"
}

build_compile_classpath() {
  log "Compiling the repo so benchmark runs can reuse repo classes"
  sbt compile >/dev/null

  sbt -Dsbt.log.noformat=true "show Compile / fullClasspath" \
    | sed -n 's#^\[info\] \* Attributed(##p' \
    | sed 's#)$##' \
    | paste -sd ':' - \
    | tr -d '\n'
}

generate_source() {
  local mode="$1"
  local size="$2"
  local source_file="$3"

  cat > "$source_file" <<'EOF'
package bench

import ctdc.ContractsCore.SchemaPolicy
import ctdc.ContractsCore.CompileTime.SchemaConforms

object GeneratedBench:
EOF

  local i
  for i in $(seq 1 "$size"); do
    cat >> "$source_file" <<EOF
  final case class Geo$i(lat: Double, lon: Double)
  final case class Address$i(street: String, city: String, zip: Option[Int], geo: Geo$i)
  final case class Event$i(kind: String, at: Long, tags: List[Option[String]], attrs: Map[String, String])
  final case class Producer$i(
      id: Long,
      email: String,
      age: Option[Int],
      address: Address$i,
      events: List[Event$i],
      metrics: Map[String, Option[Int]]
  )
  final case class Contract$i(
      id: Long,
      email: String,
      age: Option[Int],
      address: Address$i,
      events: List[Event$i],
      metrics: Map[String, Option[Int]]
  )
EOF

    if [[ "$mode" == "baseline" ]]; then
      cat >> "$source_file" <<EOF
  type Pair$i = (Producer$i, Contract$i)
EOF
    else
      cat >> "$source_file" <<EOF
  val witness$i = summon[SchemaConforms[Producer$i, Contract$i, SchemaPolicy.Exact.type]]
EOF
    fi
  done
}

time_scalac() {
  local classpath="$1"
  local source_file="$2"
  local output_dir="$3"
  local time_file="$4"

  rm -rf "$output_dir"
  mkdir -p "$output_dir"

  if /usr/bin/time -lp true >/dev/null 2>&1; then
    /usr/bin/time -lp -o "$time_file" \
      scalac -classpath "$classpath" -d "$output_dir" "$source_file" >/dev/null
    awk '
      /^real / { real = $2 }
      /maximum resident set size/ { rss = $1 }
      END { printf "%s,%s\n", real, rss }
    ' "$time_file"
  else
    /usr/bin/time -f 'real %e\nmax_rss_kb %M' -o "$time_file" \
      scalac -classpath "$classpath" -d "$output_dir" "$source_file" >/dev/null
    awk '
      /^real / { real = $2 }
      /^max_rss_kb / { rss = $2 * 1024 }
      END { printf "%s,%s\n", real, rss }
    ' "$time_file"
  fi
}

run_compile_benchmarks() {
  local classpath="$1"
  local compile_csv="$RUN_DIR/compile.csv"
  printf 'mode,size,phase,iteration,real_seconds,max_rss_bytes\n' > "$compile_csv"

  local size mode phase iteration source_file output_dir time_file measurement real rss
  for size in $COMPILE_SIZES_RAW; do
    for mode in baseline contract; do
      source_file="$TMP_DIR/${mode}-${size}.scala"
      generate_source "$mode" "$size" "$source_file"

      for phase in warmup measure; do
        local limit="$WARMUPS"
        [[ "$phase" == "measure" ]] && limit="$RUNS"

        iteration=1
        while [[ "$iteration" -le "$limit" ]]; do
          output_dir="$TMP_DIR/out-${mode}-${size}-${phase}-${iteration}"
          time_file="$TMP_DIR/time-${mode}-${size}-${phase}-${iteration}.txt"
          measurement="$(time_scalac "$classpath" "$source_file" "$output_dir" "$time_file")"
          real="${measurement%%,*}"
          rss="${measurement##*,}"
          printf '%s,%s,%s,%s,%s,%s\n' "$mode" "$size" "$phase" "$iteration" "$real" "$rss" >> "$compile_csv"
          iteration=$((iteration + 1))
        done
      done
    done
  done
}

run_runtime_benchmark() {
  log "Running runtime schema comparator micro-benchmark"
  sbt "runMain ctdc.bench.RuntimeSchemaBenchmark $RUN_DIR/runtime.csv $RUNTIME_WARMUPS $RUNTIME_RUNS $RUNTIME_OPS" >/dev/null
}

avg_from_csv() {
  local file="$1"
  local mode="$2"
  local size="$3"
  local column="$4"

  awk -F, -v mode="$mode" -v size="$size" -v column="$column" '
    NR > 1 && $1 == mode && $2 == size && $3 == "measure" {
      sum += $column
      count += 1
    }
    END {
      if (count == 0) exit 1
      printf "%.3f", sum / count
    }
  ' "$file"
}

runtime_metric() {
  local file="$1"
  local name="$2"
  awk -F, -v name="$name" '
    $1 == name { printf "%.2f", $2 }
  ' "$file"
}

generate_summary() {
  local compile_csv="$RUN_DIR/compile.csv"
  local runtime_csv="$RUN_DIR/runtime.csv"
  local summary_file="$RUN_DIR/summary.md"

  {
    printf '# Benchmark summary\n\n'
    printf 'Run id: `%s`\n\n' "$RUN_ID"

    printf '## Compile-time overhead\n\n'
    printf '| schema pairs | baseline avg (s) | contract avg (s) | delta (s) | delta (%%) | baseline avg rss (MiB) | contract avg rss (MiB) |\n'
    printf '| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n'

    local size baseline_time contract_time baseline_rss contract_rss delta_seconds delta_percent
    for size in $COMPILE_SIZES_RAW; do
      baseline_time="$(avg_from_csv "$compile_csv" baseline "$size" 5)"
      contract_time="$(avg_from_csv "$compile_csv" contract "$size" 5)"
      baseline_rss="$(avg_from_csv "$compile_csv" baseline "$size" 6)"
      contract_rss="$(avg_from_csv "$compile_csv" contract "$size" 6)"
      baseline_rss="$(awk -v rss="$baseline_rss" 'BEGIN { printf "%.1f", rss / 1048576.0 }')"
      contract_rss="$(awk -v rss="$contract_rss" 'BEGIN { printf "%.1f", rss / 1048576.0 }')"
      delta_seconds="$(awk -v base="$baseline_time" -v contract="$contract_time" 'BEGIN { printf "%.3f", contract - base }')"
      delta_percent="$(awk -v base="$baseline_time" -v contract="$contract_time" 'BEGIN { printf "%.1f", ((contract - base) / base) * 100.0 }')"
      printf '| %s | %s | %s | %s | %s | %s | %s |\n' \
        "$size" "$baseline_time" "$contract_time" "$delta_seconds" "$delta_percent" "$baseline_rss" "$contract_rss"
    done

    printf '\n## Runtime comparator overhead\n\n'
    printf '| benchmark | avg ns/op |\n'
    printf '| --- | ---: |\n'
    local unordered_custom unordered_spark by_position_custom by_position_spark unordered_ratio by_position_ratio
    unordered_custom="$(runtime_metric "$runtime_csv" custom_exact_unordered_match)"
    unordered_spark="$(runtime_metric "$runtime_csv" spark_equals_ignore_case_and_nullability_match)"
    by_position_custom="$(runtime_metric "$runtime_csv" custom_exact_by_position_match)"
    by_position_spark="$(runtime_metric "$runtime_csv" spark_equals_structurally_match)"
    unordered_ratio="$(awk -v custom="$unordered_custom" -v spark="$unordered_spark" 'BEGIN { printf "%.1f", custom / spark }')"
    by_position_ratio="$(awk -v custom="$by_position_custom" -v spark="$by_position_spark" 'BEGIN { printf "%.1f", custom / spark }')"

    printf '| custom exact unordered match | %s |\n' "$unordered_custom"
    printf '| Spark equalsIgnoreCaseAndNullability | %s |\n' "$unordered_spark"
    printf '| custom exact by position match | %s |\n' "$by_position_custom"
    printf '| Spark equalsStructurally | %s |\n' "$by_position_spark"

    printf '\n## Notes\n\n'
    printf -- '- Compile numbers come from direct `scalac` runs against the repo classpath.\n'
    printf -- '- Runtime numbers are micro-bench measurements on `StructType` comparison only.\n'
    printf -- '- Custom unordered exact matching is about %sx Spark ignore-case comparison in this run, but still stays in the low-microsecond range per schema comparison.\n' "$unordered_ratio"
    printf -- '- Custom by-position matching is about %sx Spark structural comparison in this run.\n' "$by_position_ratio"
    printf -- '- Treat this run as local evidence, not a cross-machine claim.\n'
  } > "$summary_file"
}

main() {
  log "Writing environment metadata"
  collect_environment

  log "Preparing compile classpath"
  local classpath
  classpath="$(build_compile_classpath)"

  log "Running compile-time benchmark"
  run_compile_benchmarks "$classpath"

  run_runtime_benchmark

  log "Writing markdown summary"
  generate_summary

  log "Done: $RUN_DIR"
}

main "$@"
