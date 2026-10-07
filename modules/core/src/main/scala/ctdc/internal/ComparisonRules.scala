package ctdc.internal

import ctdc.SchemaPolicy

/** How producer fields are lined up against contract fields before anything is compared. */
sealed trait FieldMatching

object FieldMatching {
  case object ByName        extends FieldMatching
  case object ByNameOrdered extends FieldMatching
  case object ByPosition    extends FieldMatching
}

/** Whether a field name has to match exactly or only up to case. */
sealed trait NameCasing

object NameCasing {
  case object Sensitive   extends NameCasing
  case object Insensitive extends NameCasing
}

/** Which differences survive once the fields are lined up. */
sealed trait Tolerance

object Tolerance {

  /** Every difference is drift. */
  case object Strict extends Tolerance

  /** The producer may add fields, and may omit a contract field that is optional or has a default. */
  case object Backward extends Tolerance

  /** The producer may omit fields, so it can be a subset of the contract. */
  case object Forward extends Tolerance

  /** Escape hatch: comparison still runs, but nothing it finds is drift. */
  case object Permissive extends Tolerance
}

/**
 * A policy flattened into the three independent questions comparison actually asks.
 *
 * [[ctdc.SchemaPolicy]] is the vocabulary users write; this is the vocabulary the
 * comparison reads. Keeping them apart is what lets the comparison be a pure function, and it makes the
 * contradictory combinations unconstructible: there is no way to ask for "by position and also ordered by
 * name", which a set of independent booleans would have allowed.
 */
final case class ComparisonRules(
  matching: FieldMatching,
  casing: NameCasing,
  tolerance: Tolerance) {

  /** Field names reduced to the form this policy compares them in. */
  def normalize(name: String): String = casing match {
    case NameCasing.Sensitive   => name
    case NameCasing.Insensitive => name.toLowerCase
  }

  def sameName(left: String, right: String): Boolean = normalize(left) == normalize(right)
}

object ComparisonRules {

  import FieldMatching._
  import NameCasing._

  /**
   * The rules each policy stands for.
   *
   * Total over the policy ADT on purpose: a new policy that nobody mapped is a compile error here, not a
   * silent fallback to some default at the call site.
   */
  def of(policy: SchemaPolicy): ComparisonRules = policy match {
    case SchemaPolicy.Exact            => ComparisonRules(ByName, Sensitive, Tolerance.Strict)
    case SchemaPolicy.ExactUnordered   => ComparisonRules(ByName, Sensitive, Tolerance.Strict)
    case SchemaPolicy.ExactUnorderedCI => ComparisonRules(ByName, Insensitive, Tolerance.Strict)
    case SchemaPolicy.ExactOrdered     => ComparisonRules(ByNameOrdered, Sensitive, Tolerance.Strict)
    case SchemaPolicy.ExactOrderedCI   => ComparisonRules(ByNameOrdered, Insensitive, Tolerance.Strict)
    case SchemaPolicy.ExactByPosition  => ComparisonRules(ByPosition, Sensitive, Tolerance.Strict)
    case SchemaPolicy.Backward         => ComparisonRules(ByName, Sensitive, Tolerance.Backward)
    case SchemaPolicy.Forward          => ComparisonRules(ByName, Sensitive, Tolerance.Forward)
    case SchemaPolicy.Full             => ComparisonRules(ByName, Sensitive, Tolerance.Permissive)
  }

  /**
   * What to compare under when the policy type is not one of the known policies.
   *
   * Reached only for an abstract `P <: SchemaPolicy`, which is generic code that has not fixed its policy
   * yet. Strict name matching is the safe default: it can report drift that a looser policy would have
   * accepted, but it never passes a producer that the requested policy would have rejected.
   */
  val strictest: ComparisonRules = ComparisonRules(ByName, Sensitive, Tolerance.Strict)
}
