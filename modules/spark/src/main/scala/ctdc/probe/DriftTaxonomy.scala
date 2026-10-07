package ctdc.probe

import org.apache.spark.sql.types.*

/** The drift axes the comparator matrix measures, derived from `StructType`'s grammar rather than chosen.
  *
  * The first version of this harness used twelve axes picked by hand, and listed Spark's comparators by hand as well.
  * Both sides were therefore convenience samples, and the predicate side turned out to be missing a member. The axes
  * here are generated from the grammar and the predicates are now selected by the discovery rule stated on
  * [[ComparatorMatrix]], so on each side "why these" has a mechanical answer and adding one means pointing at
  * something the generator should have produced and did not. This module is the generator for the axes: they come from
  * the slots of the grammar and the edits each slot's own type admits, so adding an axis means pointing at a slot the
  * grammar has and this enumeration does not.
  *
  * The grammar, as Spark 3.5.6 states it for a schema reachable from `Dataset.schema`:
  * {{{
  *   Schema   ::= Struct
  *   Struct   ::= Field*                                     -- an ordered list
  *   Field    ::= (name: String, dataType: DataType, nullable: Boolean)
  *   DataType ::= Leaf
  *              | Struct
  *              | Array(elementType: DataType, containsNull: Boolean)
  *              | Map(keyType: DataType, valueType: DataType, valueContainsNull: Boolean)
  * }}}
  *
  * `UserDefinedType`, and the `CharType`/`VarcharType` pair, are outside that grammar deliberately. Both are erased
  * before a schema reaches a sink - a UDT to its `sqlType`, `CharType` to `StringType` - so a boundary check never
  * sees them, and an axis defined on one would measure the analyzer rather than the comparators.
  */
