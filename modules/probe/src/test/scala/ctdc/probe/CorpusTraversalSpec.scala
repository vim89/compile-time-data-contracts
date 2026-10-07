package ctdc.probe

import ctdc.probe.CorpusRelevance.{Carrier, Facts}
import munit.FunSuite
import org.apache.avro.Schema

/**
 * The four schema shapes where this traversal and `spark-avro`'s converter part company.
 *
 * `CorpusRelevance` reports corpus-wide frequencies, and a frequency is only as good as the classification under it.
 * These fixtures pin the classification on the shapes that are easy to get wrong and rare enough in the corpus that a
 * silent misreading would not show up in a total: a slot that can only ever be null, a union of several non-null
 * alternatives, a record that refers to itself, and a record reused at two places that is not recursive at either.
 *
 * Each assertion is a statement about what the traversal records, not about what Spark produces. Where the two differ
 * the difference is the point, and the comment on each test says which way `SchemaConverters.toSqlTypeHelper` at
 * `v3.5.6` goes, so the measured divergence counts can be read against the converter rather than against a guess at
 * it.
 */
final class CorpusTraversalSpec extends FunSuite:

  private def parse(json: String): Schema = new Schema.Parser().parse(json)

  private def facts(json: String): Facts = CorpusRelevance.factsOf(parse(json))

  private def record(name: String, fields: String): String =
    s"""{"type":"record","name":"$name","fields":[$fields]}"""

  test("a union whose only branch is null has no data branch and counts as a null-only slot") {
    // The converter maps the remaining `null` to `NullType` with `nullable = true`. The traversal has a null branch
    // to see here, so the carrier bit agrees; what diverges is the leaf type, and the slot is flagged for that.
    val f = facts(record("NullOnlyUnion", """{"name":"nothing","type":["null"]}"""))
    assertEquals(f.nullOnlySlots, 1)
    assertEquals(f.complexUnionSlots, 0)
    assertEquals(f.slotsOf(Carrier.FieldNullable), CorpusRelevance.SlotCount(1, 1))
  }

  test("a slot declared as a bare null type is recorded as required where the converter calls it nullable") {
    // `case NULL => SchemaType(NullType, nullable = true)`. There is no union and therefore no null branch for
    // `isNullable` to find, so the traversal records the slot as required. The two disagree on the carrier bit itself.
    val f = facts(record("BareNull", """{"name":"nothing","type":"null"}"""))
    assertEquals(f.nullOnlySlots, 1)
    assertEquals(f.slotsOf(Carrier.FieldNullable), CorpusRelevance.SlotCount(1, 0))
  }

  test("a union of several non-null alternatives is one complex union slot") {
    // The converter turns this into a struct of `member0`, `member1`, every field `nullable = true`, so the
    // `DataFrame` gains two optional slots the declaration never wrote. The traversal counts the declared slot once.
    val f = facts(record("ComplexUnion", """{"name":"payload","type":["null","int","string","bytes"]}"""))
    assertEquals(f.complexUnionSlots, 1)
    assertEquals(f.nullOnlySlots, 0)
    assertEquals(f.slotsOf(Carrier.FieldNullable), CorpusRelevance.SlotCount(1, 1))
  }

  test("the two unions the converter widens to a single leaf are not counted as complex") {
    // `Set(INT, LONG) => LongType` and `Set(FLOAT, DOUBLE) => DoubleType`. No struct appears and no field slot is
    // introduced, so there is nothing to diverge on and counting them would overstate the gap.
    val f = facts(
      record(
        "WidenedUnions",
        """{"name":"a","type":["int","long"]},{"name":"b","type":["null","float","double"]}"""
      )
    )
    assertEquals(f.complexUnionSlots, 0)
  }

  test("a record that refers to itself is counted once as a recursive re-entry") {
    // `SchemaConverters` throws `IncompatibleSchemaException` on this schema, so it has no `StructType` at all and no
    // flags to compare against. The traversal terminates and records the re-entry rather than the absence.
    val f = facts(record("Node", """{"name":"next","type":["null","Node"]}"""))
    assertEquals(f.recursiveRecords, 1)
    assertEquals(f.reusedNamedTypes, 0)
    assertEquals(f.records, 1)
  }

  test("a named record used at two places is a reuse and not a recursion") {
    // The converter expands `Point` at both occurrences, so the `DataFrame` carries two copies of its slots. The
    // traversal counts the declaration once, which is the construct the paper measures, and reports the reuse.
    val point = """{"type":"record","name":"Point","fields":[{"name":"x","type":"int"}]}"""
    val f     = facts(record("Segment", s"""{"name":"from","type":$point},{"name":"to","type":"Point"}"""))
    assertEquals(f.reusedNamedTypes, 1)
    assertEquals(f.recursiveRecords, 0)
    assertEquals(f.records, 2)
    assertEquals(f.slotsOf(Carrier.FieldNullable), CorpusRelevance.SlotCount(3, 0))
  }

  test("a self-reference beside a plain sibling field is recursion and leaves the sibling countable") {
    // The guard has to be read off the right set. `path` closes over the branch being walked, so a sibling that
    // mentions the same name after the walk has returned is a reuse; only a mention still inside it is recursion.
    val f = facts(
      record(
        "Tree",
        """{"name":"left","type":["null","Tree"]},{"name":"label","type":"string"}"""
      )
    )
    assertEquals(f.recursiveRecords, 1)
    assertEquals(f.reusedNamedTypes, 0)
  }

  test("collection element and value slots are tallied for divergence the same way field slots are") {
    // A complex union is a complex union wherever it is declared. An array of `["int","string"]` becomes an array of
    // structs, so the introduced optional slots are inside the element type rather than beside it.
    val f = facts(
      record(
        "Collections",
        """{"name":"xs","type":{"type":"array","items":["int","string"]}},
          |{"name":"m","type":{"type":"map","values":["null"]}}""".stripMargin
      )
    )
    assertEquals(f.complexUnionSlots, 1)
    assertEquals(f.nullOnlySlots, 1)
    assertEquals(f.slotsOf(Carrier.ArrayContainsNull), CorpusRelevance.SlotCount(1, 0))
    assertEquals(f.slotsOf(Carrier.MapValueContainsNull), CorpusRelevance.SlotCount(1, 1))
  }

  test("convertedDifferently sums the four and is zero for a schema none of them touches") {
    val plain = facts(record("Plain", """{"name":"id","type":"long"},{"name":"name","type":["null","string"]}"""))
    assertEquals(plain.convertedDifferently, 0)

    val touched = facts(record("Touched", """{"name":"a","type":"null"},{"name":"b","type":["int","string"]}"""))
    assertEquals(touched.convertedDifferently, 2)
  }
