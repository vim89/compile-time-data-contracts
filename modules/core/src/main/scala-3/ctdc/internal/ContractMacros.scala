package ctdc.internal

import ctdc.{ SchemaConforms, SchemaPolicy }

import scala.quoted.*

/**
 * Scala 3 half of compile-time contract validation.
 *
 * This only turns the two types into [[TypeShape]]s and hands them to [[ShapeDiff]], which owns the policy
 * rules and the error text. The Scala 2 macro in `src/main/scala-2` does the same, so the two versions share
 * their decision rules, which removes one source of drift between them. It does not remove the other: each
 * version builds the [[TypeShape]] with its own reflection API, and a difference there is a difference in what
 * they accept. `SchemaConformsSpec` and `SchemaConformsNegativeSpec` assert the same cases on each front end
 * for that reason.
 */
object ContractMacros {

  def conformsImpl[Out: Type, Contract: Type, P <: SchemaPolicy: Type](
    using
    q: Quotes,
  ): Expr[SchemaConforms[Out, Contract, P]] = {
    import q.reflect.*

    ShapeDiff
      .report(
        policyName = TypeRepr.of[P].show,
        outName = TypeRepr.of[Out].show,
        contractName = TypeRepr.of[Contract].show,
        rules = rulesOf[P],
        out = TypeShapes.of(TypeRepr.of[Out]),
        contract = TypeShapes.of(TypeRepr.of[Contract]),
      )
      .foreach(message => report.errorAndAbort(message))

    '{ new SchemaConforms[Out, Contract, P] {} }
  }

  /**
   * The comparison rules the policy type `P` stands for.
   *
   * Matched by subtyping rather than by the rendered type name, so that a policy named as the trait
   * (`SchemaPolicy.Backward`) and the same policy named as the case object (`SchemaPolicy.Backward.type`)
   * resolve to the same rules. The policy traits are disjoint, so at most one branch can match.
   *
   * An abstract `P` matches nothing and is refused rather than compared under some default. There is no
   * default that would be sound: the policies are not ordered by strictness, so no single relation implies
   * all of them. `ExactOrdered` rejects a field permutation that every by-name policy accepts, while
   * `ExactUnorderedCI` accepts a case change that every case-sensitive policy rejects, so a relation strict
   * enough to stand in for one is wrong for the other. Generic code therefore has to take the evidence as a
   * parameter and let the call site that knows the policy derive it.
   */
  private def rulesOf[P <: SchemaPolicy: Type](using q: Quotes): ComparisonRules = {
    import q.reflect.*
    val requested = TypeRepr.of[P]

    val known: List[(TypeRepr, SchemaPolicy)] = List(
      TypeRepr.of[SchemaPolicy.Exact]            -> SchemaPolicy.Exact,
      TypeRepr.of[SchemaPolicy.ExactUnordered]   -> SchemaPolicy.ExactUnordered,
      TypeRepr.of[SchemaPolicy.ExactUnorderedCI] -> SchemaPolicy.ExactUnorderedCI,
      TypeRepr.of[SchemaPolicy.ExactOrdered]     -> SchemaPolicy.ExactOrdered,
      TypeRepr.of[SchemaPolicy.ExactOrderedCI]   -> SchemaPolicy.ExactOrderedCI,
      TypeRepr.of[SchemaPolicy.ExactByPosition]  -> SchemaPolicy.ExactByPosition,
      TypeRepr.of[SchemaPolicy.Backward]         -> SchemaPolicy.Backward,
      TypeRepr.of[SchemaPolicy.Forward]          -> SchemaPolicy.Forward,
      TypeRepr.of[SchemaPolicy.Full]             -> SchemaPolicy.Full,
    )

    known.collectFirst { case (tpe, policy) if requested <:< tpe => ComparisonRules.of(policy) }
      .getOrElse(report.errorAndAbort(ComparisonRules.unresolvedPolicy(requested.show)))
  }
}
