package ctdc

import ctdc.ContractsCore.SchemaPolicy
import org.apache.spark.sql.{DataFrame, Dataset, Encoder, SaveMode, SparkSession}
import org.apache.spark.sql.types.*

/** Spark side of the house (POC)
  *   - Derive StructType from a Scala product type (case class) at compile time
  *   - Provide runtime pins that mirror SchemaPolicy semantics via Spark comparators
  *   - Offer a tiny typed IO and a phantom-typed pipeline builder for demos
  *
  * Spark comparator references (3.5.x):
  *   - equalsIgnoreCaseAndNullability (unordered by name, CI, ignore nullability)
  *   - equalsStructurally (by position, names ignored)
  *   - equalsStructurallyByName (ordered by name with a name resolver)
  */
object SparkCore:

  // 1> Typed endpoints

  final case class TypedSource[C](format: String, path: String, options: Map[String, String] = Map.empty)
  final case class TypedSink[C](
      path: String,
      mode: SaveMode = SaveMode.Overwrite,
      options: Map[String, String] = Map.empty
  )

  // 2> PolicyRuntime: pick Spark comparator by policy
  trait PolicyRuntime[P <: SchemaPolicy]:
    def ok(found: StructType, expected: StructType): Boolean

  object PolicyRuntime:
    // unordered, case-insensitive, ignore nullability
    given PolicyRuntime[SchemaPolicy.Exact.type] with
      def ok(found: StructType, expected: StructType) =
        DataType.equalsIgnoreCaseAndNullability(found, expected)

    given PolicyRuntime[SchemaPolicy.ExactUnorderedCI.type] with
      def ok(found: StructType, expected: StructType) =
        DataType.equalsIgnoreCaseAndNullability(found, expected)

    // ordered by name
    given PolicyRuntime[SchemaPolicy.ExactOrdered.type] with
      def ok(found: StructType, expected: StructType) =
        DataType.equalsStructurallyByName(found, expected, _ == _)

    // ordered by name (case-insensitive resolver)
    given PolicyRuntime[SchemaPolicy.ExactOrderedCI.type] with
      def ok(found: StructType, expected: StructType) =
        DataType.equalsStructurallyByName(found, expected, _.equalsIgnoreCase(_))

    // by position only (names ignored)
    given PolicyRuntime[SchemaPolicy.ExactByPosition.type] with
      def ok(found: StructType, expected: StructType) =
        DataType.equalsStructurally(found, expected, /*ignoreNullability*/ true)

    // Backward/Forward rely on compile-time subset/extras rules;
    // at runtime we still use a tolerant comparator to catch obvious shape drift.
    given PolicyRuntime[SchemaPolicy.Backward.type] with
      def ok(found: StructType, expected: StructType) =
        DataType.equalsIgnoreCaseAndNullability(found, expected)

    given PolicyRuntime[SchemaPolicy.Forward.type] with
      def ok(found: StructType, expected: StructType) =
        DataType.equalsIgnoreCaseAndNullability(found, expected)

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
        else '{ StringType }                                                                    // POC fallback

      def dtOf(t: TypeRepr): Expr[DataType] =
        optionArg(t).map(dtOf).getOrElse {
          if isSeqLike(t) then
            val elem =
              appliedArgs(t).headOption.getOrElse(report.errorAndAbort(s"Missing type arg for sequence in ${t.show}"))
            '{ ArrayType(${ dtOf(elem) }, containsNull = true) }
          else
            mapArgs(t)
              .map { case (k, v) =>
                if !isAtomicKey(k) then
                  report.errorAndAbort(
                    s"Unsupported Map key type for ${t.show}. Allowed keys: String, Int, Long, Short, Byte, Boolean."
                  )
                '{ MapType(${ primitiveDt(k) }, ${ dtOf(v) }, valueContainsNull = true) }
              }
              .getOrElse {
                if t.typeSymbol.flags.is(Flags.Case) then structOf(t)
                else primitiveDt(t)
              }
        }

      def structOf(tc: TypeRepr): Expr[DataType] =
        val params = tc.typeSymbol.primaryConstructor.paramSymss.flatten
        val fieldExprs: List[Expr[StructField]] = params.map { p =>
          val name       = p.name
          val ptpe       = tc.memberType(p)
          val (u, isOpt) = optionArg(ptpe).fold(ptpe -> false)(a => a -> true)
          val dt         = dtOf(u)
          '{ StructField(${ Expr(name) }, $dt, ${ Expr(isOpt) }) }
        }
        '{ StructType(${ Expr.ofList(fieldExprs) }) }

      val structExpr: Expr[StructType] = structOf(tpe).asExprOf[StructType]

      '{
        new SparkSchema[C]:
          def struct: StructType = $structExpr
      }

  // 4> Runtime pins
  object SchemaCheck:

    /** Default pin: unordered, case-insensitive, ignore nullability. */
    def assertMatchesContract[C](df: DataFrame)(using sch: SparkSchema[C]): Unit =
      val expected = sch.struct
      val ok       = DataType.equalsIgnoreCaseAndNullability(df.schema, expected)
      if !ok then throw mismatch("contract", df.schema.treeString, expected.treeString)

    /** Policy-aware pin using PolicyRuntime[P] for comparator choice. */
    def assertMatchesContract[C, P <: SchemaPolicy](
        df: DataFrame
    )(using sch: SparkSchema[C], pr: PolicyRuntime[P]): Unit =
      val expected = sch.struct
      val ok       = pr.ok(df.schema, expected)
      if !ok then throw mismatch(s"policy ${pr.getClass.getName}", df.schema.treeString, expected.treeString)

    private def mismatch(what: String, found: String, expected: String) =
      new IllegalArgumentException(
        s"""Runtime schema mismatch against $what.
           |Found:
           |$found
           |Expected:
           |$expected
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

    /** Write a DF to a typed sink after defensive pin (default comparator). */
    def writeDF[C](df: DataFrame, sink: TypedSink[C])(using SparkSchema[C]): Unit =
      SchemaCheck.assertMatchesContract[C](df)
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

    def addSource[C](src: TypedSource[C])(using SparkSchema[C]): PipelineBuilder[WithSource, C] =
      val step = PipelineStep.Source { spark =>
        given SparkSession = spark
        TypedIO.readDF(src)
      }
      PipelineBuilder[WithSource, C](name, steps :+ step)

    def transformAs[Next](desc: String = "")(f: DataFrame => DataFrame)(using
        ev: S <:< WithSource,
        sch: SparkSchema[Next]
    ): PipelineBuilder[WithTransform, Next] =
      val step = PipelineStep.Transform { df =>
        val out = f(df)
        // Mid-pipeline defensive pin (default comparator).
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
        SchemaCheck.assertMatchesContract[R, P](df)
        TypedIO.writeDF(df, sink)
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
