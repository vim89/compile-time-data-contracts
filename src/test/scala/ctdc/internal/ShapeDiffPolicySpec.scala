package ctdc.internal

import ctdc.ContractsCore.SchemaPolicy
import ctdc.internal.TypeShape.*

import munit.FunSuite

/** The policy matrix, exercised without a compiler.
  *
  * [[ctdc.SchemaConformsSpec]] proves the macro end to end, which means every case it covers costs a typecheck of a
  * source string and can only assert on error text. These tests go at the same policies through [[ShapeDiff]]
  * directly, so a policy's behaviour is pinned as data: which fields are missing, which are extra, and at which path.
  */
class ShapeDiffPolicySpec extends FunSuite:

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

  private val nestedUser     = StructShape(List(field("inner", StructShape(List(id)))))
  private val nestedIdString = StructShape(List(field("inner", StructShape(List(field("id", string))))))

  // Policy to rules

  test("Exact and ExactUnorderedCI are the same comparison") {
    assertEquals(ComparisonRules.of(SchemaPolicy.Exact), ComparisonRules.of(SchemaPolicy.ExactUnorderedCI))
  }

  /** The one intentional difference from FlowForge's port of this engine.
    *
    * There, `Exact` matches field names case-sensitively and the case-insensitive comparison has to be asked for by
    * name. Here `Exact` is case-insensitive, following Spark's `equalsIgnoreCaseAndNullability`, and the
    * case-sensitive unordered comparison is its own policy, `ExactUnordered`. Both behaviours are reachable in both
    * repositories; only the name `Exact` points at a different one.
    */
  test("ExactUnordered is Exact without case-insensitivity") {
    val exact      = ComparisonRules.of(SchemaPolicy.Exact)
    val unordered  = ComparisonRules.of(SchemaPolicy.ExactUnordered)
    assertEquals(unordered.matching, exact.matching)
    assertEquals(unordered.tolerance, exact.tolerance)
    assertEquals(unordered.casing, NameCasing.Sensitive)
    assertEquals(exact.casing, NameCasing.Insensitive)
  }

  // Exact

  test("Exact accepts an identical shape") {
    assert(conforms(SchemaPolicy.Exact, user, user))
  }

  test("Exact ignores field order") {
    assert(conforms(SchemaPolicy.Exact, reordered, user))
  }

  test("Exact accepts field names that differ only in case, unlike FlowForge's Exact") {
    assert(conforms(SchemaPolicy.Exact, differentCase, user))
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

  test("Exact treats a field-level Option as the same column as a required field") {
    assert(conforms(SchemaPolicy.Exact, userWithRequiredNickname, userWithOptionalNickname))
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

  test("Backward does not relax optionality nested inside a sequence") {
    val mismatched = drift(SchemaPolicy.Backward, listOfInt, listOfOptionalInt).mismatched
    assertEquals(mismatched.map(m => (m.path, m.expected, m.found)), List(("values[]", "optional Int", "Int")))
  }

  // Forward

  test("Forward accepts a producer that is a subset of the contract") {
    assert(conforms(SchemaPolicy.Forward, withoutEmail, user))
  }

  test("Forward rejects a producer that adds fields") {
    assertEquals(drift(SchemaPolicy.Forward, withAge, user).extra.map(_.path), List("age"))
  }

  // Full

  test("Full accepts shapes with nothing in common") {
    assert(conforms(SchemaPolicy.Full, renamed, withAge))
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
