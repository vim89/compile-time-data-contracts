package ctdc.internal

/** Normalized structural shape of a Scala type.
  *
  * This is the whole vocabulary that policy comparison works in. It holds no compiler types on purpose, so the
  * comparison in [[ShapeDiff]] is an ordinary pure function that can be exercised without running a macro.
  *
  * Not user-facing: it exists to reduce different Scala surface forms (`List` vs `Vector`, `Option[Int]` field vs
  * `Int` field) to one model, so that a policy is a deterministic walk rather than ad hoc matching over reflection
  * nodes.
  */
private[ctdc] sealed trait TypeShape

private[ctdc] object TypeShape:

  final case class PrimitiveShape(name: String)                    extends TypeShape
  final case class OptionalShape(inner: TypeShape)                 extends TypeShape
  final case class SequenceShape(elem: TypeShape)                  extends TypeShape
  final case class MapShape(key: PrimitiveShape, value: TypeShape) extends TypeShape
  final case class StructShape(fields: List[FieldShape])           extends TypeShape

  /** A field of a struct.
    *
    * Deliberately not a [[TypeShape]]: a field *has* a shape, it is not one, and nothing compares a field against a
    * bare shape. `hasDefault` and `isOptional` are the two facts a policy needs that the shape cannot carry, because
    * a field-level `Option` is unwrapped into `shape` so that `Option[Int]` and `Int` compare equal under the exact
    * policies.
    */
  final case class FieldShape(name: String, shape: TypeShape, hasDefault: Boolean, isOptional: Boolean)

  /** How a shape reads in a compile error.
    *
    * Package prefixes are stripped because comparison is structural: `scala.Predef.String` and `java.lang.String` are
    * the same leaf, and printing either in full buries the field that actually drifted.
    */
  def render(shape: TypeShape): String = shape match
    case PrimitiveShape(name) => simpleName(name)
    case OptionalShape(inner) => s"optional ${render(inner)}"
    case SequenceShape(elem)  => s"seq[${render(elem)}]"
    case MapShape(key, value) => s"map[${render(key)} -> ${render(value)}]"
    case StructShape(fields)  => fields.map(f => s"${f.name}: ${render(f.shape)}").mkString("{", ", ", "}")

  /** A rendered type name with the prefixes a reader does not need.
    *
    * Shared with [[ctdc.Shape]] so that a field type reads the same whether it is printed by a compile error or by a
    * runtime field listing.
    */
  def simpleName(rendered: String): String =
    rendered
      .stripPrefix("scala.Predef.")
      .stripPrefix("scala.")
      .stripPrefix("java.lang.")

  /** How a field reads in a compile error, including the two facts its shape cannot carry. */
  def renderField(field: FieldShape): String =
    val optional = if field.isOptional then " (optional)" else ""
    val default  = if field.hasDefault then " (default)" else ""
    s"${render(field.shape)}$optional$default"
