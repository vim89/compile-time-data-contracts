# Benchmark summary

Run id: `2026-10-08-local`

## Compile-time overhead

| schema pairs | baseline avg (s) | contract avg (s) | delta (s) | delta (%) | baseline avg rss (MiB) | contract avg rss (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 2.117 | 2.490 | 0.373 | 17.6 | 351.7 | 405.3 |
| 25 | 2.800 | 3.497 | 0.697 | 24.9 | 419.8 | 523.1 |
| 50 | 3.527 | 4.357 | 0.830 | 23.5 | 475.4 | 487.9 |

## Runtime comparator overhead

| benchmark | avg ns/op |
| --- | ---: |
| custom exact unordered match | 7412.39 |
| Spark equalsIgnoreCaseAndNullability | 277.79 |
| custom exact by position match | 114.96 |
| Spark equalsStructurally | 304.52 |

## Notes

- Compile numbers come from direct `scalac` runs against the repo classpath.
- Runtime numbers are micro-bench measurements on `StructType` comparison only.
- Custom unordered exact matching is about 26.7x Spark ignore-case comparison in this run, but still stays in the low-microsecond range per schema comparison.
- Custom by-position matching is about 0.4x Spark structural comparison in this run.
- Treat this run as local evidence, not a cross-machine claim.
