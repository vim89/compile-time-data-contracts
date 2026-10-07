package ctdc.internal

import ctdc.SchemaPolicy
import ctdc.internal.TypeShape._

import munit.FunSuite

/** The policy matrix, exercised without a compiler.
  *
  * [[ctdc.SchemaConformsSpec]] proves the macro end to end, which means every case it covers costs a typecheck of a
  * source string and can only assert on error text. These tests go at the same policies through [[ShapeDiff]]
  * directly, so a policy's behaviour is pinned as data: which fields are missing, which are extra, and at which path.
  */
class ShapeDiffPolicySpec extends FunSuite {

  private def drift(policy: SchemaPolicy, out: TypeShape, contract: TypeShape): ShapeDiff.Drift =
    ShapeDiff.diff(ComparisonRules.of(policy), out, contract)

  private def conforms(policy: SchemaPolicy, out: TypeShape, contract: TypeShape): Boolean =
    drift(policy, out, contract).isEmpty

  private def field(name: String, shape: TypeShape): FieldShape =
    FieldShape(name, shape, hasDefault = false, isOptional = false)

  private val long   = PrimitiveShape("Long")
  private val string = PrimitiveShape("String")
  private val int    = PrimitiveShape("Int")

  private val id    = field("id", long)
  private val name  = field("name", string)
  private val email = field("email", string)

  private val user         = StructShape(List(id, name, email))
  private val reordered    = StructShape(List(id, email, name))
  private val withAge      = StructShape(List(id, name, email, field("age", int)))
  private val withoutEmail = StructShape(List(id, name))
  private val idAsString   = StructShape(List(field("id", string), name, email))
  private val differentCase =
    StructShape(List(field("Id", long), field("NAME", string), email))
  private val renamed =
    StructShape(List(field("identifier", long), field("label", string), field("contact", string)))

  // Same field names as `user`, but `name` and `id` swapped, so positions disagree on type rather than on name.
  private val swappedTypes = StructShape(List(name, id, email))

  private val nickname                  = string
  private val userWithOptionalNickname  = StructShape(List(id, FieldShape("nickname", nickname, false, true)))
  private val userWithDefaultedNickname = StructShape(List(id, FieldShape("nickname", nickname, true, false)))
  private val userWithRequiredNickname  = StructShape(List(id, field("nickname", nickname)))
  private val withoutNickname           = StructShape(List(id))

  private val listOfInt         = StructShape(List(field("values", SequenceShape(int))))
  private val listOfString      = StructShape(List(field("values", SequenceShape(string))))
  private val listOfOptionalInt = StructShape(List(field("values", SequenceShape(OptionalShape(int)))))

  private val mapOfInt         = StructShape(List(field("counts", MapShape(string, int))))
  private val mapOfIntByInt    = StructShape(List(field("counts", MapShape(int, int))))
  private val mapOfOptionalInt = StructShape(List(field("counts", MapShape(string, OptionalShape(int)))))

  // Two names that are one name under case-insensitive matching, and the one-field struct they are compared
  // against so that a collision is the only thing a failure can be about.
  private val caseColliding = StructShape(List(id, field("ID", long)))
  private val justId        = StructShape(List(id))

  private val nestedUser     = StructShape(List(field("inner", StructShape(List(id)))))
  private val nestedIdString = StructShape(List(field("inner", StructShape(List(field("id", string))))))

  // Policy to rules

  test("Exact and ExactUnordered are the same comparison") {
    assertEquals(ComparisonRules.of(SchemaPolicy.Exact), ComparisonRules.of(SchemaPolicy.ExactUnordered))
  }

