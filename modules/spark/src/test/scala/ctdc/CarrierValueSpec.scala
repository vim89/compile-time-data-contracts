package ctdc

import ctdc.SparkCore.{SchemaCheck, SparkSchema, TypedIO, TypedSource}
import munit.FunSuite
import org.apache.spark.sql.SparkSession

import java.nio.file.{Files, Path}

/**
 * The three carriers of optionality, checked against data rather than against a read schema.
 *
 * `rulesFor` drops all three carriers at the runtime pin, because a `StructType` from a reader records a
 * default where the producer may have said nothing. These fixtures hold both halves of that decision to
 * account: a valid file is no longer rejected for a bit its reader invented, and a file that actually violates
 * the contract is still caught, by `assertNoForbiddenNulls` reading rows. One without the other would be a
 * dropped check wearing the name of a fix.
 *
 * The contracts live at the top level rather than inside the test bodies so that the same ones can be read
 * from JSON and from Parquet, which is the point of having both formats here: the carriers are defaulted by
 * every reader that does not record the claim, not by one of them.
 */
final case class TagsContract(tags: List[String])
final case class MetricsContract(metrics: Map[String, Int])
final case class InnerContract(id: Long)
final case class NestedContract(inner: InnerContract)
final case class OptionalNestedContract(inner: Option[InnerContract])
final case class TwoFieldContract(id: Long, label: String)

class CarrierValueSpec extends FunSuite:

  private lazy val spark: SparkSession =
    SparkSession
      .builder()
      .appName("ctdc-carrier-spec")
      .master("local[1]")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .getOrCreate()

  override def afterAll(): Unit =
    try spark.stop()
    finally super.afterAll()

  /** One JSON line per element, in a file that is deleted with the suite's temp directory. */
  private def jsonFile(lines: String*): String =
    val file = Files.createTempFile(tempDir, "rows", ".json")
    Files.writeString(file, lines.mkString("", "\n", "\n"))
    file.toString

  private lazy val tempDir: Path = Files.createTempDirectory("ctdc-carrier")

  private def jsonSource[C](lines: String*): TypedSource[C] = TypedSource[C]("json", jsonFile(lines*))

  /** The same rows, round-tripped through Parquet, which records the carriers but reads them back relaxed. */
  private def parquetSource[C](lines: String*)(using SparkSession, SparkSchema[C]): TypedSource[C] =
    val out = tempDir.resolve(s"parquet-${java.util.UUID.randomUUID()}").toString
    TypedIO.readDF(jsonSource[C](lines*)).write.parquet(out)
    TypedSource[C]("parquet", out)

  private def violation(body: => Unit): String =
    intercept[IllegalArgumentException](body).getMessage

  given SparkSession = spark

  test("a valid JSON array is read without a schema mismatch") {
    // The review's own reproduction. The reader returns `containsNull = true` for every array it is asked for,
    // so this read used to fail against a contract of `List[String]` on data with no nulls in it.
    val df = TypedIO.readDF(jsonSource[TagsContract]("""{"tags":["a","b"]}"""))

    assertEquals(df.count(), 1L)
    SchemaCheck.assertNoForbiddenNulls[TagsContract](df)
  }

  test("a null array element is caught by the value check") {
    val df      = TypedIO.readDF(jsonSource[TagsContract]("""{"tags":["a",null]}"""))
    val message = violation(SchemaCheck.assertNoForbiddenNulls[TagsContract](df))

    assert(clue(message).contains("tags[]: 1 row(s)"))
  }

  test("a null map value is caught by the value check") {
    val df = TypedIO.readDF(jsonSource[MetricsContract]("""{"metrics":{"hits":1,"misses":null}}"""))

    assert(clue(violation(SchemaCheck.assertNoForbiddenNulls[MetricsContract](df))).contains("metrics<value>"))
  }

  test("a valid map is read and accepted") {
    val df = TypedIO.readDF(jsonSource[MetricsContract]("""{"metrics":{"hits":1}}"""))

    SchemaCheck.assertNoForbiddenNulls[MetricsContract](df)
  }

  test("a null field inside a required nested struct is caught by the value check") {
    val df = TypedIO.readDF(jsonSource[NestedContract]("""{"inner":{"id":null}}"""))

    assert(clue(violation(SchemaCheck.assertNoForbiddenNulls[NestedContract](df))).contains("inner.id"))
  }

  test("a valid nested struct is read and accepted") {
    val df = TypedIO.readDF(jsonSource[NestedContract]("""{"inner":{"id":7}}"""))

    SchemaCheck.assertNoForbiddenNulls[NestedContract](df)
  }

  test("an absent optional parent is not reported as a violation at each of its children") {
    // `inner` is allowed to be absent, and `inner.id` is not. A null parent must not be counted as a null
    // child, or every legitimately absent struct would report one violation per required field under it.
    val df = TypedIO.readDF(jsonSource[OptionalNestedContract]("""{}"""))

    SchemaCheck.assertNoForbiddenNulls[OptionalNestedContract](df)
  }

  test("Parquet behaves the same way: valid data passes both the schema pin and the value check") {
    val df = TypedIO.readDF(parquetSource[TagsContract]("""{"tags":["a","b"]}"""))

    assertEquals(df.count(), 1L)
    SchemaCheck.assertNoForbiddenNulls[TagsContract](df)
  }

  test("Parquet behaves the same way: a null array element is caught by the value check") {
    val df = TypedIO.readDF(parquetSource[TagsContract]("""{"tags":[null]}"""))

    assert(clue(violation(SchemaCheck.assertNoForbiddenNulls[TagsContract](df))).contains("tags[]"))
  }

  test("a field the file does not contain passes the schema pin and is caught by the value check") {
    // What `readDF` can and cannot establish. The schema is an input to the read, so `df.schema` has the
    // column whether or not the file did; Spark fills it with nulls. The pin therefore accepts, and the row
    // check is what sees the file.
    val df = TypedIO.readDF(jsonSource[TwoFieldContract]("""{"id":1}"""))

    assert(df.schema.fieldNames.contains("label"))
    assert(clue(violation(SchemaCheck.assertNoForbiddenNulls[TwoFieldContract](df))).contains("label: 1 row(s)"))
  }

  test("a value that does not parse at the requested type passes the schema pin and is caught by the value check") {
    // The other way a `PERMISSIVE` read produces a null the contract forbids. `mode -> FAILFAST` is the
    // alternative, and is the caller's choice because it fails the whole read on one bad row.
    val df = TypedIO.readDF(jsonSource[TwoFieldContract]("""{"id":"not-a-number","label":"x"}"""))

    assert(clue(violation(SchemaCheck.assertNoForbiddenNulls[TwoFieldContract](df))).contains("id: 1 row(s)"))
  }

  test("every violated path is reported, not only the first") {
    val df = TypedIO.readDF(jsonSource[TwoFieldContract]("""{}"""))
    val message = violation(SchemaCheck.assertNoForbiddenNulls[TwoFieldContract](df))

    assert(clue(message).contains("id: 1 row(s)"))
    assert(clue(message).contains("label: 1 row(s)"))
  }
