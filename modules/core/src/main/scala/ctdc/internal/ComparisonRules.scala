package ctdc.internal

import ctdc.SchemaPolicy

/** How producer fields are lined up against contract fields before anything is compared. */
sealed trait FieldMatching

object FieldMatching {
  case object ByName        extends FieldMatching
  case object ByNameOrdered extends FieldMatching
  case object ByPosition    extends FieldMatching
}

/**
 * Whether a field name has to match exactly or only up to case.
 *
 * The folding itself lives here rather than at the comparison sites, because it is one decision and every
 * caller has to make it the same way: the compile-time comparison, the runtime pin and the duplicate-name
 * diagnostic all have to agree on what two names being "the same name" means, or a schema can pass one and
 * fail another. `Locale.ROOT` for the same reason - the answer must not depend on the JVM's default locale.
 */
sealed trait NameCasing {
  def normalize(name: String): String
}

object NameCasing {

  case object Sensitive extends NameCasing {
    def normalize(name: String): String = name
  }

  case object Insensitive extends NameCasing {
    def normalize(name: String): String = name.toLowerCase(java.util.Locale.ROOT)
  }
}

/**
 * What a difference in field-level optionality means for conformance.
 *
 * A schema carries "can be absent" in three independent places, and they are three different statements about
 * data: a field may be absent from a row, a present collection may have holes in it, and a present map may
 * have null values. The two nested carriers are structural in [[TypeShape]] - `seq[optional A]` and
 * `optional seq[A]` are different shapes - so they are always compared, and this axis is only about the
 * field-level one, which is the carrier that has to be a policy decision because `Backward` and `Forward`
 * already depend on it to decide whether an absent field is tolerable.
 *
 * It is an axis rather than a `Boolean` because there are three answers and only two of them are the ends of
 * a scale. Collapsing it would reintroduce exactly the problem this comparison exists to fix.
 */
sealed trait FieldOptionality

object FieldOptionality {

  /** The producer and the contract must agree about whether the field can be absent. */
  case object MustAgree extends FieldOptionality

  /**
   * The producer may promise more than the contract asks - a required field where the contract allows an
   * absent one - but never less. The unsafe direction is the other one: a producer that makes a field
   * optional where the contract says it is always present sends absent values to a consumer whose types say
   * they cannot arrive.
   */
  case object AllowStricter extends FieldOptionality

  /** The carrier is not compared. */
  case object Ignored extends FieldOptionality
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
  tolerance: Tolerance,
  fieldOptionality: FieldOptionality) {

  /** Field names reduced to the form this policy compares them in. */
  def normalize(name: String): String = casing.normalize(name)

  def sameName(left: String, right: String): Boolean = normalize(left) == normalize(right)

  /**
   * Whether a producer field that is `outOptional` conforms to a contract field that is `contractOptional`.
   *
   * A calculation rather than a branch at each call site, so that every place that lines fields up - by name,
   * by name in order, by position - asks the question the same way and cannot answer it differently by
   * accident. That is how this axis came to be unchecked in the first place.
   */
  /**
   * The same rules with the field-level optionality carrier left uncompared.
   *
   * For a comparison whose producer side does not actually state the claim. Callers must say why, because
   * dropping this axis is what [[FieldOptionality]] exists to stop happening by accident.
   */
  def ignoringFieldOptionality: ComparisonRules = copy(fieldOptionality = FieldOptionality.Ignored)

  def optionalityConforms(outOptional: Boolean, contractOptional: Boolean): Boolean =
    fieldOptionality match {
      case FieldOptionality.Ignored       => true
      case FieldOptionality.MustAgree     => outOptional == contractOptional
      case FieldOptionality.AllowStricter => contractOptional || !outOptional
    }
}

object ComparisonRules {

  import FieldMatching._
  import FieldOptionality._
  import NameCasing._

  /**
   * The rules each policy stands for.
   *
   * Total over the policy ADT on purpose: a new policy that nobody mapped is a compile error here, not a
   * silent fallback to some default at the call site.
   */
  def of(policy: SchemaPolicy): ComparisonRules = policy match {
    case SchemaPolicy.Exact            => ComparisonRules(ByName, Sensitive, Tolerance.Strict, MustAgree)
    case SchemaPolicy.ExactUnordered   => ComparisonRules(ByName, Sensitive, Tolerance.Strict, MustAgree)
    case SchemaPolicy.ExactUnorderedCI => ComparisonRules(ByName, Insensitive, Tolerance.Strict, MustAgree)
    case SchemaPolicy.ExactOrdered     => ComparisonRules(ByNameOrdered, Sensitive, Tolerance.Strict, MustAgree)
    case SchemaPolicy.ExactOrderedCI   => ComparisonRules(ByNameOrdered, Insensitive, Tolerance.Strict, MustAgree)
    case SchemaPolicy.ExactByPosition  => ComparisonRules(ByPosition, Sensitive, Tolerance.Strict, MustAgree)
    // A relaxation is drift under both compatibility policies, and for the same reason: it is the direction
    // that sends absent values to a consumer that is not expecting them. The policies differ in what they do
    // about fields that are present on one side only, not in what they do about optionality.
    case SchemaPolicy.Backward => ComparisonRules(ByName, Sensitive, Tolerance.Backward, AllowStricter)
    case SchemaPolicy.Forward  => ComparisonRules(ByName, Sensitive, Tolerance.Forward, AllowStricter)
    case SchemaPolicy.Full     => ComparisonRules(ByName, Sensitive, Tolerance.Permissive, Ignored)
  }

  /**
   * What to compare under when the policy type is not one of the known policies.
   *
   * Reached only for an abstract `P <: SchemaPolicy`, which is generic code that has not fixed its policy
   * yet. Strict name matching is the safe default: it can report drift that a looser policy would have
   * accepted, but it never passes a producer that the requested policy would have rejected.
   */
  val strictest: ComparisonRules = ComparisonRules(ByName, Sensitive, Tolerance.Strict, MustAgree)
}
