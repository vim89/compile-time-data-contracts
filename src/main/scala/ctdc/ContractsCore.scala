package ctdc

import ctdc.internal.{ PolicyTypes, ShapeDiff, TypeShapes }

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
  * This file contains the user-facing surface only:
  *   - SchemaPolicy: what "conforms" means
  *   - SchemaConforms: the evidence, and the macro that materializes it
  *
  * The structural model and the comparison itself live in [[ctdc.internal]], where they can be tested without
  * invoking the compiler.
  */

// 1> Policy: what counts as a "match"?
object ContractsCore:

  /** Policy controls how compile-time comparison is performed.
    *
    * Mapping to Spark's runtime comparators (for docs & intuition):
    *   - Exact, ExactUnorderedCI: unordered by name, case-insensitive, ignore nullability (≈
    *     DataType.equalsIgnoreCaseAndNullability) [Spark 3.5]
    *   - ExactUnordered : unordered by name, case-sensitive; `Exact` without the case-insensitivity
    *   - ExactByPosition : by position only (names ignored) (~ DataType.equalsStructurally) [Spark 3.5]
    *   - ExactOrdered : ordered by name, case-sensitive (~ equalsStructurallyByName, resolver ==)
    *   - ExactOrderedCI : ordered by name, case-insensitive (~ equalsStructurallyByName, resolver equalsIgnoreCase)
    *   - Backward : allow producer extras; missing contract fields allowed only if they are optional or have a default
    *     value
    *   - Forward : producer must be a subset of contract
    *   - Full : escape hatch; compile-time still runs, but accepts everything
    *
    * See Spark comparator docs:
    *   - equalsIgnoreCaseAndNullability (unordered, CI, ignore nullability)
    *   - equalsStructurally (by position)
    *   - equalsStructurallyByName (ordered by name, resolver provided)
    */
  enum SchemaPolicy:
    case Exact
    case ExactUnordered
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
    type ExactUnordered   = SchemaPolicy.ExactUnordered.type
    type ExactUnorderedCI = SchemaPolicy.ExactUnorderedCI.type
    type ExactOrdered     = SchemaPolicy.ExactOrdered.type
    type ExactOrderedCI   = SchemaPolicy.ExactOrderedCI.type
    type ExactByPosition  = SchemaPolicy.ExactByPosition.type
    type Backward         = SchemaPolicy.Backward.type
    type Forward          = SchemaPolicy.Forward.type
    type Full             = SchemaPolicy.Full.type

  // 2> Compile-time evidence that Out conforms to Contract under P
  object CompileTime:

    /** Evidence that `Out` conforms to `Contract` under policy `P`.
      *
      * `P` is bounded so that a typo in the policy type is a bounds error at the call site, rather than a derivation
      * that quietly compares under default rules.
      *
      * No `@implicitNotFound` message here on purpose. Scala suppresses errors raised while searching for a given, so
      * when derivation aborts with a drift report the call site only sees "no given instance found". A custom message
      * would replace the default one, which at least names the parameter and method that needed the evidence.
      */
    trait SchemaConforms[Out, Contract, P <: SchemaPolicy]

    object SchemaConforms:
      export CompileTimeInternal.SchemaConformsDerivation.given

    /** Ergonomic inline helper to summon evidence explicitly in user code.
      *   import ctdc.ContractsCore.CompileTime.conforms
      *   val ev = conforms[Out, Contract, SchemaPolicy.Exact]
      */
    inline def conforms[Out, Contract, P <: SchemaPolicy](using
        SchemaConforms[Out, Contract, P]
    ): SchemaConforms[Out, Contract, P] = summon[SchemaConforms[Out, Contract, P]]

    // Internals

    private object CompileTimeInternal:

      object SchemaConformsDerivation:

        /** Materialize evidence at the call site. If we can compute a consistent diff under policy P, we succeed.
          * otherwise we abort with a path-rich message.
          */
        inline given derived[Out, Contract, P <: SchemaPolicy]: SchemaConforms[Out, Contract, P] =
          ${ conformsImpl[Out, Contract, P] }

        /** The whole macro: reflect both types into shapes, compare, and turn a report into a compile error.
          *
          * Deliberately thin. Everything that decides an outcome is a pure function underneath it, so the only thing
          * that needs a compiler to test is the reflection in [[TypeShapes]].
          */
        private def conformsImpl[Out: Type, Contract: Type, P <: SchemaPolicy: Type](using
            Quotes
        ): Expr[SchemaConforms[Out, Contract, P]] =
          import quotes.reflect.*

          ShapeDiff
            .report(
              policyName = Type.show[P],
              outName = Type.show[Out],
              contractName = Type.show[Contract],
              rules = PolicyTypes.rulesOf[P],
              out = TypeShapes.of(TypeRepr.of[Out]),
              contract = TypeShapes.of(TypeRepr.of[Contract])
            )
            .foreach(message => report.errorAndAbort(message))

          '{ new SchemaConforms[Out, Contract, P] {} }
