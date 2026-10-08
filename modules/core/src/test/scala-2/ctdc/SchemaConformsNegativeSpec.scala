package ctdc

import munit.FunSuite

import scala.reflect.runtime.{ currentMirror => mirror }
import scala.tools.reflect.{ ToolBox, ToolBoxError }

/**
 * Rejections the Scala 2 macro has to report, asserted on the error text.
 *
 * [[ctdc.SchemaConformsMaterializationSpec]] covers the cases where evidence must materialize, which plain
 * `implicitly` can express on either version. A case where it must *not* materialize cannot be written that
 * way, because the file would stop compiling. Scala 3 has `scala.compiletime.testing` for that; Scala 2 does
 * not, so the snippet is compiled by a toolbox and the macro's abort is read off the thrown error.
 *
 * These are the same assertions as the Scala 3 fixtures of the same name, and that is the point: the two
 * front ends share [[ctdc.internal.ShapeDiff]], but each one does its own reflection, and the defects these
 * cover were defects of one front end's reflection only.
 */
class SchemaConformsNegativeSpec extends FunSuite {

  private val toolbox = mirror.mkToolBox()

  /** The declarations every snippet is compiled against, so that each test holds only what it is about. */
  private val preamble =
    """
      import ctdc.{ SchemaConforms, SchemaPolicy }

      case class ContractUser(id: Long, email: String)
      case class Reordered(email: String, id: Long)
      case class Box[T](x: T)
      case class Outer[T](box: Box[T])
      type IntBox = Box[Int]
      case class One(x: Option[Int])
      case class Two(x: Option[Option[Int]])
      case class AlsoTwo(x: Option[Option[Int]])
      case class Node(id: Long, next: Option[Node])
      case class Branch(id: Long, children: Seq[Branch])
      case class Tree(root: Branch)
      case class Leaf(id: Long)
      case class TwoLeaves(a: Leaf, b: Leaf)
    """

  private def compile(snippet: String): Unit = {
    val tree = toolbox.parse(s"{ $preamble ; $snippet ; () }")
    toolbox.compile(tree): Unit
  }

  private def assertCompiles(snippet: String): Unit =
    try compile(snippet)
    catch {
      case e: ToolBoxError => fail(s"Expected the snippet to compile, but it failed with: ${e.getMessage}")
    }

  private def assertFailsWith(snippet: String, expectedSnippets: String*): Unit = {
    val message =
      try {
        compile(snippet)
        fail("Expected the snippet to be rejected, but it compiled successfully.")
      } catch {
        case e: ToolBoxError => e.getMessage
      }
    expectedSnippets.foreach { expected =>
      assert(message.contains(expected), clues(s"Expected the error to contain: $expected", message))
    }
  }

  test("an abstract policy type is refused rather than compared under a default") {
    // A generic method that has not fixed its policy used to get strict by-name rules by default, which let it
    // manufacture `ExactOrdered` evidence for a reordered pair that `ExactOrdered` itself rejects.
    assertFailsWith(
      """
        def generic[P <: SchemaPolicy]: SchemaConforms[Reordered, ContractUser, P] =
          implicitly[SchemaConforms[Reordered, ContractUser, P]]
      """,
      "which is not a known policy here",
      "Take the evidence as a parameter instead",
    )
  }

  test("generic code works when the call site that fixes the policy supplies the evidence") {
    // The other half of the refusal above: taking the evidence as a parameter is a working alternative, not a
    // dead end, so an ordinary generic API still compiles.
    assertCompiles(
      """
        def generic[P <: SchemaPolicy](implicit ev: SchemaConforms[Reordered, ContractUser, P]): Unit = ()
        generic[SchemaPolicy.Exact]
      """,
    )
  }

  test("a generic product's type argument is resolved, so Box[Int] does not conform to Box[String]") {
    // The accessor's own signature is `T`, which compares equal to any other application's `T`. Reading it
    // raw made every `Box[_]` the same shape.
    assertFailsWith(
      "implicitly[SchemaConforms[Box[Int], Box[String], SchemaPolicy.Exact]]",
      "x expected String, found Int",
    )
  }

  test("a generic product conforms to itself at the same type argument") {
    assertCompiles("implicitly[SchemaConforms[Box[Int], Box[Int], SchemaPolicy.Exact]]")
  }

  test("a nested generic product resolves its type argument through the outer application") {
    assertFailsWith(
      "implicitly[SchemaConforms[Outer[Int], Outer[String], SchemaPolicy.Exact]]",
      "box.x expected String, found Int",
    )
  }

  test("a type alias of a generic product resolves to the type it aliases") {
    assertFailsWith(
      "implicitly[SchemaConforms[IntBox, Box[String], SchemaPolicy.Exact]]",
      "x expected String, found Int",
    )
  }

  test("a nested field Option keeps every layer, so Option[Int] does not conform to Option[Option[Int]]") {
    // One layer is consumed onto the field's own optionality. A second strip made these two the same shape,
    // which silently equated a field that can be absent with one that can be absent or present-and-empty.
    assertFailsWith(
      "implicitly[SchemaConforms[One, Two, SchemaPolicy.Exact]]",
      "x expected optional Int, found Int",
    )
  }

  test("a nested field Option conforms when both sides declare the same layers") {
    assertCompiles("implicitly[SchemaConforms[Two, AlsoTwo, SchemaPolicy.Exact]]")
  }

  // A recursive type has no finite shape, and before it was rejected explicitly the walk recursed until the
  // compiler ran out of stack. The Scala 3 fixtures of the same name assert the same three cases.

  test("a type that contains itself is rejected by name, not walked until the stack ends") {
    assertFailsWith(
      "implicitly[SchemaConforms[Node, Node, SchemaPolicy.Exact]]",
      "Unsupported recursive type",
      "Node -> Node",
    )
  }

  test("a cycle through a collection is caught too, and the error names the path into it") {
    assertFailsWith(
      "implicitly[SchemaConforms[Tree, Tree, SchemaPolicy.Exact]]",
      "Unsupported recursive type",
      "Tree -> Branch -> Branch",
    )
  }

  test("the same type in two sibling fields is not a cycle") {
    assertCompiles("implicitly[SchemaConforms[TwoLeaves, TwoLeaves, SchemaPolicy.Exact]]")
  }
}
