package ctdc.probe

import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.*

import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/** Does accepting a nullability relaxation have a consequence, or only a return value?
  *
  * [[ComparatorMatrix]] establishes that six of Spark's nine schema-equality predicates accept a schema in which a
  * collection's elements became optional. On its own that is a fact about an API. It becomes a hazard only if a
  * pipeline that tolerated the drift then does something a reader would not predict.
  *
  * So this measures the other half. Each scenario below declares a schema in which something is not nullable, puts a
  * null there anyway, and records what Spark actually does at a real sink. The outcomes worth separating are:
  *
  *   - rejected: an exception at write time, which is the safe outcome, because the pipeline stops
  *   - silently accepted: the null is written and read back, so the `nullable = false` in the schema is a claim no
  *     layer ever checked, and every downstream operator that trusts it is wrong
  *   - coerced: the null becomes something else, which is worse than either, because the data changed
  *
  * A result of "rejected" everywhere would weaken the paper considerably: it would mean Spark enforces at the sink
  * what its comparators decline to check, and the predicate divergence costs nothing. That outcome is reported as
  * found if it is what happens.
  *
  * Parquet is the sink under test because it is built in, it is the default for Spark tables, and its format has a
  * genuine notion of a required field, so there is something for Spark to either honour or ignore.
  */
