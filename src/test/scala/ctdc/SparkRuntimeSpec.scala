package ctdc

import ctdc.ContractsCore.SchemaPolicy
import ctdc.SparkCore.{PolicyRuntime, SchemaCheck, SparkSchema, TypedIO, TypedSink}
import munit.FunSuite
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.*

import java.nio.file.Files

class SparkRuntimeSpec extends FunSuite:

  private lazy val spark: SparkSession =
    SparkSession
      .builder()
      .appName("ctdc-runtime-spec")
      .master("local[1]")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .getOrCreate()

  override def afterAll(): Unit =
    try spark.stop()
    finally super.afterAll()

  private def emptyDf(schema: StructType) =
    spark.createDataFrame(spark.sparkContext.emptyRDD[Row], schema)

  test("PolicyRuntime Exact rejects nested optionality drift in arrays and maps") {
    final case class Contract(values: List[Int], metrics: Map[String, Int])

    val found =
      StructType(
        List(
          StructField("values", ArrayType(IntegerType, containsNull = true), nullable = false),
          StructField("metrics", MapType(StringType, IntegerType, valueContainsNull = true), nullable = false)
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.Exact.type]]

    assertEquals(runtime.ok(found, expected), false)
  }

  test("SchemaCheck default pin rejects nested optionality drift") {
    final case class Contract(values: List[Int])

    val df =
      emptyDf(
        StructType(
          List(
            StructField("values", ArrayType(IntegerType, containsNull = true), nullable = false)
          )
        )
      )

    val ex = intercept[IllegalArgumentException] {
      SchemaCheck.assertMatchesContract[Contract](df)
    }

    assert(clue(ex.getMessage).contains("Runtime schema mismatch"))
  }

  test("TypedIO policy-aware write honors ExactByPosition without reapplying the default comparator") {
    final case class Contract(id: Long, email: String)

    val df =
      emptyDf(
        StructType(
          List(
            StructField("col0", LongType, nullable = false),
            StructField("col1", StringType, nullable = false)
          )
        )
      )

    val out = Files.createTempDirectory("ctdc-runtime-write").toString

    TypedIO.writeDF[Contract, SchemaPolicy.ExactByPosition.type](df, TypedSink[Contract](out))
  }

  test("PolicyRuntime ExactOrdered rejects reordered fields") {
    final case class Contract(id: Long, email: String)

    val found =
      StructType(
        List(
          StructField("email", StringType, nullable = false),
          StructField("id", LongType, nullable = false)
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.ExactOrdered.type]]

    assertEquals(runtime.ok(found, expected), false)
  }

  test("PolicyRuntime ExactOrderedCI accepts case-only name drift when order matches") {
    final case class Contract(id: Long, email: String)

    val found =
      StructType(
        List(
          StructField("ID", LongType, nullable = false),
          StructField("EMAIL", StringType, nullable = false)
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.ExactOrderedCI.type]]

    assertEquals(runtime.ok(found, expected), true)
  }

  test("PolicyRuntime ExactUnorderedCI accepts reordering and case drift") {
    final case class Contract(id: Long, email: String)

    val found =
      StructType(
        List(
          StructField("EMAIL", StringType, nullable = false),
          StructField("ID", LongType, nullable = false)
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.ExactUnorderedCI.type]]

    assertEquals(runtime.ok(found, expected), true)
  }

  test("SchemaCheck policy-aware pin for Full allows mismatched shapes") {
    final case class Contract(id: Long, email: String)

    val df =
      emptyDf(
        StructType(
          List(
            StructField("segment", StringType, nullable = false)
          )
        )
      )

    SchemaCheck.assertMatchesContract[Contract, SchemaPolicy.Full.type](df)
  }
