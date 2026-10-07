package ctdc

/**
 * Evidence that producer type `Out` conforms to contract type `Contract` under policy `P`.
 *
 * Materialized at compile time by a macro that compares normalized shapes of both sides and aborts
 * compilation with a path-aware drift report when they disagree. If the two do not conform, the code does not
 * compile.
 *
 * `P` is bounded so that a typo in the policy type is a bounds error at the call site, rather than a
 * derivation that quietly compares under default rules.
 *
 * No `@implicitNotFound` message here on purpose. Scala suppresses errors raised while searching for an
 * implicit, so when derivation aborts with a drift report the call site only sees "no instance found". A custom
 * message would replace the default one, which at least names the parameter and method that needed the
 * evidence.
 */
trait SchemaConforms[Out, Contract, P <: SchemaPolicy]

/**
 * The evidence itself has no members, so everything here is the materializer, and the materializer is the one
 * part of contract checking that cannot be written once: it is a macro, and the two Scala versions spell
 * macros differently. [[SchemaConformsMaterializer]] is supplied per version from its own source directory.
 * Both spellings end up calling the same comparison in `ctdc.internal.ShapeDiff`.
 */
object SchemaConforms extends SchemaConformsMaterializer
