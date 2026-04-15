#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS_ROOT="$ROOT_DIR/benchmarks/results"

usage() {
  cat >&2 <<'EOF'
Usage:
  ./benchmarks/compare-results.sh <run-a> <run-b> [output-file]

Example:
  ./benchmarks/compare-results.sh 2026-04-15-local 2026-04-15-gha-ubuntu-latest \
    benchmarks/results/2026-04-15-cross-env-comparison.md
EOF
}

[[ $# -lt 2 || $# -gt 3 ]] && usage && exit 1

RUN_A="$1"
RUN_B="$2"
OUTPUT_FILE="${3:-}"

RUN_A_DIR="$RESULTS_ROOT/$RUN_A"
RUN_B_DIR="$RESULTS_ROOT/$RUN_B"

require_file() {
  local file="$1"
  [[ -f "$file" ]] || {
    printf 'Missing file: %s\n' "$file" >&2
    exit 1
  }
}

require_file "$RUN_A_DIR/environment.txt"
require_file "$RUN_A_DIR/compile.csv"
require_file "$RUN_A_DIR/runtime.csv"
require_file "$RUN_B_DIR/environment.txt"
require_file "$RUN_B_DIR/compile.csv"
require_file "$RUN_B_DIR/runtime.csv"

read_env_value() {
  local file="$1"
  local key="$2"
  awk -F= -v key="$key" '
    index($0, key "=") == 1 {
      print substr($0, length(key) + 2)
      exit
    }
  ' "$file"
}

env_or_unknown() {
  local value="$1"
  if [[ -n "$value" ]]; then
    printf '%s' "$value"
  else
    printf 'unknown'
  fi
}

compile_metric() {
  local file="$1"
  local size="$2"
  local column="$3"
  awk -F, -v size="$size" -v column="$column" '
    NR > 1 && $2 == size && $3 == "measure" {
      sum[$1] += $column
      count[$1] += 1
    }
    END {
      if (count["baseline"] == 0 || count["contract"] == 0) exit 1
      baseline = sum["baseline"] / count["baseline"]
      contract = sum["contract"] / count["contract"]
      if (column == 5) {
        printf "%.3f,%.1f", contract - baseline, ((contract - baseline) / baseline) * 100.0
      } else {
        printf "%.1f,%.1f", (sum["baseline"] / count["baseline"]) / 1048576.0, (sum["contract"] / count["contract"]) / 1048576.0
      }
    }
  ' "$file"
}

runtime_metric() {
  local file="$1"
  local name="$2"
  awk -F, -v name="$name" '$1 == name { printf "%.2f", $2; exit }' "$file"
}

render_comparison() {
  local env_a="$RUN_A_DIR/environment.txt"
  local env_b="$RUN_B_DIR/environment.txt"
  local compile_a="$RUN_A_DIR/compile.csv"
  local compile_b="$RUN_B_DIR/compile.csv"
  local runtime_a="$RUN_A_DIR/runtime.csv"
  local runtime_b="$RUN_B_DIR/runtime.csv"

  local head_a head_b os_a os_b cpu_a cpu_b java_a java_b
  head_a="$(env_or_unknown "$(read_env_value "$env_a" git_head | cut -c1-12)")"
  head_b="$(env_or_unknown "$(read_env_value "$env_b" git_head | cut -c1-12)")"
  os_a="$(env_or_unknown "$(read_env_value "$env_a" uname)")"
  os_b="$(env_or_unknown "$(read_env_value "$env_b" uname)")"
  cpu_a="$(env_or_unknown "$(read_env_value "$env_a" cpu_model)")"
  cpu_b="$(env_or_unknown "$(read_env_value "$env_b" cpu_model)")"
  java_a="$(env_or_unknown "$(read_env_value "$env_a" java_version)")"
  java_b="$(env_or_unknown "$(read_env_value "$env_b" java_version)")"

  {
    printf '# Cross-environment benchmark comparison\n\n'
    printf 'Compared runs: `%s` vs `%s`\n\n' "$RUN_A" "$RUN_B"

    printf '## Environment summary\n\n'
    printf '| field | %s | %s |\n' "$RUN_A" "$RUN_B"
    printf '| --- | --- | --- |\n'
    printf '| git head | `%s` | `%s` |\n' "$head_a" "$head_b"
    printf '| os | `%s` | `%s` |\n' "$os_a" "$os_b"
    printf '| cpu | `%s` | `%s` |\n' "$cpu_a" "$cpu_b"
    printf '| java | `%s` | `%s` |\n' "$java_a" "$java_b"

    printf '\n## Compile-time overhead delta\n\n'
    printf '| schema pairs | %s delta (s) | %s delta (%%) | %s delta (s) | %s delta (%%) |\n' "$RUN_A" "$RUN_A" "$RUN_B" "$RUN_B"
    printf '| --- | ---: | ---: | ---: | ---: |\n'

    local sizes size delta_a percent_a delta_b percent_b
    sizes="$(awk -F, 'NR > 1 && $3 == "measure" { print $2 }' "$compile_a" "$compile_b" | sort -n | uniq)"
    for size in $sizes; do
      IFS=, read -r delta_a percent_a <<< "$(compile_metric "$compile_a" "$size" 5)"
      IFS=, read -r delta_b percent_b <<< "$(compile_metric "$compile_b" "$size" 5)"
      printf '| %s | %s | %s | %s | %s |\n' "$size" "$delta_a" "$percent_a" "$delta_b" "$percent_b"
    done

    printf '\n## Runtime comparator averages\n\n'
    printf '| benchmark | %s ns/op | %s ns/op | %s / %s |\n' "$RUN_A" "$RUN_B" "$RUN_B" "$RUN_A"
    printf '| --- | ---: | ---: | ---: |\n'

    local benchmarks name value_a value_b ratio
    benchmarks="$(awk -F, 'NF > 1 { print $1 }' "$runtime_a" "$runtime_b" | sort -u)"
    while IFS= read -r name; do
      [[ -z "$name" ]] && continue
      value_a="$(runtime_metric "$runtime_a" "$name")"
      value_b="$(runtime_metric "$runtime_b" "$name")"
      ratio="$(awk -v a="$value_a" -v b="$value_b" 'BEGIN { printf "%.2f", b / a }')"
      printf '| %s | %s | %s | %s |\n' "$name" "$value_a" "$value_b" "$ratio"
    done <<< "$benchmarks"

    printf '\n## Notes\n\n'
    printf -- '- This comparison is meant to show that the harness reproduces on a second environment, not to claim a stable cross-machine baseline.\n'
    printf -- '- Compile numbers compare `SchemaConforms` witness generation against the same generated sources without witness generation.\n'
    printf -- '- Runtime numbers are micro-bench measurements on schema comparison only, not end-to-end Spark job timings.\n'
  }
}

if [[ -n "$OUTPUT_FILE" ]]; then
  mkdir -p "$(dirname "$OUTPUT_FILE")"
  render_comparison > "$OUTPUT_FILE"
else
  render_comparison
fi
