package ctdc.internal

import ctdc.SchemaPolicy
import ctdc.internal.TypeShape._

import scala.reflect.macros.blackbox

/**
 * Scala 2 half of compile-time contract validation.
 *
 * This only turns the two types into [[TypeShape]]s and hands them to [[ShapeDiff]], which owns the policy
 * rules and the error text. Keeping the comparison out of the macro is what lets Scala 3 reuse it and what
 * makes the rules testable without compiling anything. Shared rules remove one source of drift between the two
 * versions and not the other: the reflection that builds the [[TypeShape]] is this file's own, and a
 * difference there is a difference in what the version accepts. `SchemaConformsNegativeSpec` asserts the cases
 * where that went wrong.
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

    /**
     * A type that contains itself, rejected by name rather than walked.
     *
     * `TypeShape` is a finite tree with no node for a back edge, and neither is a Spark `StructType`, so
     * there is no shape a recursive type could be given here even if the walk were made to terminate.
     * Without this check the walk recursed until the compiler ran out of stack, which reports this macro's
     * own frames and never names the field that closed the loop.
     */
    def recursiveType(chain: List[Type]): Nothing =
      c.abort(
        c.enclosingPosition,
        s"Unsupported recursive type in SchemaConforms derivation: " +
          s"${chain.map(t => TypeShape.simpleName(t.toString)).mkString(" -> ")}. " +
          "A schema has a fixed depth, so a type that contains itself has no shape to compare.",
      )

    // TypeShape builder - pure functional approach
    object ShapeBuilder {

      /**
       * The shape of a type, with every `Option` it carries kept as an `OptionalShape` layer.
       *
       * Every layer is kept, including the outermost. A caller that has already consumed one layer - which
       * is what a field does, on `FieldShape.isOptional` - must pass what is left after consuming it and
       * not ask this function to drop a second one. That is the distinction between `Option[A]` and
       * `Option[Option[A]]` in a field, and dropping it here made the two conform.
       *
       * `enclosing` is the chain of case classes this call is already inside, outermost first. It exists only
       * to stop a cycle; see `recursiveType`.
       */
      def buildTypeShape(tpe: Type, enclosing: List[Type] = Nil): TypeShape = {
        import TypeInspector._

        optionArg(tpe).map(inner => TypeShape.OptionalShape(buildTypeShape(inner, enclosing))).getOrElse {
          seqArg(tpe).map(elem => SequenceShape(buildTypeShape(elem, enclosing))).getOrElse {
            mapArgs(tpe).map {
              case (k, v) =>
                if (!isAtomicKey(k)) {
                  c.abort(
                    c.enclosingPosition,
                    s"Unsupported Map key type: ${k.toString}. Allowed: String, Int, Long, Short, Byte, Boolean",
                  )
                }
                MapShape(PrimitiveShape(TypeShape.simpleName(k.toString)), buildTypeShape(v, enclosing))
            }.getOrElse {
              // Tuples are checked first because every TupleN is itself a case class. Reading one as a
              // struct of `_1`, `_2` would make positional junk look like a named schema.
              if (isTuple(tpe)) unsupportedTuple(tpe)
              else if (!isCaseClass(tpe)) opaqueLeaf(tpe)
              else if (enclosing.exists(_ =:= tpe)) recursiveType(enclosing :+ tpe)
              else buildStructShape(tpe, enclosing :+ tpe)
            }
          }
        }
      }

      private def buildStructShape(tpe: Type, enclosing: List[Type]): StructShape = {
        val sym    = tpe.typeSymbol
        val ctor   = sym.asClass.primaryConstructor
        val params = ctor.asMethod.paramLists.flatten

        val fields = params.map { param =>
          val name = param.name.toString
          // `infoIn(tpe)` rather than the member's own `returnType`, so that a type parameter is substituted
          // by the arguments the owner was applied with. The raw signature of `Box[T](x: T)`'s accessor is
          // `T`, which compares equal to the `T` of any other application, so `Box[Int]` conformed to
          // `Box[String]`. Scala 3's `memberType` already resolves in the owner, which is why only this half
          // was wrong.
          val paramType  = tpe.member(param.name).infoIn(tpe).resultType
          val hasDefault = param.asTerm.isParamWithDefault
          val (underlyingType, isOptional) =
            TypeInspector.optionArg(paramType).fold((paramType, false))(t => (t, true))
          // One layer of Option is consumed here, onto isOptional; whatever is left keeps its layers.
          FieldShape(name, buildTypeShape(underlyingType, enclosing), hasDefault, isOptional)
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

    // An abstract P matches nothing and is refused rather than compared under some default. See
    // `ComparisonRules.unresolvedPolicy` for why no default would be sound.
    val rules = known.collectFirst { case (t, policy) if weakTypeOf[P] <:< t => ComparisonRules.of(policy) }
      .getOrElse(c.abort(c.enclosingPosition, ComparisonRules.unresolvedPolicy(weakTypeOf[P].toString)))

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
