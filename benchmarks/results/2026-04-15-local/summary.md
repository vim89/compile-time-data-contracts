# Benchmark summary

Run id: `2026-04-15-local`

## Compile-time overhead

| schema pairs | baseline avg (s) | contract avg (s) | delta (s) | delta (%) | baseline avg rss (MiB) | contract avg rss (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 2.193 | 2.423 | 0.230 | 10.5 | 374.7 | 434.8 |
| 25 | 2.813 | 3.210 | 0.397 | 14.1 | 462.6 | 522.0 |
| 50 | 3.547 | 4.163 | 0.616 | 17.4 | 495.5 | 543.1 |

## Runtime comparator overhead

| benchmark | avg ns/op |
| --- | ---: |
| custom exact unordered match | 4894.41 |
| Spark equalsIgnoreCaseAndNullability | 268.76 |
| custom exact by position match | 108.72 |
| Spark equalsStructurally | 313.95 |

## Notes

- Compile numbers come from direct `scalac` runs against the repo classpath.
- Runtime numbers are micro-bench measurements on `StructType` comparison only.
- Custom unordered exact matching is about 18.2x Spark ignore-case comparison in this run, but still stays in the low-microsecond range per schema comparison.
- Custom by-position matching is about 0.3x Spark structural comparison in this run.
- Treat this run as local evidence, not a cross-machine claim.
