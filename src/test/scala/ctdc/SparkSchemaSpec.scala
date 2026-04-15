package ctdc

import ctdc.SparkCore.SparkSchema
import munit.FunSuite
import org.apache.spark.sql.types.{ArrayType, IntegerType, LongType, MapType, MetadataBuilder, StringType, StructField, StructType}

class SparkSchemaSpec extends FunSuite:
  private def metadata(hasDefault: Boolean) =
    new MetadataBuilder().putBoolean("ctdc.hasDefault", hasDefault).build()

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
          StructField("id", LongType, nullable = false, metadata(false)),
          StructField("tags", ArrayType(IntegerType, containsNull = true), nullable = false, metadata(false)),
          StructField("metrics", MapType(StringType, IntegerType, valueContainsNull = true), nullable = false, metadata(false)),
          StructField("notes", StringType, nullable = true, metadata(false))
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

  test("SparkSchema records default-valued fields in metadata for runtime subset semantics") {
    final case class Payload(id: Long, notes: Option[String], region: String = "IN")

    val struct = summon[SparkSchema[Payload]].struct

    assertEquals(struct("id").metadata.getBoolean("ctdc.hasDefault"), false)
    assertEquals(struct("notes").metadata.getBoolean("ctdc.hasDefault"), false)
    assertEquals(struct("region").metadata.getBoolean("ctdc.hasDefault"), true)
  }
