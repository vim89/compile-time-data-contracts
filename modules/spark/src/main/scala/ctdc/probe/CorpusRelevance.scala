package ctdc.probe

import ctdc.probe.DriftTaxonomy.{Position, Slot}
import org.apache.avro.Schema

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/** What real Avro schemas actually declare about optionality, measured over a pinned public corpus.
  *
  * [[ComparatorMatrix]] measures predicates against controlled stimuli. Controlled stimuli establish what a
  * predicate does, and nothing else: a reader can accept every cell of that table and still say that no production
  * schema is shaped like its rows. This module is the other half. It takes schemas nobody wrote for this paper,
  * reduces each one to the three optionality carriers `StructType` has, and reports how much of the corpus is in a
  * configuration no shipped Spark comparator can express.
  *
  * The corpus is `paper/corpus`: twenty public repositories, pinned to a commit each, fetched by
  * `paper/corpus/fetch.sh`. It is a convenience sample of open-source systems that read or write Avro, not a random
  * sample of the world's schemas, and the selection and the rejects are both recorded in that directory so the bias
  * is visible rather than argued about.
  *
  * Avro rather than `StructType` because `StructType` is not a source format: a `StructType` is what a reader
  * produced, so a corpus of them would measure the readers. An `.avsc` file is a claim its author wrote down. The
  * mapping used here is the one `spark-avro` uses - a field whose type is a union containing `null` is nullable, and
  * the same rule applies to an array's element type and a map's value type - so the carriers counted below are the
  * carriers the `DataFrame` would have.
  *
  * Avro also bounds what this can show, in one way worth stating up front: an Avro map key is always a string, so
  * the `MapKey` rows of [[DriftTaxonomy]] have no instance in any Avro corpus. That is reported as a gap rather than
  * left for a reader to notice.
  */
