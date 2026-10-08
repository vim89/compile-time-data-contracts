# Compile-time data contracts (Scala 2.13 and 3 + Spark 3.5)

![Using](https://img.shields.io/badge/Scala%203-%23de3423.svg?logo=scala&logoColor=white)

> Derived evidence rejects supported declared-shape mismatches at checked build boundaries: within the shapes
> listed under [Supported shapes](#supported-shapes), a producer type that does not conform to its contract under the
> selected policy fails to compile.
> "Derived evidence" is part of the claim and not a flourish: `SchemaConforms` is an ordinary trait with no
> members, so an instance can also be written by hand, which asserts conformance rather than checking it. The
> guarantee holds for evidence the macro produced, reaching a sink through the checked API.
> This repository demonstrates that claim with **macros** (quotes reflection on Scala 3, blackbox macros on 2.13) +
> **Spark 3.5**, and a runtime pin at the sink.

For paper work, use [ARTIFACT.md](ARTIFACT.md) as the canonical claim-to-evidence map.
If a claim is not marked `closed` there, do not state it as already proven by this repo.
For measured overhead, use [benchmarks/README.md](benchmarks/README.md) and the saved runs
under [benchmarks/results](benchmarks/results).

## What this is

A small but complete reference artifact:

- **Policies** describe *how* two schemas should match (exact, by position, by name and order, backward/forward
  compatible, etc.).
- A **macro** computes a **deep structural shape** of your case classes and **proves** at compile time that the producer
  type conforms to the target contract under a selected policy.
- At **runtime**, the sink boundary mirrors the chosen policy with Spark-style name/order matching and real subset
  checks for `Backward` and `Forward`. It compares no carrier of optionality, because a schema that came from a reader
  no longer says whether a permissive bit was a claim or a default. For that, `assertNoForbiddenNulls` reads the rows.

If the proof cannot be derived, your code **fails to compile**. No surprises at midnight.

## Why it's useful

Schema changes are the sneakiest failures in data systems.
Here, the compiler enforces your intent: if `Out` no longer conforms to `Contract` under a policy `P`, compilation
aborts with a readable diff.
At runtime, the sink pin adds a second seatbelt over field names, field order and leaf types; the three carriers of
optionality are not among them, for the reason given above. ([Apache Spark][3])

Data shape drift is subtle (nullability, reordering, nested optionality, case changes, maps/arrays).
This repository pushes those checks to the compiler.
You get **fast feedback**, **explicit diffs**, and **documented intent** via policy types.

---

## How it works (at a glance)

* **Policies as types** - `SchemaPolicy` encodes *how* to compare schemas (`Exact`, `ExactUnordered`,
  `ExactUnorderedCI`, `ExactOrdered`, `ExactOrderedCI`, `ExactByPosition`, `Backward`, `Forward`, `Full`) as
  **singleton types**.
* **Macro shape** - The macro in `ctdc.internal.ContractMacros` walks your types and builds a normalized structural
  **TypeShape**, then computes a diff. If the diff is non-empty => **compile error**. Only this front end is written per
  Scala version: quotes reflection on 3, `scala.reflect.macros` on 2.13. Mirrors are not required here; on Scala 3 the
  artifact uses `inline` + `${ ... }` + `TypeRepr` directly. ([Scala Documentation][2])
* **One comparison, both versions** - everything the policies actually decide (`ComparisonRules`, `ShapeDiff`,
  `TypeShape`) is ordinary version-agnostic code, so the two front ends share the decision rules. What each
  front end extracts from a type is its own reflection code, and parity of that extraction is tested separately.
* **Compile-time fuse** - code that wires a sink must provide `SchemaConforms[Out, Contract, P]`. If it can’t be
  summoned, the pipeline won’t compile.
* **Runtime pin (Spark)** - the sink boundary mirrors the chosen policy with Spark-style comparators:
    * case-insensitive, ignore optionality --> `DataType.equalsIgnoreCaseAndNullability`
    * by position -> `DataType.equalsStructurally`
    * ordered by name (CS/CI) -> `DataType.equalsStructurallyByName` with the chosen resolver. ([Apache Spark][3])

  All three of those Spark methods zip the two field lists positionally, so none of them is an unordered comparison;
  the unordered-by-name policies are matched by this artifact's own comparator rather than by Spark's.
* **Mid-pipeline pin** - `transformAs` intentionally uses the default unordered exact-style pin to catch gross drift
  during reshaping; policy-aware runtime enforcement happens at `addSink[R, P]`.

---

## Quick start

### Requirements

* Scala 2.13.16 or 3.3.x for `ctdc-core`; Scala 3.3.x for `ctdc-spark`.
* Spark 3.5.x (`spark-sql`) - the Scala 3 build depends on Spark's Scala 2.13 artifacts, selected by sbt's
  `CrossVersion.for3Use2_13`. Those artifacts are Scala 2.13 output, not Scala 3 TASTy.
* A JVM 11+.

### Modules

```scala
// The compile-time engine. No Spark, cross-built for 2.13 and 3.
libraryDependencies += "com.vitthalmirji" %% "ctdc-core" % "0.2.0"

// The Spark runtime pin and typed pipeline builder. Scala 3 only, depends on ctdc-core.
libraryDependencies += "com.vitthalmirji" %% "ctdc-spark" % "0.2.0"
```

Both are published to GitHub Packages, which requires a token even for a public read. So the resolver and a
credential have to be declared; Maven Central would need neither, and moving there is open work.

```scala
resolvers += "ctdc" at "https://maven.pkg.github.com/vim89/compile-time-data-contracts"

credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env("GITHUB_ACTOR"),
  sys.env("GITHUB_TOKEN")
)
```

Any GitHub account works and the token needs only `read:packages`. Put it in the environment or in
`~/.sbt/1.0/credentials.sbt`, not in a build file that is committed. In Actions, `${{ github.actor }}` and the
job's `GITHUB_TOKEN` are enough, with `permissions: packages: read` on the job.

`ctdc-core` is split out so that a caller who only wants contracts checked at compile time does not take a Spark
dependency, and so that the engine is usable from 2.13 where the Spark half is not.

Those two are the whole published surface. The paper's measurement harnesses live in `modules/probe`, which is not
published: they pull in Avro and `spark-avro`, and one of them reaches two `private[sql]` comparators from a class
declared inside `org.apache.spark.sql`, none of which belongs in a dependency a pipeline resolves.

### Scala 3 notes (this artifact)

- Quotes-first: macros are structured around `inline`/splice (`${ ... }`) and `quotes`/`reflect` APIs. We use
  `inline given derived[...] = ${ ... }` and traverse `TypeRepr` to compute deep shapes and diffs, emitting precise
  compile-time errors via `report.errorAndAbort`.
- Mirrors optional: Scala 3 introduces compiler‑derived `Mirror`s for ADTs that enable higher‑level generic derivation.
  This artifact does not rely on `Mirror.Of`; the reflection is explicit for control and clarity. You can layer
  Mirror‑based derivation on top later if desired.

### Compile-only example

```scala
import ctdc.{ SchemaConforms, SchemaPolicy, conforms }

final case class ContractUser(id: Long, email: String, age: Option[Int] = None)

final case class OutExact_Same(id: Long, email: String, age: Option[Int])

// If fields/types drift, this line fails at compile time with a diff:
val ev: SchemaConforms[OutExact_Same, ContractUser, SchemaPolicy.Exact] = implicitly

// Or use the helper, which is the same request named for what it proves:
val ev2 = conforms[OutExact_Same, ContractUser, SchemaPolicy.Exact]
```

A policy can be named either as the type `SchemaPolicy.Exact` or as the singleton `SchemaPolicy.Exact.type`; both
resolve, because the macro matches the requested policy by subtyping.

### PipelineBuilder example (CSV -> Parquet, file created in code)

```scala
import ctdc.SchemaPolicy
import ctdc.SparkCore.*
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.*
import java.nio.file.Files

object Demo:
  final case class CustomerContract(id: Long, email: String, age: Option[Int] = None, region: String = "IN")

  final case class CustomerProducer(id: Long, email: String, age: Option[Int], segment: String)

  final case class CustomerNext(id: Long, email: String, age: Option[Int], region: String)

  @main def run(): Unit =
    given spark: SparkSession =
      SparkSession.builder().appName("ctdc").master("local[*]").getOrCreate()

    try
      // 1> Make CSV in a temp dir (no external files)
      import spark.implicits.*
      val header = "id,email,age,segment"
      val rows = Seq("1,a@b.com,21,S", "2,b@c.com,,L")
      val inDir = Files.createTempDirectory("ctdc_in").toUri.toString
      (header +: rows).toDS.coalesce(1).write.text(inDir) // write CSV as text
      // Read CSV with an explicit schema is first-class Spark: schema(...) + load(...)
      // (same API pattern for csv/json/parquet).

      // 2> Build & run pipelines
      val src = TypedSource[CustomerProducer]("csv", inDir, Map("header" -> "true"))
      val sink = TypedSink[CustomerContract](Files.createTempDirectory("ctdc_out").toUri.toString)

      // A> No transform — Backward accepts producer extras and the missing defaulted region
      val outA =
        PipelineBuilder[CustomerContract]("A")
          .addSource(src)
          .noTransform
          .addSink[CustomerContract, SchemaPolicy.Backward.type](sink) // compile-time evidence required here
          .build
          .apply(spark)

      // B> Transform to a declared Next, then require Next ⟶ Contract under Exact
      val normalizeForExact: DataFrame => DataFrame =
        _.select($"id", $"email", $"age", lit("IN").as("region"))
      val outB =
        PipelineBuilder[CustomerContract]("B")
          .addSource(src)
          .transformAs[CustomerNext]("drop segment and add region")(normalizeForExact)
          .addSink[CustomerContract, SchemaPolicy.Exact.type](sink)
          .build
          .apply(spark)

      println(s"A: ${outA.count()} rows");
      println(s"B: ${outB.count()} rows")
    finally spark.stop()
```

*Why no `toDF()`?* Creating the CSV via a `Dataset[String]` avoids case-class encoders and keeps the example
dependency-free. If you prefer `Seq[CaseClass].toDF`, see “Encoders (Scala 3)” below. `toDF`/`toDS` require
`import spark.implicits._` from **that** SparkSession. ([Apache Spark][4])

---

## Policy <-> Spark comparator mapping

The `CI` suffix is what asks for case-insensitive matching. Every policy without it, `Exact` included, compares field
names case-sensitively, because the formats a pipeline writes to keep the case they are given: a field renamed only by
case is a column the consumer does not find.

* `Exact` / `ExactUnordered` -> unordered, case-sensitive matching
* `ExactUnorderedCI` -> the same, matching field names case-insensitively, following
  `DataType.equalsIgnoreCaseAndNullability` semantics; for a destination that folds case, such as a Hive metastore
* `ExactByPosition` -> by-position matching, following `DataType.equalsStructurally` semantics
* `ExactOrdered` (case-sensitive) / `ExactOrderedCI` (case-insensitive) -> ordered-by-name matching, following
  `DataType.equalsStructurallyByName` semantics
* `Backward` -> case-sensitive subset matching by field name; producer extras are allowed and missing contract fields
  are allowed only when the contract field is optional or has a default value. The relation tolerates the missing
  defaulted field; nothing here applies the default. Materialising it needs a reader or adaptation step the caller
  writes
* `Forward` -> case-sensitive subset matching by field name; producer fields must all exist in the contract, and missing
  contract fields are allowed
* `Full` -> accept all structural combinations; useful only when enforcement is intentionally disabled

---

## Supported shapes

* Primitives (`Int`, `Long`, `Double`, `Boolean`, `String`, Java time/sql basics)
* `Option[T]` at field level and nested positions
* `List`/`Seq`/`Vector`/`Array`/`Set[T]` with element optionality preserved
* `Map[K,V]` with atomic keys (`String`, `Int`, `Long`, `Short`, `Byte`, `Boolean`) and value optionality preserved
* Nested case classes.
  (These align naturally with Spark’s `StructType`, `ArrayType`, and `MapType`) ([ibiblio.uib.no][5])

The two derivations do not treat an unlisted shape the same way, and the difference is deliberate.

Spark-schema derivation rejects one: there is no `StructType` it could produce, so it aborts with the supported list
rather than widening to a permissive fallback type. The same goes for a construct with nothing to compare, such as a
tuple, in either derivation.

The compile-time contract check accepts an unlisted leaf and compares it by name. It has to: `ctdc-core` is
Spark-free, and whether a `UUID` or a domain enum can be written is decided by the writer a sink is given, not by a
list in this library, so rejecting the leaf turned away pipelines that were fine. What that buys is narrower than
what the listed shapes get. Two leaves with the same rendered name are taken to be the same leaf, which catches drift
because a leaf that changes type changes its name, and which is not evidence that the two encode identically on the
wire. If a leaf's encoding matters to a contract, that is checked by the writer and the runtime pin, not here.

Important semantic note:

A Spark schema records "can be absent" in three independent places: `StructField.nullable`, `ArrayType.containsNull`
and `MapType.valueContainsNull`. All three are compared at compile time, under one policy axis, against the Scala types
that state them: `Option[T]`, `List[Option[T]]` and `Map[K, Option[V]]` are each different from their non-`Option`
counterpart, and every policy except `Full` says so.

None of the three is compared at runtime. Spark's file readers return the permissive value for each on every format
that does not record the claim, so a `true` is not a producer saying values may be absent, it is a producer that was
never asked, and comparing the bits rejects valid CSV, JSON and inferred Parquet. The runtime answer is to ask the data
instead:

```scala
SparkCore.SchemaCheck.assertNoForbiddenNulls[Contract](df)
```

For every position the contract says is always present, that counts the rows where a value is actually absent, in one
pass however deep the contract is. It is a separate call because of that cost. It also catches two things no schema
comparison can see: a column the file does not contain, and a value that did not parse at the requested type, both of
which a `PERMISSIVE` read turns into nulls.

---

## Encoders (Scala 3)

Spark's product encoders historically rely on Scala 2 reflection (`TypeTag`). In Scala 3 you’ll see *"missing TypeTag"*
if you do `Seq[CaseClass].toDF()` without extra help. Two options:

1. **Add Scala 3 encoders lib**

   ```scala
   libraryDependencies += "io.github.vincenzobaz" %% "spark-scala3-encoders" % "0.3.2"
   ```

   and `import scala3encoders.given` next to `import spark.implicits.*`. ([Scaladex][6])

2. **Stay DataFrame-only for inputs** (as in the example): write CSV/JSON strings and read with an explicit schema via
   `DataFrameReader.schema(..).load(..)`. ([Apache Spark][7])

## Why I'm confident in the behavior

- The compile-time proof relies on **Scala 3 quotes reflection** (`TypeRepr`, `AppliedType`, `=:=`, `<:<`) - the
  official metaprogramming API. Mirrors are optional for this approach and currently unused in the artifact.
- The runtime validations follow Spark’s **documented** structural comparison semantics for name/order matching, and
  the optionality check they cannot make is made against rows instead, with fixtures for JSON and Parquet over arrays,
  map values and nested structs.
- Context parameters (`using`/`given`) make compile-time evidence explicit and ergonomic.

## References

* Rock the JVM: Scala Macros & Metaprogramming course. ([Rock the JVM][8])
* Scala 3 macros & reflection (`quotes`, `reflect`, `TypeRepr`), and macro best practices. ([Scala Documentation][2])
* Spark structural comparators on `DataType`. ([Apache Spark][3])
* CSV read/write and explicit schemas. ([Apache Spark][7])
* `toDF`/`toDS` via `import spark.implicits._`. ([Apache Spark][4])
* Scala 3 encoders for Spark (community). ([Scaladex][6])

---

**TL;DR**
Compile-time evidence + policy types make schema intent explicit and enforceable.
Spark-style runtime checks keep you safe at runtime. If schemas drift, your job doesn’t ship.


[1]: https://docs.scala-lang.org/scala3/guides/macros/best-practices.html "Best Practices | Macros in Scala 3"

[2]: https://docs.scala-lang.org/scala3/guides/macros/reflection.html "Reflection | Macros in Scala 3"

[3]: https://spark.apache.org/docs/3.5.6/api/scala/org/apache/spark/sql/types/DataType%24.html "Spark 3.5.6 ScalaDoc - DataType (companion)"

[4]: https://spark.apache.org/docs/3.5.6/api/scala/org/apache/spark/sql/DatasetHolder.html "DatasetHolder (Spark 3.5.6 ScalaDoc)"

[5]: https://spark.apache.org/docs/3.5.6/api/scala/org/apache/spark/sql/types/StructType.html "StructType (Spark 3.5.6 ScalaDoc)"

[6]: https://index.scala-lang.org/vincenzobaz/spark-scala3-encoders "spark-scala3-encoders"

[7]: https://spark.apache.org/docs/3.5.6/sql-data-sources-csv.html "CSV Files - Spark 3.5.6 Documentation"

[8]: https://courses.rockthejvm.com/p/scala-macros-and-metaprogramming "Scala Macros and Metaprogramming | Rock the JVM"