object DriftTaxonomy:

  /** A place in the grammar that holds a value, and therefore a place two schemas can differ.
    *
    * One case per terminal slot of the grammar above. There is no map-key-optionality slot, which is a property of
    * the grammar rather than an omission here: a Spark map key cannot be null, so the three optionality slots named
    * below are all the optionality a `StructType` can carry.
    */
  enum Slot:
    case FieldSet, FieldName, FieldNullable, FieldType
    case ArrayContainsNull, ArrayElement
    case MapValueContainsNull, MapKey, MapValue

    /** The edits this slot's own type admits.
      *
      * Derived from the slot rather than listed per axis, so the taxonomy cannot acquire an extra axis by anybody's
      * preference: a new axis requires either a new slot or a new edit that some slot's type admits.
      */
    def edits: List[Edit] = this match
      case FieldSet                                                => List(Edit.Remove, Edit.Add, Edit.Permute)
      case FieldName                                               => List(Edit.Rename, Edit.Recase)
      case FieldNullable | ArrayContainsNull | MapValueContainsNull => List(Edit.Flip)
      case FieldType | ArrayElement | MapKey | MapValue => List(Edit.WidenLeaf, Edit.SwapLeaf, Edit.SwapKind)

  /** A single change to one slot, admissible for the slots whose type admits it.
    *
    * The edits are grouped by the slot type that generates them, and each group is exhaustive over that type. A
    * `Boolean` has one non-identity edit. A name can change, or change only in case, and the two are separate because
    * the consumers of this grammar resolve names under a case-sensitivity flag. An ordered list can lose an element,
    * gain one, or keep its elements and change their order. A type slot can take a wider leaf, an unrelated leaf, or
    * a value of a different kind altogether.
    */
  enum Edit:
    case Flip
    case Rename, Recase
    case Remove, Add, Permute
    case WidenLeaf, SwapLeaf, SwapKind

  /** Where in the schema the edited slot sits.
    *
    * The grammar is recursive, so every slot occurs at unbounded depth and no finite enumeration covers all of them.
    * Two depths are measured instead, and `ComparatorMatrix.recursionUniformity` checks the assumption that makes two
    * enough: a predicate that recurses structurally returns the same verdict for the same edit wherever that edit
    * sits. Any predicate for which the two depths disagree is reported rather than averaged, so in this harness the
    * assumption is a measured result and not a premise.
    */
  enum Position:
    case Root, Nested

  /** The identity of a matrix row.
    *
    * A value rather than a string, so that nothing downstream - a requirement, a carrier signature, a lookup - can
    * name a row the enumeration does not contain.
    *
    * Two cases, because the taxonomy has two tiers and they are complete in different senses. [[Single]] is a census:
    * every slot of the grammar, every edit its type admits, at both measured depths. [[Relocation]] is not a census
    * of multi-slot edits, and there is no finite census of those. It is the one derived family where a predicate can
    * be correct about each slot in isolation and still wrong about the pair, because the number of optionality bits
    * is unchanged and only their position moved. A predicate that counts optionality rather than locating it passes
    * every [[Single]] row and fails these.
    */
  enum DriftId:
    case Single(slot: Slot, edit: Edit, position: Position)
    case Relocation(from: Slot, to: Slot, position: Position)

    def name: String = this match
      case Single(slot, edit, p)     => snakeCase(s"${slot}_${edit}_$p")
      case Relocation(from, to, p)   => snakeCase(s"reloc_${from}_to_${to}_$p")

    /** The grouping label the paper's table uses for this row. */
    def axis: String = this match
      case Single(slot, _, _)   => snakeCase(slot.toString)
      case Relocation(_, _, _)  => "optionality_relocation"

  /** One row of the matrix: a minimal pair and the reason the difference matters.
    *
    * The pair differs along exactly the slot its [[DriftId]] names and is otherwise identical, so a predicate's
    * verdict is attributable to that slot alone. `semantic` records what the drift would do to a consumer, so a
    * reader can judge whether "reports equal" is a defect or a documented relaxation.
    */
  final case class DriftCase(id: DriftId, semantic: String, baseline: StructType, drifted: StructType):
    def name: String  = id.name
    def axis: String  = id.axis

  /** A row's identity with its depth removed, which is what lets two depths of the same edit be compared.
    *
    * `ComparatorMatrix.recursionUniformity` pairs rows by this, so the pairing is by construction rather than by
    * string surgery on row names.
    */
  private[probe] enum Edge:
    case SingleEdge(slot: Slot, edit: Edit)
    case RelocationEdge(from: Slot, to: Slot)

  // ===== STIMULUS CONSTRUCTION =====

  private def f(name: String, dt: DataType, nullable: Boolean): StructField =
    StructField(name, dt, nullable)

  private def struct(fields: StructField*): StructType = StructType(fields.toArray)

  /** A minimal pair before it is placed at a depth. */
  private final case class Stimulus(semantic: String, baseline: StructType, drifted: StructType)

  /** The pair for one identity, at the root, with the reason the difference matters.
    *
    * One match rather than parallel matches for the schemas and the prose: a row's pair and the sentence explaining
    * it have to describe the same edit, and two matches over the same sum can drift apart silently.
    */
  private def stimulus(id: DriftId): Stimulus = id match
    case DriftId.Single(Slot.FieldSet, Edit.Remove, _) =>
      Stimulus(
        "A contract field disappeared from the producer.",
        twoFields,
        struct(f("id", LongType, nullable = false))
      )
    case DriftId.Single(Slot.FieldSet, Edit.Add, _) =>
      Stimulus(
        "The producer gained a field the contract does not declare.",
        twoFields,
        struct(
          f("id", LongType, nullable = false),
          f("email", StringType, nullable = false),
          f("extra", StringType, nullable = false)
        )
      )
    case DriftId.Single(Slot.FieldSet, Edit.Permute, _) =>
      Stimulus(
        "Same fields, different order. Breaks positional reads, harmless for by-name reads.",
        twoFields,
        struct(f("email", StringType, nullable = false), f("id", LongType, nullable = false))
      )
    case DriftId.Single(Slot.FieldName, Edit.Rename, _) =>
      Stimulus(
        "A field was renamed. Positional reads silently keep working on the wrong name.",
        twoFields,
        struct(f("id", LongType, nullable = false), f("mail", StringType, nullable = false))
      )
    case DriftId.Single(Slot.FieldName, Edit.Recase, _) =>
      Stimulus(
        "Casing changed only. Matters for case-sensitive sinks, not for Spark's default resolver.",
        twoFields,
        struct(f("id", LongType, nullable = false), f("EMAIL", StringType, nullable = false))
      )
    case DriftId.Single(Slot.FieldNullable, Edit.Flip, _) =>
      Stimulus(
        "A required field became optional. Code that never null-checks can now see null.",
        leafField,
        struct(f("n", IntegerType, nullable = true))
      )
    case DriftId.Single(Slot.FieldType, Edit.WidenLeaf, _) =>
      Stimulus(
        "Int became Long. Safe to read, but the physical layout changed.",
        leafField,
        struct(f("n", LongType, nullable = false))
      )
    case DriftId.Single(Slot.FieldType, Edit.SwapLeaf, _) =>
      Stimulus(
        "A numeric field became a string. Same name, unrelated type.",
        leafField,
        struct(f("n", StringType, nullable = false))
      )
    case DriftId.Single(Slot.FieldType, Edit.SwapKind, _) =>
      Stimulus(
        "A struct field was replaced by a scalar of the same name, so the name tree is unchanged.",
        struct(f("n", struct(f("inner", StringType, nullable = false)), nullable = false)),
        struct(f("n", StringType, nullable = false))
      )
    case DriftId.Single(Slot.ArrayContainsNull, Edit.Flip, _) =>
      Stimulus(
        "Seq[String] became Seq[Option[String]]. A present array can now have holes.",
        arrayField,
        struct(f("tags", ArrayType(StringType, containsNull = true), nullable = false))
      )
    case DriftId.Single(Slot.ArrayElement, Edit.WidenLeaf, _) =>
      Stimulus(
        "Seq[Int] became Seq[Long].",
        struct(f("tags", ArrayType(IntegerType, containsNull = false), nullable = false)),
        struct(f("tags", ArrayType(LongType, containsNull = false), nullable = false))
      )
    case DriftId.Single(Slot.ArrayElement, Edit.SwapLeaf, _) =>
      Stimulus(
        "Seq[String] became Seq[Int].",
        arrayField,
        struct(f("tags", ArrayType(IntegerType, containsNull = false), nullable = false))
      )
    case DriftId.Single(Slot.ArrayElement, Edit.SwapKind, _) =>
      Stimulus(
        "Seq of struct became Seq of scalar, leaving the field name tree unchanged.",
        struct(
          f("tags", ArrayType(struct(f("inner", StringType, nullable = false)), containsNull = false), nullable = false)
        ),
        arrayField
      )
    case DriftId.Single(Slot.MapValueContainsNull, Edit.Flip, _) =>
      Stimulus(
        "Map[String, Int] became Map[String, Option[Int]]. Values can now be null.",
        mapField,
        struct(f("attrs", MapType(StringType, IntegerType, valueContainsNull = true), nullable = false))
      )
    case DriftId.Single(Slot.MapKey, Edit.WidenLeaf, _) =>
      Stimulus(
        "The map key widened from Int to Long.",
        struct(f("attrs", MapType(IntegerType, IntegerType, valueContainsNull = false), nullable = false)),
        struct(f("attrs", MapType(LongType, IntegerType, valueContainsNull = false), nullable = false))
      )
    case DriftId.Single(Slot.MapKey, Edit.SwapLeaf, _) =>
      Stimulus(
        "The map key changed from String to Int, so every existing key fails to resolve.",
        mapField,
        struct(f("attrs", MapType(IntegerType, IntegerType, valueContainsNull = false), nullable = false))
      )
    case DriftId.Single(Slot.MapKey, Edit.SwapKind, _) =>
      Stimulus(
        "The map key changed from a struct to a scalar.",
        struct(
          f(
            "attrs",
            MapType(struct(f("inner", StringType, nullable = false)), IntegerType, valueContainsNull = false),
            nullable = false
          )
        ),
        mapField
      )
    case DriftId.Single(Slot.MapValue, Edit.WidenLeaf, _) =>
      Stimulus(
        "The map value widened from Int to Long.",
        mapField,
        struct(f("attrs", MapType(StringType, LongType, valueContainsNull = false), nullable = false))
      )
    case DriftId.Single(Slot.MapValue, Edit.SwapLeaf, _) =>
      Stimulus(
        "The map value changed from Int to String.",
        mapField,
        struct(f("attrs", MapType(StringType, StringType, valueContainsNull = false), nullable = false))
      )
    case DriftId.Single(Slot.MapValue, Edit.SwapKind, _) =>
      Stimulus(
        "The map value changed from a struct to a scalar, leaving the field name tree unchanged.",
        struct(
          f(
            "attrs",
            MapType(StringType, struct(f("inner", StringType, nullable = false)), valueContainsNull = false),
            nullable = false
          )
        ),
        struct(f("attrs", MapType(StringType, StringType, valueContainsNull = false), nullable = false))
      )
    case DriftId.Relocation(Slot.FieldNullable, Slot.ArrayContainsNull, _) =>
      Stimulus(
        "Option[Seq[String]] became Seq[Option[String]]. Absence moved from the collection to its elements.",
        struct(f("tags", ArrayType(StringType, containsNull = false), nullable = true)),
        struct(f("tags", ArrayType(StringType, containsNull = true), nullable = false))
      )
    case DriftId.Relocation(Slot.FieldNullable, Slot.MapValueContainsNull, _) =>
      Stimulus(
        "Option[Map[String, Int]] became Map[String, Option[Int]]. Absence moved from the map to its values.",
        struct(f("attrs", MapType(StringType, IntegerType, valueContainsNull = false), nullable = true)),
        struct(f("attrs", MapType(StringType, IntegerType, valueContainsNull = true), nullable = false))
      )
    case DriftId.Relocation(Slot.ArrayContainsNull, Slot.MapValueContainsNull, _) =>
      Stimulus(
        "Seq[Option[Map[String, Int]]] became Seq[Map[String, Option[Int]]]. Absence moved one level inward.",
        struct(
          f(
            "rows",
            ArrayType(MapType(StringType, IntegerType, valueContainsNull = false), containsNull = true),
            nullable = false
          )
        ),
        struct(
          f(
            "rows",
            ArrayType(MapType(StringType, IntegerType, valueContainsNull = true), containsNull = false),
            nullable = false
          )
        )
      )
    // No default branch. Every identity `cases` generates is matched above, so a slot or edit added to the grammar
    // enumeration without a stimulus fails here at the first run rather than quietly producing a shorter table.
    case other =>
      throw new MatchError(s"no stimulus derived for ${other.name}")

  private val twoFields =
    struct(f("id", LongType, nullable = false), f("email", StringType, nullable = false))

  private val leafField = struct(f("n", IntegerType, nullable = false))

  private val arrayField =
    struct(f("tags", ArrayType(StringType, containsNull = false), nullable = false))

  private val mapField =
    struct(f("attrs", MapType(StringType, IntegerType, valueContainsNull = false), nullable = false))

  /** The same edit, one level further down.
    *
    * The whole root pair is wrapped in one non-optional struct field, identically on both sides, so the only
    * difference between the two schemas is still the edit and the only difference from the [[Position.Root]] row is
    * the depth it sits at. Uniform across every slot, which is what makes the depth comparison a clean one.
    */
  private def nest(schema: StructType): StructType =
    struct(f("outer", schema, nullable = false))

  /** The depth an identity names.
    *
    * A free function rather than a member of [[DriftId]]: both cases already carry a `position` parameter, and a
    * method of the same name on the enum itself would shadow those accessors instead of reading them.
    */
  private[probe] def positionOf(id: DriftId): Position = id match
    case DriftId.Single(_, _, p)     => p
    case DriftId.Relocation(_, _, p) => p

  /** The edit an identity names, with its depth dropped. */
  private[probe] def edgeOf(id: DriftId): Edge = id match
    case DriftId.Single(slot, edit, _)  => Edge.SingleEdge(slot, edit)
    case DriftId.Relocation(from, to, _) => Edge.RelocationEdge(from, to)

  private def build(id: DriftId): DriftCase =
    val raw = stimulus(id)
    positionOf(id) match
      case Position.Root => DriftCase(id, raw.semantic, raw.baseline, raw.drifted)
      case Position.Nested =>
        DriftCase(id, s"${raw.semantic} One level below the root.", nest(raw.baseline), nest(raw.drifted))

  /** The unordered pairs of optionality slots, which are what a relocation can move a bit between.
    *
    * Unordered because which side is the baseline is already a separate dimension of the matrix: the harness runs
    * every pair in both argument orders, so listing `(a, b)` and `(b, a)` as different axes would measure the same
    * thing twice under two names.
    */
  private val optionalityPairs: List[(Slot, Slot)] =
    List(
      Slot.FieldNullable     -> Slot.ArrayContainsNull,
      Slot.FieldNullable     -> Slot.MapValueContainsNull,
      Slot.ArrayContainsNull -> Slot.MapValueContainsNull
    )

  /** Every row, generated.
    *
    * The product of slots, the edits each admits, and both measured depths, followed by the relocation tier. Nothing
    * is filtered: a combination that could not be instantiated would be a hole in the derivation, so [[stimulus]] is
    * total over the identities this produces rather than partial with the gaps silently dropped.
    *
    * Declared after the schemas it is built from. Scala initializes an object's `val`s in declaration order, so a
    * `val` placed above the ones it reads sees them as `null`, and the failure arrives at the first run rather than
    * at compile time.
    */
  val cases: List[DriftCase] =
    val singles =
      for
        slot     <- Slot.values.toList
        edit     <- slot.edits
        position <- Position.values.toList
      yield DriftId.Single(slot, edit, position)
    val relocations =
      for
        (from, to) <- optionalityPairs
        position   <- Position.values.toList
      yield DriftId.Relocation(from, to, position)
    (singles ::: relocations).map(build)

  /** A statement of what the enumeration covers, computed from it.
    *
    * Printed with the matrix so the completeness claim in the paper is reproduced by the run rather than asserted in
    * prose, and so a reader can check the arithmetic: slots times admissible edits times depths, plus the relocation
    * tier.
    */
  def census: String =
    val perSlot = Slot.values.toList.map { slot =>
      s"  $slot: ${slot.edits.mkString(", ")} (${slot.edits.size})"
    }
    val singles = Slot.values.toList.map(_.edits.size).sum
    s"""drift taxonomy, derived from StructType's grammar
       |
       |slots and the edits their own type admits
       |${perSlot.mkString("\n")}
       |
       |single-slot axes: $singles edits x ${Position.values.length} depths = ${singles * Position.values.length}
       |relocation axes:  ${optionalityPairs.size} optionality pairs x ${Position.values.length} depths = ${optionalityPairs.size * Position.values.length}
       |total rows:       ${cases.size}
       |
       |completeness: every difference between two StructTypes is a set of single-slot edits, and every single-slot
       |edit is a row above. Compositions of edits are not enumerated - there is no finite enumeration of those - with
       |the exception of the relocation tier, which is included because it is the family where a predicate can be
       |correct on each slot alone and wrong on the pair.""".stripMargin

  private[probe] def snakeCase(name: String): String =
    name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT)
