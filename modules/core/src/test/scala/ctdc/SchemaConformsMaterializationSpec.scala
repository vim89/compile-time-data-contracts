package ctdc

import munit.FunSuite

import scala.annotation.nowarn

/** The macro front end, on whichever Scala version is being compiled.
  *
  * [[ctdc.SchemaConformsSpec]] asserts on the drift report, which needs `scala.compiletime.testing` and so only runs
  * on Scala 3. These cases only need evidence to materialize, which both versions can express, so they are what keeps
  * the Scala 2 macro from going untested.
  */
class SchemaConformsMaterializationSpec extends FunSuite {

  private case class Address(street: String, zip: String)
  private case class ContractUser(id: Long, name: String, shipTo: Address, tags: List[String])
  private case class Producer(tags: List[String], shipTo: Address, name: String, id: Long)
  private case class ProducerWithAge(id: Long, name: String, shipTo: Address, tags: List[String], age: Int)

  test("Exact materializes evidence for a shape that matches, whatever the field order") {
    implicitly[SchemaConforms[Producer, ContractUser, SchemaPolicy.Exact]]
  }

  test("ExactOrdered materializes evidence when the producer declares the contract's order") {
    implicitly[SchemaConforms[ContractUser, ContractUser, SchemaPolicy.ExactOrdered]]
  }

  test("Backward materializes evidence for a producer that adds a field") {
    implicitly[SchemaConforms[ProducerWithAge, ContractUser, SchemaPolicy.Backward]]
  }

  test("Unchecked materializes evidence for shapes with nothing in common") {
    implicitly[SchemaConforms[Address, ContractUser, SchemaPolicy.Unchecked]]
  }

  // The `0.1.0` name has to keep resolving to the same policy, or the alias is a courtesy that does not work.
  // Deprecation is suppressed here and nowhere else: a warning is the point of the alias, not a defect in it.
  test("the deprecated Full alias materializes the same evidence as Unchecked") {
    val viaAlias: AnyRef = implicitly[SchemaConforms[Address, ContractUser, SchemaPolicy.Full]]: @nowarn(
      "cat=deprecation"
    )
    assert(viaAlias ne null)
  }

  // Evidence carries no members, so the assertion that matters is that the call compiles at all.
  test("conforms reaches the same macro as implicitly") {
    assert(conforms[Producer, ContractUser, SchemaPolicy.Exact] ne null)
  }
}
