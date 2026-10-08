package ctdc

/**
 * Schema comparison policies for compile-time contract validation.
 *
 * Clean enum-based design. Each policy defines how two schemas should be compared for conformance at compile
 * time.
 *
 * Policy behavior:
 *   - [[SchemaPolicy.Exact]]: Perfect match (unordered, case-sensitive)
 *   - [[SchemaPolicy.ExactUnordered]]: Back-compat alias for [[SchemaPolicy.Exact]]
 *   - [[SchemaPolicy.ExactUnorderedCI]]: Perfect match, case-insensitive names, unordered
 *   - [[SchemaPolicy.ExactOrdered]]: Perfect match with enforced field order
 *   - [[SchemaPolicy.ExactOrderedCI]]: Perfect match, case-insensitive + ordered
 *   - [[SchemaPolicy.ExactByPosition]]: Match by position only, names ignored
 *   - [[SchemaPolicy.Backward]]: Producer can have extras; missing allowed if optional/default
 *   - [[SchemaPolicy.Forward]]: Producer subset of contract; missing contract fields allowed
 *   - [[SchemaPolicy.Unchecked]]: Escape hatch: compares nothing, so everything conforms
 *
 * The escape hatch is named `Unchecked` and not `Full`, which is what it was called in `0.1.0`. Confluent
 * Schema Registry's `FULL` means compatible in both directions at once, which is the strictest of its
 * settings; this one is the absence of a comparison. Two names that read alike and mean opposite things is
 * the kind of thing a boundary policy cannot afford, so the name now says what the policy does.
 */
sealed trait SchemaPolicy

/**
 * Each policy appears twice: as a sealed trait the macros match on at the type level, and as a case object
 * for code that passes a policy as a value. The two are linked by the case object extending the trait of the
 * same name, so a policy named in either position means the same thing.
 */
object SchemaPolicy {
  // Sealed traits for type-level usage in macros
  sealed trait Exact extends SchemaPolicy
  // Back-compat: unordered, case-sensitive equality of fields
  sealed trait ExactUnordered   extends SchemaPolicy
  sealed trait ExactUnorderedCI extends SchemaPolicy
  sealed trait ExactOrdered     extends SchemaPolicy
  sealed trait ExactOrderedCI   extends SchemaPolicy
  sealed trait ExactByPosition  extends SchemaPolicy
  sealed trait Backward         extends SchemaPolicy
  sealed trait Forward          extends SchemaPolicy
  sealed trait Unchecked        extends SchemaPolicy

  // Case objects for runtime usage - implement the traits
  case object Exact extends SchemaPolicy.Exact
  // Back-compat alias. Semantics match Exact (unordered, case-sensitive)
  case object ExactUnordered   extends SchemaPolicy.ExactUnordered
  case object ExactUnorderedCI extends SchemaPolicy.ExactUnorderedCI
  case object ExactOrdered     extends SchemaPolicy.ExactOrdered
  case object ExactOrderedCI   extends SchemaPolicy.ExactOrderedCI
  case object ExactByPosition  extends SchemaPolicy.ExactByPosition
  case object Backward         extends SchemaPolicy.Backward
  case object Forward          extends SchemaPolicy.Forward
  case object Unchecked        extends SchemaPolicy.Unchecked

  // The `0.1.0` name, kept so that every call site still compiles while it is rewritten. All three spellings
  // carry over: `SchemaPolicy.Full` as a type, `SchemaPolicy.Full` as a value, and `SchemaPolicy.Full.type`,
  // which is the singleton type of the `val` below and so dealiases to `Unchecked.type`. A `given` keyed on
  // `Unchecked.type` therefore answers a summon for `Full.type`.
  @deprecated("renamed to SchemaPolicy.Unchecked", "0.2.0")
  type Full = Unchecked

  @deprecated("renamed to SchemaPolicy.Unchecked", "0.2.0")
  val Full: Unchecked.type = Unchecked
}
