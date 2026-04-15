# Cross-environment benchmark comparison

Compared runs: `2026-04-15-local` vs `2026-04-15-gha-ubuntu-latest`

## Environment summary

| field | 2026-04-15-local | 2026-04-15-gha-ubuntu-latest |
| --- | --- | --- |
| git head | `5657b3dd2414` | `5657b3dd2414` |
| os | `Darwin localhost 25.3.0 Darwin Kernel Version 25.3.0: Wed Jan 28 20:55:08 PST 2026; root:xnu-12377.91.3~2/RELEASE_ARM64_T6020 arm64` | `Linux runnervm35a4x 6.17.0-1010-azure #10~24.04.1-Ubuntu SMP Fri Mar  6 22:00:57 UTC 2026 x86_64 x86_64 x86_64 GNU/Linux` |
| cpu | `Apple M2 Max` | `AMD EPYC 7763 64-Core Processor` |
| java | `openjdk version "21.0.10" 2026-01-20 LTS OpenJDK Runtime Environment Temurin-21.0.10+7 (build 21.0.10+7-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.10+7 (build 21.0.10+7-LTS, mixed mode, sharing) ` | `openjdk version "21.0.10" 2026-01-20 LTS OpenJDK Runtime Environment Temurin-21.0.10+7 (build 21.0.10+7-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.10+7 (build 21.0.10+7-LTS, mixed mode, sharing) ` |

## Compile-time overhead delta

| schema pairs | 2026-04-15-local delta (s) | 2026-04-15-local delta (%) | 2026-04-15-gha-ubuntu-latest delta (s) | 2026-04-15-gha-ubuntu-latest delta (%) |
| --- | ---: | ---: | ---: | ---: |
| 10 | 0.270 | 11.8 | 0.847 | 12.6 |
| 25 | 0.397 | 13.5 | 1.000 | 11.1 |
| 50 | 0.513 | 13.9 | 1.880 | 16.6 |

## Runtime comparator averages

| benchmark | 2026-04-15-local ns/op | 2026-04-15-gha-ubuntu-latest ns/op | 2026-04-15-gha-ubuntu-latest / 2026-04-15-local |
| --- | ---: | ---: | ---: |
| custom_exact_by_position_match | 116.82 | 180.55 | 1.55 |
| custom_exact_unordered_match | 4736.41 | 8149.74 | 1.72 |
| spark_equals_ignore_case_and_nullability_match | 278.92 | 331.42 | 1.19 |
| spark_equals_structurally_match | 332.13 | 380.36 | 1.15 |

## Notes

- This comparison is meant to show that the harness reproduces on a second environment, not to claim a stable cross-machine baseline.
- Compile numbers compare `SchemaConforms` witness generation against the same generated sources without witness generation.
- Runtime numbers are micro-bench measurements on schema comparison only, not end-to-end Spark job timings.
