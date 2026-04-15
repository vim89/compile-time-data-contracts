package ctdc

import ctdc.SparkCore.SparkSchema
import munit.FunSuite
import org.apache.spark.sql.types.{ArrayType, IntegerType, LongType, MapType, StringType, StructField, StructType}

class SparkSchemaSpec extends FunSuite:

  test("SparkSchema preserves field nullability and nested element optionality") {
    final case class Payload(
        id: Long,
        tags: List[Option[Int]],
        metrics: Map[String, Option[Int]],
        notes: Option[String]
    )

    val struct = summon[SparkSchema[Payload]].struct

    assertEquals(
      struct,
      StructType(
        List(
          StructField("id", LongType, nullable = false),
          StructField("tags", ArrayType(IntegerType, containsNull = true), nullable = false),
          StructField("metrics", MapType(StringType, IntegerType, valueContainsNull = true), nullable = false),
          StructField("notes", StringType, nullable = true)
        )
      )
    )
  }

  test("SparkSchema distinguishes non-optional collection elements from optional ones") {
    final case class StrictPayload(values: List[Int], metrics: Map[String, Int])

    val struct = summon[SparkSchema[StrictPayload]].struct
    val values = struct("values").dataType.asInstanceOf[ArrayType]
    val map    = struct("metrics").dataType.asInstanceOf[MapType]

    assertEquals(values.containsNull, false)
    assertEquals(map.valueContainsNull, false)
  }