  /** The suffix is the only thing that asks for case-insensitivity.
    *
    * `Exact` used to be case-insensitive here, following Spark's `equalsIgnoreCaseAndNullability`, which made the one
    * name in the family carrying no suffix the only unsuffixed policy that was lenient. Pinned because the default is
    * what a caller gets without thinking about casing, and the strict reading is the one that fails at compile time
    * rather than at the destination.
    */
  test("ExactUnorderedCI is Exact with case-insensitivity") {
    val exact        = ComparisonRules.of(SchemaPolicy.Exact)
    val insensitive  = ComparisonRules.of(SchemaPolicy.ExactUnorderedCI)
    assertEquals(insensitive.matching, exact.matching)
    assertEquals(insensitive.tolerance, exact.tolerance)
    assertEquals(exact.casing, NameCasing.Sensitive)
    assertEquals(insensitive.casing, NameCasing.Insensitive)
  }

  // Exact

  test("Exact accepts an identical shape") {
    assert(conforms(SchemaPolicy.Exact, user, user))
  }

  test("Exact ignores field order") {
    assert(conforms(SchemaPolicy.Exact, reordered, user))
  }

  test("Exact reports field names that differ only in case") {
    // Parquet, Avro and JSON all keep the case they are given, so `userId` written against a contract declaring
    // `userid` is a column the consumer does not find. ExactUnorderedCI is how a case-folding destination says so.
    assert(!conforms(SchemaPolicy.Exact, differentCase, user))
    assert(conforms(SchemaPolicy.ExactUnorderedCI, differentCase, user))
  }

  test("Exact reports a producer field the contract does not mention") {
    assertEquals(drift(SchemaPolicy.Exact, withAge, user).extra.map(_.path), List("age"))
  }

  test("Exact reports a contract field the producer does not have") {
    assertEquals(drift(SchemaPolicy.Exact, withoutEmail, user).missing.map(_.path), List("email"))
  }

  test("Exact reports a field whose type drifted") {
    val mismatched = drift(SchemaPolicy.Exact, idAsString, user).mismatched
    assertEquals(mismatched.map(m => (m.path, m.expected, m.found)), List(("id", "Long", "String")))
  }

  test("Exact reports a field-level Option against a required contract field") {
    // The carrier Spark's comparators drop and this one used to drop with them. The two shapes agree on the
    // column and on its type; they disagree about whether a value can be absent, which is the only claim in
    // the pair a producer can actually violate.
    val mismatched = drift(SchemaPolicy.Exact, userWithOptionalNickname, userWithRequiredNickname).mismatched
    assertEquals(
      mismatched.map(m => (m.path, m.expected, m.found)),
      List(("nickname", "a required field", "an optional field")),
    )
  }

  test("Exact reports a required field against an optional contract field too") {
    // Symmetric on purpose: a producer that never sends an absent value still disagrees with a contract that
    // says the column is optional, and under an exact policy a disagreement is drift.
    assert(!conforms(SchemaPolicy.Exact, userWithRequiredNickname, userWithOptionalNickname))
  }

  // ExactUnordered

  test("ExactUnordered ignores field order") {
    assert(conforms(SchemaPolicy.ExactUnordered, reordered, user))
  }

  test("ExactUnordered rejects field names that differ only in case") {
    assert(!conforms(SchemaPolicy.ExactUnordered, differentCase, user))
  }

  test("ExactUnordered reports a case-drifted name as both missing and extra") {
    val result = drift(SchemaPolicy.ExactUnordered, differentCase, user)
    assertEquals(result.missing.map(_.path), List("id", "name"))
    assertEquals(result.extra.map(_.path), List("Id", "NAME"))
  }

  // ExactOrdered

  test("ExactOrdered accepts fields declared in the contract's order") {
    assert(conforms(SchemaPolicy.ExactOrdered, user, user))
  }

  test("ExactOrdered reports a reordered field at its index") {
    val mismatched = drift(SchemaPolicy.ExactOrdered, reordered, user).mismatched
    assertEquals(mismatched.map(_.path), List("@1(name)", "@2(name)"))
  }

  test("ExactOrdered rejects a name that drifted only in case") {
    assert(!conforms(SchemaPolicy.ExactOrdered, differentCase, user))
  }

  // ExactOrderedCI

  test("ExactOrderedCI accepts a name that drifted only in case") {
    assert(conforms(SchemaPolicy.ExactOrderedCI, differentCase, user))
  }