object CorpusRelevance:

  /** The three places a `StructType` can record that a value may be absent.
    *
    * The same three as `ComparatorMatrix.optionalityCarrierSignatures` measures, named here so the two halves of the
    * evidence are keyed on one vocabulary: the matrix says no shipped predicate distinguishes them, and this says how
    * often real schemas need them distinguished.
    */
  enum Carrier:
    case FieldNullable, ArrayContainsNull, MapValueContainsNull

  /** How many slots of one carrier a schema has, and how many of those admit absence. */
  final case class SlotCount(total: Int, optional: Int):
    def strict: Int = total - optional
    def plus(that: SlotCount): SlotCount = SlotCount(total + that.total, optional + that.optional)

  object SlotCount:
    val empty: SlotCount = SlotCount(0, 0)

    def one(optional: Boolean): SlotCount = if optional then SlotCount(1, 1) else SlotCount(1, 0)

  /** Everything one traversal of a schema records.
    *
    * A value with a `plus`, so the traversal is a fold and the aggregate over a stratum is the same fold one level
    * up. Counts add; `maxRecordDepth` takes the larger, because it describes a single deepest path rather than a
    * quantity of anything.
    */
  final case class Facts(
      slots: Map[Carrier, SlotCount],
      records: Int,
      maxRecordDepth: Int,
      structValuedFields: Int,
      nestedCollections: Int,
      branchingUnions: Int
  ):
    def slotsOf(carrier: Carrier): SlotCount = slots.getOrElse(carrier, SlotCount.empty)

    def withSlot(carrier: Carrier, optional: Boolean): Facts =
      copy(slots = slots.updated(carrier, slotsOf(carrier).plus(SlotCount.one(optional))))

    def withRecordAt(depth: Int): Facts =
      copy(records = records + 1, maxRecordDepth = math.max(maxRecordDepth, depth))

    def withStructValuedField: Facts   = copy(structValuedFields = structValuedFields + 1)
    def withNestedCollection: Facts    = copy(nestedCollections = nestedCollections + 1)
    def withBranchingUnion: Facts      = copy(branchingUnions = branchingUnions + 1)

    def plus(that: Facts): Facts =
      Facts(
        slots = Carrier.values.toList.map(c => c -> slotsOf(c).plus(that.slotsOf(c))).toMap,
        records = records + that.records,
        maxRecordDepth = math.max(maxRecordDepth, that.maxRecordDepth),
        structValuedFields = structValuedFields + that.structValuedFields,
        nestedCollections = nestedCollections + that.nestedCollections,
        branchingUnions = branchingUnions + that.branchingUnions
      )

    /** Whether this schema is strict on one carrier and permissive on another.
      *
      * This is the configuration the matrix shows no shipped Spark comparator can express. Every one of the nine
      * treats the three carriers identically, so checking such a schema requires either accepting drift on the
      * carrier it is strict about or rejecting conformant data on the carrier it is permissive about. A count of
      * these schemas is the relevance number: without it, the characterisation is about stimuli only.
      */
    def needsPerCarrierStrictness: Boolean =
      val carriers = Carrier.values.toList
      carriers.exists(strict => slotsOf(strict).strict > 0 && carriers.exists(loose => loose != strict && slotsOf(loose).optional > 0))

  object Facts:
    val empty: Facts = Facts(Map.empty, 0, 0, 0, 0, 0)

  /** One corpus file, where it came from, and what it says. */
  final case class SchemaFacts(repo: String, stratum: String, path: String, facts: Facts)

  /** A file that would not parse, kept rather than dropped so the denominator stays honest. */
  final case class Unparsed(repo: String, path: String, reason: String)

  final case class Corpus(parsed: List[SchemaFacts], unparsed: List[Unparsed])

  // ===== TRAVERSAL (calculations) =====

  /** Whether a slot holding this schema admits absence.
    *
    * `spark-avro`'s rule, and the only rule Avro gives: optionality is a `null` branch of a union. A field declared
    * with a default but no `null` branch is still required, which is the distinction this whole measurement turns on.
    */
  private def isNullable(schema: Schema): Boolean =
    schema.getType == Schema.Type.UNION &&
      schema.getTypes.asScala.exists(_.getType == Schema.Type.NULL)

  /** The branches of a slot's type that carry data.
    *
    * Total over every schema, not only unions: a non-union is its own single branch. That keeps the traversal free of
    * a "strip the null" step that would have to decide what to do with a three-branch union.
    */
  private def dataBranches(schema: Schema): List[Schema] =
    if schema.getType == Schema.Type.UNION then
      schema.getTypes.asScala.toList.filter(_.getType != Schema.Type.NULL)
    else List(schema)

  /** The traversal state: what has been counted so far, and which named types are already counted.
    *
    * `seen` accumulates across siblings rather than only along the current path, and the two reasons point the same
    * way. Avro records may be recursive - a tree node whose child field is the node type - so some guard is required
    * for the traversal to terminate at all. But a named record reused in twenty places is also *declared* once, and
    * its carriers are one set of claims rather than twenty copies of them, so counting it once is the measurement
    * this paper wants. It is also what keeps the traversal linear in the number of distinct named types instead of
    * exponential in the nesting of reused ones, and that is not a micro-optimisation: the corpus contains schemas
    * where the path-local version does not finish.
    *
    * The cost is that `maxRecordDepth` can under-report, when a type first reached at depth two is reused deeper. The
    * measurement it feeds only asks whether nested records occur at all, so an under-report is the safe direction.
    */
  private final case class Visit(facts: Facts, seen: Set[String])

  private def walkAll(branches: List[Schema], visit: Visit, depth: Int): Visit =
    branches.foldLeft(visit)((acc, branch) => walk(branch, acc, depth))

  /** Count the carriers one schema declares.
    *
    * `depth` counts record nesting only, because that is what [[Position]] names. A collection does not increment it,
    * so an array of records inside a record still reports depth 2.
    */
  private def walk(schema: Schema, visit: Visit, depth: Int): Visit =
    schema.getType match
      case Schema.Type.RECORD =>
        if visit.seen.contains(schema.getFullName) then visit
        else
          val entered = Visit(visit.facts.withRecordAt(depth + 1), visit.seen + schema.getFullName)
          schema.getFields.asScala.toList.foldLeft(entered) { (acc, field) =>
            val branches   = dataBranches(field.schema())
            val counted    = acc.facts.withSlot(Carrier.FieldNullable, isNullable(field.schema()))
            val withStruct =
              if branches.exists(_.getType == Schema.Type.RECORD) then counted.withStructValuedField else counted
            val tallied = if branches.sizeIs > 1 then withStruct.withBranchingUnion else withStruct
            walkAll(branches, Visit(tallied, acc.seen), depth + 1)
          }

      case Schema.Type.ARRAY =>
        val element  = schema.getElementType
        val branches = dataBranches(element)
        val counted  = visit.facts.withSlot(Carrier.ArrayContainsNull, isNullable(element))
        val tallied  = if branches.exists(isCollection) then counted.withNestedCollection else counted
        walkAll(branches, Visit(tallied, visit.seen), depth)

      case Schema.Type.MAP =>
        val value    = schema.getValueType
        val branches = dataBranches(value)
        val counted  = visit.facts.withSlot(Carrier.MapValueContainsNull, isNullable(value))
        val tallied  = if branches.exists(isCollection) then counted.withNestedCollection else counted
        walkAll(branches, Visit(tallied, visit.seen), depth)

      case Schema.Type.UNION =>
        // A union reached here is not in a carrier slot: it is a branch of another union. Its own branches still hold
        // carriers, so they are walked, but there is no slot to attribute to this node.
        walkAll(dataBranches(schema), visit, depth)

      // Leaves. An enum or a fixed carries no optionality of its own; a null outside a union is a schema whose only
      // value is absence, which declares nothing about a slot.
      case _ => visit

  private def factsOf(schema: Schema): Facts =
    walk(schema, Visit(Facts.empty, Set.empty), depth = 0).facts

  private def isCollection(schema: Schema): Boolean =
    schema.getType == Schema.Type.ARRAY || schema.getType == Schema.Type.MAP

  /** Whether the corpus contains a slot the given taxonomy axis could edit.
    *
    * The bridge between the two halves of the evidence: an axis with no instance here is an axis the matrix measures
    * and real Avro cannot exercise. There is one such family and it is a property of Avro rather than an oversight -
    * an Avro map key is always a string, so no `.avsc` file can hold a non-string map key to drift.
    */
  private def instantiates(slot: Slot, facts: Facts): Boolean = slot match
    case Slot.FieldSet | Slot.FieldName | Slot.FieldNullable => facts.slotsOf(Carrier.FieldNullable).total > 0
    case Slot.FieldType                                      => facts.slotsOf(Carrier.FieldNullable).total > 0
    case Slot.ArrayContainsNull | Slot.ArrayElement          => facts.slotsOf(Carrier.ArrayContainsNull).total > 0
    case Slot.MapValueContainsNull | Slot.MapValue           => facts.slotsOf(Carrier.MapValueContainsNull).total > 0
    case Slot.MapKey                                         => false

  // ===== READING THE CORPUS (actions) =====

  /** Parse one file with a parser of its own.
    *
    * A fresh [[Schema.Parser]] per file, deliberately. A parser accumulates the named types it has seen, so a shared
    * one would let a file resolve a name another file defined, and the result would depend on the order the corpus
    * was listed in. Per-file parsing makes a cross-file reference an honest failure instead of an order-dependent
    * success.
    */
  private def parse(file: Path): Try[Schema] =
    Try(new Schema.Parser().parse(file.toFile))

  private def readCorpus(root: Path): Corpus =
    val manifest = Files.readAllLines(root.resolve("manifest.tsv")).asScala.toList
    val entries = manifest.filterNot(line => line.startsWith("#") || line.isBlank).map(_.split("\t", -1))

    val results = entries.map { case Array(repo, _, stratum, path, _*) =>
      val file = root.resolve("schemas").resolve(repo.replace("/", "__")).resolve(path)
      parse(file) match
        case Success(schema) => Right(SchemaFacts(repo, stratum, path, factsOf(schema)))
        case Failure(error)  => Left(Unparsed(repo, path, reasonOf(error)))
    }

    Corpus(results.collect { case Right(f) => f }, results.collect { case Left(u) => u })

  /** A parse failure's category rather than its message.
    *
    * The messages carry type names from the corpus, which would make the evidence file churn on every refetch. The
    * categories are stable and are what the limitation section needs: a cross-file name reference is a schema that is
    * valid in its own project and only incomplete on its own, which is a different fact about the corpus than a
    * malformed file.
    */
  private def reasonOf(error: Throwable): String =
    val message = Option(error.getMessage).getOrElse("")
    if message.contains("Undefined name") then "reference to a type defined in another file"
    else if message.contains("No type") || message.contains("Unknown") then "not a schema document"
    else error.getClass.getSimpleName

  // ===== REPORTS (calculations) =====

  /** Whether a schema sits in a project's test or example tree, by the conventional path segments.
    *
    * Its own split, because slot-weighted percentages are vulnerable to one file: the corpus's largest schema is a
    * generated fixture carrying over three thousand field slots, enough to move a corpus-wide ratio on its own. A
    * reader who suspects the headline numbers are an artefact of test data can read the `source-path` row instead of
    * taking the `all` row on trust.
    */
  private def provenance(path: String): String =
    val segments = path.split("/").toList
    val testish  = Set("test", "tests", "it", "example", "examples", "testdata", "fixtures", "jsonschemas")
    if segments.exists(testish.contains) then "test-path" else "source-path"

  /** The splits every aggregate is reported over.
    *
    * Two independent splits rather than their cross product. `platform`/`application` asks whether the kind of system
    * changes the answer; `test-path`/`source-path` asks whether test data does. Crossing them would quarter some
    * cells into counts too small to read, and neither question needs the other to be answered.
    */
  private def strata(corpus: Corpus): List[(String, List[SchemaFacts])] =
    val byStratum    = corpus.parsed.map(_.stratum).distinct.sorted
    val byProvenance = corpus.parsed.map(s => provenance(s.path)).distinct.sorted
    ("all", corpus.parsed) ::
      byStratum.map(s => s -> corpus.parsed.filter(_.stratum == s)) :::
      byProvenance.map(p => p -> corpus.parsed.filter(s => provenance(s.path) == p))

  private def percent(numerator: Int, denominator: Int): String =
    if denominator == 0 then "n/a" else f"${numerator * 100.0 / denominator}%.1f%%"

  private def carrierDensity(corpus: Corpus): String =
    val lines = strata(corpus).flatMap { case (stratum, schemas) =>
      s"  $stratum (${schemas.size} schemas)" :: Carrier.values.toList.map { carrier =>
        val counts  = schemas.map(_.facts.slotsOf(carrier)).foldLeft(SlotCount.empty)(_.plus(_))
        val present = schemas.count(_.facts.slotsOf(carrier).total > 0)
        f"    $carrier%-22s slots ${counts.total}%6d  required ${counts.strict}%6d (${percent(counts.strict, counts.total)}%6s)  " +
          f"in ${present}%4d of ${schemas.size}%4d schemas"
      }
    }
    s"""optionality carriers as real schemas declare them
       |${lines.mkString("\n")}
       |
       |  A required slot is one Avro declares without a null branch. Every one is a claim a producer wrote down, and
       |  the matrix shows no shipped Spark comparator can hold two carriers to different standards.""".stripMargin

  private def perCarrierDemand(corpus: Corpus): String =
    val lines = strata(corpus).map { case (stratum, schemas) =>
      val need = schemas.count(_.facts.needsPerCarrierStrictness)
      f"  $stratum%-12s ${need}%4d of ${schemas.size}%4d schemas (${percent(need, schemas.size)})"
    }
    s"""schemas that are required on one carrier and optional on another
       |${lines.mkString("\n")}
       |
       |  No shipped Spark comparator can check one of these. The three that ignore optionality miss the required
       |  carrier; the six that demand agreement reject the optional one. This is the population the compile-time
       |  policies address, counted in schemas nobody wrote for this paper.""".stripMargin

  private def axisInstantiability(corpus: Corpus): String =
    val lines = Slot.values.toList.map { slot =>
      val schemas = corpus.parsed.count(f => instantiates(slot, f.facts))
      val note    = if schemas == 0 then "  <- no instance in Avro: map keys are always strings" else ""
      f"  ${DriftTaxonomy.snakeCase(slot.toString)}%-22s ${schemas}%4d of ${corpus.parsed.size}%4d schemas$note"
    }
    val nested = corpus.parsed.count(_.facts.maxRecordDepth >= 2)
    s"""taxonomy slots with an instance in the corpus
       |${lines.mkString("\n")}
       |
       |  ${Position.Nested} rows need a record inside a record: $nested of ${corpus.parsed.size} schemas have one
       |  (${percent(nested, corpus.parsed.size)}), so the depth half of the taxonomy is exercised by real schemas too.""".stripMargin

  private def shape(corpus: Corpus): String =
    val totals = corpus.parsed.map(_.facts).foldLeft(Facts.empty)(_.plus(_))
    val byReason = corpus.unparsed.groupBy(_.reason).toList.sortBy(-_._2.size)
    val failures =
      if byReason.isEmpty then "  (none)"
      else byReason.map { case (reason, files) => f"  ${files.size}%4d  $reason" }.mkString("\n")
    val fieldSlots = totals.slotsOf(Carrier.FieldNullable).total
    val largest    = corpus.parsed.maxByOption(_.facts.slotsOf(Carrier.FieldNullable).total)
    val skew = largest.fold("  (no schemas parsed)") { s =>
      val slots = s.facts.slotsOf(Carrier.FieldNullable).total
      s"  largest schema    ${s.repo}/${s.path}, $slots field slots (${percent(slots, fieldSlots)} of the corpus)"
    }
    s"""corpus
       |  repositories      ${corpus.parsed.map(_.repo).distinct.size}
       |  files in manifest ${corpus.parsed.size + corpus.unparsed.size}
       |  parsed            ${corpus.parsed.size} (${percent(corpus.parsed.size, corpus.parsed.size + corpus.unparsed.size)})
       |  records reached   ${totals.records}
       |  field slots       $fieldSlots
       |  struct fields     ${totals.structValuedFields}
       |  nested arrays or maps ${totals.nestedCollections}
       |  multi-branch unions   ${totals.branchingUnions}
       |$skew
       |
       |not parsed, by reason
       |$failures""".stripMargin

  // ===== EVIDENCE FILES =====

  /** One row per schema, so every aggregate above can be recomputed from the data rather than trusted. */
  private def schemaCsv(corpus: Corpus): String =
    val header =
      "repo,stratum,provenance,path,records,max_record_depth,field_slots,field_required,array_slots,array_required," +
        "map_value_slots,map_value_required,needs_per_carrier_strictness"
    val rows = corpus.parsed.map { s =>
      val f = s.facts
      List(
        s.repo,
        s.stratum,
        provenance(s.path),
        s.path,
        f.records,
        f.maxRecordDepth,
        f.slotsOf(Carrier.FieldNullable).total,
        f.slotsOf(Carrier.FieldNullable).strict,
        f.slotsOf(Carrier.ArrayContainsNull).total,
        f.slotsOf(Carrier.ArrayContainsNull).strict,
        f.slotsOf(Carrier.MapValueContainsNull).total,
        f.slotsOf(Carrier.MapValueContainsNull).strict,
        f.needsPerCarrierStrictness
      ).mkString(",")
    }
    (header :: rows).mkString("\n")

  /** The numbers the paper cites, as numerator over denominator rather than as a rendered percentage.
    *
    * A percentage in an evidence file is a number nobody can check: the rounding hides which denominator was used.
    * Both are emitted and the prose does the division.
    */
  private def measureCsv(corpus: Corpus): String =
    val header = "stratum,measure,numerator,denominator"
    val rows = strata(corpus).flatMap { case (stratum, schemas) =>
      val totals = schemas.map(_.facts).foldLeft(Facts.empty)(_.plus(_))
      val carrierRows = Carrier.values.toList.flatMap { carrier =>
        val counts = schemas.map(_.facts.slotsOf(carrier)).foldLeft(SlotCount.empty)(_.plus(_))
        List(
          s"$stratum,${carrier}_required_slots,${counts.strict},${counts.total}",
          s"$stratum,${carrier}_schemas_with_slot,${schemas.count(_.facts.slotsOf(carrier).total > 0)},${schemas.size}"
        )
      }
      val slotRows = Slot.values.toList.map { slot =>
        s"$stratum,instantiates_${DriftTaxonomy.snakeCase(slot.toString)}," +
          s"${schemas.count(f => instantiates(slot, f.facts))},${schemas.size}"
      }
      carrierRows ::: slotRows ::: List(
        s"$stratum,needs_per_carrier_strictness,${schemas.count(_.facts.needsPerCarrierStrictness)},${schemas.size}",
        s"$stratum,nested_records,${schemas.count(_.facts.maxRecordDepth >= 2)},${schemas.size}",
        s"$stratum,struct_valued_fields,${totals.structValuedFields},${totals.slotsOf(Carrier.FieldNullable).total}",
        s"$stratum,multi_branch_unions,${totals.branchingUnions},${totals.slotsOf(Carrier.FieldNullable).total}"
      )
    }
    (header :: rows).mkString("\n")

  private def write(path: Path, content: String): Unit =
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.writeString(path, content + "\n")
    ()

  /** Usage: `CorpusRelevance [corpus dir] [evidence dir]`, defaulting to the paper's own directories.
    *
    * Run from the repository root; the build sets a forked run's working directory there so that the defaults mean
    * the same thing however the run is started.
    */
  def main(args: Array[String]): Unit =
    val corpusDir   = Path.of(args.lift(0).getOrElse("paper/corpus"))
    val evidenceDir = Path.of(args.lift(1).getOrElse("paper/evidence"))

    val corpus = readCorpus(corpusDir)

    write(evidenceDir.resolve("corpus-schema-facts.csv"), schemaCsv(corpus))
    write(evidenceDir.resolve("corpus-relevance.csv"), measureCsv(corpus))

    println(shape(corpus))
    println()
    println(carrierDensity(corpus))
    println()
    println(perCarrierDemand(corpus))
    println()
    println(axisInstantiability(corpus))
