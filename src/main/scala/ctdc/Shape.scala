package ctdc

import ctdc.internal.{ TypeShape, TypeShapes }

import scala.quoted.*

/** One field of a case class, as a value.
  *
  * `tpe` is the rendered field type. It is a string rather than a type because the only consumers are diagnostics and
  * schema printing; anything that needs the type itself should be a macro and read it from reflection directly.
  */
final case class Field(name: String, tpe: String, hasDefault: Boolean, isOptional: Boolean)

/** The field list of `T`, available at runtime without Spark on the classpath.
  *
  * [[ctdc.ContractsCore.CompileTime.SchemaConforms]] answers "do these two types agree", and answers it at compile
  * time only. This answers the different and smaller question "what fields does this type have", as ordinary data, so
  * that logging, documentation and tests can see a contract without deriving a Spark schema for it.
  */
trait Shape[T]:
  def fields: List[Field]

object Shape extends ShapeDerivation:

  // Leaves have no fields of their own. They are given explicitly, rather than left to derivation, because the
  // derivation only knows how to read a case class.
  given stringShape: Shape[String]   = new Shape[String]   { val fields: List[Field] = List.empty }
  given intShape: Shape[Int]         = new Shape[Int]      { val fields: List[Field] = List.empty }
  given longShape: Shape[Long]       = new Shape[Long]     { val fields: List[Field] = List.empty }
  given booleanShape: Shape[Boolean] = new Shape[Boolean]  { val fields: List[Field] = List.empty }
  given doubleShape: Shape[Double]   = new Shape[Double]   { val fields: List[Field] = List.empty }

  // Containers report the fields of what they contain, so that `Shape[List[User]]` is as useful as `Shape[User]`.
  given listShape[A](using inner: Shape[A]): Shape[List[A]] = new Shape[List[A]]:
    val fields: List[Field] = inner.fields

  given optionShape[A](using inner: Shape[A]): Shape[Option[A]] = new Shape[Option[A]]:
    val fields: List[Field] = inner.fields

  given mapShape[K, V](using inner: Shape[V]): Shape[Map[K, V]] = new Shape[Map[K, V]]:
    val fields: List[Field] = inner.fields

/** Lower-priority derivation for `Shape`.
  *
  * Separated into a parent trait purely for given priority: the leaf instances in [[Shape]] are defined closer and so
  * win over this one for the types they cover.
  */
trait ShapeDerivation:

  inline given derived[T]: Shape[T] = ${ ShapeDerivation.derivedImpl[T] }

object ShapeDerivation:

  /** Public because an inline given cannot reach a private macro implementation from inside a trait. */
  def derivedImpl[T: Type](using q: Quotes): Expr[Shape[T]] =
    import q.reflect.*

    val tpe = TypeRepr.of[T].dealias
    if !TypeShapes.isCaseClass(tpe) then
      report.errorAndAbort(s"Shape derivation supports case classes only (got ${tpe.show})")

    // Every reflection call happens out here. Inside a quote the ambient `Quotes` is a different one, so a
    // reflection value computed there would not belong to this expansion.
    val fieldExprs = TypeShapes.params(tpe).map { case (name, fieldType, hasDefault) =>
      val rendered   = Expr(TypeShape.simpleName(fieldType.show))
      val isOptional = Expr(TypeShapes.isOption(fieldType.dealias))
      '{ Field(name = ${ Expr(name) }, tpe = $rendered, hasDefault = ${ Expr(hasDefault) }, isOptional = $isOptional) }
    }

    val allFields = Expr.ofList(fieldExprs)
    '{
      new Shape[T]:
        val fields: List[Field] = $allFields
    }