  test("ExactOrderedCI still rejects a reordered field") {
    assert(!conforms(SchemaPolicy.ExactOrderedCI, reordered, user))
  }

  // ExactByPosition

  test("ExactByPosition ignores field names entirely") {
    assert(conforms(SchemaPolicy.ExactByPosition, renamed, user))
  }

  test("ExactByPosition reports each position whose type drifted") {
    val mismatched = drift(SchemaPolicy.ExactByPosition, user, swappedTypes).mismatched
    assertEquals(mismatched.map(_.path), List("@0", "@1"))
  }

  test("ExactByPosition reports a count difference at the first unpaired index") {
    assertEquals(drift(SchemaPolicy.ExactByPosition, withAge, user).extra.map(_.path), List("@3"))
  }

  // Backward

  test("Backward accepts a producer that adds fields") {
    assert(conforms(SchemaPolicy.Backward, withAge, user))
  }

  test("Backward accepts a missing contract field that is optional") {
    assert(conforms(SchemaPolicy.Backward, withoutNickname, userWithOptionalNickname))
  }

  test("Backward accepts a missing contract field that has a default") {
    assert(conforms(SchemaPolicy.Backward, withoutNickname, userWithDefaultedNickname))
  }

  test("Backward rejects a missing contract field that is required") {
    assertEquals(drift(SchemaPolicy.Backward, withoutNickname, userWithRequiredNickname).missing.map(_.path), List("nickname"))
  }

  test("Backward still reports a field whose type drifted") {
    assert(!conforms(SchemaPolicy.Backward, idAsString, user))
  }

  test("Backward accepts a producer that is stricter than the contract about absence") {
    assert(conforms(SchemaPolicy.Backward, userWithRequiredNickname, userWithOptionalNickname))
  }

  test("Backward rejects a producer that relaxes a required contract field") {
    // The unsafe direction, and the reason this is an axis and not a flag: absent values would arrive at a
    // consumer whose types say they cannot.
    assert(!conforms(SchemaPolicy.Backward, userWithOptionalNickname, userWithRequiredNickname))
  }

  test("Backward treats a sequence element the same way it treats a field: stricter is fine") {
    // A producer whose elements are never absent satisfies a contract that allows holes. This used to be
    // drift, which made Backward mean "stricter is fine" about a field and "must agree" about an element.
    assertEquals(drift(SchemaPolicy.Backward, listOfInt, listOfOptionalInt), ShapeDiff.Drift.empty)
  }

  test("Backward rejects a producer that relaxes a sequence element the contract requires") {
    // The unsafe direction at the nested carrier, reported under the element path.
    val mismatched = drift(SchemaPolicy.Backward, listOfOptionalInt, listOfInt).mismatched
    assertEquals(mismatched.map(m => (m.path, m.expected, m.found)), List(("values[]", "Int", "optional Int")))
  }

  // Forward

  test("Forward accepts a producer that is a subset of the contract") {
    assert(conforms(SchemaPolicy.Forward, withoutEmail, user))
  }

  test("Forward rejects a producer that adds fields") {
    assertEquals(drift(SchemaPolicy.Forward, withAge, user).extra.map(_.path), List("age"))
  }

  test("Forward rejects a producer that relaxes a required contract field") {
    assert(!conforms(SchemaPolicy.Forward, userWithOptionalNickname, userWithRequiredNickname))
  }

  // Full

  test("Full ignores field-level optionality, like every other difference") {
    assert(conforms(SchemaPolicy.Full, userWithOptionalNickname, userWithRequiredNickname))
  }

  test("Full accepts shapes with nothing in common") {
    assert(conforms(SchemaPolicy.Full, renamed, withAge))
  }

  // Name collisions
  //
  // By-name matching indexes fields by their normalized name, so two fields that normalize alike would
  // collapse into one slot and the comparison would silently answer about whichever survived. The runtime
  // comparator in `ctdc.SparkCore` refuses such a schema outright, and `SparkRuntimeSpec` pins the same
  // cases against it, so these tests are half of a parity pair rather than a local preference.

