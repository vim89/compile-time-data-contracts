package ctdc.probe

import ctdc.SchemaPolicy
import ctdc.SparkCore.{PolicyRuntime, SparkSchema}
import ctdc.internal.TypeShape.*
import ctdc.internal.{ComparisonRules, ShapeDiff, TypeShape}
import org.apache.spark.sql.ctdcprobe.SparkPrivateComparators
import org.apache.spark.sql.types.*

import java.nio.file.{Files, Path}

/** Characterisation harness for schema-equality predicates.
  *
  * This does not test ctdc. It measures what Spark's own comparators do, so that a claim about which structural drift
  * they detect is produced by executing Spark rather than by reading its source and guessing. Every number in the
  * paper's comparator table comes from here.
  *
  * Method: minimal pairs. Each drift case differs from its baseline along exactly one axis, and the two schemas are
  * otherwise identical, so a predicate's verdict on a row is attributable to that axis alone. A predicate that reports
  * the pair as equal has missed that drift.
  *
  * The run is pure schema comparison. No SparkSession is started, because none of these predicates touch a session.
  */
object ComparatorMatrix:

  /** A predicate's verdict on one pair. `ReportsEqual` means the predicate accepted the drifted schema. */
  enum Verdict:
    case ReportsEqual, ReportsDifferent, Errored

  /** `owner` separates Spark's shipped predicates from ctdc's, so the table can state what is upstream behaviour and
    * what is this artifact's.
    *
    * ctdc's two halves are separate owners because they do not see the same thing. The runtime half compares two
    * `StructType`s, and a `StructType` arriving from a reader has already lost whether `nullable = true` was a claim
    * or a default. The compile-time half compares the Scala types, where `Option[A]` and `A` are different types and
    * the claim is still present. Reporting them as one predicate would hide the gap this table exists to measure.
    */
  enum Owner:
    case Spark, CtdcRuntime, CtdcCompileTime

  final case class Predicate(name: String, owner: Owner, run: (StructType, StructType) => Boolean)

  /** `axis` groups rows in the paper table; `semantic` records why the drift matters, so a reader can judge whether
    * "reports equal" is a defect or a deliberate, documented relaxation.
    */
  final case class DriftCase(
      name: String,
      axis: String,
      semantic: String,
      baseline: StructType,
      drifted: StructType
  )

  final case class Cell(drift: DriftCase, predicate: Predicate, verdict: Verdict)

  // ===== SCHEMA BUILDING HELPERS =====
  // Written out longhand rather than derived from case classes: the point is to control each bit independently,
  // including combinations no Scala type can express (for example a non-nullable field holding a null-containing
  // array), which is exactly where upstream comparators are suspected of collapsing axes.

  private def f(name: String, dt: DataType, nullable: Boolean): StructField =
    StructField(name, dt, nullable)

  private def struct(fields: StructField*): StructType = StructType(fields.toArray)

  /** The same schema as the [[TypeShape]] ctdc's macro would have built for it.
    *
    * Written here rather than reusing the macro because the macro starts from a Scala type, and several of the drift
    * cases below are schemas no Scala type can express. The translation is faithful in this direction: each of the
    * three carriers of optionality has its own home in `TypeShape` - a field's `isOptional`, or an `OptionalShape`
    * around a sequence element or a map value - so nothing is collapsed on the way in. It is the way back that cannot
    * be written, and that asymmetry is the finding, not an implementation detail.
    */
  private def shapeOf(dt: DataType): TypeShape = dt match
    case s: StructType                 => StructShape(s.fields.toList.map(shapeOfField))
    case ArrayType(elem, containsNull) => SequenceShape(nest(shapeOf(elem), containsNull))
    case MapType(key, value, valueContainsNull) =>
      MapShape(PrimitiveShape(key.typeName), nest(shapeOf(value), valueContainsNull))
    case leaf => PrimitiveShape(leaf.typeName)

  private def shapeOfField(field: StructField): FieldShape =
    // `hasDefault` is false throughout: none of these pairs is about defaults, and a default is a property of a Scala
    // declaration that a `StructField` does not carry.
    FieldShape(field.name, shapeOf(field.dataType), hasDefault = false, isOptional = field.nullable)

  private def nest(shape: TypeShape, optional: Boolean): TypeShape =
    if optional then OptionalShape(shape) else shape

  // ===== DRIFT TAXONOMY =====

  private val driftCases: List[DriftCase] =
    // --- axis: field nullability (top level) ---
    val fieldNullableBase = struct(f("id", LongType, nullable = false))
    val fieldNullableTop = DriftCase(
      name = "field_nullable_top_level",
      axis = "field nullability",
      semantic = "A required field became optional. Downstream code that never null-checks can now see null.",
      baseline = fieldNullableBase,
      drifted = struct(f("id", LongType, nullable = true))
    )

    // --- axis: field nullability (nested struct) ---
    val nestedNullableBase =
      struct(f("addr", struct(f("city", StringType, nullable = false)), nullable = false))
    val fieldNullableNested = DriftCase(
      name = "field_nullable_nested_struct",
      axis = "field nullability",
      semantic = "Same as above but one level down, where manual review is least likely to catch it.",
      baseline = nestedNullableBase,
      drifted = struct(f("addr", struct(f("city", StringType, nullable = true)), nullable = false))
    )

    // --- axis: collection element optionality ---
    val arrayBase = struct(f("tags", ArrayType(StringType, containsNull = false), nullable = false))
    val arrayContainsNull = DriftCase(
      name = "array_contains_null",
      axis = "collection optionality",
      semantic = "Seq[String] became Seq[Option[String]]. Elements can now be null inside a non-null array.",
      baseline = arrayBase,
      drifted = struct(f("tags", ArrayType(StringType, containsNull = true), nullable = false))
    )

    val mapBase =
      struct(f("attrs", MapType(StringType, IntegerType, valueContainsNull = false), nullable = false))
    val mapValueContainsNull = DriftCase(
      name = "map_value_contains_null",
      axis = "collection optionality",
      semantic = "Map[String,Int] became Map[String,Option[Int]]. Values can now be null.",
      baseline = mapBase,
      drifted = struct(f("attrs", MapType(StringType, IntegerType, valueContainsNull = true), nullable = false))
    )

    // --- axis: optionality relocation ---
    // The sharpest case. Both schemas carry exactly one "can be null" bit; the bit moved between the field and the
    // element. Option[Seq[String]] and Seq[Option[String]] are different contracts: the first can be absent wholesale,
    // the second has holes. Any predicate that discards nullability before recursing cannot distinguish them.
    val optionalityRelocated = DriftCase(
      name = "optionality_field_to_element",
      axis = "optionality relocation",
      semantic = "Option[Seq[String]] became Seq[Option[String]]. Absence moved from the collection to its elements.",
      baseline = struct(f("tags", ArrayType(StringType, containsNull = false), nullable = true)),
      drifted = struct(f("tags", ArrayType(StringType, containsNull = true), nullable = false))
    )

    // --- axis: collection optionality inside an array of structs ---
    val arrayOfStructBase =
      struct(
        f("events", ArrayType(struct(f("kind", StringType, nullable = false)), containsNull = false), nullable = false)
      )
    val arrayOfStructFieldNullable = DriftCase(
      name = "array_of_struct_field_nullable",
      axis = "field nullability",
      semantic = "A field inside array elements became optional. Two levels of nesting plus a collection.",
      baseline = arrayOfStructBase,
      drifted = struct(
        f("events", ArrayType(struct(f("kind", StringType, nullable = true)), containsNull = false), nullable = false)
      )
    )

    // --- axis: field set ---
    val fieldSetBase = struct(f("id", LongType, nullable = false), f("email", StringType, nullable = false))
    val fieldMissing = DriftCase(
      name = "field_missing",
      axis = "field set",
      semantic = "A contract field disappeared from the producer.",
      baseline = fieldSetBase,
      drifted = struct(f("id", LongType, nullable = false))
    )
    val fieldAdded = DriftCase(
      name = "field_added",
      axis = "field set",
      semantic = "The producer gained a field the contract does not declare.",
      baseline = fieldSetBase,
      drifted = struct(
        f("id", LongType, nullable = false),
        f("email", StringType, nullable = false),
        f("extra", StringType, nullable = false)
      )
    )

    // --- axis: field identity ---
    val fieldReordered = DriftCase(
      name = "field_reordered",
      axis = "field identity",
      semantic = "Same fields, different order. Breaks positional reads, harmless for by-name reads.",
      baseline = fieldSetBase,
      drifted = struct(f("email", StringType, nullable = false), f("id", LongType, nullable = false))
    )
    val fieldRenamed = DriftCase(
      name = "field_renamed",
      axis = "field identity",
      semantic = "A field was renamed. Positional reads silently keep working on the wrong name.",
      baseline = fieldSetBase,
      drifted = struct(f("id", LongType, nullable = false), f("mail", StringType, nullable = false))
    )
    val fieldCaseChanged = DriftCase(
      name = "field_case_changed",
      axis = "field identity",
      semantic = "Casing changed only. Matters for case-sensitive sinks, not for Spark's default resolver.",
      baseline = fieldSetBase,
      drifted = struct(f("id", LongType, nullable = false), f("EMAIL", StringType, nullable = false))
    )

    // --- axis: leaf type ---
    val leafWidened = DriftCase(
      name = "leaf_type_widened",
      axis = "leaf type",
      semantic = "Int became Long. A widening that is safe to read but changes the physical layout.",
      baseline = struct(f("n", IntegerType, nullable = false)),
      drifted = struct(f("n", LongType, nullable = false))
    )

    List(
      fieldNullableTop,
      fieldNullableNested,
      arrayContainsNull,
      mapValueContainsNull,
      optionalityRelocated,
      arrayOfStructFieldNullable,
      fieldMissing,
      fieldAdded,
      fieldReordered,
      fieldRenamed,
      fieldCaseChanged,
      leafWidened
    )

  // ===== PREDICATES UNDER TEST =====

  private val caseSensitiveResolver: (String, String) => Boolean   = _ == _
  private val caseInsensitiveResolver: (String, String) => Boolean = _.equalsIgnoreCase(_)

  /** The policies that get a compile-time row.
    *
    * The same six the runtime arm pins below, in the same order, so every compile-time row has a runtime row to be
    * read against. A difference between the two rows of one policy is the gap between what the claim says and what a
    * `StructType` can still state about it, which is the only reason both arms are in this table.
    */
  private val compileTimePolicies: List[SchemaPolicy] =
    List(
      SchemaPolicy.Exact,
      SchemaPolicy.ExactUnorderedCI,
      SchemaPolicy.ExactOrdered,
      SchemaPolicy.ExactByPosition,
      SchemaPolicy.Backward,
      SchemaPolicy.Forward
    )

  private val predicates: List[Predicate] =
    List(
      Predicate("spark_equals", Owner.Spark, (a, b) => a == b),
      Predicate("spark_same_type", Owner.Spark, (a, b) => SparkPrivateComparators.sameType(a, b)),
      Predicate(
        "spark_equals_ignore_nullability",
        Owner.Spark,
        (a, b) => DataType.equalsIgnoreNullability(a, b)
      ),
      Predicate(
        "spark_equals_ignore_case_and_nullability",
        Owner.Spark,
        (a, b) => DataType.equalsIgnoreCaseAndNullability(a, b)
      ),
      Predicate(
        "spark_equals_ignore_compatible_nullability",
        Owner.Spark,
        (a, b) => SparkPrivateComparators.equalsIgnoreCompatibleNullability(a, b)
      ),
      Predicate(
        "spark_equals_structurally_strict",
        Owner.Spark,
        (a, b) => DataType.equalsStructurally(a, b, ignoreNullability = false)
      ),
      Predicate(
        "spark_equals_structurally_ignore_nullability",
        Owner.Spark,
        (a, b) => DataType.equalsStructurally(a, b, ignoreNullability = true)
      ),
      Predicate(
        "spark_equals_structurally_by_name_cs",
        Owner.Spark,
        (a, b) => DataType.equalsStructurallyByName(a, b, caseSensitiveResolver)
      ),
      Predicate(
        "spark_equals_structurally_by_name_ci",
        Owner.Spark,
        (a, b) => DataType.equalsStructurallyByName(a, b, caseInsensitiveResolver)
      ),
      Predicate(
        "ctdc_rt_exact",
        Owner.CtdcRuntime,
        summon[PolicyRuntime[SchemaPolicy.Exact.type]].ok
      ),
      Predicate(
        "ctdc_rt_exact_unordered_ci",
        Owner.CtdcRuntime,
        summon[PolicyRuntime[SchemaPolicy.ExactUnorderedCI.type]].ok
      ),
      Predicate(
        "ctdc_rt_exact_ordered",
        Owner.CtdcRuntime,
        summon[PolicyRuntime[SchemaPolicy.ExactOrdered.type]].ok
      ),
      Predicate(
        "ctdc_rt_exact_by_position",
        Owner.CtdcRuntime,
        summon[PolicyRuntime[SchemaPolicy.ExactByPosition.type]].ok
      ),
      Predicate(
        "ctdc_rt_backward",
        Owner.CtdcRuntime,
        summon[PolicyRuntime[SchemaPolicy.Backward.type]].ok
      ),
      Predicate(
        "ctdc_rt_forward",
        Owner.CtdcRuntime,
        summon[PolicyRuntime[SchemaPolicy.Forward.type]].ok
      )
    ) ::: compileTimePolicies.map(compileTime)

  /** The comparison ctdc's macro performs for `policy`, run on a schema pair instead of a type pair.
    *
    * `ShapeDiff` is the whole of that comparison: each version's macro only turns types into shapes and a report into
    * a compile error. So this is the same predicate the compiler applies, not a reimplementation of it, which is what
    * makes the compile-time rows comparable with the rest of the table.
    */
  private def compileTime(policy: SchemaPolicy): Predicate =
    Predicate(
      s"ctdc_ct_${snakeCase(policy.toString)}",
      Owner.CtdcCompileTime,
      (found, expected) => ShapeDiff.diff(ComparisonRules.of(policy), shapeOf(found), shapeOf(expected)).isEmpty
    )

  private def snakeCase(name: String): String =
    name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT)

  // ===== EXECUTION =====

  /** Direction matters for subset policies, so the convention is fixed and stated once: the drifted schema is what
    * arrived at the boundary (`found`), the baseline is what the contract declared (`expected`).
    */
  private def evaluate(drift: DriftCase, predicate: Predicate): Cell =
    val verdict =
      try if predicate.run(drift.drifted, drift.baseline) then Verdict.ReportsEqual else Verdict.ReportsDifferent
      catch case _: Throwable => Verdict.Errored
    Cell(drift, predicate, verdict)

  def run(): List[Cell] =
    for
      drift     <- driftCases
      predicate <- predicates
    yield evaluate(drift, predicate)

  private def csv(cells: List[Cell]): String =
    val header = "drift_case,axis,predicate,owner,verdict"
    val rows = cells.map { cell =>
      s"${cell.drift.name},${cell.drift.axis},${cell.predicate.name},${cell.predicate.owner},${cell.verdict}"
    }
    (header :: rows).mkString("\n")

  /** A compact pivot for reading at a glance: one row per drift case, one column per predicate, `.` where the
    * predicate accepted the drift (missed it) and `X` where it rejected it (caught it).
    */
  private def pivot(cells: List[Cell]): String =
    val byDrift = cells.groupBy(_.drift.name)
    val predicateNames = predicates.map(_.name)
    val nameWidth = (driftCases.map(_.name.length) :+ 0).max
    val header =
      " " * nameWidth + "  " + predicateNames.map(_.take(6).padTo(7, ' ')).mkString
    val legend = predicateNames.zipWithIndex.map { case (n, i) => f"  [$i%2d] $n" }.mkString("\n")
    val rows = driftCases.map { drift =>
      val marks = predicateNames.map { pn =>
        byDrift(drift.name).find(_.predicate.name == pn).map(_.verdict) match
          case Some(Verdict.ReportsEqual)     => ".      "
          case Some(Verdict.ReportsDifferent) => "X      "
          case Some(Verdict.Errored)          => "!      "
          case None                           => "?      "
      }.mkString
      drift.name.padTo(nameWidth, ' ') + "  " + marks
    }
    s"legend: X = drift rejected, . = drift accepted (missed), ! = threw\n\n$header\n${rows.mkString("\n")}\n\n$legend"

  private def verdictOf(cells: List[Cell], predicate: String, drift: String): Verdict =
    cells.find(c => c.predicate.name == predicate && c.drift.name == drift).map(_.verdict).get

  /** The paper's central claim, computed rather than asserted.
    *
    * "Can be null" is carried in three independent places in a Spark schema: `StructField.nullable`,
    * `ArrayType.containsNull` and `MapType.valueContainsNull`. They are different statements about data. A field that
    * went optional means a row can lack a value; an array whose elements went optional means a present collection has
    * holes. A contract usually wants to treat them differently.
    *
    * This groups the predicates by the triple of verdicts they return on those three axes. Every group of size > 1
    * whose members all agree is a predicate that cannot tell the axes apart. If every group is internally uniform, the
    * family offers no way to be lenient on one carrier and strict on another, whatever the caller wants.
    */
  private def optionalityCarrierSignatures(cells: List[Cell]): String =
    val carriers =
      List("field_nullable_top_level", "array_contains_null", "map_value_contains_null")
    val mark: Verdict => String =
      case Verdict.ReportsEqual     => "accept"
      case Verdict.ReportsDifferent => "reject"
      case Verdict.Errored          => "threw "
    val grouped =
      predicates.groupBy(p => carriers.map(d => mark(verdictOf(cells, p.name, d))))
    val lines = grouped.toList
      .sortBy(_._1.mkString)
      .map { case (sig, ps) =>
        val shape = if sig.distinct.size == 1 then "uniform" else "mixed"
        s"  ${sig.mkString("[", " ", "]")} $shape: ${ps.map(_.name).mkString(", ")}"
      }
    // Reported per owner rather than over the whole family, because a mixed signature does not mean the same thing
    // for each of them. For Spark's shipped predicates it would mean a caller can pick leniency per carrier. For
    // ctdc's runtime half it means something else entirely: `field.nullable` is accepted not as a choice but because
    // a `StructType` that came from a reader no longer records whether `true` was the producer's claim or its
    // default. The compile-time half, which still has the Scala types, rejects all three.
    val sparkUniform = grouped.filter(_._2.exists(_.owner == Owner.Spark)).keys.forall(_.distinct.size == 1)
    val verdict =
      if sparkUniform then
        "  every shipped Spark predicate treats the three carriers identically: none lets a caller be strict on one"
      else "  some shipped Spark predicate discriminates between the carriers"
    s"optionality carriers [field.nullable array.containsNull map.valueContainsNull]\n${lines.mkString("\n")}\n$verdict"

  /** Which predicates implement the policy a data contract actually asks for: reject a relocation of optionality,
    * tolerate a reordering of fields. A predicate strict enough for the first is usually strict about the second too,
    * which is why callers end up choosing between a check that is too loose and one that is too noisy.
    */
  private def contractShapedPredicates(cells: List[Cell]): String =
    val wanted = predicates.filter { p =>
      verdictOf(cells, p.name, "optionality_field_to_element") == Verdict.ReportsDifferent &&
      verdictOf(cells, p.name, "field_reordered") == Verdict.ReportsEqual
    }
    val listing = if wanted.isEmpty then "  (none)" else wanted.map(p => s"  ${p.name} [${p.owner}]").mkString("\n")
    s"rejects optionality relocation and tolerates field reordering\n$listing"

  def main(args: Array[String]): Unit =
    val cells = run()

    args.headOption.map(Path.of(_)).foreach { path =>
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.writeString(path, csv(cells) + "\n")
    }

    println(pivot(cells))
    println()
    println(optionalityCarrierSignatures(cells))
    println()
    println(contractShapedPredicates(cells))
