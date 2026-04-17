package ctdc

import munit.FunSuite

import scala.compiletime.testing.typeCheckErrors

class SchemaConformsSpec extends FunSuite:

  private inline def assertTypeChecks(inline code: String): Unit =
    val errors = typeCheckErrors(code)
    assert(
      errors.isEmpty,
      clues(
        "Expected code to compile, but got:",
        errors.map(_.message).mkString("\n---\n")
      )
    )

  private inline def assertTypeFails(inline code: String, expectedSnippets: String*): Unit =
    val errors = typeCheckErrors(code)
    assert(
      errors.nonEmpty,
      clues("Expected code to fail typechecking, but it compiled successfully.")
    )
    val rendered = errors.map(_.message).mkString("\n---\n")
    expectedSnippets.foreach { snippet =>
      assert(
        rendered.contains(snippet),
        clues(s"Expected error output to contain: $snippet", rendered)
      )
    }

  test("Exact accepts unordered case-insensitive field names and ignores nullability") {
    assertTypeChecks(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractUser(id: Long, Email: String, age: Option[Int])
        final case class Producer(age: Int, email: String, id: Long)

        summon[SchemaConforms[Producer, ContractUser, SchemaPolicy.Exact.type]]
      """
    )
  }

  test("Exact treats field-level Option and non-Option as structurally equal") {
    assertTypeChecks(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractUser(id: Long, age: Option[Int])
        final case class Producer(id: Long, age: Int)

        summon[SchemaConforms[Producer, ContractUser, SchemaPolicy.Exact.type]]
      """
    )
  }

  test("Backward accepts extra producer fields and missing optional/default contract fields") {
    assertTypeChecks(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractUser(id: Long, email: String, age: Option[Int] = None)
        final case class Producer(id: Long, email: String, region: String)

        summon[SchemaConforms[Producer, ContractUser, SchemaPolicy.Backward.type]]
      """
    )
  }

  test("Backward accepts nested collections and maps under the same structural shape") {
    assertTypeChecks(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class LineItem(sku: String, qty: Int, attrs: Map[String, String])
        final case class ContractOrder(id: Long, items: Seq[LineItem], tags: Seq[String], note: Option[String] = None)
        final case class Producer(id: Long, items: List[LineItem], tags: Set[String], extra: String)

        summon[SchemaConforms[Producer, ContractOrder, SchemaPolicy.Backward.type]]
      """
    )
  }

  test("Exact preserves nested optionality inside sequences") {
    assertTypeChecks(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractRow(values: List[Option[Int]])
        final case class Producer(values: List[Option[Int]])

        summon[SchemaConforms[Producer, ContractRow, SchemaPolicy.Exact.type]]
      """
    )
  }

  test("Forward accepts a producer subset of the contract schema") {
    assertTypeChecks(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractUser(id: Long, email: String, age: Option[Int], note: Option[String] = None)
        final case class Producer(id: Long, email: String)

        summon[SchemaConforms[Producer, ContractUser, SchemaPolicy.Forward.type]]
      """
    )
  }

  test("ExactOrdered rejects reordered fields with an indexed path in the error") {
    assertTypeFails(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractUser(id: Long, email: String)
        final case class Producer(email: String, id: Long)

        SchemaConforms.derived[Producer, ContractUser, SchemaPolicy.ExactOrdered.type]
      """,
      "Compile-time contract drift",
      "@0(name)"
    )
  }

  test("ExactOrderedCI rejects reordered fields even when names only drift by case") {
    assertTypeFails(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractUser(id: Long, email: String)
        final case class Producer(EMAIL: String, ID: Long)

        SchemaConforms.derived[Producer, ContractUser, SchemaPolicy.ExactOrderedCI.type]
      """,
      "Compile-time contract drift",
      "@0(name)"
    )
  }

  test("ExactByPosition rejects reordered positions even when field names still exist") {
    assertTypeFails(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractUser(id: Long, email: String)
        final case class Producer(email: String, id: Long)

        SchemaConforms.derived[Producer, ContractUser, SchemaPolicy.ExactByPosition.type]
      """,
      "Compile-time contract drift",
      "@0 expected"
    )
  }

  test("Backward rejects missing required fields with a readable field path") {
    assertTypeFails(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractUser(id: Long, email: String)
        final case class Producer(id: Long)

        SchemaConforms.derived[Producer, ContractUser, SchemaPolicy.Backward.type]
      """,
      "Missing attributes: email"
    )
  }

  test("[A3/D12] SchemaConforms rejects unsupported leaf types instead of silently accepting them") {
    assertTypeFails(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms
        import java.util.UUID

        final case class ContractUser(id: UUID)
        final case class Producer(id: UUID)

        SchemaConforms.derived[Producer, ContractUser, SchemaPolicy.Exact.type]
      """,
      "Unsupported structural leaf type in SchemaConforms derivation",
      "java.util.UUID"
    )
  }

  test("[A3/D2] SchemaConforms rejects unsupported non-case-class contracts cleanly") {
    assertTypeFails(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        sealed trait Contract
        final case class Producer(id: Long)

        SchemaConforms.derived[Producer, Contract, SchemaPolicy.Exact.type]
      """,
      "Unsupported structural leaf type in SchemaConforms derivation",
      "Contract"
    )
  }

  test("[A3/D4] SchemaConforms rejects tuple leaves explicitly") {
    assertTypeFails(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractUser(payload: (Int, String))
        final case class Producer(payload: (Int, String))

        SchemaConforms.derived[Producer, ContractUser, SchemaPolicy.Exact.type]
      """,
      "Unsupported structural leaf type in SchemaConforms derivation"
    )
  }

  test("Exact surfaces nested mismatch paths for deep structural failures") {
    assertTypeFails(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class Address(zip: String)
        final case class BadAddress(zip: Int)
        final case class ContractUser(id: Long, shipTo: Address, tags: List[String])
        final case class Producer(id: Long, shipTo: BadAddress, tags: List[Int])

        SchemaConforms.derived[Producer, ContractUser, SchemaPolicy.Exact.type]
      """,
      "shipTo.zip expected",
      "tags[] expected"
    )
  }

  test("Exact rejects nested optionality drift in sequences") {
    assertTypeFails(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractRow(values: List[Int])
        final case class Producer(values: List[Option[Int]])

        SchemaConforms.derived[Producer, ContractRow, SchemaPolicy.Exact.type]
      """,
      "values[] expected",
      "found optional"
    )
  }

  test("Exact rejects nested optionality drift in map values") {
    assertTypeFails(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractRow(values: Map[String, Int])
        final case class Producer(values: Map[String, Option[Int]])

        SchemaConforms.derived[Producer, ContractRow, SchemaPolicy.Exact.type]
      """,
      "values<value> expected",
      "found optional"
    )
  }

  test("ExactUnorderedCI rejects structural type drift") {
    assertTypeFails(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractUser(id: Long, email: String)
        final case class Producer(id: Long, email: Int)

        SchemaConforms.derived[Producer, ContractUser, SchemaPolicy.ExactUnorderedCI.type]
      """,
      "Compile-time contract drift",
      "email expected"
    )
  }

  test("Full accepts unrelated producer and contract shapes at compile time") {
    assertTypeChecks(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class ContractUser(email: String)
        final case class Producer(values: List[Int], metadata: Map[String, Long])

        summon[SchemaConforms[Producer, ContractUser, SchemaPolicy.Full.type]]
      """
    )
  }

  test("Exact handles deep nesting when the structural shape matches") {
    assertTypeChecks(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class Leaf(code: Int)
        final case class Middle(payload: Map[String, Option[Leaf]])
        final case class ContractRoot(items: List[Middle])
        final case class ProducerRoot(items: Vector[Middle])

        summon[SchemaConforms[ProducerRoot, ContractRoot, SchemaPolicy.Exact.type]]
      """
    )
  }

  test("Exact surfaces deep nested mismatch paths beyond two levels") {
    assertTypeFails(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.ContractsCore.CompileTime.SchemaConforms

        final case class Leaf(code: Int)
        final case class BadLeaf(code: String)
        final case class Middle(payload: Map[String, Option[Leaf]])
        final case class BadMiddle(payload: Map[String, Option[BadLeaf]])
        final case class ContractRoot(items: List[Middle])
        final case class ProducerRoot(items: List[BadMiddle])

        SchemaConforms.derived[ProducerRoot, ContractRoot, SchemaPolicy.Exact.type]
      """,
      "items[].payload<value>.code expected"
    )
  }
