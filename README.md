# Compile-time data contracts (Scala 3 + Spark 3.5)
![Using](https://img.shields.io/badge/Scala%203-%23de3423.svg?logo=scala&logoColor=white)

> If the source/target schemas drift, your pipeline **doesn’t compile**.
> This blog demonstrates that claim with **Scala 3 macros** (quotes reflection; Mirrors optional) + **Spark 3.5**.

**Pipelines don’t even compile if producer/contract schemas drift.**
This article proves it with Scala 3 macros (compile-time evidence) and Spark structural checks (runtime pin).

## What this is

A tiny but complete proof-of-concept:

- **Policies** describe *how* two schemas should match (exact, by position, by name and order, backward/forward compatible, etc.).
- A **macro** computes a **deep structural shape** of your case classes and **proves** at compile time that the producer type conforms to the target contract under a selected policy.
- At **runtime**, we mirror the policy with Spark’s built-in schema comparators for extra safety.

If the proof cannot be derived, your code **fails to compile**. No surprises at midnight.

## Why it's useful

Schema changes are the sneakiest failures in data systems. 
Here, the compiler enforces your intent: if `Out` no longer conforms to `Contract` under a policy `P`, compilation aborts with a readable diff.
At runtime, Spark's schema comparators add a second seatbelt. ([Scala Documentation][1])

Data shape drift is subtle (nullability, reordering, nested options, case changes, maps/arrays).
This article pushes those checks to the compiler.
You get **fast feedback**, **explicit diffs**, and **documented intent** via policy types.

---

## How it works (at a glance)

* **Policies as types** - `SchemaPolicy` encodes *how* to compare schemas (Exact, Ordered, ByPosition, Backward/Forward, Full) semantics as **singleton types**.
* **Macro shape** - The macro in `ContractsCore` walks your types via Scala 3 **quotes reflection** and builds a normalized shape. A Scala 3 macro inspects our case classes (using `quotes`/`reflect`), builds a normalized structural **TypeShape**, and computes a diff. If non-empty => **compile error**. Mirrors are not required here; this POC uses `inline` + `${ ... }` + `TypeRepr` directly. ([Scala Documentation][2])
* **Compile-time fuse** - code that wires a sink must provide `SchemaConforms[Out, Contract, P]`. If it can’t be summoned, the pipeline won’t compile.
* **Runtime pin (Spark)** - we mirror the policy with Spark’s built-in schema comparators:
    * unordered, case-insensitive, ignore nullability --> `DataType.equalsIgnoreCaseAndNullability`
    * by position -> `DataType.equalsStructurally`
    * ordered by name (CS/CI) -> `DataType.equalsStructurallyByName` with the chosen resolver. ([Apache Spark][3])

---

## Quick start

### Requirements

* Scala 3.3.x
* Spark 3.5.x (`spark-sql`) - Scala 3 consumes the 2.13 artifacts via TASTy.
* A JVM 11+.

### Scala 3 notes (this POC)

- Quotes-first: macros are structured around `inline`/splice (`${ ... }`) and `quotes`/`reflect` APIs. We use `inline given derived[...] = ${ ... }` and traverse `TypeRepr` to compute deep shapes and diffs, emitting precise compile-time errors via `report.errorAndAbort`.
- Mirrors optional: Scala 3 introduces compiler‑derived `Mirror`s for ADTs that enable higher‑level generic derivation. This POC does not rely on `Mirror.Of`; the reflection is explicit for control and clarity. You can layer Mirror‑based derivation on top later if desired.

### Compile-only example

```scala
import ctdc.ContractsCore.{SchemaPolicy, CompileTime}
import CompileTime.SchemaConforms

final case class ContractUser(id: Long, email: String, age: Option[Int] = None)
final case class OutExact_Same(id: Long, email: String, age: Option[Int])

// If fields/types drift, this line fails at compile time with a diff:
val ev: SchemaConforms[OutExact_Same, ContractUser, SchemaPolicy.Exact.type] = summon

// Or use the ergonomic inline helper:
import CompileTime.conforms
val ev2 = conforms[OutExact_Same, ContractUser, SchemaPolicy.Exact.type]
```

### PipelineBuilder example (CSV -> Parquet, file created in code)

```scala
import ctdc.ContractsCore.SchemaPolicy
import ctdc.SparkCore.*
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.*
import java.nio.file.Files

object Demo:
  final case class CustomerContract(id: Long, email: String, age: Option[Int] = None)
  final case class CustomerProducer(id: Long, email: String, age: Option[Int], segment: String)
  final case class CustomerNext(id: Long, email: String, age: Option[Int])

  @main def run(): Unit =
    given spark: SparkSession =
      SparkSession.builder().appName("ctdc").master("local[*]").getOrCreate()
    try
      // 1> Make CSV in a temp dir (no external files)
      import spark.implicits.*
      val header = "id,email,age,segment"
      val rows   = Seq("1,a@b.com,21,S", "2,b@c.com,,L")
      val inDir  = Files.createTempDirectory("ctdc_in").toUri.toString
      (header +: rows).toDS.coalesce(1).write.text(inDir) // write CSV as text
      // Read CSV with an explicit schema is first-class Spark: schema(...) + load(...)
      // (same API pattern for csv/json/parquet).

      // 2> Build & run pipelines
      val src  = TypedSource[CustomerProducer]("csv", inDir, Map("header" -> "true"))
      val sink = TypedSink[CustomerContract](Files.createTempDirectory("ctdc_out").toUri.toString)

      // A> No transform — compile-time fuse: Producer ⟶ Contract under Backward
      val outA =
        PipelineBuilder[CustomerContract]("A")
          .addSource(src)
          .noTransform
          .addSink[CustomerContract, SchemaPolicy.Backward.type](sink) // compile-time evidence required here
          .build
          .apply(spark)

      // B> Transform to a declared Next, then require Next ⟶ Contract under Exact
      val dropExtras: DataFrame => DataFrame = _.select($"id", $"email", $"age")
      val outB =
        PipelineBuilder[CustomerContract]("B")
          .addSource(src)
          .transformAs[CustomerNext]("drop segment")(dropExtras)
          .addSink[CustomerContract, SchemaPolicy.Exact.type](sink)
          .build
          .apply(spark)

      println(s"A: ${outA.count()} rows"); println(s"B: ${outB.count()} rows")
    finally spark.stop()
```

*Why no `toDF()`?* Creating the CSV via a `Dataset[String]` avoids case-class encoders and keeps the example dependency-free. If you prefer `Seq[CaseClass].toDF`, see “Encoders (Scala 3)” below. `toDF`/`toDS` require `import spark.implicits._` from **that** SparkSession. ([Apache Spark][4])

---

## Policy <-> Spark comparator mapping

* `Exact` / `ExactUnorderedCI` -> `DataType.equalsIgnoreCaseAndNullability`
* `ExactByPosition` -> `DataType.equalsStructurally`
* `ExactOrdered` (case-sensitive) / `ExactOrderedCI` (case-insensitive) -> `DataType.equalsStructurallyByName`
* `Backward`/`Forward` subset rules are enforced at **compile time**; runtime pin still uses a tolerant comparator to catch accidental drift.

---

## Supported shapes

* Primitives (`Int`, `Long`, `Double`, `Boolean`, `String`, Java time/sql basics)
* `Option[T]` (nullable), `List`/`Seq`/`Vector`/`Array`/`Set[T]` (elements nullable)
* `Map[K,V]` with atomic keys (`String`, `Int`, `Long`, `Short`, `Byte`, `Boolean`)
* Nested case classes.
  (These align naturally with Spark’s `StructType`, `ArrayType`, and `MapType`) ([ibiblio.uib.no][5])

---

## Encoders (Scala 3)

Spark's product encoders historically rely on Scala 2 reflection (`TypeTag`). In Scala 3 you’ll see *"missing TypeTag"* if you do `Seq[CaseClass].toDF()` without extra help. Two options:

1. **Add Scala 3 encoders lib**

   ```scala
   libraryDependencies += "io.github.vincenzobaz" %% "spark-scala3-encoders" % "0.3.2"
   ```

   and `import scala3encoders.given` next to `import spark.implicits.*`. ([Scaladex][6])

2. **Stay DataFrame-only for inputs** (as in the example): write CSV/JSON strings and read with an explicit schema via `DataFrameReader.schema(..).load(..)`. ([Apache Spark][7])


## Why I'm confident in the behavior

- The compile-time proof relies on **Scala 3 quotes reflection** (`TypeRepr`, `AppliedType`, `=:=`, `<:<`) - the official metaprogramming API. Mirrors are optional for this approach and currently unused in the POC.
- The runtime validations exactly reuse Spark’s **documented** structural comparators, matching our policies 1-to-1.
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
Spark's comparators keep you safe at runtime. If schemas drift, your job doesn’t ship.


[1]: https://docs.scala-lang.org/scala3/guides/macros/best-practices.html "Best Practices | Macros in Scala 3"
[2]: https://docs.scala-lang.org/scala3/guides/macros/reflection.html "Reflection | Macros in Scala 3"
[3]: https://spark.apache.org/docs/3.5.1/api/scala/org/apache/spark/sql/types/DataType%24.html "Spark 3.5.1 ScalaDoc - DataType (companion)"
[4]: https://spark.apache.org/docs/3.5.1/api/scala/org/apache/spark/sql/DatasetHolder.html "DatasetHolder (Spark 3.5.1 ScalaDoc)"
[5]: https://spark.apache.org/docs/3.5.1/api/scala/org/apache/spark/sql/types/StructType.html "StructType (Spark 3.5.1 ScalaDoc)"
[6]: https://index.scala-lang.org/vincenzobaz/spark-scala3-encoders "spark-scala3-encoders"
[7]: https://spark.apache.org/docs/3.5.1/sql-data-sources-csv.html "CSV Files - Spark 3.5.1 Documentation"
[8]: https://courses.rockthejvm.com/p/scala-macros-and-metaprogramming "Scala Macros and Metaprogramming | Rock the JVM"
