# Benchmark summary

Run id: `2026-04-15-local`

## Compile-time overhead

| schema pairs | baseline avg (s) | contract avg (s) | delta (s) | delta (%) | baseline avg rss (MiB) | contract avg rss (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 2.283 | 2.553 | 0.270 | 11.8 | 376.1 | 440.1 |
| 25 | 2.940 | 3.337 | 0.397 | 13.5 | 458.6 | 520.5 |
| 50 | 3.680 | 4.193 | 0.513 | 13.9 | 506.4 | 570.5 |

## Runtime comparator overhead

| benchmark | avg ns/op |
| --- | ---: |
| custom exact unordered match | 4736.41 |
| Spark equalsIgnoreCaseAndNullability | 278.92 |
| custom exact by position match | 116.82 |
| Spark equalsStructurally | 332.13 |

## Notes

- Compile numbers come from direct `scalac` runs against the repo classpath.
- Runtime numbers are micro-bench measurements on `StructType` comparison only.
- Custom unordered exact matching is about 17.0x Spark ignore-case comparison in this run, but still stays in the low-microsecond range per schema comparison.
- Custom by-position matching is about 0.4x Spark structural comparison in this run.
- Treat this run as local evidence, not a cross-machine claim.
