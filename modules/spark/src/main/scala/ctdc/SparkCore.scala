package ctdc

import ctdc.SchemaPolicy
import ctdc.internal.{ComparisonRules, FieldMatching, NameCasing, Tolerance}
import org.apache.spark.sql.{Column, DataFrame, Dataset, Encoder, SaveMode, SparkSession}
import org.apache.spark.sql.functions.{col, exists, map_values, sum, when}
import org.apache.spark.sql.types.*

/** Spark side of the house (POC)
  *   - Derive StructType from a Scala product type (case class) at compile time
  *   - Provide runtime pins that follow SchemaPolicy semantics, using Spark-like name/order matching plus a deep check
  *     for nested collection optionality that Spark's comparators ignore
  *   - Offer a tiny typed IO and a phantom-typed pipeline builder for demos
  *
  * Spark comparator references (3.5.x). All three zip fields positionally, so none of them is unordered; what
  * differs is whether a name is compared at all and how:
  *   - equalsIgnoreCaseAndNullability (names up to case, position-zipped, all three carriers ignored)
  *   - equalsStructurally (position-zipped, names ignored, all three carriers compared unless
  *     ignoreNullability is set)
  *   - equalsStructurallyByName (position-zipped names through a resolver, leaf types and all three carriers
  *     ignored: its non-struct case returns true)
  */
