package ctdc

import ctdc.SchemaPolicy
import ctdc.internal.{ComparisonRules, FieldMatching, NameCasing, Tolerance}
import org.apache.spark.sql.{DataFrame, Dataset, Encoder, SaveMode, SparkSession}
import org.apache.spark.sql.types.*

/** Spark side of the house (POC)
  *   - Derive StructType from a Scala product type (case class) at compile time
  *   - Provide runtime pins that follow SchemaPolicy semantics, using Spark-like name/order matching plus a deep check
  *     for nested collection optionality that Spark's comparators ignore
  *   - Offer a tiny typed IO and a phantom-typed pipeline builder for demos
  *
  * Spark comparator references (3.5.x):
  *   - equalsIgnoreCaseAndNullability (unordered by name, CI, ignore nullability)
  *   - equalsStructurally (by position, names ignored)
  *   - equalsStructurallyByName (ordered by name with a name resolver)
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
   * The rules the runtime pin for `policy` compares under: the rules its macro compared under, minus the one
   * carrier a `StructType` cannot state.
   *
   * `StructField.nullable` has two values and has to carry three meanings on the producer side: this field
   * may be absent, this field is always present, and nobody said. Spark's file readers return `true` for
   * every field of every format that does not record the claim, so a `true` is not a producer saying values
   * may be absent - it is a producer that was never asked. Comparing it against a contract that does state
   * the claim fails every pipeline that reads from CSV or JSON, and fails it for a reason that is about
   * Spark's representation rather than about the data.
   *
   * So the carrier is checked where it is stated - in the macro, against the Scala types, which distinguish
   * `Option[A]` from `A` - and deliberately not here. This is a limit of `StructType`, not a relaxation that
   * someone chose: there is no rule this function could pass that would make the check mean anything, because
   * the information is already gone by the time a `DataFrame` exists.
   */
  private def rulesFor(policy: SchemaPolicy): ComparisonRules =
    ComparisonRules.of(policy).ignoringFieldOptionality

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

        case (ArrayType(leftElem, leftContainsNull), ArrayType(rightElem, rightContainsNull)) =>
          leftContainsNull == rightContainsNull &&
            compareDataType(leftElem, rightElem, rules)

        case (MapType(leftKey, leftValue, leftValueContainsNull), MapType(rightKey, rightValue, rightValueContainsNull)) =>
          leftValueContainsNull == rightValueContainsNull &&
            compareDataType(leftKey, rightKey, rules) &&
            compareDataType(leftValue, rightValue, rules)

        case _ =>
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

      def splitOptional(t: TypeRepr): (TypeRepr, Boolean) =
        optionArg(t).fold(t -> false)(a => a -> true)

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
          val (elem, containsNull) = splitOptional(elemRaw)
          '{ ArrayType(${ dtOf(elem) }, containsNull = ${ Expr(containsNull) }) }
        else
          mapArgs(t)
            .map { case (k, vRaw) =>
              if !isAtomicKey(k) then
                report.errorAndAbort(
                  s"Unsupported Map key type for ${t.show}. Allowed keys: String, Int, Long, Short, Byte, Boolean."
                )
              val (v, valueContainsNull) = splitOptional(vRaw)
              '{ MapType(${ primitiveDt(k) }, ${ dtOf(v) }, valueContainsNull = ${ Expr(valueContainsNull) }) }
            }
            .getOrElse {
              optionArg(t).map(dtOf).getOrElse {
                if t.typeSymbol.flags.is(Flags.Case) then structOf(t)
                else primitiveDt(t)
              }
            }

      def structOf(tc: TypeRepr): Expr[DataType] =
        val params = tc.typeSymbol.primaryConstructor.paramSymss.flatten
        val fieldExprs: List[Expr[StructField]] = params.map { p =>
          val name       = p.name
          val ptpe       = tc.memberType(p)
          val hasDefault = p.flags.is(Flags.HasDefault)
          val (u, isOpt) = optionArg(ptpe).fold(ptpe -> false)(a => a -> true)
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

    /** Default pin: the comparison `ExactUnorderedCI` stands for. */
    def assertMatchesContract[C](df: DataFrame)(using sch: SparkSchema[C]): Unit =
      val expected = sch.struct
      val ok       = RuntimeSchemaComparator.matches(df.schema, expected, rulesFor(SchemaPolicy.ExactUnorderedCI))
      if !ok then throw mismatch("contract", df.schema, expected)

    /** Policy-aware pin using PolicyRuntime[P] for comparator choice. */
    def assertMatchesContract[C, P <: SchemaPolicy](
        df: DataFrame
    )(using sch: SparkSchema[C], pr: PolicyRuntime[P]): Unit =
      val expected = sch.struct
      val ok       = pr.ok(df.schema, expected)
      if !ok then throw mismatch(s"policy ${pr.getClass.getName}", df.schema, expected)

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

    /** Read a DF from a typed source, pin with the contract schema, validate at runtime. */
    def readDF[C](src: TypedSource[C])(using SparkSession, SparkSchema[C]): DataFrame =
      val spark  = summon[SparkSession]
      val schema = summon[SparkSchema[C]].struct
      val reader = src.options.foldLeft(spark.read.format(src.format)) { case (r, (k, v)) => r.option(k, v) }
      val df     = reader.schema(schema).load(src.path)
      SchemaCheck.assertMatchesContract[C](df) // defensive pin
      df

    /** Write a DF to a typed sink after a policy-aware defensive pin. */
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
