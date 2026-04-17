package ctdc

import ctdc.ContractsCore.SchemaPolicy
import org.apache.spark.sql.{DataFrame, Dataset, Encoder, SaveMode, SparkSession}
import org.apache.spark.sql.types.*
import java.util.Locale

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

  private object RuntimeSchemaComparator:
    enum StructMode:
      case UnorderedByName(caseInsensitive: Boolean)
      case OrderedByName(caseInsensitive: Boolean)
      case BackwardByName(caseInsensitive: Boolean)
      case ForwardByName(caseInsensitive: Boolean)
      case ByPosition

    def unordered(found: StructType, expected: StructType, caseInsensitive: Boolean): Boolean =
      matches(found, expected, StructMode.UnorderedByName(caseInsensitive))

    def ordered(found: StructType, expected: StructType, caseInsensitive: Boolean): Boolean =
      matches(found, expected, StructMode.OrderedByName(caseInsensitive))

    def backward(found: StructType, expected: StructType, caseInsensitive: Boolean): Boolean =
      matches(found, expected, StructMode.BackwardByName(caseInsensitive))

    def forward(found: StructType, expected: StructType, caseInsensitive: Boolean): Boolean =
      matches(found, expected, StructMode.ForwardByName(caseInsensitive))

    def byPosition(found: StructType, expected: StructType): Boolean =
      matches(found, expected, StructMode.ByPosition)

    private def matches(found: StructType, expected: StructType, mode: StructMode): Boolean =
      compareStruct(found, expected, mode)

    private def normalize(name: String, caseInsensitive: Boolean): String =
      if caseInsensitive then name.toLowerCase(Locale.ROOT) else name

    def duplicateNames(struct: StructType, caseInsensitive: Boolean): List[List[String]] =
      struct.fields
        .groupBy(field => normalize(field.name, caseInsensitive))
        .values
        .collect { case fields if fields.length > 1 => fields.toList.map(_.name).sorted }
        .toList

    private def hasDefault(field: StructField): Boolean =
      field.metadata.contains(HasDefaultMetadataKey) && field.metadata.getBoolean(HasDefaultMetadataKey)

    private def missingAllowed(field: StructField): Boolean =
      field.nullable || hasDefault(field)

    private def uniqueFieldsByName(struct: StructType, caseInsensitive: Boolean): Option[Map[String, StructField]] =
      val duplicates = duplicateNames(struct, caseInsensitive)
      if duplicates.isEmpty then
        val grouped = struct.fields.groupBy(field => normalize(field.name, caseInsensitive))
        Some(grouped.view.mapValues(_.head).toMap)
      else None

    private def compareStruct(found: StructType, expected: StructType, mode: StructMode): Boolean =
      mode match
        case StructMode.ByPosition =>
          found.fields.length == expected.fields.length &&
            found.fields.lazyZip(expected.fields).forall(compareFieldByPosition(_, _, mode))

        case StructMode.OrderedByName(caseInsensitive) =>
          found.fields.length == expected.fields.length &&
            found.fields.lazyZip(expected.fields).forall { (left, right) =>
              normalize(left.name, caseInsensitive) == normalize(right.name, caseInsensitive) &&
              compareDataType(left.dataType, right.dataType, mode)
            }

        case StructMode.UnorderedByName(caseInsensitive) =>
          uniqueFieldsByName(found, caseInsensitive)
            .zip(uniqueFieldsByName(expected, caseInsensitive))
            .exists { case (foundByName, expectedByName) =>
              foundByName.keySet == expectedByName.keySet &&
              expectedByName.forall { case (name, expectedField) =>
                foundByName.get(name).exists(foundField =>
                  compareDataType(foundField.dataType, expectedField.dataType, mode)
                )
              }
            }

        case StructMode.BackwardByName(caseInsensitive) =>
          uniqueFieldsByName(found, caseInsensitive)
            .zip(uniqueFieldsByName(expected, caseInsensitive))
            .exists { case (foundByName, expectedByName) =>
              expectedByName.forall { case (name, expectedField) =>
                foundByName.get(name) match
                  case Some(foundField) =>
                    compareDataType(foundField.dataType, expectedField.dataType, mode)
                  case None =>
                    missingAllowed(expectedField)
              }
            }

        case StructMode.ForwardByName(caseInsensitive) =>
          uniqueFieldsByName(found, caseInsensitive)
            .zip(uniqueFieldsByName(expected, caseInsensitive))
            .exists { case (foundByName, expectedByName) =>
              foundByName.forall { case (name, foundField) =>
                expectedByName.get(name).exists(expectedField =>
                  compareDataType(foundField.dataType, expectedField.dataType, mode)
                )
              }
            }

    private def compareFieldByPosition(found: StructField, expected: StructField, mode: StructMode): Boolean =
      compareDataType(found.dataType, expected.dataType, mode)

    private def compareDataType(found: DataType, expected: DataType, mode: StructMode): Boolean =
      (found, expected) match
        case (left: StructType, right: StructType) =>
          compareStruct(left, right, mode)

        case (ArrayType(leftElem, leftContainsNull), ArrayType(rightElem, rightContainsNull)) =>
          leftContainsNull == rightContainsNull &&
            compareDataType(leftElem, rightElem, mode)

        case (MapType(leftKey, leftValue, leftValueContainsNull), MapType(rightKey, rightValue, rightValueContainsNull)) =>
          leftValueContainsNull == rightValueContainsNull &&
            compareDataType(leftKey, rightKey, mode) &&
            compareDataType(leftValue, rightValue, mode)

        case _ =>
          found == expected

  // 2> PolicyRuntime: pick Spark comparator by policy
  trait PolicyRuntime[P <: SchemaPolicy]:
    def ok(found: StructType, expected: StructType): Boolean

  object PolicyRuntime:
    // unordered, case-insensitive, ignore field nullability; preserve nested collection optionality
    given PolicyRuntime[SchemaPolicy.Exact.type] with
      def ok(found: StructType, expected: StructType) =
        RuntimeSchemaComparator.unordered(found, expected, caseInsensitive = true)

    given PolicyRuntime[SchemaPolicy.ExactUnorderedCI.type] with
      def ok(found: StructType, expected: StructType) =
        RuntimeSchemaComparator.unordered(found, expected, caseInsensitive = true)

    // ordered by name, ignore field nullability; preserve nested collection optionality
    given PolicyRuntime[SchemaPolicy.ExactOrdered.type] with
      def ok(found: StructType, expected: StructType) =
        RuntimeSchemaComparator.ordered(found, expected, caseInsensitive = false)

    // ordered by name (case-insensitive resolver), ignore field nullability; preserve nested collection optionality
    given PolicyRuntime[SchemaPolicy.ExactOrderedCI.type] with
      def ok(found: StructType, expected: StructType) =
        RuntimeSchemaComparator.ordered(found, expected, caseInsensitive = true)

    // by position only (names ignored), ignore field nullability; preserve nested collection optionality
    given PolicyRuntime[SchemaPolicy.ExactByPosition.type] with
      def ok(found: StructType, expected: StructType) =
        RuntimeSchemaComparator.byPosition(found, expected)

    // Backward/Forward use the same subset direction at runtime, while still preserving
    // the custom nested collection optionality checks that Spark ignores.
    given PolicyRuntime[SchemaPolicy.Backward.type] with
      def ok(found: StructType, expected: StructType) =
        RuntimeSchemaComparator.backward(found, expected, caseInsensitive = false)

    given PolicyRuntime[SchemaPolicy.Forward.type] with
      def ok(found: StructType, expected: StructType) =
        RuntimeSchemaComparator.forward(found, expected, caseInsensitive = false)

    given PolicyRuntime[SchemaPolicy.Full.type] with
      def ok(found: StructType, expected: StructType) = true

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

    /** Default pin: unordered, case-insensitive, ignore field nullability, preserve nested collection optionality. */
    def assertMatchesContract[C](df: DataFrame)(using sch: SparkSchema[C]): Unit =
      val expected = sch.struct
      val ok       = RuntimeSchemaComparator.unordered(df.schema, expected, caseInsensitive = true)
      if !ok then throw mismatch("contract", df.schema, expected)

    /** Policy-aware pin using PolicyRuntime[P] for comparator choice. */
    def assertMatchesContract[C, P <: SchemaPolicy](
        df: DataFrame
    )(using sch: SparkSchema[C], pr: PolicyRuntime[P]): Unit =
      val expected = sch.struct
      val ok       = pr.ok(df.schema, expected)
      if !ok then throw mismatch(s"policy ${pr.getClass.getName}", df.schema, expected)

    private def duplicateDetail(label: String, schema: StructType): Option[String] =
      val duplicates = RuntimeSchemaComparator.duplicateNames(schema, caseInsensitive = true)
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

  import ContractsCore.CompileTime.SchemaConforms
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
