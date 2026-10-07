package ctdc

import ctdc.SchemaPolicy
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

  test("PolicyRuntime Exact accepts field-level nullability drift, because a read schema does not state it") {
    final case class Contract(id: Long)

    // What `spark.read` hands back for a format that does not record the claim: `nullable = true` on every
    // field, whether or not values can actually be absent. The macro rejects this pair, since `Long` and
    // `Option[Long]` are different Scala types; the runtime pin cannot, because by this point both producers
    // look the same. The asymmetry is the point and is pinned here so that closing it has to be a decision.
    val found    = StructType(List(StructField("id", LongType, nullable = true)))
    val expected = summon[SparkSchema[Contract]].struct

    assertEquals(expected.fields.head.nullable, false)
    assertEquals(summon[PolicyRuntime[SchemaPolicy.Exact.type]].ok(found, expected), true)
  }

  test("PolicyRuntime Exact accepts nested optionality drift for the same reason it accepts the field kind") {
    final case class Contract(values: List[Int], metrics: Map[String, Int])

    // `containsNull` and `valueContainsNull` are defaulted by a reader exactly as `nullable` is, so comparing
    // them while ignoring `nullable` rejected valid files for a reason about Spark's representation rather than
    // about the data. All three carriers now read one axis, and the pin drops all three.
    val found =
      StructType(
        List(
          StructField("values", ArrayType(IntegerType, containsNull = true), nullable = false),
          StructField("metrics", MapType(StringType, IntegerType, valueContainsNull = true), nullable = false)
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.Exact.type]]

    assertEquals(expected.fields.head.dataType, ArrayType(IntegerType, containsNull = false))
    assertEquals(runtime.ok(found, expected), true)
  }

  test("PolicyRuntime Exact still rejects a leaf type change under a relaxed nested carrier") {
    final case class Contract(values: List[Int])

    // Tolerating a carrier is not tolerating a type change underneath it.
    val found   = StructType(List(StructField("values", ArrayType(StringType, containsNull = true))))
    val runtime = summon[PolicyRuntime[SchemaPolicy.Exact.type]]

    assertEquals(runtime.ok(found, summon[SparkSchema[Contract]].struct), false)
  }

  test("SchemaCheck default pin accepts nested optionality drift") {
    final case class Contract(values: List[Int])

    val df =
      emptyDf(
        StructType(
          List(
            StructField("values", ArrayType(IntegerType, containsNull = true), nullable = false)
          )
        )
      )

    SchemaCheck.assertMatchesContract[Contract](df)
  }

  test("[A8/D9] SchemaCheck surfaces case-insensitive duplicate field names in the runtime mismatch") {
    final case class Contract(email: String)

    val df =
      emptyDf(
        StructType(
          List(
            StructField("Email", StringType, nullable = false),
            StructField("email", StringType, nullable = false)
          )
        )
      )

    val ex = intercept[IllegalArgumentException] {
      SchemaCheck.assertMatchesContract[Contract](df)
    }

    assert(clue(ex.getMessage).contains("case-insensitive duplicate field names"))
    assert(clue(ex.getMessage).contains("[Email, email]"))
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

  test("PolicyRuntime ExactOrderedCI rejects reordered fields even when names only drift by case") {
    final case class Contract(id: Long, email: String)

    val found =
      StructType(
        List(
          StructField("EMAIL", StringType, nullable = false),
          StructField("ID", LongType, nullable = false)
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.ExactOrderedCI.type]]

    assertEquals(runtime.ok(found, expected), false)
  }

  test("PolicyRuntime ExactUnordered accepts reordering") {
    final case class Contract(id: Long, email: String)

    val found =
      StructType(
        List(
          StructField("email", StringType, nullable = false),
          StructField("id", LongType, nullable = false)
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.ExactUnordered.type]]

    assertEquals(runtime.ok(found, expected), true)
  }

  test("PolicyRuntime ExactUnordered rejects case drift that ExactUnorderedCI accepts") {
    final case class Contract(id: Long, email: String)

    val found =
      StructType(
        List(
          StructField("EMAIL", StringType, nullable = false),
          StructField("ID", LongType, nullable = false)
        )
      )

    val expected = summon[SparkSchema[Contract]].struct

    assertEquals(summon[PolicyRuntime[SchemaPolicy.ExactUnordered.type]].ok(found, expected), false)
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

  test("PolicyRuntime ExactUnorderedCI rejects structural type drift") {
    final case class Contract(id: Long, email: String)

    val found =
      StructType(
        List(
          StructField("ID", LongType, nullable = false),
          StructField("EMAIL", IntegerType, nullable = false)
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.ExactUnorderedCI.type]]

    assertEquals(runtime.ok(found, expected), false)
  }

  test("PolicyRuntime Backward accepts producer extras and missing optional or defaulted contract fields") {
    final case class Contract(id: Long, email: String, age: Option[Int], region: String = "IN")

    val found =
      StructType(
        List(
          StructField("id", LongType, nullable = false),
          StructField("email", StringType, nullable = false),
          StructField("segment", StringType, nullable = false)
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.Backward.type]]

    assertEquals(runtime.ok(found, expected), true)
  }

  test("PolicyRuntime Backward applies subset semantics recursively inside nested structs") {
    final case class Address(city: String, region: String = "IN")
    final case class Contract(id: Long, address: Address)

    val found =
      StructType(
        List(
          StructField("id", LongType, nullable = false),
          StructField(
            "address",
            StructType(
              List(
                StructField("city", StringType, nullable = false),
                StructField("segment", StringType, nullable = false)
              )
            ),
            nullable = false
          )
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.Backward.type]]

    assertEquals(runtime.ok(found, expected), true)
  }

  test("PolicyRuntime Backward rejects missing required contract fields") {
    final case class Contract(id: Long, email: String, age: Option[Int] = None)

    val found =
      StructType(
        List(
          StructField("id", LongType, nullable = false),
          StructField("segment", StringType, nullable = false)
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.Backward.type]]

    assertEquals(runtime.ok(found, expected), false)
  }

  test("PolicyRuntime Backward falls back to nullable-only allowance when expected metadata is missing") {
    val found =
      StructType(
        List(
          StructField("id", LongType, nullable = false),
          StructField("email", StringType, nullable = false)
        )
      )

    val expectedMissingNonNullable =
      StructType(
        List(
          StructField("id", LongType, nullable = false),
          StructField("email", StringType, nullable = false),
          StructField("region", StringType, nullable = false)
        )
      )

    val expectedMissingNullable =
      StructType(
        List(
          StructField("id", LongType, nullable = false),
          StructField("email", StringType, nullable = false),
          StructField("notes", StringType, nullable = true)
        )
      )

    val runtime = summon[PolicyRuntime[SchemaPolicy.Backward.type]]

    assertEquals(runtime.ok(found, expectedMissingNonNullable), false)
    assertEquals(runtime.ok(found, expectedMissingNullable), true)
  }

  test("PolicyRuntime Forward accepts a producer subset of the contract schema") {
    final case class Contract(id: Long, email: String, age: Option[Int], region: String)

    val found =
      StructType(
        List(
          StructField("id", LongType, nullable = false),
          StructField("email", StringType, nullable = false)
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.Forward.type]]

    assertEquals(runtime.ok(found, expected), true)
  }

  test("PolicyRuntime Forward applies subset semantics recursively inside nested structs") {
    final case class Address(city: String, region: String)
    final case class Contract(id: Long, address: Address)

    val found =
      StructType(
        List(
          StructField("id", LongType, nullable = false),
          StructField(
            "address",
            StructType(
              List(
                StructField("city", StringType, nullable = false)
              )
            ),
            nullable = false
          )
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.Forward.type]]

    assertEquals(runtime.ok(found, expected), true)
  }

  test("PolicyRuntime Forward rejects producer extras outside the contract") {
    final case class Contract(id: Long, email: String)

    val found =
      StructType(
        List(
          StructField("id", LongType, nullable = false),
          StructField("email", StringType, nullable = false),
          StructField("segment", StringType, nullable = false)
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.Forward.type]]

    assertEquals(runtime.ok(found, expected), false)
  }

  test("PolicyRuntime Exact accepts deep nested matching structures") {
    final case class Leaf(code: Int)
    final case class Middle(payload: Map[String, Option[Leaf]])
    final case class Contract(items: List[Middle])

    val found =
      StructType(
        List(
          StructField(
            "items",
            ArrayType(
              StructType(
                List(
                  StructField(
                    "payload",
                    MapType(
                      StringType,
                      StructType(List(StructField("code", IntegerType, nullable = false))),
                      valueContainsNull = true
                    ),
                    nullable = false
                  )
                )
              ),
              containsNull = false
            ),
            nullable = false
          )
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.Exact.type]]

    assertEquals(runtime.ok(found, expected), true)
  }

  test("PolicyRuntime Exact rejects deep nested mismatches beyond two levels") {
    final case class Leaf(code: Int)
    final case class Middle(payload: Map[String, Option[Leaf]])
    final case class Contract(items: List[Middle])

    val found =
      StructType(
        List(
          StructField(
            "items",
            ArrayType(
              StructType(
                List(
                  StructField(
                    "payload",
                    MapType(
                      StringType,
                      StructType(List(StructField("code", StringType, nullable = false))),
                      valueContainsNull = true
                    ),
                    nullable = false
                  )
                )
              ),
              containsNull = false
            ),
            nullable = false
          )
        )
      )

    val expected = summon[SparkSchema[Contract]].struct
    val runtime  = summon[PolicyRuntime[SchemaPolicy.Exact.type]]

    assertEquals(runtime.ok(found, expected), false)
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
