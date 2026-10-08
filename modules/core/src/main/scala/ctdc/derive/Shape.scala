package ctdc.derive

/**
 * Compile-time field metadata for a case class.
 *
 * `tpe` is the field's type as the compiler renders it, which differs between Scala versions; treat it as a
 * label, not an identity. `isOptional` is the reliable test for `Option`.
 */
final case class Field(
  name: String,
  tpe: String,
  hasDefault: Boolean,
  isOptional: Boolean)

/**
 * The top-level fields of `T`, for a caller that wants the names and the optionality of one record without
 * depending on Spark.
 *
 * This is one flat list and nothing more. It is deliberately not the model the contract check runs on: that is
 * `ctdc.internal.TypeShape`, a recursive algebra with its own nodes for sequences, maps, nested structs and the
 * `Option` layers between them, and it is internal because the comparison rules are defined over it. `Shape`
 * keeps each field's type only as the string the compiler renders, so two different types can produce the same
 * label and a nested record produces no children at all.
 *
 * So it is enough to list or log a record's fields, and it is not enough to write a comparator or a new format
 * back end against. Supporting another schema language means a front end that builds a `TypeShape`, next to the
 * two that exist, rather than an implementation of this trait.
 */
trait Shape[T] { def fields: List[Field] }

/**
 * Instances for the types that have no fields, plus derivation for the ones that do.
 *
 * Derivation arrives through [[ShapeDerivation]], which each Scala version supplies from its own source
 * directory: Magnolia on 2.13, a quotes macro on 3. Being inherited also makes it lower priority than the
 * instances declared here, so `Shape[String]` resolves to `stringShape` rather than deriving an empty shape.
 */
object Shape extends ShapeDerivation {

  /** A type with no fields of its own. Named once so that the five primitives below say which types those are and
    * nothing else, instead of repeating what having no fields means.
    */
  private def leaf[A]: Shape[A] = new Shape[A] { val fields: List[Field] = List.empty }

  // Primitive instances
  implicit val stringShape: Shape[String]   = leaf
  implicit val intShape: Shape[Int]         = leaf
  implicit val longShape: Shape[Long]       = leaf
  implicit val booleanShape: Shape[Boolean] = leaf
  implicit val doubleShape: Shape[Double]   = leaf

  // Containers report the fields of what they contain, so that `Shape[List[User]]` is as useful as `Shape[User]`.
  implicit def listShape[A](implicit inner: Shape[A]): Shape[List[A]] =
    new Shape[List[A]] { val fields: List[Field] = inner.fields }

  implicit def optionShape[A](implicit inner: Shape[A]): Shape[Option[A]] =
    new Shape[Option[A]] { val fields: List[Field] = inner.fields }

  implicit def mapShape[K, V](implicit inner: Shape[V]): Shape[Map[K, V]] =
    new Shape[Map[K, V]] { val fields: List[Field] = inner.fields }
}
