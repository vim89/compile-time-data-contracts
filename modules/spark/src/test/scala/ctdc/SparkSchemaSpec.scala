package ctdc

import ctdc.SparkCore.SparkSchema
import munit.FunSuite
import org.apache.spark.sql.types.{ArrayType, IntegerType, LongType, MapType, MetadataBuilder, StringType, StructField, StructType}

import scala.compiletime.testing.typeCheckErrors

class SparkSchemaSpec extends FunSuite:
  private def metadata(hasDefault: Boolean) =
    new MetadataBuilder().putBoolean("ctdc.hasDefault", hasDefault).build()

  private inline def assertTypeFails(inline code: String, expectedSnippets: String*): Unit =
    val errors = typeCheckErrors(code)
    assert(
      errors.nonEmpty,
      clues("Expected code to fail typechecking, but it compiled successfully.")
    )
    val rendered = errors.map(_.message).mkString("\n---\n")
    expectedSnippets.foreach { snippet =>
      assert(
        rendered.contains(snippet),
        clues(s"Expected error output to contain: $snippet", rendered)
      )
    }

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

  test("[A1/A2/D12] SparkSchema rejects unsupported primitive leaves instead of silently mapping them to StringType") {
    assertTypeFails(
      """
        import ctdc.SparkCore.SparkSchema
        import java.util.UUID

        final case class Payload(id: UUID)

        SparkSchema.derived[Payload]
      """,
      "Unsupported type in SparkSchema derivation",
      "java.util.UUID"
    )
  }

  test("[A1/A2/D3] SparkSchema rejects enum leaves instead of silently mapping them to StringType") {
    assertTypeFails(
      """
        import ctdc.SparkCore.SparkSchema

        enum Status:
          case Active, Disabled

        final case class Payload(status: Status)

        SparkSchema.derived[Payload]
      """,
      "Unsupported type in SparkSchema derivation",
      "Status"
    )
  }

  test("SparkSchema reports a nested Option at a field rather than flattening it to one null bit") {
    // `nullable` is one bit, so it can record one layer. Setting it and dropping the rest derived the same
    // StructType for `Option[A]` and `Option[Option[A]]`, which is a loss the caller cannot see.
    assertTypeFails(
      """
        import ctdc.SparkCore.SparkSchema

        final case class Payload(notes: Option[Option[String]])

        SparkSchema.derived[Payload]
      """,
      "Unsupported nested Option in SparkSchema derivation",
      "StructField.nullable"
    )
  }

  test("SparkSchema reports a nested Option in a sequence element") {
    assertTypeFails(
      """
        import ctdc.SparkCore.SparkSchema

        final case class Payload(tags: List[Option[Option[Int]]])

        SparkSchema.derived[Payload]
      """,
      "Unsupported nested Option in SparkSchema derivation",
      "ArrayType.containsNull"
    )
  }

  test("SparkSchema reports a nested Option in a map value") {
    assertTypeFails(
      """
        import ctdc.SparkCore.SparkSchema

        final case class Payload(metrics: Map[String, Option[Option[Int]]])

        SparkSchema.derived[Payload]
      """,
      "Unsupported nested Option in SparkSchema derivation",
      "MapType.valueContainsNull"
    )
  }

  test("[A1/A2/D4] SparkSchema rejects tuple leaves explicitly") {
    assertTypeFails(
      """
        import ctdc.SparkCore.SparkSchema

        final case class Payload(coords: (Int, String))

        SparkSchema.derived[Payload]
      """,
      "Unsupported type in SparkSchema derivation"
    )
  }

  // A StructType has a fixed depth, so a recursive contract type has no schema at all. Before it was rejected
  // explicitly the derivation recursed until the compiler ran out of stack. The core walk refuses the same
  // shapes, which `SchemaConformsSpec` asserts, so a contract type is accepted by both halves or by neither.

  test("SparkSchema rejects a type that contains itself, naming the cycle") {
    assertTypeFails(
      """
        import ctdc.SparkCore.SparkSchema

        final case class Node(id: Long, next: Option[Node])

        SparkSchema.derived[Node]
      """,
      "Unsupported recursive type in SparkSchema derivation",
      "Node -> Node"
    )
  }

  test("SparkSchema rejects a cycle through a collection, naming the path into it") {
    assertTypeFails(
      """
        import ctdc.SparkCore.SparkSchema

        final case class Branch(id: Long, children: Seq[Branch])
        final case class Tree(root: Branch)

        SparkSchema.derived[Tree]
      """,
      "Unsupported recursive type in SparkSchema derivation",
      "Tree -> Branch -> Branch"
    )
  }
