# Contributing

Issues, questions and pull requests are welcome. This is a small project with one maintainer,
so the fastest route for anything larger than a typo is to open an issue first and agree on
the shape of the change before writing it.

## Getting help or reporting a problem

Open a [GitHub issue](https://github.com/vim89/compile-time-data-contracts/issues). For a bug,
include the Scala version, the Spark version if the Spark half is involved, the policy you
selected, and the two case classes that did or did not compile. A compile error from this
library names the field path and both sides of the mismatch, so pasting it is usually enough
to reproduce.

For a question about what the library claims, read [ARTIFACT.md](ARTIFACT.md) first. It maps
every claim to the test or saved run that backs it. A claim not marked `closed` there is not
something the repository proves.

## Building from source

No published artifact and no credentials are needed to build or test this repository.

```bash
git clone https://github.com/vim89/compile-time-data-contracts.git
cd compile-time-data-contracts
sbt -batch clean "+core/test" "spark/test" "probe/test"
```

Requirements are a JVM (11 or newer; 21 is what CI uses) and sbt. sbt downloads both Scala
versions and Spark itself.

The gate is clean and crossed, in that order and in one invocation, for two reasons. Clean,
because the compile-time tests are macro expansions and a stale expansion from an earlier
compile can pass a test the current sources fail. Crossed, because `core` ships for Scala
2.13 and Scala 3 and the two macro front ends are separate reflection code that is only
checked for parity by running both.

At the time of writing the gate runs 234 tests: 67 in `core` on 2.13, 101 in `core` on 3, 57
in `spark`, and 9 in `probe`.

To use a local build from another project on the same machine:

```bash
sbt -batch "+core/publishLocal" "spark/publishLocal"
```

## Repository layout

- `modules/core` is the compile-time engine. No Spark dependency, cross-built for 2.13 and 3.
  Published as `ctdc-core`.
- `modules/spark` is the runtime pin and the typed pipeline builder. Scala 3 only. Published
  as `ctdc-spark`.
- `modules/probe` holds the measurement harnesses for the paper. Not published: it depends on
  Avro and reaches comparators that no pipeline should resolve.
- `modules/spark4` is probe-only, against Spark 4.2.0, and is not aggregated by the root
  project.
- `benchmarks/` holds the harness and the saved runs. `paper/` holds the manuscript, the
  evidence, and the scripts that regenerate it. `joss/` holds the JOSS submission.

## What a pull request needs

- The full gate above passing. CI runs the same command on every pull request to `main`.
- A test for the behaviour you changed. If the change is to the comparison rules, the test
  belongs in `core` and has to hold on both Scala versions.
- Lines no longer than 120 columns, matching the surrounding style. There is no scalafmt
  configuration in this project; match the file you are editing.
- No new claim in `README.md`, `ARTIFACT.md` or the paper without the evidence that backs it.
  If you change a number that the paper reports, regenerate the evidence rather than editing
  the number, and say which commit the regenerated run belongs to.
- One logical change per pull request, and a commit message that says what changed in plain
  words.

## Changing a measured number

The numbers in the paper belong to one revision of the comparison engine. A matrix
regenerated after a change to that engine is a different measurement, not a failed
reproduction. The regeneration commands are in
[ARTIFACT.md](ARTIFACT.md#regenerating-the-evidence); record the commit before running them
and compare it with the `revision` field in `paper/evidence/claims.json`.

## Code of conduct

Participation is governed by [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Licence

Contributions are accepted under the MIT licence of this repository. There is no contributor
licence agreement to sign.
