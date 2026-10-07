package ctdc.internal

import ctdc.SchemaPolicy
import ctdc.internal.TypeShape._

import scala.reflect.macros.blackbox

/**
 * Scala 2 half of compile-time contract validation.
 *
 * This only turns the two types into [[TypeShape]]s and hands them to [[ShapeDiff]], which owns the policy
 * rules and the error text. Keeping the comparison out of the macro is what lets Scala 3 reuse it and what
 * makes the rules testable without compiling anything.
 */
object ContractMacros {

  def conformsImpl[Out: c.WeakTypeTag, Contract: c.WeakTypeTag, P <: SchemaPolicy: c.WeakTypeTag](
    c: blackbox.Context,
  ): c.Tree = {
    import c.universe._

    // Type inspection utilities
    object TypeInspector {
      def isCaseClass(t: Type): Boolean = {
        val sym = t.typeSymbol
        sym.isClass && sym.asClass.isCaseClass
      }

      def appliedArgs(t: Type): List[Type] = t match {
        case TypeRef(_, _, args) => args
        case _                   => Nil
      }

      def optionArg(t: Type): Option[Type] =
        if (t <:< typeOf[Option[_]]) appliedArgs(t).headOption
        else None

      def seqArg(t: Type): Option[Type] = {
        val isSeqLike = t <:< typeOf[List[_]] || t <:< typeOf[Seq[_]] ||
          t <:< typeOf[Vector[_]] || t <:< typeOf[Array[_]] ||
          t <:< typeOf[Set[_]]
        if (isSeqLike) appliedArgs(t).headOption
        else None
      }

      def mapArgs(t: Type): Option[(Type, Type)] =
        if (t <:< typeOf[Map[_, _]]) {
          appliedArgs(t) match {
            case k :: v :: Nil => Some((k, v))
            case _             => None
          }
        } else None

      def isAtomicKey(t: Type): Boolean =
        t =:= typeOf[String] || t =:= typeOf[Int] || t =:= typeOf[Long] ||
          t =:= typeOf[Short] || t =:= typeOf[Byte] || t =:= typeOf[Boolean]

      def isTuple(t: Type): Boolean =
        t.typeSymbol.fullName.startsWith("scala.Tuple")
    }

    /**
     * A type this macro cannot look inside, compared by its name.
     *
     * There used to be a closed list of leaf types here, on the reasoning that a type outside it is one no
     * sink can write. That is not something a contract can know: a sink takes the writer as a function, so
     * whether a `UUID` or a domain enum can be written is decided by the writer the caller supplies, not by
     * this list. Rejecting an unlisted leaf therefore turned away pipelines that were fine. Comparing it by
     * name still catches the drift that is this macro's job, because a leaf that changes type changes its
     * name.
     */
    def opaqueLeaf(t: Type): PrimitiveShape = PrimitiveShape(TypeShape.simpleName(t.toString))

    def unsupportedTuple(t: Type): Nothing =
      c.abort(
        c.enclosingPosition,
        s"Unsupported tuple in SchemaConforms derivation: ${t.toString}. " +
          "A tuple has no field names to compare, so use a case class instead.",
      )

    // TypeShape builder - pure functional approach
    object ShapeBuilder {
      def buildTypeShape(tpe: Type, inField: Boolean = false): TypeShape = {
        import TypeInspector._

        optionArg(tpe).map { inner =>
          // Field-level Option is captured on FieldShape.isOptional; avoid double-wrapping there.
          // Outside of field context (e.g., List[Option[A]]), preserve optionality as OptionalShape.
          if (inField) buildTypeShape(inner, inField = false)
          else TypeShape.OptionalShape(buildTypeShape(inner, inField = false))
        }.getOrElse {
          seqArg(tpe).map(elem => SequenceShape(buildTypeShape(elem))).getOrElse {
            mapArgs(tpe).map {
              case (k, v) =>
                if (!isAtomicKey(k)) {
                  c.abort(
                    c.enclosingPosition,
                    s"Unsupported Map key type: ${k.toString}. Allowed: String, Int, Long, Short, Byte, Boolean",
                  )
                }
                MapShape(PrimitiveShape(TypeShape.simpleName(k.toString)), buildTypeShape(v))
            }.getOrElse {
              // Tuples are checked first because every TupleN is itself a case class. Reading one as a
              // struct of `_1`, `_2` would make positional junk look like a named schema.
              if (isTuple(tpe)) unsupportedTuple(tpe)
              else if (isCaseClass(tpe)) buildStructShape(tpe)
              else opaqueLeaf(tpe)
            }
          }
        }
      }

      private def buildStructShape(tpe: Type): StructShape = {
        val sym    = tpe.typeSymbol
        val ctor   = sym.asClass.primaryConstructor
        val params = ctor.asMethod.paramLists.flatten

        val fields = params.map { param =>
          val name       = param.name.toString
          val paramType  = tpe.member(param.name).asMethod.returnType
          val hasDefault = param.asTerm.isParamWithDefault
          val (underlyingType, isOptional) =
            TypeInspector.optionArg(paramType).fold((paramType, false))(t => (t, true))
          // For field-level shape, pass inField = true so Option is carried via isOptional flag
          FieldShape(name, buildTypeShape(underlyingType, inField = true), hasDefault, isOptional)
        }

        StructShape(fields)
      }
    }

    // Matched by subtyping rather than by the rendered type name, so that a policy named as the trait
    // (SchemaPolicy.Backward) and the same policy named as the case object (SchemaPolicy.Backward.type)
    // resolve to the same rules. The policy traits are disjoint, so at most one entry can match.
    val known: List[(Type, SchemaPolicy)] = List(
      typeOf[SchemaPolicy.Exact]            -> SchemaPolicy.Exact,
      typeOf[SchemaPolicy.ExactUnordered]   -> SchemaPolicy.ExactUnordered,
      typeOf[SchemaPolicy.ExactUnorderedCI] -> SchemaPolicy.ExactUnorderedCI,
      typeOf[SchemaPolicy.ExactOrdered]     -> SchemaPolicy.ExactOrdered,
      typeOf[SchemaPolicy.ExactOrderedCI]   -> SchemaPolicy.ExactOrderedCI,
      typeOf[SchemaPolicy.ExactByPosition]  -> SchemaPolicy.ExactByPosition,
      typeOf[SchemaPolicy.Backward]         -> SchemaPolicy.Backward,
      typeOf[SchemaPolicy.Forward]          -> SchemaPolicy.Forward,
      typeOf[SchemaPolicy.Full]             -> SchemaPolicy.Full,
    )

    val rules = known.collectFirst { case (t, policy) if weakTypeOf[P] <:< t => ComparisonRules.of(policy) }
      .getOrElse(ComparisonRules.strictest)

    ShapeDiff
      .report(
        policyName = weakTypeOf[P].toString,
        outName = weakTypeOf[Out].toString,
        contractName = weakTypeOf[Contract].toString,
        rules = rules,
        out = ShapeBuilder.buildTypeShape(weakTypeOf[Out]),
        contract = ShapeBuilder.buildTypeShape(weakTypeOf[Contract]),
      )
      .foreach(message => c.abort(c.enclosingPosition, message))

    q"new _root_.ctdc.SchemaConforms[${weakTypeOf[Out]}, ${weakTypeOf[Contract]}, ${weakTypeOf[P]}] {}"
  }
}