object SparkCore:
  private val HasDefaultMetadataKey = "ctdc.hasDefault"

  // 1> Typed endpoints

  final case class TypedSource[C](format: String, path: String, options: Map[String, String] = Map.empty)
  final case class TypedSink[C](
      path: String,
      mode: SaveMode = SaveMode.Overwrite,
      options: Map[String, String] = Map.empty
  )

  /**
   * The rules the runtime pin for `policy` compares under: the rules its macro compared under, minus every
   * carrier of optionality a `StructType` cannot state.
   *
   * Each of the three carriers is one bit, and each bit has to carry three meanings on the producer side:
   * the value may be absent, the value is always present, and nobody said. Spark's file readers return the
   * permissive value for all three on every format that does not record the claim - `nullable = true` for
   * every field, `containsNull = true` for every inferred array, `valueContainsNull = true` for every
   * inferred map - so a `true` is not a producer saying values may be absent, it is a producer that was
   * never asked. Comparing any of them against a contract that does state the claim fails pipelines that
   * read from CSV, JSON or inferred Parquet, and fails them for a reason about Spark's representation rather
   * than about the data.
   *
   * So the carriers are checked where they are stated - in the macro, against the Scala types, which
   * distinguish `Option[A]` from `A` at each of the three positions - and deliberately not here. All three
   * go, not one: dropping `nullable` while demanding exact equality of the two nested bits is what this used
   * to do, and the review that found it reproduced a valid single-file JSON read being rejected by its own
   * contract.
   *
   * The bound is on the information this function has, not on runtime checking generally. A `StructType` a
   * reader produced cannot distinguish a stated claim from a defaulted one, so no total rule over these two
   * arguments both fires on real drift and tolerates a file read. What is available instead is the data:
   * [[SchemaCheck.assertNoForbiddenNulls]] answers the same question by reading rows, and is the honest
   * replacement for the check this function cannot make. It is opt-in because it costs a pass over the
   * frame, and it is not a weaker version of what was dropped - it is a stronger one, because a value that
   * is absent is drift and a bit that was defaulted is not.
   */
  private def rulesFor(policy: SchemaPolicy): ComparisonRules =
    ComparisonRules.of(policy).ignoringOptionality

  /**
   * The runtime half of contract checking, over `StructType` instead of [[ctdc.internal.TypeShape]].
   *
   * It takes the same [[ComparisonRules]] the macro takes, rather than a mode of its own, so that a policy
   * cannot mean one thing at compile time and another at runtime. The previous shape of this object - its own
   * five-case mode enum, each case carrying a `caseInsensitive: Boolean` - modelled the casing decision a
   * second time, and that is how it came to compare two of the three carriers of optionality and not the
   * third.
   */
  private object RuntimeSchemaComparator:

    def matches(found: StructType, expected: StructType, rules: ComparisonRules): Boolean =
      rules.tolerance match
        case Tolerance.Permissive => true
        case _                    => compareStruct(found, expected, rules)

    def duplicateNames(struct: StructType, casing: NameCasing): List[List[String]] =
      struct.fields
        .groupBy(field => casing.normalize(field.name))
        .values
        .collect { case fields if fields.length > 1 => fields.toList.map(_.name).sorted }
        .toList

    private def hasDefault(field: StructField): Boolean =
      field.metadata.contains(HasDefaultMetadataKey) && field.metadata.getBoolean(HasDefaultMetadataKey)

    /** Whether a contract field the producer does not have at all is still tolerable. */
    private def missingTolerated(expected: StructField, tolerance: Tolerance): Boolean =
      tolerance match
        case Tolerance.Strict     => false
        case Tolerance.Backward   => expected.nullable || hasDefault(expected)
        case Tolerance.Forward    => true
        case Tolerance.Permissive => true

    /** Whether a producer field the contract does not mention is tolerable. */
    private def extraTolerated(tolerance: Tolerance): Boolean =
      tolerance match
        case Tolerance.Strict     => false
        case Tolerance.Backward   => true
        case Tolerance.Forward    => false
        case Tolerance.Permissive => true

    private def uniqueFieldsByName(struct: StructType, casing: NameCasing): Option[Map[String, StructField]] =
      if duplicateNames(struct, casing).isEmpty then
        Some(struct.fields.groupBy(field => casing.normalize(field.name)).view.mapValues(_.head).toMap)
      else None

    private def compareStruct(found: StructType, expected: StructType, rules: ComparisonRules): Boolean =
      rules.matching match
        case FieldMatching.ByPosition =>
          found.fields.length == expected.fields.length &&
            found.fields.lazyZip(expected.fields).forall(compareField(_, _, rules))

        case FieldMatching.ByNameOrdered =>
          found.fields.length == expected.fields.length &&
            found.fields.lazyZip(expected.fields).forall { (left, right) =>
              rules.sameName(left.name, right.name) && compareField(left, right, rules)
            }

        case FieldMatching.ByName =>
          uniqueFieldsByName(found, rules.casing)
            .zip(uniqueFieldsByName(expected, rules.casing))
            .exists { case (foundByName, expectedByName) =>
              val contractSatisfied = expectedByName.forall { case (name, expectedField) =>
                foundByName
                  .get(name)
                  .fold(missingTolerated(expectedField, rules.tolerance))(compareField(_, expectedField, rules))
              }
              val extrasSatisfied =
                extraTolerated(rules.tolerance) || foundByName.keySet.subsetOf(expectedByName.keySet)
              contractSatisfied && extrasSatisfied
            }

    /**
     * One field against its counterpart: the same value may be absent, and it must be the same type.
     *
     * The first conjunct is the carrier Spark's own comparators drop and this one used to drop with them.
     * `nullable = false` on a `StructField` is the only part of a `StructType` that states an invariant
     * rather than a layout, so a comparison that skips it reports agreement about everything except the
     * single claim that can be violated.
     */
    private def compareField(found: StructField, expected: StructField, rules: ComparisonRules): Boolean =
      rules.optionalityConforms(found.nullable, expected.nullable) &&
        compareDataType(found.dataType, expected.dataType, rules)

    private def compareDataType(found: DataType, expected: DataType, rules: ComparisonRules): Boolean =
      (found, expected) match
        case (left: StructType, right: StructType) =>
          compareStruct(left, right, rules)

        // The other two carriers, read through the same axis as `nullable` above rather than compared for
        // equality here. Equality here is what made a valid file unreadable: a JSON reader returns
        // `containsNull = true` for every array it infers, exactly as it returns `nullable = true` for every
        // field, so a contract that says `List[String]` was rejected by its own derived schema on data with
        // no nulls in it. One bit defaulted by a reader is one bit defaulted by a reader wherever it sits.
        case (ArrayType(leftElem, leftContainsNull), ArrayType(rightElem, rightContainsNull)) =>
          rules.optionalityConforms(leftContainsNull, rightContainsNull) &&
            compareDataType(leftElem, rightElem, rules)

        case (MapType(leftKey, leftValue, leftValueContainsNull), MapType(rightKey, rightValue, rightValueContainsNull)) =>
          rules.optionalityConforms(leftValueContainsNull, rightValueContainsNull) &&
            compareDataType(leftKey, rightKey, rules) &&
            compareDataType(leftValue, rightValue, rules)

        case _ =>
          // Leaves only. Every composite case is above, so no carrier reaches this equality.
          found == expected

  // 2> PolicyRuntime: pick Spark comparator by policy
  trait PolicyRuntime[P <: SchemaPolicy]:
    def ok(found: StructType, expected: StructType): Boolean

  object PolicyRuntime:

    /**
     * The pin for one policy.
     *
     * Every instance below is this function applied to its own policy value, so there is nothing left in the
     * runtime pin for a policy to be wrong about: the comparison a policy stands for is written once, in
     * [[ComparisonRules.of]], and both halves of the library read it from there. What used to be here was a
     * per-policy choice of comparator plus a literal casing boolean, which is the same decision taken nine
     * more times.
     */
    private def pin[P <: SchemaPolicy](policy: SchemaPolicy): PolicyRuntime[P] =
      val rules = rulesFor(policy)
      new PolicyRuntime[P]:
        def ok(found: StructType, expected: StructType): Boolean =
          RuntimeSchemaComparator.matches(found, expected, rules)

    given PolicyRuntime[SchemaPolicy.Exact.type]            = pin(SchemaPolicy.Exact)
    given PolicyRuntime[SchemaPolicy.ExactUnordered.type]   = pin(SchemaPolicy.ExactUnordered)
    given PolicyRuntime[SchemaPolicy.ExactUnorderedCI.type] = pin(SchemaPolicy.ExactUnorderedCI)
    given PolicyRuntime[SchemaPolicy.ExactOrdered.type]     = pin(SchemaPolicy.ExactOrdered)
    given PolicyRuntime[SchemaPolicy.ExactOrderedCI.type]   = pin(SchemaPolicy.ExactOrderedCI)
    given PolicyRuntime[SchemaPolicy.ExactByPosition.type]  = pin(SchemaPolicy.ExactByPosition)
    given PolicyRuntime[SchemaPolicy.Backward.type]         = pin(SchemaPolicy.Backward)
    given PolicyRuntime[SchemaPolicy.Forward.type]          = pin(SchemaPolicy.Forward)
    given PolicyRuntime[SchemaPolicy.Full.type]             = pin(SchemaPolicy.Full)

  // 3> Derive StructType from a Scala product type (case class)
  trait SparkSchema[C]:
    def struct: StructType

  object SparkSchema:
    import scala.quoted.*

    inline given derived[C]: SparkSchema[C] = ${ sparkSchemaImpl[C] }

    private def sparkSchemaImpl[C: Type](using Quotes): Expr[SparkSchema[C]] =
      import quotes.reflect.*

      val tpe = TypeRepr.of[C]
      val sym = tpe.typeSymbol
      if !sym.isClassDef || !sym.flags.is(Flags.Case) then
        report.errorAndAbort(s"SparkSchema requires a contract case class: ${tpe.show}")

      // helpers over TypeRepr

      def isSeqLike(t: TypeRepr): Boolean =
        t <:< TypeRepr.of[List[?]] || t <:< TypeRepr.of[Seq[?]] ||
          t <:< TypeRepr.of[Vector[?]] || t <:< TypeRepr.of[Array[?]] || t <:< TypeRepr.of[Set[?]]

      def appliedArgs(t: TypeRepr): List[TypeRepr] = t match
        case AppliedType(_, args) => args
        case _                    => Nil

      def optionArg(t: TypeRepr): Option[TypeRepr] =
        if t <:< TypeRepr.of[Option[?]] then appliedArgs(t).headOption else None

      /**
       * The error for an `Option` the target `StructType` has no room for.
       *
       * `StructField.nullable`, `ArrayType.containsNull` and `MapType.valueContainsNull` are one bit each, so
       * each records exactly one layer of optionality. `Option[Option[A]]` states two. Setting the bit and
       * dropping the rest would make `Option[A]` and `Option[Option[A]]` the same schema, which is a loss the
       * caller cannot see, so the derivation reports it instead of flattening.
       */
      def nestedOptional(t: TypeRepr, carrier: String): Nothing =
        report.errorAndAbort(
          s"Unsupported nested Option in SparkSchema derivation: ${t.show}. Spark records optionality in one " +
            s"bit here ($carrier), so only one layer can be represented. Flatten it in the contract type, or " +
            "model the inner layer as data the Spark schema can hold."
        )

      /** One `Option` layer taken off `t` onto `carrier`, the single bit Spark has for it at this position. */
      def consumeOptional(t: TypeRepr, carrier: String): (TypeRepr, Boolean) =
        optionArg(t).fold(t -> false) { inner =>
          if optionArg(inner).isDefined then nestedOptional(t, carrier)
          inner -> true
        }

      def mapArgs(t: TypeRepr): Option[(TypeRepr, TypeRepr)] =
        if t <:< TypeRepr.of[Map[?, ?]] then
          appliedArgs(t) match
            case k :: v :: Nil => Some((k, v))
            case _             => report.errorAndAbort(s"Map requires two type args: ${t.show}")
        else None

      def isAtomicKey(t: TypeRepr): Boolean =
        t =:= TypeRepr.of[String] ||
          t =:= TypeRepr.of[Int] || t =:= TypeRepr.of[Long] ||
          t =:= TypeRepr.of[Short] || t =:= TypeRepr.of[Byte] || t =:= TypeRepr.of[Boolean]

      // map types to Spark DataType

      def primitiveDt(t: TypeRepr): Expr[DataType] =
        if t =:= TypeRepr.of[String] then '{ StringType }
        else if t =:= TypeRepr.of[Int] then '{ IntegerType }
        else if t =:= TypeRepr.of[Long] then '{ LongType }
        else if t =:= TypeRepr.of[Short] then '{ ShortType }
        else if t =:= TypeRepr.of[Byte] then '{ ByteType }
        else if t =:= TypeRepr.of[Double] then '{ DoubleType }
        else if t =:= TypeRepr.of[Float] then '{ FloatType }
        else if t =:= TypeRepr.of[Boolean] then '{ BooleanType }
        else if t =:= TypeRepr.of[BigDecimal] then '{ DecimalType.SYSTEM_DEFAULT }
        else if t =:= TypeRepr.of[java.math.BigDecimal] then '{ DecimalType.SYSTEM_DEFAULT }
        else if t =:= TypeRepr.of[java.sql.Date] || t =:= TypeRepr.of[java.time.LocalDate] then '{ DateType }
        else if t =:= TypeRepr.of[java.sql.Timestamp] || t =:= TypeRepr.of[java.time.Instant] then '{ TimestampType }
        else if t =:= TypeRepr.of[java.time.LocalDateTime] then '{ DataTypes.TimestampNTZType } // Spark ≥ 3.4
        else
          report.errorAndAbort(
            s"Unsupported type in SparkSchema derivation: ${t.show}. Supported leaf types: String, Int, Long, Short, Byte, Double, Float, Boolean, BigDecimal, java.math.BigDecimal, java.sql.Date, java.time.LocalDate, java.sql.Timestamp, java.time.Instant, java.time.LocalDateTime. Supported container shapes: case classes, Option, List/Seq/Vector/Array/Set, and Map[atomic, _]."
          )

      def dtOf(t: TypeRepr): Expr[DataType] =
        if isSeqLike(t) then
          val elemRaw =
            appliedArgs(t).headOption.getOrElse(report.errorAndAbort(s"Missing type arg for sequence in ${t.show}"))
          val (elem, containsNull) = consumeOptional(elemRaw, "ArrayType.containsNull")
          '{ ArrayType(${ dtOf(elem) }, containsNull = ${ Expr(containsNull) }) }
        else
          mapArgs(t)
            .map { case (k, vRaw) =>
              if !isAtomicKey(k) then
                report.errorAndAbort(
                  s"Unsupported Map key type for ${t.show}. Allowed keys: String, Int, Long, Short, Byte, Boolean."
                )
              val (v, valueContainsNull) = consumeOptional(vRaw, "MapType.valueContainsNull")
              '{ MapType(${ primitiveDt(k) }, ${ dtOf(v) }, valueContainsNull = ${ Expr(valueContainsNull) }) }
            }
            .getOrElse {
              // An `Option` reaching here is one whose carrier was already consumed by the caller, so it is a
              // second layer with no bit left to hold it. This used to strip it and carry on, which is how
              // `Option[Option[A]]` came to derive the same `StructType` as `Option[A]`.
              if optionArg(t).isDefined then nestedOptional(t, "the enclosing field, element or map value")
              else if t.typeSymbol.flags.is(Flags.Case) then structOf(t)
              else primitiveDt(t)
            }

      def structOf(tc: TypeRepr): Expr[DataType] =
        val params = tc.typeSymbol.primaryConstructor.paramSymss.flatten
        val fieldExprs: List[Expr[StructField]] = params.map { p =>
          val name       = p.name
          val ptpe       = tc.memberType(p)
          val hasDefault = p.flags.is(Flags.HasDefault)
          val (u, isOpt) = consumeOptional(ptpe, "StructField.nullable")
          val dt         = dtOf(u)
          val metadata =
            '{ new MetadataBuilder().putBoolean(${ Expr(HasDefaultMetadataKey) }, ${ Expr(hasDefault) }).build() }
          '{ StructField(${ Expr(name) }, $dt, ${ Expr(isOpt) }, $metadata) }
        }
        '{ StructType(${ Expr.ofList(fieldExprs) }) }

      val structExpr: Expr[StructType] = structOf(tpe).asExprOf[StructType]

      '{
        new SparkSchema[C]:
          def struct: StructType = $structExpr
      }

  // 4> Runtime pins
  object SchemaCheck:

    /**
     * Default pin: the comparison `ExactUnorderedCI` stands for.
     *
     * What this checks is the frame's `StructType`, under [[rulesFor]], which is to say its field names,
     * their order where the policy cares, and the leaf types. It does not check the rows, and it does not
     * check any of the three carriers of optionality, for the reason [[rulesFor]] gives. For the carriers,
     * call [[assertNoForbiddenNulls]]; it is a separate call because it costs a pass over the data.
     */
    def assertMatchesContract[C](df: DataFrame)(using sch: SparkSchema[C]): Unit =
      val expected = sch.struct
      val ok       = RuntimeSchemaComparator.matches(df.schema, expected, rulesFor(SchemaPolicy.ExactUnorderedCI))
      if !ok then throw mismatch("contract", df.schema, expected)

    /** Policy-aware pin using PolicyRuntime[P] for comparator choice. Checks the same object as above. */
    def assertMatchesContract[C, P <: SchemaPolicy](
        df: DataFrame
    )(using sch: SparkSchema[C], pr: PolicyRuntime[P]): Unit =
      val expected = sch.struct
      val ok       = pr.ok(df.schema, expected)
      if !ok then throw mismatch(s"policy ${pr.getClass.getName}", df.schema, expected)

    /**
     * The carrier check the schema pin cannot make, made against the data instead.
     *
     * [[rulesFor]] drops all three carriers of optionality because a `StructType` from a reader records a
     * default where the producer may have said nothing, so comparing the bits rejects valid files. Dropping
     * them leaves the contract's `Option`-free positions unenforced at runtime, and a dropped check that
     * nothing replaces is the state this method exists to avoid. It asks the question the carriers were
     * asking - can a value the contract says is always present actually be absent here - of the rows, where
     * the answer is a fact rather than a default.
     *
     * The three positions are read off the contract's own derived schema: a field with `nullable = false`, an
     * array with `containsNull = false`, a map with `valueContainsNull = false`. Each becomes one aggregate,
     * and they are counted in one pass, so the cost is one scan however deep the contract is.
     *
     * Two things it deliberately does not do. It does not check fields the frame does not have: a missing
     * field is the schema pin's business and reporting it twice in different vocabulary helps no one. It
     * does not look under a parent that is itself legitimately absent, because a null inside a null struct
     * or a null array element is not a second violation.
     */
    def assertNoForbiddenNulls[C](df: DataFrame)(using sch: SparkSchema[C]): Unit =
      val probes = requiredValueProbes(df, sch.struct)
      if probes.nonEmpty then
        val counts = df.select(probes.map((path, probe) => sum(when(probe, 1).otherwise(0)).as(path))*).head()
        val violated =
          probes.map(_._1).zipWithIndex.collect {
            case (path, index) if !counts.isNullAt(index) && counts.getLong(index) > 0 =>
              s"$path: ${counts.getLong(index)} row(s)"
          }
        if violated.nonEmpty then
          throw new IllegalArgumentException(
            s"""Contract violated by the data: a value the contract requires is absent.
               |${violated.mkString("\n")}
               |Expected:
               |${sch.struct.treeString}
               |""".stripMargin
          )

    /** One `(path, predicate)` per position the contract says is always present, rooted at the frame. */
    private def requiredValueProbes(df: DataFrame, expected: StructType): List[(String, Column)] =
      val present = df.schema.fieldNames.toSet
      expected.fields.toList.filter(field => present.contains(field.name)).flatMap { field =>
        val base = col(field.name)
        val here = Option.unless(field.nullable)(field.name -> base.isNull)
        here.toList ++ forbiddenNullProbes(field.name, field.dataType).map((path, probe) =>
          path -> (base.isNotNull && probe(base))
        )
      }

    /**
     * The probes for the carriers *inside* a value, each as a function of the column holding that value.
     *
     * A function rather than a column because the recursion passes under `exists`, where the value being
     * probed is a lambda parameter and not a column anything outside can name. Every step guards on the
     * parent being present, which is what keeps a legitimately absent parent from being reported as a
     * violation at each of its children.
     */
    private def forbiddenNullProbes(path: String, dt: DataType): List[(String, Column => Column)] =
      dt match
        case struct: StructType =>
          struct.fields.toList.flatMap { field =>
            val at    = s"$path.${field.name}"
            val under = (c: Column) => c.getField(field.name)
            val here  = Option.unless(field.nullable)(at -> ((c: Column) => under(c).isNull))
            here.toList ++ forbiddenNullProbes(at, field.dataType).map((inner, probe) =>
              inner -> ((c: Column) => under(c).isNotNull && probe(under(c)))
            )
          }

        case ArrayType(elem, containsNull) =>
          val at   = s"$path[]"
          val here = Option.unless(containsNull)(at -> ((c: Column) => exists(c, _.isNull)))
          here.toList ++ forbiddenNullProbes(at, elem).map((inner, probe) =>
            inner -> ((c: Column) => exists(c, e => e.isNotNull && probe(e)))
          )

        case MapType(_, value, valueContainsNull) =>
          // Only the value carrier: Spark map keys cannot be null, so there is no third bit here to probe.
          val at   = s"$path<value>"
          val here = Option.unless(valueContainsNull)(at -> ((c: Column) => exists(map_values(c), _.isNull)))
          here.toList ++ forbiddenNullProbes(at, value).map((inner, probe) =>
            inner -> ((c: Column) => exists(map_values(c), v => v.isNotNull && probe(v)))
          )

        case _ => Nil

    private def duplicateDetail(label: String, schema: StructType): Option[String] =
      val duplicates = RuntimeSchemaComparator.duplicateNames(schema, NameCasing.Insensitive)
      Option.when(duplicates.nonEmpty) {
        val rendered = duplicates.map(names => names.mkString("[", ", ", "]")).mkString(", ")
        s"$label has case-insensitive duplicate field names: $rendered"
      }

    private def mismatch(what: String, found: StructType, expected: StructType) =
      val detailLines =
        List(
          duplicateDetail("Found schema", found),
          duplicateDetail("Expected schema", expected)
        ).flatten
      val detailBlock =
        if detailLines.nonEmpty then s"Detail:\n${detailLines.mkString("\n")}\n" else ""
      new IllegalArgumentException(
        s"""Runtime schema mismatch against $what.
           |${detailBlock}Found:
           |${found.treeString}
           |Expected:
           |${expected.treeString}
           |""".stripMargin
      )

  // 5> Typed IO — small convenience only
  object TypedIO:

    /**
     * A frame read under the contract's schema, with that schema given to the reader.
     *
     * Be precise about what the pin below does and does not establish, because the schema is an input to the
     * read and not an observation of it. Spark is asked for these columns at these types, so `df.schema` is
     * the requested schema whether or not the file had anything to do with it: a column the file does not
     * contain comes back as all nulls, and a value that does not parse at the requested type comes back as
     * null under the default `PERMISSIVE` mode. The pin therefore confirms that the reader honoured the
     * request, which is close to a tautology, and not that the file matches the contract.
     *
     * The check that does see the file is on the rows. [[SchemaCheck.assertNoForbiddenNulls]] catches both
     * of those cases wherever the contract says a value is always present, and `src.options` is the place to
     * put `mode -> FAILFAST` when a malformed value should stop the read rather than become a null. Neither
     * is done here: a read that costs an extra pass over the data, or that fails on a row the caller was
     * willing to drop, is the caller's decision and not this function's.
     */
    def readDF[C](src: TypedSource[C])(using SparkSession, SparkSchema[C]): DataFrame =
      val spark  = summon[SparkSession]
      val schema = summon[SparkSchema[C]].struct
      val reader = src.options.foldLeft(spark.read.format(src.format)) { case (r, (k, v)) => r.option(k, v) }
      val df     = reader.schema(schema).load(src.path)
      SchemaCheck.assertMatchesContract[C](df) // the reader honoured the requested schema
      df

    /**
     * A frame written to a typed sink after a policy-aware pin, unchanged.
     *
     * Unchanged is the word to hold on to under `Backward`, which accepts a producer that omits a contract
     * field when that field is optional or has a default. What it accepts is a relation between two field
     * sets; it does not make the omitted field appear in the output. `ctdc.hasDefault` on the derived schema
     * is a `Boolean` saying a Scala default exists, not the default's value and not a way to evaluate it, so
     * there is nothing here that could fill the column even if this function tried. A consumer reading the
     * result with the contract's own type will find the field absent.
     *
     * So passing `Backward` is not evidence that a complete `Contract` value can be decoded from the output.
     * Adapting the frame - selecting the contract's columns, supplying the defaults - is a transformation the
     * caller writes, and the pin is what tells them whether they have to.
     */
    def writeDF[C, P <: SchemaPolicy](df: DataFrame, sink: TypedSink[C])(using
        SparkSchema[C],
        PolicyRuntime[P]
    ): Unit =
      SchemaCheck.assertMatchesContract[C, P](df)
      df.write.format("parquet").mode(sink.mode).options(sink.options).save(sink.path)

    // Dataset helpers (optional)
    def read[A: Encoder](path: String)(using SparkSession): Dataset[A] =
      summon[SparkSession].read.parquet(path).as[A]

    def write[A: Encoder](ds: Dataset[A], sink: TypedSink[A]): Unit =
      ds.write.mode(sink.mode).parquet(sink.path)

  // 6> Phantom-typed pipeline (POC)
  sealed trait BuilderState
  sealed trait Empty         extends BuilderState
  sealed trait WithSource    extends BuilderState
  sealed trait WithTransform extends BuilderState
  sealed trait Complete      extends BuilderState

  sealed trait PipelineStep:
    def run(spark: SparkSession, in: Option[DataFrame]): DataFrame

  object PipelineStep:
    final case class Source(step: SparkSession => DataFrame) extends PipelineStep:
      def run(spark: SparkSession, in: Option[DataFrame]): DataFrame = step(spark)

    final case class Transform(step: DataFrame => DataFrame) extends PipelineStep:
      def run(spark: SparkSession, in: Option[DataFrame]): DataFrame =
        step(in.getOrElse(sys.error("No input DataFrame for transform")))

    final case class Sink(step: DataFrame => Unit) extends PipelineStep:
      def run(spark: SparkSession, in: Option[DataFrame]): DataFrame =
        val df = in.getOrElse(sys.error("No input DataFrame for sink"))
        step(df); df

  import ctdc.SchemaConforms
  import SparkCore.PolicyRuntime

  final case class PipelineBuilder[S <: BuilderState, CurContract] private (name: String, steps: List[PipelineStep]):

    def addSource[C](src: TypedSource[C])(using sch: SparkSchema[C], ev: S =:= Empty): PipelineBuilder[WithSource, C] =
      val step = PipelineStep.Source { spark =>
        given SparkSession = spark
        TypedIO.readDF(src)(using spark, sch)
      }
      PipelineBuilder[WithSource, C](name, steps :+ step)

    def transformAs[Next](desc: String = "")(f: DataFrame => DataFrame)(using
        ev: S <:< WithSource,
        sch: SparkSchema[Next]
    ): PipelineBuilder[WithTransform, Next] =
      val step = PipelineStep.Transform { df =>
        val out = f(df)
        // Mid-pipeline pins intentionally stay on the default unordered comparator.
        // Policy-aware enforcement happens at the sink boundary.
        SchemaCheck.assertMatchesContract[Next](out)
        out
      }
      PipelineBuilder[WithTransform, Next](name, steps :+ step)

    def noTransform(using ev: S <:< WithSource): PipelineBuilder[WithTransform, CurContract] =
      PipelineBuilder[WithTransform, CurContract](name, steps :+ PipelineStep.Transform(identity))

    /** Compile-time fuse triggers here via SchemaConforms[CurContract, R, P]. Runtime pin mirrors the chosen policy P
      * using PolicyRuntime[P].
      */
    def addSink[R, P <: SchemaPolicy](sink: TypedSink[R])(using
        ev0: S <:< WithTransform,
        ev1: SchemaConforms[CurContract, R, P],
        sch: SparkSchema[R],
        pr: PolicyRuntime[P]
    ): PipelineBuilder[Complete, CurContract] =
      val step = PipelineStep.Sink { df =>
        TypedIO.writeDF[R, P](df, sink)
      }
      PipelineBuilder[Complete, CurContract](name, steps :+ step)

    def build(using ev: S =:= Complete): SparkSession => DataFrame =
      (spark: SparkSession) =>
        steps
          .foldLeft(Option.empty[DataFrame]) { (acc, step) =>
            Some(step.run(spark, acc))
          }
          .get

  object PipelineBuilder:
    def apply[CurContract](name: String): PipelineBuilder[Empty, CurContract] =
      PipelineBuilder[Empty, CurContract](name, Nil)
