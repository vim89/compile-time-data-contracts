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

trait Shape[T] { def fields: List[Field] }

/**
 * Instances for the types that have no fields, plus derivation for the ones that do.
 *
 * Derivation arrives through [[ShapeDerivation]], which each Scala version supplies from its own source
 * directory: Magnolia on 2.13, a quotes macro on 3. Being inherited also makes it lower priority than the
 * instances declared here, so `Shape[String]` resolves to `stringShape` rather than deriving an empty shape.
 */
object Shape extends ShapeDerivation {

  // Primitive instances
  implicit val stringShape: Shape[String]   = new Shape[String] { val fields = List.empty }
  implicit val intShape: Shape[Int]         = new Shape[Int] { val fields = List.empty }
  implicit val longShape: Shape[Long]       = new Shape[Long] { val fields = List.empty }
  implicit val booleanShape: Shape[Boolean] = new Shape[Boolean] { val fields = List.empty }
  implicit val doubleShape: Shape[Double]   = new Shape[Double] { val fields = List.empty }

  // Containers report the fields of what they contain, so that `Shape[List[User]]` is as useful as `Shape[User]`.
  implicit def listShape[A](implicit inner: Shape[A]): Shape[List[A]] =
    new Shape[List[A]] { val fields: List[Field] = inner.fields }

  implicit def optionShape[A](implicit inner: Shape[A]): Shape[Option[A]] =
    new Shape[Option[A]] { val fields: List[Field] = inner.fields }

  implicit def mapShape[K, V](implicit inner: Shape[V]): Shape[Map[K, V]] =
    new Shape[Map[K, V]] { val fields: List[Field] = inner.fields }
}
