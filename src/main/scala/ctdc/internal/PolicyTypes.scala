package ctdc.internal

import ctdc.ContractsCore.SchemaPolicy

import scala.quoted.*

/** Resolution of the policy *type* parameter of a contract to the rules it stands for.
  *
  * A policy reaches the macro as a singleton type, not as a value, so it has to be matched on the type level. The
  * table is keyed by `TypeRepr` rather than by a rendered type name: a name can be printed in more than one form
  * (`SchemaPolicy.Exact`, `SchemaPolicy.Exact.type`), and a lookup that misses would silently pick the wrong policy
  * instead of failing.
  */
private[ctdc] object PolicyTypes:

  /** The comparison rules for policy type `P`.
    *
    * Falls back to [[ComparisonRules.strictest]] when `P` is abstract, which can only happen in generic code that has
    * not fixed its policy yet.
    */
  def rulesOf[P: Type](using q: Quotes): ComparisonRules =
    import q.reflect.*
    val requested = TypeRepr.of[P]

    val known: List[(TypeRepr, SchemaPolicy)] = List(
      TypeRepr.of[SchemaPolicy.Exact.type]            -> SchemaPolicy.Exact,
      TypeRepr.of[SchemaPolicy.ExactUnordered.type]   -> SchemaPolicy.ExactUnordered,
      TypeRepr.of[SchemaPolicy.ExactUnorderedCI.type] -> SchemaPolicy.ExactUnorderedCI,
      TypeRepr.of[SchemaPolicy.ExactOrdered.type]     -> SchemaPolicy.ExactOrdered,
      TypeRepr.of[SchemaPolicy.ExactOrderedCI.type]   -> SchemaPolicy.ExactOrderedCI,
      TypeRepr.of[SchemaPolicy.ExactByPosition.type]  -> SchemaPolicy.ExactByPosition,
      TypeRepr.of[SchemaPolicy.Backward.type]         -> SchemaPolicy.Backward,
      TypeRepr.of[SchemaPolicy.Forward.type]          -> SchemaPolicy.Forward,
      TypeRepr.of[SchemaPolicy.Full.type]             -> SchemaPolicy.Full
    )

    known
      .collectFirst { case (tpe, policy) if requested =:= tpe => ComparisonRules.of(policy) }
      .getOrElse(ComparisonRules.strictest)
