package ctdc.probe

import ctdc.SchemaPolicy
import ctdc.SparkCore.{PolicyRuntime, SparkSchema}
import ctdc.internal.TypeShape.*
import ctdc.internal.{ComparisonRules, ShapeDiff, TypeShape}
import ctdc.probe.DriftTaxonomy.{DriftCase, DriftId, Edit, Position, Slot}
import org.apache.spark.sql.ctdcprobe.SparkPrivateComparators
import org.apache.spark.sql.types.*

import java.nio.file.{Files, Path}

/** Characterisation harness for schema-equality predicates.
  *
  * This does not test ctdc. It measures what Spark's own comparators do, so that a claim about which structural drift
  * they detect is produced by executing Spark rather than by reading its source and guessing. Every number in the
  * paper's comparator table comes from here.
  *
  * Method: controlled stimuli in minimal pairs. Each drift case differs from its baseline along exactly one slot of
  * `StructType`'s grammar, and the two schemas are otherwise identical, so a predicate's verdict on a row is
  * attributable to that slot alone. A predicate that reports the pair as equal has missed that drift. The rows are
  * not samples of real schemas and nothing here is a case study; what real schemas contain is measured separately by
  * [[CorpusRelevance]].
  *
  * The rows come from [[DriftTaxonomy]], which derives them from the grammar rather than choosing them, so both sides
  * of this table are enumerated: nine shipped Spark comparators because that is all Spark 3.5.6 exposes, and the
  * drift axes because that is what the grammar admits.
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

  /** Which of a pair's two schemas is passed in the `found` position.
    *
    * Both orders are run because not every predicate is symmetric, and a table that fixed one order would report a
    * directional subtype check as though it were an equality check. `equalsIgnoreCompatibleNullability` is exactly
    * that: it accepts a strict schema where a relaxed one was expected and rejects the reverse. Measured in one
    * order only, it appears to be the strictest predicate in the family, which is a mis-characterisation rather than
    * an incomplete one.
    *
    * Two cases rather than a `Boolean` for the reason this whole table exists: a flag at a call site does not say
    * which way round it means.
    */
  enum Direction:
    case DriftedAsFound, BaselineAsFound

  final case class Cell(drift: DriftCase, predicate: Predicate, direction: Direction, verdict: Verdict)

  // ===== DRIFT ROWS =====

  private val driftCases: List[DriftCase] = DriftTaxonomy.cases

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

  /** One predicate applied to one pair in one order.
    *
    * The primary direction is the one a boundary check actually performs, and the convention is fixed and stated
    * once: the drifted schema is what arrived at the boundary (`found`), the baseline is what the contract declared
    * (`expected`). [[Direction.BaselineAsFound]] is the same pair swapped, which is a different question and is
    * reported separately rather than mixed into the same column.
    */
  private def evaluate(drift: DriftCase, predicate: Predicate, direction: Direction): Cell =
    val (found, expected) = direction match
      case Direction.DriftedAsFound  => (drift.drifted, drift.baseline)
      case Direction.BaselineAsFound => (drift.baseline, drift.drifted)
    val verdict =
      try if predicate.run(found, expected) then Verdict.ReportsEqual else Verdict.ReportsDifferent
      catch case _: Throwable => Verdict.Errored
    Cell(drift, predicate, direction, verdict)

  def run(): List[Cell] =
    for
      drift     <- driftCases
      predicate <- predicates
      direction <- Direction.values.toList
    yield evaluate(drift, predicate, direction)

  /** `slot` and `edit` are emitted next to the row name so that the derivation is visible in the data file itself: a
    * reader can group the CSV by either and get the same partition the taxonomy defines, without parsing the name.
    */
  private def csv(cells: List[Cell]): String =
    val header = "drift_case,axis,position,predicate,owner,direction,verdict"
    val rows = cells.map { cell =>
      s"${cell.drift.name},${cell.drift.axis},${DriftTaxonomy.positionOf(cell.drift.id)}," +
        s"${cell.predicate.name},${cell.predicate.owner},${cell.direction},${cell.verdict}"
    }
    (header :: rows).mkString("\n")

  /** A compact pivot for reading at a glance: one row per drift case, one column per predicate, `.` where the
    * predicate accepted the drift (missed it) and `X` where it rejected it (caught it).
    *
    * The primary direction only. A grid that averaged the two orders, or silently picked one, would be unreadable
    * for exactly the predicates where the order is the finding; those are listed by `directionalPredicates`.
    */
  private def pivot(cells: List[Cell]): String =
    val byDrift = cells.filter(_.direction == Direction.DriftedAsFound).groupBy(_.drift.name)
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
    s"legend: X = drift rejected, . = drift accepted (missed), ! = threw" +
      s"\ndirection: drifted schema as found, baseline as expected" +
      s"\n\n$header\n${rows.mkString("\n")}\n\n$legend"

  /** `direction` is required rather than defaulted, so that no report can depend on an argument order it never
    * named. That is the same mistake, one level up, as the one C4 records in Spark's own predicate family.
    *
    * `drift` is a [[DriftId]] and not a row name, so a report cannot ask for a row the taxonomy does not generate.
    * Before the taxonomy was derived, the rows were addressed by string and renaming one would have left a report
    * looking up a row that no longer existed.
    */
  private def verdictOf(cells: List[Cell], predicate: String, drift: DriftId, direction: Direction): Verdict =
    cells
      .find(c => c.predicate.name == predicate && c.drift.id == drift && c.direction == direction)
      .map(_.verdict)
      .get

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
    // The three optionality slots of the grammar, each flipped at the root. Named as slots rather than as row names
    // because that is what makes the list exhaustive: these are the only optionality slots `StructType` has.
    val carriers =
      List(Slot.FieldNullable, Slot.ArrayContainsNull, Slot.MapValueContainsNull)
        .map(DriftId.Single(_, Edit.Flip, Position.Root))
    val mark: Verdict => String =
      case Verdict.ReportsEqual     => "accept"
      case Verdict.ReportsDifferent => "reject"
      case Verdict.Errored          => "threw "
    val grouped =
      predicates.groupBy(p => carriers.map(d => mark(verdictOf(cells, p.name, d, Direction.DriftedAsFound))))
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

  /** Which predicates are not symmetric, and on which axes.
    *
    * A predicate whose verdict changes when the pair is swapped is a subtype or subset check wearing the name of an
    * equality check. That cannot be read off a single-order table, and missing it mis-ranks the family: a
    * directional check looks maximally strict in whichever order happens to be the one it rejects.
    */
  private def directionalPredicates(cells: List[Cell]): String =
    val asymmetric = predicates.flatMap { p =>
      val axes = driftCases
        .filter { d =>
          verdictOf(cells, p.name, d.id, Direction.DriftedAsFound) !=
            verdictOf(cells, p.name, d.id, Direction.BaselineAsFound)
        }
        .map(_.name)
      if axes.isEmpty then None else Some(p -> axes)
    }
    val listing =
      if asymmetric.isEmpty then "  (none: every predicate returned the same verdict in both directions)"
      else
        asymmetric
          .map((p, axes) => s"  ${p.name} [${p.owner}] differs on: ${axes.mkString(", ")}")
          .mkString("\n")
    s"direction-sensitive predicates (verdict changes when the pair is swapped)\n$listing"

  /** Whether depth changes any verdict, which is what decides how far the taxonomy's claim reaches.
    *
    * The grammar is recursive, so each slot occurs at unbounded depth and the enumeration can only instantiate finitely
    * many of them. [[DriftTaxonomy]] instantiates two, and this is the check that makes two enough: for every
    * predicate and every edit, the root row and the nested row are compared. An empty report means every predicate in
    * the table recurses uniformly, and a result measured at one depth therefore holds at any depth. A non-empty report
    * is the more interesting outcome and is listed rather than summarised, because a predicate that behaves
    * differently one level down is a predicate whose published description does not say what it does.
    */
  private def recursionUniformity(cells: List[Cell]): String =
    val rootRows = driftCases.filter(d => DriftTaxonomy.positionOf(d.id) == Position.Root)
    val nestedBy = driftCases
      .filter(d => DriftTaxonomy.positionOf(d.id) == Position.Nested)
      .map(d => DriftTaxonomy.edgeOf(d.id) -> d)
      .toMap
    val divergent =
      for
        predicate <- predicates
        root      <- rootRows
        nested    <- nestedBy.get(DriftTaxonomy.edgeOf(root.id)).toList
        direction <- Direction.values.toList
        if verdictOf(cells, predicate.name, root.id, direction) !=
          verdictOf(cells, predicate.name, nested.id, direction)
      yield s"  ${predicate.name} [${predicate.owner}] on ${root.axis}, $direction: " +
        s"root ${verdictOf(cells, predicate.name, root.id, direction)}, " +
        s"nested ${verdictOf(cells, predicate.name, nested.id, direction)}"
    val listing =
      if divergent.isEmpty then
        "  (none: every predicate returned the same verdict at both depths, so the one-depth result generalises)"
      else divergent.mkString("\n")
    s"depth sensitivity (same edit at the root and one level down)\n$listing"

  /** One requirement a schema-compatibility checker has to meet, and the specification it comes from.
    *
    * `source` is the reason this type exists. If the requirements were ctdc's own preferences, the finding that no
    * Spark predicate meets them would be circular: the target would have been drawn around the predicate that
    * occupies it. Each requirement below is instead read off a specification written by other people for other
    * systems, and the matrix then measures who satisfies it.
    *
    * A requirement is data - an axis and the verdict the specification demands on it - rather than a function, so it
    * can be printed next to its citation and checked by a reader against the pivot above.
    */
  final case class Requirement(name: String, source: String, demand: String, drift: DriftId, required: Verdict)

  /** The requirements the external specifications impose.
    *
    * R1 is Avro's schema resolution rule for records, which governs reader-against-writer matching wherever Avro is
    * the encoding: "the ordering of fields may be different: fields are matched by name". A checker that rejects a
    * reordered pair rejects a pair the governing rule declares compatible. Against the standard that is a false
    * positive, not strictness.
    *
    * R2 is Confluent Schema Registry's compatibility levels, which are stated in terms of optionality and almost
    * nothing else: BACKWARD permits "add optional fields, remove fields", FORWARD permits "remove optional fields,
    * add fields", FULL permits "add/remove optional fields only". A checker that cannot see whether a field is
    * optional cannot decide any of those levels. Ignoring the carrier therefore does not make a predicate lenient,
    * it makes it unable to implement the rules at all.
    *
    * Neither specification was written for ctdc and neither mentions Spark. Their conjunction is what makes the
    * empty cell in this table a defect rather than a defensible design choice.
    */
  private val requirements: List[Requirement] =
    List(
      Requirement(
        name = "R1 tolerates field reordering",
        source = "Avro 1.12.0 specification, Schema Resolution, records: fields are matched by name",
        demand = "a reordered pair must be accepted",
        drift = DriftId.Single(Slot.FieldSet, Edit.Permute, Position.Root),
        required = Verdict.ReportsEqual
      ),
      Requirement(
        name = "R2 reads field-level optionality",
        source = "Confluent Schema Registry compatibility levels, stated in terms of optional fields",
        demand = "a required field that became optional must be rejected",
        drift = DriftId.Single(Slot.FieldNullable, Edit.Flip, Position.Root),
        required = Verdict.ReportsDifferent
      )
    )

  /** Who satisfies the external requirements, separately and jointly.
    *
    * Reported per requirement as well as jointly so that the derivation stays visible: a reader who disputes one
    * requirement can see exactly which predicates it eliminated, instead of being handed a single filtered list.
    *
    * The primary direction only. Both requirements are about what a boundary check must do to a schema that
    * arrived, which is the drifted one.
    */
  private def requirementSatisfaction(cells: List[Cell]): String =
    def satisfies(predicate: Predicate, requirement: Requirement): Boolean =
      verdictOf(cells, predicate.name, requirement.drift, Direction.DriftedAsFound) == requirement.required

    val perRequirement = requirements.map { requirement =>
      val holders = predicates.filter(satisfies(_, requirement))
      val byOwner = Owner.values.toList
        .map(owner => s"$owner ${holders.count(_.owner == owner)}/${predicates.count(_.owner == owner)}")
        .mkString(", ")
      s"  ${requirement.name}\n    source: ${requirement.source}" +
        s"\n    demand: ${requirement.demand}\n    satisfied by: $byOwner"
    }
    val joint = predicates.filter(p => requirements.forall(satisfies(p, _)))
    val jointListing =
      if joint.isEmpty then "  (none)" else joint.map(p => s"  ${p.name} [${p.owner}]").mkString("\n")
    s"external requirements\n${perRequirement.mkString("\n")}\n\nsatisfies both\n$jointListing"

  def main(args: Array[String]): Unit =
    val cells = run()

    args.headOption.map(Path.of(_)).foreach { path =>
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.writeString(path, csv(cells) + "\n")
    }

    println(DriftTaxonomy.census)
    println()
    println(pivot(cells))
    println()
    println(optionalityCarrierSignatures(cells))
    println()
    println(directionalPredicates(cells))
    println()
    println(recursionUniformity(cells))
    println()
    println(requirementSatisfaction(cells))
