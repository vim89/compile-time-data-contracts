package ctdc.internal

/**
 * Normalized structural shape for compile-time comparison.
 *
 * Clean, minimal ADT focused on the essentials needed for deep schema comparison.
 */
sealed trait TypeShape

object TypeShape {
  final case class PrimitiveShape(name: String)                    extends TypeShape
  final case class SequenceShape(elem: TypeShape)                  extends TypeShape
  final case class MapShape(key: PrimitiveShape, value: TypeShape) extends TypeShape
  // Represents nested optionality (e.g., List[Option[A]]). Field-level optionality remains on FieldShape.
  final case class OptionalShape(inner: TypeShape) extends TypeShape

  /**
   * A named member of a struct, deliberately not a `TypeShape` itself.
   *
   * A field is not a shape: it is a name, a shape and two claims about that shape, and the only place one can
   * occur is inside a [[StructShape]]. Extending `TypeShape` made `OptionalShape(FieldShape(...))` and
   * `SequenceShape(FieldShape(...))` constructible, neither of which any shape walk produces or any
   * comparison knows what to do with, and it obliged every match over `TypeShape` to carry a case that the
   * walks cannot reach. The containment is stated by `StructShape.fields` instead.
   */
  final case class FieldShape(
    name: String,
    shape: TypeShape,
    hasDefault: Boolean,
    isOptional: Boolean)

  final case class StructShape(fields: List[FieldShape]) extends TypeShape

  /**
   * Prefixes that carry no information for a reader of a drift report.
   *
   * Matched anywhere in the name rather than only at the front, because a type argument carries its own
   * prefix: `Option[scala.Predef.String]` has to reduce to `Option[String]`, which stripping a leading prefix
   * cannot do. The uppercase lookahead is what keeps the reduction to type names only, so
   * `scala.collection.immutable.Map` is left alone instead of becoming `collection.immutable.Map`. The
   * alternation is ordered longest-first so that `scala.Predef.` wins over the `scala.` that starts it.
   */
  private val redundantPrefixes = """(?:scala\.Predef\.|scala\.|java\.lang\.)(?=[A-Z])""".r

  /**
   * A rendered type name without the prefixes a reader does not need.
   *
   * The two macros print the same type differently - Scala 3 reflection says `scala.Predef.String` where
   * Scala 2 says `java.lang.String` - so a drift report would read differently depending on which compiler
   * produced it. Names are reduced here, at the one place both macros build a shape through.
   */
  def simpleName(rendered: String): String = redundantPrefixes.replaceAllIn(rendered, "")

  /**
   * A shape as it reads in a drift report.
   *
   * Deliberately not the Scala source spelling of the type: what a report needs to say is what the comparison
   * actually looked at, which is the normalized shape rather than the collection class the producer happened
   * to use.
   */
  def render(shape: TypeShape): String = shape match {
    case PrimitiveShape(name) => simpleName(name)
    case OptionalShape(inner) => s"optional ${render(inner)}"
    case SequenceShape(elem)  => s"seq[${render(elem)}]"
    case MapShape(key, value) => s"map[${render(key)} -> ${render(value)}]"
    case StructShape(fields) =>
      fields.map(f => s"${f.name}: ${render(f.shape)}").mkString("{", ", ", "}")
  }

  /** A field as it reads in a drift report, with what the contract says about absent values. */
  def renderField(field: FieldShape): String = {
    val optional = if (field.isOptional) " (optional)" else ""
    val default  = if (field.hasDefault) " (default)" else ""
    s"${render(field.shape)}$optional$default"
  }

  /** The Scala source spelling of a shape, for callers that want the type back rather than the report form. */
  def pretty(shape: TypeShape): String = shape match {
    case PrimitiveShape(name) => name
    case SequenceShape(elem)  => s"List[${pretty(elem)}]"
    case MapShape(key, value) => s"Map[${pretty(key)}, ${pretty(value)}]"
    case OptionalShape(inner) => s"Option[${pretty(inner)}]"
    case StructShape(fields) =>
      fields.map(f => f.name + ":" + pretty(f.shape)).mkString("{", ",", "}")
  }
}
