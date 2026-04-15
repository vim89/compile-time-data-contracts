# Benchmark summary

Run id: `2026-04-15-gha-ubuntu-latest`

## Compile-time overhead

| schema pairs | baseline avg (s) | contract avg (s) | delta (s) | delta (%) | baseline avg rss (MiB) | contract avg rss (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 6.717 | 7.563 | 0.846 | 12.6 | 386.7 | 430.6 |
| 25 | 8.990 | 9.990 | 1.000 | 11.1 | 420.0 | 458.6 |
| 50 | 11.340 | 13.220 | 1.880 | 16.6 | 461.6 | 513.0 |

## Runtime comparator overhead

| benchmark | avg ns/op |
| --- | ---: |
| custom exact unordered match | 8149.74 |
| Spark equalsIgnoreCaseAndNullability | 331.42 |
| custom exact by position match | 180.55 |
| Spark equalsStructurally | 380.36 |

## Notes

- Compile numbers come from direct `scalac` runs against the repo classpath.
- Runtime numbers are micro-bench measurements on `StructType` comparison only.
- Custom unordered exact matching is about 24.6x Spark ignore-case comparison in this run, but still stays in the low-microsecond range per schema comparison.
- Custom by-position matching is about 0.5x Spark structural comparison in this run.
- Treat this run as local evidence, not a cross-machine claim.
