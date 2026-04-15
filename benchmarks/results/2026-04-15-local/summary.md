# Benchmark summary

Run id: `2026-04-15-local`

## Compile-time overhead

| schema pairs | baseline avg (s) | contract avg (s) | delta (s) | delta (%) | baseline avg rss (MiB) | contract avg rss (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 2.207 | 2.470 | 0.263 | 11.9 | 376.2 | 434.1 |
| 25 | 2.810 | 3.437 | 0.627 | 22.3 | 457.5 | 519.3 |
| 50 | 3.657 | 4.163 | 0.506 | 13.8 | 511.3 | 556.6 |

## Runtime comparator overhead

| benchmark | avg ns/op |
| --- | ---: |
| custom exact unordered match | 2887.63 |
| Spark equalsIgnoreCaseAndNullability | 286.50 |
| custom exact by position match | 205.97 |
| Spark equalsStructurally | 280.41 |

## Notes

- Compile numbers come from direct `scalac` runs against the repo classpath.
- Runtime numbers are micro-bench measurements on `StructType` comparison only.
- Custom unordered exact matching is about 10.1x Spark ignore-case comparison in this run, but still stays in the low-microsecond range per schema comparison.
- Custom by-position matching is about 0.7x Spark structural comparison in this run.
- Treat this run as local evidence, not a cross-machine claim.
