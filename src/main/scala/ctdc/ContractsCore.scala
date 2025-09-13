package ctdc

import scala.annotation.implicitNotFound
import scala.quoted.*

/** Compile-time contracts (policies + derivation)
  *
  * The contract idea: for producer type `Out` and target contract `Contract`, we derive *compile-time evidence* that
  * `Out` conforms to `Contract` under a policy `P`.
  *
  * If it does not conform, your code DOES NOT COMPILE.
  *
  * At runtime we still perform a defensive pin (Spark-side) to guard drift that might creep in from external
  * files/sources (e.g., CSV/JSON).
  *
  * This file contains:
  *   - SchemaPolicy: what "conforms" means
  *   - A tiny internal structural model (TypeShape)
  *   - Macro derivation for SchemaConforms
  */

// 1> Policy: what counts as a "match"?
object ContractsCore:

  /** Policy controls how compile-time comparison is performed.
    *
    * Mapping to Spark's runtime comparators (for docs & intuition):
    *   - Exact, ExactUnorderedCI: unordered by name, case-insensitive, ignore nullability (≈
    *     DataType.equalsIgnoreCaseAndNullability) [Spark 3.5]
    */
  /**   - ExactByPosition : by position only (names ignored) (~ DataType.equalsStructurally) [Spark 3.5]
    *   - ExactOrdered : ordered by name, case-sensitive (~ equalsStructurallyByName, resolver ==)
    *   - ExactOrderedCI : ordered by name, case-insensitive (~ equalsStructurallyByName, resolver equalsIgnoreCase)
    *   - Backward : allow producer extras; missing contract fields allowed only if they are optional or have a default
    *     value (compile-time only)
    *   - Forward : producer must be a subset of contract (compile-time only)
    *   - Full : escape hatch; compile-time still runs, but accepts everything
    *
    * See Spark comparator docs:
    *   - equalsIgnoreCaseAndNullability (unordered, CI, ignore nullability)
    *   - equalsStructurally (by position)
    *   - equalsStructurallyByName (ordered by name, resolver provided)
    */
  enum SchemaPolicy:
    case Exact
    case ExactUnorderedCI
    case ExactOrdered
    case ExactOrderedCI
    case ExactByPosition
    case Backward
    case Forward
    case Full

  object SchemaPolicy:
    // Handy type aliases for short, singleton-style types at call sites
    type Exact            = SchemaPolicy.Exact.type
    type ExactUnorderedCI = SchemaPolicy.ExactUnorderedCI.type
    type ExactOrdered     = SchemaPolicy.ExactOrdered.type
    type ExactOrderedCI   = SchemaPolicy.ExactOrderedCI.type
    type ExactByPosition  = SchemaPolicy.ExactByPosition.type
    type Backward         = SchemaPolicy.Backward.type
    type Forward          = SchemaPolicy.Forward.type
    type Full             = SchemaPolicy.Full.type

  // 2> Internal deep shape (normalized)
  object Model:

    /** Normalized structural shape we compare at compile time. Keep this tiny and focused - it's an *internal*
      * representation.
      */
    sealed trait TypeShape
    final case class PrimitiveShape(name: String)                    extends TypeShape
    final case class SequenceShape(elem: TypeShape)                  extends TypeShape
    final case class MapShape(key: PrimitiveShape, value: TypeShape) extends TypeShape
    final case class FieldShape(name: String, shape: TypeShape, hasDefault: Boolean, isOptional: Boolean)
    final case class StructShape(fields: List[FieldShape]) extends TypeShape

  // 3> Compile-time evidence that Out conforms to Contract under P
  object CompileTime:

    /** If the compiler can’t summon this, we abort with a helpful message. */
    @implicitNotFound("""
        |Compile-time contract drift (policy: ${P})
        |Out: ${Out} vs Contract: ${Contract}
        |Missing: ${?m}
        |Extra: ${?e}
        |Mismatch: ${?x}
        |""".stripMargin)
    trait SchemaConforms[Out, Contract, P]

    object SchemaConforms:
      export CompileTimeInternal.SchemaConformsDerivation.given

    // Internals

    private object CompileTimeInternal:

      import ContractsCore.Model.*
      import scala.annotation.tailrec

      object SchemaConformsDerivation:

        /** Materialize evidence at the call site. If we can compute a consistent diff under policy P, we succeed.
          * otherwise we abort with a path-rich message.
          */
        inline given derived[Out, Contract, P <: SchemaPolicy]: SchemaConforms[Out, Contract, P] =
          ${ conformsImpl[Out, Contract, P] }

        // macro impl
        private def conformsImpl[Out: Type, Contract: Type, P: Type](using
            Quotes
        ): Expr[SchemaConforms[Out, Contract, P]] =
          import quotes.reflect.*

          // utilities over TypeRepr
          def isCaseClass(t: TypeRepr): Boolean =
            val s = t.typeSymbol
            s.isClassDef && s.flags.is(Flags.Case)

          def appliedArgs(t: TypeRepr): List[TypeRepr] = t match
            case AppliedType(_, args) => args
            case _                    => Nil

          def optionArg(t: TypeRepr): Option[TypeRepr] =
            if t <:< TypeRepr.of[Option[?]] then appliedArgs(t).headOption else None

          def seqArg(t: TypeRepr): Option[TypeRepr] =
            val isSeqLike =
              t <:< TypeRepr.of[List[?]] || t <:< TypeRepr.of[Seq[?]] ||
                t <:< TypeRepr.of[Vector[?]] || t <:< TypeRepr.of[Array[?]] ||
                t <:< TypeRepr.of[Set[?]]
            if isSeqLike then
              Some(
                appliedArgs(t).headOption.getOrElse(report.errorAndAbort(s"Missing type arg for sequence in ${t.show}"))
              )
            else None

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

          // compute deep normalized shape
          def typeShapeOf(t: TypeRepr): TypeShape =
            optionArg(t).map(typeShapeOf).getOrElse {
              seqArg(t).map(a => SequenceShape(typeShapeOf(a))).getOrElse {
                mapArgs(t)
                  .map { case (k, v) =>
                    if !isAtomicKey(k) then
                      report.errorAndAbort(
                        s"Unsupported Map key type for ${t.show}. Allowed keys: String, Int, Long, Short, Byte, Boolean."
                      )
                    MapShape(PrimitiveShape(k.show), typeShapeOf(v))
                  }
                  .getOrElse {
                    if isCaseClass(t) then
                      val sym    = t.typeSymbol
                      val params = sym.primaryConstructor.paramSymss.flatten
                      val fields = params.map { p =>
                        val name        = p.name
                        val ptpe        = t.memberType(p)
                        val hasDefault  = p.flags.is(Flags.HasDefault)
                        val (uT, isOpt) = optionArg(ptpe).fold(ptpe -> false)(a => a -> true)
                        FieldShape(name, typeShapeOf(uT), hasDefault, isOpt)
                      }
                      StructShape(fields)
                    else PrimitiveShape(t.show) // fall back to pretty name
                  }
              }
            }

          // tiny diff model
          final case class Missing(path: String, field: FieldShape)
          final case class Extra(path: String, name: String)
          final case class Mismatch(path: String, expected: String, found: String)

          // policy toggles
          val policyT            = TypeRepr.of[P]
          inline def is[T: Type] = policyT =:= TypeRepr.of[T]

          val caseInsensitive = is[SchemaPolicy.ExactUnorderedCI.type] || is[SchemaPolicy.ExactOrderedCI.type]
          val orderedByName   = is[SchemaPolicy.ExactOrdered.type] || is[SchemaPolicy.ExactOrderedCI.type]
          val byPosition      = is[SchemaPolicy.ExactByPosition.type]

          val norm: String => String = s => if caseInsensitive then s.toLowerCase else s

          // structural comparers (three modes)
          def compareByName(path: String, out: TypeShape, in: TypeShape): (List[Missing], List[Extra], List[Mismatch]) =
            (out, in) match
              case (PrimitiveShape(ao), PrimitiveShape(ai)) =>
                if ao == ai then (Nil, Nil, Nil) else (Nil, Nil, List(Mismatch(path, ai, ao)))

              case (SequenceShape(ao), SequenceShape(ai)) =>
                compareByName(s"$path[]", ao, ai)

              case (MapShape(ko, vo), MapShape(ki, vi)) =>
                val keyMismatch =
                  if (caseInsensitive && ko.name.equalsIgnoreCase(ki.name)) || (!caseInsensitive && ko.name == ki.name)
                  then Nil
                  else List(Mismatch(s"$path<key>", ki.name, ko.name))
                val (m, e, x) = compareByName(s"$path<value>", vo, vi)
                (m, e, keyMismatch ++ x)

              case (StructShape(outs), StructShape(ins)) =>
                val outMap = outs.map(f => norm(f.name) -> f).toMap
                val inMap  = ins.map(f => norm(f.name) -> f).toMap

                val missing = ins.collect {
                  case f if !outMap.contains(norm(f.name)) => Missing(pathOf(path, f.name), f)
                }
                val extra = outs.collect {
                  case f if !inMap.contains(norm(f.name)) => Extra(pathOf(path, f.name), f.name)
                }

                val nestedDiffs =
                  ins.flatMap { f =>
                    outMap.get(norm(f.name)).toList.flatMap { of =>
                      val (m, e, x) = compareByName(pathOf(path, f.name), of.shape, f.shape)
                      m ++ e ++ x
                    }
                  }

                foldDiffs(missing, extra, nestedDiffs)

              case (ao, ai) =>
                (Nil, Nil, List(Mismatch(path, ai.toString, ao.toString)))

          def compareOrdered(
              path: String,
              out: TypeShape,
              in: TypeShape
          ): (List[Missing], List[Extra], List[Mismatch]) =
            (out, in) match
              case (PrimitiveShape(ao), PrimitiveShape(ai)) =>
                if ao == ai then (Nil, Nil, Nil) else (Nil, Nil, List(Mismatch(path, ai, ao)))

              case (SequenceShape(ao), SequenceShape(ai)) =>
                compareOrdered(s"$path[]", ao, ai)

              case (MapShape(ko, vo), MapShape(ki, vi)) =>
                val keyMismatch =
                  val ok = if caseInsensitive then ko.name.equalsIgnoreCase(ki.name) else ko.name == ki.name
                  if ok then Nil else List(Mismatch(s"$path<key>", ki.name, ko.name))
                val (m, e, x) = compareOrdered(s"$path<value>", vo, vi)
                (m, e, keyMismatch ++ x)

              case (StructShape(outs), StructShape(ins)) =>
                val min = math.min(outs.length, ins.length)

                val (nameMismatches, nested) =
                  (0 until min).foldLeft(List.empty[Mismatch] -> List.empty[(TypeShape, TypeShape, String)]) {
                    case ((mm, ns), i) =>
                      val (of, inf) = (outs(i), ins(i))
                      val okName =
                        if caseInsensitive then of.name.equalsIgnoreCase(inf.name) else of.name == inf.name
                      val mm2 = if okName then mm else mm :+ Mismatch(s"$path.@$i(name)", inf.name, of.name)
                      mm2 -> (ns :+ (of.shape, inf.shape, pathOf(path, inf.name)))
                  }

                val nestedDiffs = nested.foldLeft((List.empty[Missing], List.empty[Extra], List.empty[Mismatch])) {
                  case ((am, ae, ax), (os, is, p)) =>
                    val (m, e, x) = compareOrdered(p, os, is); (am ++ m, ae ++ e, ax ++ x)
                }

                val tailMissing =
                  if ins.length > outs.length then ins.drop(min).map(f => Missing(pathOf(path, f.name), f)) else Nil
                val tailExtra =
                  if outs.length > ins.length then outs.drop(min).map(f => Extra(pathOf(path, f.name), f.name)) else Nil

                (nestedDiffs._1 ++ tailMissing, nestedDiffs._2 ++ tailExtra, nestedDiffs._3 ++ nameMismatches)

              case (ao, ai) =>
                (Nil, Nil, List(Mismatch(path, ai.toString, ao.toString)))

          def compareByPos(path: String, out: TypeShape, in: TypeShape): (List[Missing], List[Extra], List[Mismatch]) =
            (out, in) match
              case (PrimitiveShape(ao), PrimitiveShape(ai)) =>
                if ao == ai then (Nil, Nil, Nil) else (Nil, Nil, List(Mismatch(path, ai, ao)))

              case (SequenceShape(ao), SequenceShape(ai)) =>
                compareByPos(s"$path[]", ao, ai)

              case (MapShape(ko, vo), MapShape(ki, vi)) =>
                val keyMismatch = if ko.name == ki.name then Nil else List(Mismatch(s"$path<key>", ki.name, ko.name))
                val (m, e, x)   = compareByPos(s"$path<value>", vo, vi)
                (m, e, keyMismatch ++ x)

              case (StructShape(outs), StructShape(ins)) =>
                val min = math.min(outs.length, ins.length)

                val nested = (0 until min).toList.flatMap { i =>
                  val (of, inf) = (outs(i), ins(i))
                  val (m, e, x) = compareByPos(s"$path.@$i", of.shape, inf.shape)
                  m ++ e ++ x
                }

                val tailMissing =
                  if ins.length > outs.length then ins.drop(min).map(f => Missing(s"$path.@$min", f)) else Nil
                val tailExtra =
                  if outs.length > ins.length then outs.drop(min).map(f => Extra(s"$path.@$min", f.name)) else Nil

                foldDiffs(tailMissing, tailExtra, nested)

              case (ao, ai) =>
                (Nil, Nil, List(Mismatch(path, ai.toString, ao.toString)))

          // helpers for diff merging / path formatting
          inline def pathOf(base: String, seg: String): String = s"$base.$seg".stripPrefix(".")
          def foldDiffs(m0: List[Missing], e0: List[Extra], rest: List[Product]) =
            rest.foldLeft((m0, e0, List.empty[Mismatch])) { case ((am, ae, ax), d) =>
              d match
                case mm: Missing  => (am :+ mm, ae, ax)
                case ee: Extra    => (am, ae :+ ee, ax)
                case xx: Mismatch => (am, ae, ax :+ xx)
            }

          // run the chosen comparer
          val outShape = typeShapeOf(TypeRepr.of[Out])
          val inShape  = typeShapeOf(TypeRepr.of[Contract])

          val (miss0, extra0, mism0) =
            if byPosition then compareByPos("", outShape, inShape)
            else if orderedByName then compareOrdered("", outShape, inShape)
            else compareByName("", outShape, inShape)

          // post-filter diffs for Backward / Forward / Full
          val isBackward = is[SchemaPolicy.Backward.type]
          val isForward  = is[SchemaPolicy.Forward.type]
          val isFull     = is[SchemaPolicy.Full.type]

          val miss1 =
            if isBackward then miss0.filterNot(m => m.field.hasDefault || m.field.isOptional)
            else if isForward || isFull then Nil
            else miss0

          val extra1 =
            if isBackward || isFull then Nil
            else /* Forward + Exact */ extra0

          val mism1 = if isFull then Nil else mism0

          if miss1.nonEmpty || extra1.nonEmpty || mism1.nonEmpty then
            def renderField(f: FieldShape): String =
              val opt  = if f.isOptional then " (optional)" else ""
              val dflt = if f.hasDefault then " (default)" else ""
              s"${f.shape}$opt$dflt"

            val fmtMissing = miss1.map(m => s"${m.path} : ${renderField(m.field)}").mkString(", ")
            val fmtExtra   = extra1.map(e => s"${e.path}").mkString(", ")
            val fmtMis     = mism1.map(x => s"${x.path} expected ${x.expected}, found ${x.found}").mkString("; ")

            report.errorAndAbort(
              s"""Compile-time contract drift (policy: ${Type.show[P]}).
                 |Out: ${Type.show[Out]} vs Contract: ${Type.show[Contract]}
                 |Missing attributes: $fmtMissing
                 |Extra attributes: $fmtExtra
                 |Mismatch attributes: $fmtMis
                 |""".stripMargin
            )

          '{ new SchemaConforms[Out, Contract, P] {} }
