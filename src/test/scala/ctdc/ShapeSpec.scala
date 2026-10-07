package ctdc

import munit.FunSuite

class ShapeSpec extends FunSuite:

  private case class Address(street: String, zip: String)
  private case class Person(id: Long, name: String, nickname: Option[String], tags: List[String], country: String = "DE")

  test("derivation lists the fields of a case class in declaration order") {
    val shape = summon[Shape[Person]]
    assertEquals(shape.fields.map(_.name), List("id", "name", "nickname", "tags", "country"))
  }

  test("derivation records which field is optional") {
    val shape = summon[Shape[Person]]
    assertEquals(shape.fields.filter(_.isOptional).map(_.name), List("nickname"))
  }

  test("derivation records which field has a default") {
    val shape = summon[Shape[Person]]
    assertEquals(shape.fields.filter(_.hasDefault).map(_.name), List("country"))
  }

  test("a field type is rendered as a string") {
    val shape = summon[Shape[Address]]
    assertEquals(shape.fields.map(_.tpe), List("String", "String"))
  }

  test("a container reports the fields of what it contains") {
    assertEquals(summon[Shape[List[Address]]].fields.map(_.name), List("street", "zip"))
  }

  test("a leaf type has no fields of its own") {
    assertEquals(summon[Shape[String]].fields, List.empty[Field])
  }