object NullabilityConsequence:

  private def f(name: String, dt: DataType, nullable: Boolean): StructField =
    StructField(name, dt, nullable)

  private def struct(fields: StructField*): StructType = StructType(fields.toArray)

  /** What a scenario did. `note` carries the detail that makes the outcome interpretable, such as the exception type
    * or the values that came back.
    */
  private enum Outcome:
    case Rejected(at: String, error: String)
    case Accepted(note: String)
    case Coerced(note: String)

  private def describe(outcome: Outcome): String = outcome match
    case Outcome.Rejected(at, error) => s"REJECTED at $at: $error"
    case Outcome.Accepted(note)      => s"ACCEPTED SILENTLY: $note"
    case Outcome.Coerced(note)       => s"COERCED: $note"

  private def shortError(t: Throwable): String =
    val root = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).toList.last
    val msg  = Option(root.getMessage).getOrElse("").linesIterator.nextOption().getOrElse("")
    s"${root.getClass.getName}: ${msg.take(160)}"

  // ===== SCENARIO 1: write a null element against containsNull = false =====

  /** The producer's own schema forbids null elements and the data has one. If Spark validates a schema against its
    * data at any point, this is where it would.
    */
  private def writeNullElementUnderStrictSchema(spark: SparkSession, dir: java.nio.file.Path): Outcome =
    val strict = struct(f("tags", ArrayType(StringType, containsNull = false), nullable = false))
    val rows   = List(Row(Seq("a", null)))
    val path   = dir.resolve("s1").toString

    Try {
      val df = spark.createDataFrame(rows.asJava, strict)
      df.write.mode("overwrite").parquet(path)
      spark.read.parquet(path).collect().toList
    } match
      case Failure(t) => Outcome.Rejected("write", shortError(t))
      case Success(readBack) =>
        val elements = readBack.map(_.getSeq[String](0).toList)
        Outcome.Accepted(s"round-tripped as $elements under declared schema containsNull=false")

  // ===== SCENARIO 2: read null-containing data through a schema that forbids nulls =====

  /** The realistic shape of the drift. The data was written honestly, by a producer whose schema permitted null
    * elements. The consumer declares the strict contract it believes it has. Nothing in between compares the two,
    * because the consumer's declared schema is what Spark uses to interpret the file.
    *
    * If the nulls arrive, then `containsNull = false` in a reader's schema is documentation and not a constraint, and
    * a downstream operator written against that schema is reading data its types say cannot exist.
    */
  private def readNullsThroughStrictSchema(spark: SparkSession, dir: java.nio.file.Path): Outcome =
    val permissive = struct(f("tags", ArrayType(StringType, containsNull = true), nullable = false))
    val strict     = struct(f("tags", ArrayType(StringType, containsNull = false), nullable = false))
    val path       = dir.resolve("s2").toString

    Try {
      spark
        .createDataFrame(List(Row(Seq("a", null))).asJava, permissive)
        .write
        .mode("overwrite")
        .parquet(path)

      val readStrict = spark.read.schema(strict).parquet(path)
      (readStrict.schema, readStrict.collect().toList)
    } match
      case Failure(t) => Outcome.Rejected("read", shortError(t))
      case Success((schemaSeenByPlanner, readBack)) =>
        val elements   = readBack.map(_.getSeq[String](0).toList)
        val nullsFound = elements.flatten.count(_ == null)
        val declared   = schemaSeenByPlanner.fields.head.dataType.asInstanceOf[ArrayType].containsNull
        if nullsFound > 0 then
          Outcome.Accepted(
            s"$nullsFound null element(s) returned as $elements while the planner's schema says containsNull=$declared"
          )
        else Outcome.Coerced(s"nulls did not survive: $elements, planner containsNull=$declared")

  // ===== SCENARIO 3: the same question for a non-nullable field =====

  /** `StructField.nullable = false` is the carrier ctdc's own comparator ignores, so its enforcement status matters
    * for the artifact's defence as much as for the finding.
    */
  private def readNullFieldThroughStrictSchema(spark: SparkSession, dir: java.nio.file.Path): Outcome =
    val permissive = struct(f("id", LongType, nullable = true))
    val strict     = struct(f("id", LongType, nullable = false))
    val path       = dir.resolve("s3").toString

    Try {
      spark
        .createDataFrame(List(Row(null), Row(7L)).asJava, permissive)
        .write
        .mode("overwrite")
        .parquet(path)

      val read = spark.read.schema(strict).parquet(path)
      (read.schema.fields.head.nullable, read.collect().toList)
    } match
      case Failure(t) => Outcome.Rejected("read", shortError(t))
      case Success((plannerNullable, readBack)) =>
        val values = readBack.map(r => if r.isNullAt(0) then "null" else r.getLong(0).toString)
        val retained =
          if plannerNullable then "the requested nullable=false was discarded: planner schema says nullable=true"
          else "the planner kept nullable=false"
        if values.contains("null") then Outcome.Accepted(s"values $values, $retained")
        else Outcome.Coerced(s"values $values, the null did not survive, $retained")

  // ===== SCENARIO 5: a null under a strict schema that the planner does keep =====

  /** The sharpest version of the question, because it removes the file format from the loop.
    *
    * Scenarios 2 and 3 show Spark relaxing a reader's strict schema before the plan is built, so the plan never holds
    * a false claim and the optimiser is never misled. `createDataFrame` is the other path: it takes the caller's
    * schema as given. If the plan then carries `nullable = false` over data that does contain a null, `IS NULL` is a
    * predicate the optimiser is entitled to fold to `false` without looking at a row, and the query would return an
    * answer that contradicts the data.
    *
    * This is reported as a divergence only if the two counts differ. If they agree, Spark is evaluating the predicate
    * on the rows regardless of the claim, and the paper must say so.
    */
  private def inMemoryStrictSchemaPredicate(spark: SparkSession): Outcome =
    val permissive = struct(f("id", LongType, nullable = true))
    val strict     = struct(f("id", LongType, nullable = false))
    val rows       = List(Row(null), Row(7L), Row(null))

    Try {
      val honest = spark.createDataFrame(rows.asJava, permissive)
      val lying  = spark.createDataFrame(rows.asJava, strict)

      // An unfiltered collect, because there are two different explanations for a wrong `IS NULL` count and they have
      // to be told apart: either the null is still in the row and the optimiser folded the predicate away without
      // looking, or the null never survived row encoding and the data itself changed. The values decide it.
      val values = lying.collect().toList.map(r => if r.isNullAt(0) then "null" else r.get(0).toString)

      // The optimised plan says whether the predicate was still evaluated at all.
      val plan = lying
        .filter("id IS NULL")
        .queryExecution
        .optimizedPlan
        .toString
        .linesIterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .mkString(" | ")

      (lying.schema.fields.head.nullable, values, honest.filter("id IS NULL").count(), lying.filter("id IS NULL").count(), plan)
    } match
      case Failure(t) => Outcome.Rejected("query", shortError(t))
      case Success((plannerNullable, values, honestNulls, lyingNulls, plan)) =>
        val note =
          s"planner nullable=$plannerNullable; rows collected under the strict schema: $values; " +
            s"IS NULL returns honest $honestNulls vs strict $lyingNulls; optimised plan: $plan"
        if honestNulls == lyingNulls then Outcome.Accepted(s"$note, same answer")
        else Outcome.Coerced(s"$note, DIFFERENT ANSWER for the same rows")

  // ===== SCENARIO 4: does the accepted null reach an operator that assumes it cannot exist? =====

  /** The consequence of scenario 3, one step further on. A filter over a field the schema says is never null is the
    * kind of predicate Spark's optimiser is entitled to simplify. If the count here disagrees with the count from the
    * honest schema, the drift has changed a query's answer rather than merely its types.
    */
  private def operatorOverStrictSchema(spark: SparkSession, dir: java.nio.file.Path): Outcome =
    val permissive = struct(f("id", LongType, nullable = true))
    val strict     = struct(f("id", LongType, nullable = false))
    val path       = dir.resolve("s4").toString

    Try {
      spark
        .createDataFrame(List(Row(null), Row(7L), Row(null)).asJava, permissive)
        .write
        .mode("overwrite")
        .parquet(path)

      val honest = spark.read.schema(permissive).parquet(path)
      val lying  = spark.read.schema(strict).parquet(path)
      (
        honest.filter("id IS NULL").count(),
        lying.filter("id IS NULL").count(),
        lying.schema.fields.head.nullable,
        lying.count()
      )
    } match
      case Failure(t) => Outcome.Rejected("query", shortError(t))
      case Success((honestNulls, lyingNulls, plannerNullable, lyingAll)) =>
        val note =
          s"IS NULL count: honest schema $honestNulls, strict schema $lyingNulls " +
            s"(planner nullable=$plannerNullable over $lyingAll rows)"
        if honestNulls == lyingNulls then Outcome.Accepted(s"$note, same answer")
        else Outcome.Coerced(s"$note, DIFFERENT ANSWER for the same file")

  // ===== SCENARIO 6: is the outcome a property of the contract, or of the field's type? =====

  /** Scenario 1 threw on a null string element and scenario 5 silently substituted `0` for a null long. If that
    * difference is the field's type rather than anything about the contract, then `nullable = false` does not have one
    * meaning that a caller can reason about: it is enforced for some types, ignored for others, and the schema gives
    * no indication which. That is the strongest form of the paper's claim, so it is measured per type rather than
    * inferred from the two results above.
    */
  private def coercionByLeafType(spark: SparkSession): Outcome =
    val cases: List[(String, DataType)] =
      List("long" -> LongType, "int" -> IntegerType, "double" -> DoubleType, "boolean" -> BooleanType, "string" -> StringType)

    val observed = cases.map { case (label, dt) =>
      val strict = struct(f("v", dt, nullable = false))
      val result = Try(spark.createDataFrame(List(Row(null)).asJava, strict).collect().toList) match
        case Failure(t)  => s"threw ${t.getClass.getSimpleName}"
        case Success(rs) => rs.map(r => if r.isNullAt(0) then "null" else r.get(0).toString).mkString
      s"$label -> $result"
    }

    val threw = observed.count(_.contains("threw"))
    val note  = observed.mkString(", ")
    if threw == 0 || threw == cases.size then Outcome.Accepted(s"uniform across leaf types: $note")
    else Outcome.Coerced(s"outcome depends on the leaf type, not on the contract: $note")

  def main(args: Array[String]): Unit =
    val spark = SparkSession
      .builder()
      .appName("ctdc-nullability-consequence")
      .master("local[2]")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.ui.enabled", "false")
      .getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")

    val dir = Files.createTempDirectory("ctdc-consequence")

    val scenarios = List(
      "1. write a null element under a declared containsNull=false schema" ->
        (() => writeNullElementUnderStrictSchema(spark, dir)),
      "2. read null-containing Parquet through a containsNull=false schema" ->
        (() => readNullsThroughStrictSchema(spark, dir)),
      "3. read a null through a nullable=false field schema" ->
        (() => readNullFieldThroughStrictSchema(spark, dir)),
      "4. IS NULL over the same file under honest and strict schemas" ->
        (() => operatorOverStrictSchema(spark, dir)),
      "5. IS NULL over in-memory rows whose schema claims nullable=false" ->
        (() => inMemoryStrictSchemaPredicate(spark)),
      "6. a null under nullable=false, per leaf type" ->
        (() => coercionByLeafType(spark))
    )

    val header = s"sink: parquet, spark ${spark.version}"
    val lines = scenarios.map { case (label, run) =>
      val outcome = Try(run()) match
        case Success(o) => o
        case Failure(t) => Outcome.Rejected("harness", shortError(t))
      s"$label\n   ${describe(outcome)}"
    }

    val report = (header :: lines).mkString("\n\n")
    println(report)

    // Written next to the comparator matrix so the paper cites a file rather than a terminal transcript.
    args.headOption.foreach { out =>
      val path = java.nio.file.Paths.get(out)
      Option(path.getParent).foreach(p => Files.createDirectories(p): Unit)
      Files.writeString(path, report + "\n"): Unit
      println(s"\nwrote $out")
    }

    spark.stop()