  test("ExactUnorderedCI rejects a producer carrying two names that differ only in case") {
    assert(!conforms(SchemaPolicy.ExactUnorderedCI, caseColliding, justId))
  }

  test("a collision is reported once, at the normalized name, naming both fields") {
    val reported = drift(SchemaPolicy.ExactUnorderedCI, caseColliding, justId)
    assertEquals(reported.mismatched.map(_.path), List("id"))
    assertEquals(reported.mismatched.map(_.found), List("2 fields: id, ID"))
    assertEquals(reported.extra, Nil)
    assertEquals(reported.missing, Nil)
  }

  test("a case-sensitive policy still rejects an exactly duplicated name") {
    assert(!conforms(SchemaPolicy.Exact, StructShape(List(id, id)), justId))
  }

  test("a collision on the contract side is reported too") {
    assertEquals(drift(SchemaPolicy.ExactUnorderedCI, justId, caseColliding).mismatched.map(_.path), List("id"))
  }

  test("a nested collision reports under the dotted path of the struct that carries it") {
    val nestedColliding = StructShape(List(field("inner", caseColliding)))
    val nestedJustId    = StructShape(List(field("inner", justId)))
    assertEquals(
      drift(SchemaPolicy.ExactUnorderedCI, nestedColliding, nestedJustId).mismatched.map(_.path),
      List("inner.id"),
    )
  }

  test("ordered matching pairs by position, so a case-colliding pair is not a collision") {
    assert(conforms(SchemaPolicy.ExactOrderedCI, caseColliding, caseColliding))
  }

  test("positional matching ignores names, so a case-colliding pair is not a collision") {
    assert(conforms(SchemaPolicy.ExactByPosition, caseColliding, caseColliding))
  }

  /** Parity with `RuntimeSchemaComparator.matches`, which answers `true` for `Permissive` before it looks. */
  test("Full tolerates a collision, like every other difference") {
    assert(conforms(SchemaPolicy.Full, caseColliding, justId))
  }

  // Paths

  test("a sequence element reports under a [] path") {
    assertEquals(drift(SchemaPolicy.Exact, listOfInt, listOfString).mismatched.map(_.path), List("values[]"))
  }

  test("a map key reports under a <key> path") {
    assertEquals(drift(SchemaPolicy.Exact, mapOfInt, mapOfIntByInt).mismatched.map(_.path), List("counts<key>"))
  }

  test("a map value reports under a <value> path") {
    assertEquals(drift(SchemaPolicy.Exact, mapOfInt, mapOfOptionalInt).mismatched.map(_.path), List("counts<value>"))
  }

  test("a nested struct field reports under a dotted path") {
    assertEquals(drift(SchemaPolicy.Exact, nestedUser, nestedIdString).mismatched.map(_.path), List("inner.id"))
  }

  // Reporting

  test("report returns nothing when the producer conforms") {
    val rules = ComparisonRules.of(SchemaPolicy.Exact)
    assertEquals(ShapeDiff.report("Exact", "Producer", "Contract", rules, user, user), None)
  }

  test("report names the policy, both types, and the drifting field") {
    val rules   = ComparisonRules.of(SchemaPolicy.Exact)
    val message = ShapeDiff.report("Exact", "Producer", "Contract", rules, withoutEmail, user)
    assert(message.isDefined)
    val rendered = message.get
    assert(rendered.contains("Compile-time contract drift (policy: Exact)"), rendered)
    assert(rendered.contains("Out: Producer vs Contract: Contract"), rendered)
    assert(rendered.contains("Missing attributes: email : String"), rendered)
  }

  test("report marks a missing field as optional or defaulted") {
    val rules   = ComparisonRules.of(SchemaPolicy.Exact)
    val message = ShapeDiff.report("Exact", "Producer", "Contract", rules, withoutNickname, userWithOptionalNickname)
    assert(message.exists(_.contains("nickname : String (optional)")), message)
  }
}
