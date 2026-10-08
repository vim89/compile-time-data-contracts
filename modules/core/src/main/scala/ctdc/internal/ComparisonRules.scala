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
 * What a difference in a carrier of optionality means for conformance.
 *
 * A schema carries "can be absent" in three independent places, and they are three different statements about
 * data: a field may be absent from a row, a present collection may have holes in it, and a present map may
 * have null values. This one axis governs all three. It used to govern only the field-level one, on the
 * reasoning that the other two are structural in [[TypeShape]] and so need no policy. That reasoning does not
 * survive the runtime side: a `StructType` records each of the three as a single bit, Spark's file readers
 * default all three to permissive, and a comparison that drops one bit as unstated while demanding exact
 * equality of the other two is not a position anyone would defend if it were written down. It was not written
 * down; it was two hard-coded equality checks next to one axis read. Sharing the axis is what makes the
 * difference between the carriers something a policy states rather than something each comparison site
 * decides on its own.
 *
 * It is an axis rather than a `Boolean` because there are three answers and only two of them are the ends of
 * a scale. Collapsing it would reintroduce exactly the problem this comparison exists to fix.
 */
sealed trait Optionality

object Optionality {

  /** The producer and the contract must agree about whether the value can be absent. */
  case object MustAgree extends Optionality

  /**
   * The producer may promise more than the contract asks - a required value where the contract allows an
   * absent one - but never less. The unsafe direction is the other one: a producer that makes a value
   * optional where the contract says it is always present sends absent values to a consumer whose types say
   * they cannot arrive.
   */
  case object AllowStricter extends Optionality

  /** The carrier is not compared. */
  case object Ignored extends Optionality
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
  optionality: Optionality) {

  /** Field names reduced to the form this policy compares them in. */
  def normalize(name: String): String = casing.normalize(name)

  def sameName(left: String, right: String): Boolean = normalize(left) == normalize(right)

  /**
   * The same rules with every carrier of optionality left uncompared.
   *
   * For a comparison whose producer side does not actually state the claim. Callers must say why, because
   * dropping this axis is what [[Optionality]] exists to stop happening by accident. All three carriers go
   * at once, because the reason for dropping any of them - a reader that defaults the bit rather than
   * reporting what the producer declared - applies to all three equally, and a partial drop is the defect
   * this method replaced.
   */
  def ignoringOptionality: ComparisonRules = copy(optionality = Optionality.Ignored)

  /**
   * Whether a producer carrier that reads `outOptional` conforms to a contract carrier that reads
   * `contractOptional`.
   *
   * A calculation rather than a branch at each call site, so that every carrier at every site - a field
   * lined up by name, by name in order or by position, a sequence element, a map value - asks the question
   * the same way and cannot answer it differently by accident. Each call site reading its own answer is how
   * this axis came to be unchecked at one of them and hard-coded at two others.
   */
  def optionalityConforms(outOptional: Boolean, contractOptional: Boolean): Boolean =
    optionality match {
      case Optionality.Ignored       => true
      case Optionality.MustAgree     => outOptional == contractOptional
      case Optionality.AllowStricter => contractOptional || !outOptional
    }
}

object ComparisonRules {

  import FieldMatching._
  import Optionality._
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
    case SchemaPolicy.Unchecked => ComparisonRules(ByName, Sensitive, Tolerance.Permissive, Ignored)
  }

  /**
   * The compile error for a policy type [[of]] does not reach.
   *
   * Reached only for an abstract `P <: SchemaPolicy`, which is generic code that has not fixed its policy
   * yet. There used to be a default here, strict by-name matching, on the reasoning that it reports drift a
   * looser policy would have accepted and so errs safely. It does not: the policies are not ordered by
   * strictness, so no one relation stands in for an unknown one. `ExactOrdered` rejects a field permutation
   * that by-name matching accepts, which is how a generic method could manufacture ordered evidence for a
   * reordered pair; `ExactUnorderedCI` accepts a case change that every case-sensitive policy rejects. A
   * default is therefore wrong in one direction or the other whichever one is picked, and refusing is the
   * only answer that is right for every policy the caller might later supply.
   */
  def unresolvedPolicy(policyName: String): String =
    s"""Cannot derive SchemaConforms under policy type $policyName, which is not a known policy here.
       |Only the nine policies of ctdc.SchemaPolicy have comparison rules; an abstract P has none, and no
       |default would be sound because the policies are not ordered by strictness.
       |Take the evidence as a parameter instead - (using SchemaConforms[Out, Contract, P]) - so the call
       |site that fixes P derives it.
       |""".stripMargin
}
