package ctdc.internal

import ctdc.ContractsCore.SchemaPolicy

/** How producer fields are lined up against contract fields before anything is compared. */
private[ctdc] enum FieldMatching:
  case ByName, ByNameOrdered, ByPosition

/** Whether a field name has to match exactly or only up to case. */
private[ctdc] enum NameCasing:
  case Sensitive, Insensitive

/** Which differences survive once the fields are lined up. */
private[ctdc] enum Tolerance:
  /** Every difference is drift. */
  case Strict

  /** The producer may add fields, and may omit a contract field that is optional or has a default. */
  case Backward

  /** The producer may omit fields, so it can be a subset of the contract. */
  case Forward

  /** Escape hatch: comparison still runs, but nothing it finds is drift. */
  case Permissive

/** A policy flattened into the three independent questions comparison actually asks.
  *
  * [[ctdc.ContractsCore.SchemaPolicy]] is the vocabulary users write; this is the vocabulary the comparison reads.
  * Keeping them apart is what lets the comparison be a pure function, and it makes the contradictory combinations
  * unconstructible: there is no way to ask for "by position and also ordered by name", which a set of independent
  * booleans would have allowed.
  */
private[ctdc] final case class ComparisonRules(
    matching: FieldMatching,
    casing: NameCasing,
    tolerance: Tolerance
):

  /** Field names reduced to the form this policy compares them in. */
  def normalize(name: String): String = casing match
    case NameCasing.Sensitive   => name
    case NameCasing.Insensitive => name.toLowerCase

  def sameName(left: String, right: String): Boolean = normalize(left) == normalize(right)

private[ctdc] object ComparisonRules:

  import FieldMatching.*
  import NameCasing.*
  import Tolerance.*

  /** The rules each policy stands for.
    *
    * Total over the policy enum on purpose: a new policy that nobody mapped is a compile error here, not a silent
    * fallback to the strictest behaviour at the call site.
    *
    * `Exact` is case-sensitive, so the `CI` suffix is what asks for case-insensitivity everywhere in the family. It
    * used to be case-insensitive, following Spark's `equalsIgnoreCaseAndNullability`, which made the one policy name
    * carrying no suffix the only unsuffixed policy that was lenient. Two things argued against that. The suffix
    * convention: `ExactOrdered` and `ExactUnordered` are case-sensitive, so a reader has no reason to expect `Exact`
    * not to be. And the cost of being wrong, which is not symmetric: Spark's comparator is an analysis-time rule,
    * while the formats a pipeline writes to keep the case they are given, so an accepted `userId` against a contract
    * declaring `userid` is a column the consumer does not find at runtime. A strict default turns that into a
    * compile error, and a destination that really does fold case asks for `ExactUnorderedCI` by name.
    */
  def of(policy: SchemaPolicy): ComparisonRules = policy match
    case SchemaPolicy.Exact            => ComparisonRules(ByName, Sensitive, Strict)
    case SchemaPolicy.ExactUnordered   => ComparisonRules(ByName, Sensitive, Strict)
    case SchemaPolicy.ExactUnorderedCI => ComparisonRules(ByName, Insensitive, Strict)
    case SchemaPolicy.ExactOrdered     => ComparisonRules(ByNameOrdered, Sensitive, Strict)
    case SchemaPolicy.ExactOrderedCI   => ComparisonRules(ByNameOrdered, Insensitive, Strict)
    case SchemaPolicy.ExactByPosition  => ComparisonRules(ByPosition, Sensitive, Strict)
    case SchemaPolicy.Backward         => ComparisonRules(ByName, Sensitive, Backward)
    case SchemaPolicy.Forward          => ComparisonRules(ByName, Sensitive, Forward)
    case SchemaPolicy.Full             => ComparisonRules(ByName, Sensitive, Permissive)

  /** What to compare under when the policy type is not one of the known policies.
    *
    * Reached only for an abstract or unresolved `P`. Strict name matching is the safe default: it can report drift
    * that a looser policy would have accepted, but it never passes a producer that the requested policy would have
    * rejected.
    */
  val strictest: ComparisonRules = ComparisonRules(ByName, Sensitive, Strict)
