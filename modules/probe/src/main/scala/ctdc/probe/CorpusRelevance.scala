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
  * produced, so a corpus of them would measure the readers. An `.avsc` file is a claim its author wrote down.
  *
  * What this is therefore a traversal of is Avro *declarations*, and not the flags a resulting `DataFrame` would
  * carry. The rule applied is Avro's own and the one `spark-avro` applies in the common case - a slot whose type is
  * a union containing `null` admits absence, and that holds for a field's type, an array's element type and a map's
  * value type alike - but `SchemaConverters` is a different algorithm from this one and they part company at four
  * points, each measured by `converterDivergence` and reported with every run rather than argued about: a bare
  * `null` type, a union with two or more non-null branches, a recursive record and a reused named record. A reader
  * who wants the flags of the projected schema is asking for a measurement of the converter, which is a different
  * study from this one and would answer a question the paper does not ask.
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
      complexUnionSlots: Int,
      nullOnlySlots: Int,
      recursiveRecords: Int,
      reusedNamedTypes: Int
  ):
    def slotsOf(carrier: Carrier): SlotCount = slots.getOrElse(carrier, SlotCount.empty)

    def withSlot(carrier: Carrier, optional: Boolean): Facts =
      copy(slots = slots.updated(carrier, slotsOf(carrier).plus(SlotCount.one(optional))))

    def withRecordAt(depth: Int): Facts =
      copy(records = records + 1, maxRecordDepth = math.max(maxRecordDepth, depth))

    def withStructValuedField: Facts = copy(structValuedFields = structValuedFields + 1)
    def withNestedCollection: Facts  = copy(nestedCollections = nestedCollections + 1)
    def withComplexUnionSlot: Facts  = copy(complexUnionSlots = complexUnionSlots + 1)
    def withNullOnlySlot: Facts      = copy(nullOnlySlots = nullOnlySlots + 1)
    def withRecursiveRecord: Facts   = copy(recursiveRecords = recursiveRecords + 1)
    def withReusedNamedType: Facts   = copy(reusedNamedTypes = reusedNamedTypes + 1)

    /** How many slots of this schema the traversal and `spark-avro`'s converter would count differently. */
    def convertedDifferently: Int = complexUnionSlots + nullOnlySlots + recursiveRecords + reusedNamedTypes

    def plus(that: Facts): Facts =
      Facts(
        slots = Carrier.values.toList.map(c => c -> slotsOf(c).plus(that.slotsOf(c))).toMap,
        records = records + that.records,
        maxRecordDepth = math.max(maxRecordDepth, that.maxRecordDepth),
        structValuedFields = structValuedFields + that.structValuedFields,
        nestedCollections = nestedCollections + that.nestedCollections,
        complexUnionSlots = complexUnionSlots + that.complexUnionSlots,
        nullOnlySlots = nullOnlySlots + that.nullOnlySlots,
        recursiveRecords = recursiveRecords + that.recursiveRecords,
        reusedNamedTypes = reusedNamedTypes + that.reusedNamedTypes
      )

    /** Whether this schema declares a required slot on one carrier and an optional slot on another.
      *
      * This is a statement about one schema's declarations, not about what a comparison of two schemas would have to
      * do. A uniformly strict predicate checks such a schema exactly, because preserving every carrier preserves a
      * heterogeneous declaration as readily as a uniform one. What the count supports is narrower and is the reason
      * it is here: producers use the three carriers independently and write required-ness down deliberately, so a
      * predicate that drops optionality is discarding claims that a quarter of real schemas make unevenly across the
      * carriers. Whether any of that is *demanded* comes from the two external specifications in [[ComparatorMatrix]]
      * and not from this count.
      */
    def declaresHeterogeneousOptionality: Boolean =
      val carriers = Carrier.values.toList
      carriers.exists(strict => slotsOf(strict).strict > 0 && carriers.exists(loose => loose != strict && slotsOf(loose).optional > 0))

  object Facts:
    val empty: Facts = Facts(Map.empty, 0, 0, 0, 0, 0, 0, 0, 0)

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
    * a "strip the null" step that would have to decide what to do with a three-branch union. A bare `null` type is
    * the one non-union with no branches, because it carries no data at all, and that is what makes a slot declared
    * that way visible to `tallyUnionDivergence` as having no data branch.
    */
  private def dataBranches(schema: Schema): List[Schema] =
    schema.getType match
      case Schema.Type.NULL  => Nil
      case Schema.Type.UNION => schema.getTypes.asScala.toList.filter(_.getType != Schema.Type.NULL)
      case _                 => List(schema)

  /** The traversal state: what has been counted so far, and which named types are already counted.
    *
    * Two sets rather than one, because arriving at a name twice is two different facts depending on where. `path`
    * holds the names open on the way down and shrinks on the way out, so a name already on it is a *recursive*
    * reference. `seen` accumulates across siblings and never shrinks, so a name on `seen` but not on `path` is a
    * *reuse*. The counts differ: `SchemaConverters` throws on the first and expands the second at every occurrence,
    * and `converterDivergence` reports each separately rather than folding them into one caveat.
    *
    * `seen` is what the carrier counts are keyed on, and that is the measurement this paper wants: a named record
    * reused in twenty places is *declared* once, and its carriers are one set of claims rather than twenty copies of
    * them. It is also what keeps the traversal linear in the number of distinct named types rather than exponential
    * in the nesting of reused ones, which is not a micro-optimisation: the corpus contains schemas where the
    * path-local version does not finish.
    *
    * The cost is that `maxRecordDepth` can under-report, when a type first reached at depth two is reused deeper. The
    * measurement it feeds only asks whether nested records occur at all, so an under-report is the safe direction.
    */
  private final case class Visit(facts: Facts, seen: Set[String], path: Set[String])

  private def walkAll(branches: List[Schema], visit: Visit, depth: Int): Visit =
    branches.foldLeft(visit)((acc, branch) => walk(branch, acc, depth))

  /** Where this slot's declared type makes the traversal and `spark-avro`'s converter count differently.
    *
    * Read off `SchemaConverters.toSqlTypeHelper` at `v3.5.6`, which is the function a Spark Avro read actually goes
    * through, so the two cases below are differences between two algorithms rather than a doubt about one.
    *
    *   - A slot whose declared type is a bare `null`, or a union whose only branch is `null`, has no data branch.
    *     The converter maps `NULL` to `nullable = true`; this traversal has no null branch to see in the bare case
    *     and therefore records the slot as required. The two disagree on the carrier bit itself.
    *   - A union that still has two or more branches after `null` is removed becomes a struct of `member0`,
    *     `member1`, ... fields, every one of them `nullable = true`, so a `DataFrame` gains optional field slots the
    *     declaration never wrote. Avro's own exemptions are excluded here because the converter exempts them too: a
    *     two-branch `int`/`long` union becomes a plain `long` and `float`/`double` becomes a plain `double`.
    */
  private def tallyUnionDivergence(declared: Schema, facts: Facts): Facts =
    val branches = dataBranches(declared)
    if branches.isEmpty then facts.withNullOnlySlot
    else if branches.sizeIs > 1 && !widenedByConverter(branches) then facts.withComplexUnionSlot
    else facts

  private def widenedByConverter(branches: List[Schema]): Boolean =
    val kinds = branches.map(_.getType).toSet
    branches.sizeIs == 2 &&
      (kinds == Set(Schema.Type.INT, Schema.Type.LONG) || kinds == Set(Schema.Type.FLOAT, Schema.Type.DOUBLE))

  /** Count the carriers one schema declares.
    *
    * `depth` counts record nesting only, because that is what [[Position]] names. A collection does not increment it,
    * so an array of records inside a record still reports depth 2.
    */
  private def walk(schema: Schema, visit: Visit, depth: Int): Visit =
    schema.getType match
      case Schema.Type.RECORD =>
        val name = schema.getFullName
        // Two ways a named record can arrive already visited, and they are different facts about the schema. On the
        // current path it is recursive, and `SchemaConverters` throws on it, so the schema has no `DataFrame` at all.
        // Off the path it is merely reused, and the converter expands it again at every occurrence, so a `DataFrame`
        // holds one copy of its slots per use where this traversal holds one copy per declaration.
        if visit.path.contains(name) then visit.copy(facts = visit.facts.withRecursiveRecord)
        else if visit.seen.contains(name) then visit.copy(facts = visit.facts.withReusedNamedType)
        else
          val entered = Visit(visit.facts.withRecordAt(depth + 1), visit.seen + name, visit.path + name)
          val walked = schema.getFields.asScala.toList.foldLeft(entered) { (acc, field) =>
            val declared   = field.schema()
            val branches   = dataBranches(declared)
            val counted    = acc.facts.withSlot(Carrier.FieldNullable, isNullable(declared))
            val withStruct =
              if branches.exists(_.getType == Schema.Type.RECORD) then counted.withStructValuedField else counted
            walkAll(branches, acc.copy(facts = tallyUnionDivergence(declared, withStruct)), depth + 1)
          }
          // The path shrinks back on the way out; `seen` does not, which is what makes a reuse off the path visible
          // as a reuse rather than as a second declaration.
          walked.copy(path = visit.path)

      case Schema.Type.ARRAY =>
        val element  = schema.getElementType
        val branches = dataBranches(element)
        val counted  = visit.facts.withSlot(Carrier.ArrayContainsNull, isNullable(element))
        val tallied  = if branches.exists(isCollection) then counted.withNestedCollection else counted
        walkAll(branches, visit.copy(facts = tallyUnionDivergence(element, tallied)), depth)

      case Schema.Type.MAP =>
        val value    = schema.getValueType
        val branches = dataBranches(value)
        val counted  = visit.facts.withSlot(Carrier.MapValueContainsNull, isNullable(value))
        val tallied  = if branches.exists(isCollection) then counted.withNestedCollection else counted
        walkAll(branches, visit.copy(facts = tallyUnionDivergence(value, tallied)), depth)

      case Schema.Type.UNION =>
        // A union reached here is not in a carrier slot: it is a branch of another union. Its own branches still hold
        // carriers, so they are walked, but there is no slot to attribute to this node.
        walkAll(dataBranches(schema), visit, depth)

      // Leaves. An enum or a fixed carries no optionality of its own; a null outside a union is a schema whose only
      // value is absence, which declares nothing about a slot.
      case _ => visit

  private[probe] def factsOf(schema: Schema): Facts =
    walk(schema, Visit(Facts.empty, Set.empty, Set.empty), depth = 0).facts

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
       |  the six Spark configurations that ignore optionality discard all of them.""".stripMargin

  /** How often real schemas use the three carriers unevenly.
    *
    * Reported as prevalence and nothing more. The number says that the carriers' independence is exercised by
    * producers rather than only by this paper's stimuli. It does not say that these schemas are unservable by a
    * shipped predicate, because a uniformly strict one serves them.
    */
  private def heterogeneousOptionality(corpus: Corpus): String =
    val lines = strata(corpus).map { case (stratum, schemas) =>
      val uneven = schemas.count(_.facts.declaresHeterogeneousOptionality)
      f"  $stratum%-12s ${uneven}%4d of ${schemas.size}%4d schemas (${percent(uneven, schemas.size)})"
    }
    s"""schemas that are required on one carrier and optional on another
       |${lines.mkString("\n")}
       |
       |  Prevalence, not demand. These schemas declare optionality unevenly across the carriers, which is what makes
       |  the independence in the grammar a property producers use. A uniformly strict predicate still checks every
       |  one of them; what a uniformly strict predicate cannot also do is tolerate the field reordering Avro's
       |  resolution rule permits, and that conflict is the one the external requirements state.""".stripMargin

  /** The headline proportion recomputed with each repository left out in turn.
    *
    * The corpus is a convenience sample, so the first objection to the headline is that one repository supplies it.
    * Leaving each repository out in turn answers that with a range rather than with an argument about sampling.
    *
    * Worth being explicit about what this does and does not cover. The measure is per-schema, so the corpus's one
    * dominant file - a generated fixture holding a third of every field slot - counts exactly once here no matter how
    * many slots it holds, and cannot move this figure by more than one schema. Only the slot-weighted densities in
    * [[carrierDensity]] are exposed to it. What a leave-one-out range cannot show is a bias shared by every
    * repository in the corpus, which is a property of the selection and is argued rather than measured.
    */
  private def leaveOneRepoOut(corpus: Corpus): List[(String, Int, Int)] =
    corpus.parsed.map(_.repo).distinct.sorted.map { dropped =>
      val kept = corpus.parsed.filterNot(_.repo == dropped)
      (dropped, kept.count(_.facts.declaresHeterogeneousOptionality), kept.size)
    }

  private def sensitivity(corpus: Corpus): String =
    val dropped = leaveOneRepoOut(corpus)
    val lines = dropped.map { case (repo, need, total) =>
      f"  without $repo%-34s ${need}%4d of ${total}%4d (${percent(need, total)})"
    }
    val ratios = dropped.map { case (_, need, total) => if total == 0 then 0.0 else need * 100.0 / total }
    val spread =
      if ratios.isEmpty then "  (no repositories)"
      else f"  range ${ratios.min}%.1f%% to ${ratios.max}%.1f%% across ${ratios.size} leave-one-out corpora"
    s"""the headline with each repository left out
       |${lines.mkString("\n")}
       |
       |$spread
       |
       |  No single repository carries the result. The measure counts schemas rather than slots, so the corpus's
       |  largest file counts once and the slot skew reported above cannot reach this number.""".stripMargin

  /** How far the traversal's counts can be read as the flags a `DataFrame` would carry.
    *
    * Not very far, and the measurement is here so that the distance is a number rather than a caveat. This traversal
    * reads declarations; `spark-avro`'s `SchemaConverters` produces a `StructType`, and the two are different
    * algorithms at four points. Every figure this module reports is therefore a statement about what producers wrote
    * down, which is the construct the paper needs, and not a prediction of a projected schema.
    */
  private def converterDivergence(corpus: Corpus): String =
    val totals    = corpus.parsed.map(_.facts).foldLeft(Facts.empty)(_.plus(_))
    val touched   = corpus.parsed.filter(_.facts.convertedDifferently > 0)
    val untouched = corpus.parsed.filter(_.facts.convertedDifferently == 0)
    val recursive = corpus.parsed.count(_.facts.recursiveRecords > 0)
    val hetero    = corpus.parsed.count(_.facts.declaresHeterogeneousOptionality)
    val heteroTouched = touched.count(_.facts.declaresHeterogeneousOptionality)
    val heteroClean   = untouched.count(_.facts.declaresHeterogeneousOptionality)
    s"""where this traversal and spark-avro's converter disagree
       |  complex union slots  ${totals.complexUnionSlots}
       |  null-only slots      ${totals.nullOnlySlots}
       |  recursive re-entries ${totals.recursiveRecords}, in $recursive of ${corpus.parsed.size} schemas
       |  reused named types   ${totals.reusedNamedTypes}
       |  schemas touched by at least one of the four: ${touched.size} of ${corpus.parsed.size} (${percent(touched.size, corpus.parsed.size)})
       |
       |  A complex union becomes a struct of memberN fields that are all nullable, so a DataFrame has optional slots
       |  the declaration does not. A bare null is nullable to the converter and required here. A recursive record
       |  makes the converter throw, so those schemas have no DataFrame to compare against. A reused named type is
       |  expanded once per use by the converter and counted once per declaration here. The counts above are the size
       |  of each gap; they are not corrections, because the construct measured is the declaration.
       |
       |  what the gap does to the headline
       |    heterogeneous schemas touched by a divergence: $heteroTouched of $hetero
       |    headline over the untouched schemas only: $heteroClean of ${untouched.size} (${percent(heteroClean, untouched.size)})
       |
       |  The second line is reported because the first is not small. On the subset where the two algorithms agree
       |  slot for slot, the headline is lower than over the whole corpus, so a reader who insists on reading the
       |  measure as a statement about projected DataFrames should take the lower figure rather than the headline.
       |  Both are printed rather than argued about. The difference is driven by a handful of large reuse-heavy
       |  schemas, which the per-schema CSV shows and which counting schemas rather than slots already bounds.""".stripMargin

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
       |  complex union slots   ${totals.complexUnionSlots}
       |$skew
       |
       |not parsed, by reason
       |$failures""".stripMargin

  // ===== EVIDENCE FILES =====

  /** One row per schema, so every aggregate above can be recomputed from the data rather than trusted. */
  private def schemaCsv(corpus: Corpus): String =
    val header =
      "repo,stratum,provenance,path,records,max_record_depth,field_slots,field_required,array_slots,array_required," +
        "map_value_slots,map_value_required,heterogeneous_optionality,complex_union_slots,null_only_slots," +
        "recursive_records,reused_named_types"
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
        f.declaresHeterogeneousOptionality,
        f.complexUnionSlots,
        f.nullOnlySlots,
        f.recursiveRecords,
        f.reusedNamedTypes
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
        s"$stratum,heterogeneous_optionality,${schemas.count(_.facts.declaresHeterogeneousOptionality)},${schemas.size}",
        s"$stratum,nested_records,${schemas.count(_.facts.maxRecordDepth >= 2)},${schemas.size}",
        s"$stratum,struct_valued_fields,${totals.structValuedFields},${totals.slotsOf(Carrier.FieldNullable).total}",
        s"$stratum,complex_union_slots,${totals.complexUnionSlots},${totals.slotsOf(Carrier.FieldNullable).total}",
        s"$stratum,null_only_slots,${totals.nullOnlySlots},${totals.slotsOf(Carrier.FieldNullable).total}",
        s"$stratum,recursive_records,${totals.recursiveRecords},${totals.records}",
        s"$stratum,reused_named_types,${totals.reusedNamedTypes},${totals.records}",
        s"$stratum,schemas_converted_differently,${schemas.count(_.facts.convertedDifferently > 0)},${schemas.size}",
        // The headline restricted to schemas where the two algorithms agree slot for slot, so a reader who wants it
        // read as a statement about projected schemas has the lower figure without recomputing it from the per-file
        // CSV. Both are emitted; neither is presented as the correction of the other.
        s"$stratum,heterogeneous_optionality_converter_agnostic," +
          s"${schemas.count(f => f.facts.convertedDifferently == 0 && f.facts.declaresHeterogeneousOptionality)}," +
          s"${schemas.count(_.facts.convertedDifferently == 0)}"
      )
    }
    // Emitted under a `drop:` stratum rather than through `strata`, which would multiply every other measure by
    // twenty corpora to answer a question only the headline raises.
    val sensitivityRows = leaveOneRepoOut(corpus).map { case (repo, need, total) =>
      s"drop:$repo,heterogeneous_optionality,$need,$total"
    }
    (header :: rows ::: sensitivityRows).mkString("\n")

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
    println(converterDivergence(corpus))
    println()
    println(carrierDensity(corpus))
    println()
    println(heterogeneousOptionality(corpus))
    println()
    println(sensitivity(corpus))
    println()
    println(axisInstantiability(corpus))
