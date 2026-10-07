package ctdc.probe

import org.apache.spark.sql.ctdcprobe.SparkPrivateComparators
import org.apache.spark.sql.types.*

/** Follow-up experiments on three cells of [[ComparatorMatrix]] that the matrix cannot explain on its own.
  *
  * The matrix answers "which drift does each predicate report as equal". It does not answer why, and three of its
  * results are only interpretable with a second measurement:
  *
  *   1. `sameType` accepted a casing change that `equalsIgnoreNullability` rejected, even though the documented
  *      difference between them is nullability and not casing. Either the matrix is wrong or `sameType` is not a
  *      function of its arguments.
  *   2. `equalsStructurallyByName` accepted a widening of a leaf type. If it ignores leaf types entirely then it is not
  *      a schema-equality predicate at all, and the matrix row understates that.
  *   3. `equalsIgnoreCompatibleNullability` rejected every nullability drift, which would make it the strictest
  *      predicate in the family. That contradicts its name, and the likely cause is that the matrix exercises it in one
  *      direction only.
  *
  * Each experiment below is a direct observation, printed with the inputs, so the claim in the paper can be checked
  * against the run rather than against prose.
  */
object ComparatorProbes:

  private def f(name: String, dt: DataType, nullable: Boolean): StructField =
    StructField(name, dt, nullable)

  private def struct(fields: StructField*): StructType = StructType(fields.toArray)

  private def yesNo(b: Boolean): String = if b then "equal" else "different"

  // ===== 1. IS sameType A FUNCTION OF ITS ARGUMENTS? =====

  /** Same two schemas, differing only in the casing of one field name, compared under each setting of
    * `spark.sql.caseSensitive`. If the two lines disagree then `sameType`'s verdict depends on ambient configuration,
    * and a contract check built on it means something different in two sessions of the same application.
    */
  private def probeSameTypeConfigDependence(): Unit =
    val baseline = struct(f("id", LongType, nullable = false), f("email", StringType, nullable = false))
    val drifted  = struct(f("id", LongType, nullable = false), f("EMAIL", StringType, nullable = false))

    println("1. sameType on a casing-only change, under each caseSensitive setting")
    println(s"   baseline: ${baseline.simpleString}")
    println(s"   drifted:  ${drifted.simpleString}")
    for caseSensitive <- List(false, true) do
      val verdict = SparkPrivateComparators.sameTypeUnderCaseSensitivity(drifted, baseline, caseSensitive)
      println(s"   spark.sql.caseSensitive=$caseSensitive -> ${yesNo(verdict)}")
    println(s"   ambient (no conf installed)     -> ${yesNo(SparkPrivateComparators.sameType(drifted, baseline))}")

  // ===== 2. DOES equalsStructurallyByName LOOK AT LEAF TYPES? =====

  /** Three pairs that differ only in a leaf type, at increasing depth. `equalsStructurallyByName` is documented as
    * comparing field names; the question is whether anything else survives.
    */
  private def probeStructurallyByNameLeafTypes(): Unit =
    val cs: (String, String) => Boolean = _ == _

    val cases = List(
      "top-level leaf Int -> Long" ->
        (struct(f("n", IntegerType, nullable = false)), struct(f("n", LongType, nullable = false))),
      "top-level leaf Int -> String" ->
        (struct(f("n", IntegerType, nullable = false)), struct(f("n", StringType, nullable = false))),
      "leaf inside a struct Int -> String" ->
        (
          struct(f("a", struct(f("n", IntegerType, nullable = false)), nullable = false)),
          struct(f("a", struct(f("n", StringType, nullable = false)), nullable = false))
        ),
      "struct replaced by a leaf of the same name" ->
        (
          struct(f("a", struct(f("n", IntegerType, nullable = false)), nullable = false)),
          struct(f("a", StringType, nullable = false))
        )
    )

    println("\n2. equalsStructurallyByName against leaf-type changes")
    cases.foreach { case (label, (baseline, drifted)) =>
      val verdict = DataType.equalsStructurallyByName(drifted, baseline, cs)
      println(s"   $label -> ${yesNo(verdict)}")
    }

  // ===== 3. IN WHICH DIRECTION DOES equalsIgnoreCompatibleNullability RELAX? =====

  /** The same pair in both argument orders. The matrix fixes one convention, which is correct for a table but hides an
    * asymmetric predicate's actual rule. `required -> optional` and `optional -> required` are different events at a
    * data boundary and a predicate that accepts one and rejects the other is a directional subtype check, not an
    * equality check.
    */
  private def probeCompatibleNullabilityDirection(): Unit =
    val pairs = List(
      "field nullability" ->
        (struct(f("id", LongType, nullable = false)), struct(f("id", LongType, nullable = true))),
      "array containsNull" ->
        (
          struct(f("tags", ArrayType(StringType, containsNull = false), nullable = false)),
          struct(f("tags", ArrayType(StringType, containsNull = true), nullable = false))
        ),
      "map valueContainsNull" ->
        (
          struct(f("attrs", MapType(StringType, IntegerType, valueContainsNull = false), nullable = false)),
          struct(f("attrs", MapType(StringType, IntegerType, valueContainsNull = true), nullable = false))
        )
    )

    println("\n3. equalsIgnoreCompatibleNullability in both directions (strict = the non-null schema)")
    pairs.foreach { case (label, (strict, relaxed)) =>
      val strictToRelaxed = SparkPrivateComparators.equalsIgnoreCompatibleNullability(strict, relaxed)
      val relaxedToStrict = SparkPrivateComparators.equalsIgnoreCompatibleNullability(relaxed, strict)
      println(s"   $label: strict->relaxed ${yesNo(strictToRelaxed)}, relaxed->strict ${yesNo(relaxedToStrict)}")
    }

  def main(args: Array[String]): Unit =
    probeSameTypeConfigDependence()
    probeStructurallyByNameLeafTypes()
    probeCompatibleNullabilityDirection()
