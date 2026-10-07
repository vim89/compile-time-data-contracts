// scalafix:off DisableSyntax.throw
package ctdc.derive

import ctdc.internal.TypeShape
import magnolia1._

import scala.language.experimental.macros

/** Scala 2 derivation of [[Shape]], via Magnolia. Mixed into `object Shape`. */
trait ShapeDerivation {
  type Typeclass[T] = Shape[T]

  def join[T](caseClass: CaseClass[Typeclass, T]): Shape[T] =
    new Shape[T] {
      val fields: List[Field] =
        caseClass.parameters.toList.map { p =>
          val typeName = p.typeName // Magnolia 1 (Scala 2) exposes typeName
          Field(
            name = p.label,
            tpe = render(typeName),
            hasDefault = p.default.isDefined,
            isOptional = typeName.full == optionName,
          )
        }
    }

  private val optionName = "scala.Option"

  /**
   * A field type rendered the way Scala 3 reflection renders it.
   *
   * Magnolia reports a type name and its arguments separately, so `Option[String]` arrives as `scala.Option`
   * carrying one argument rather than as one rendered string. Reading only the name therefore dropped the
   * element type, and made every field read as required, because the name on its own never contains the
   * `Option[` that optionality used to be decided by.
   */
  private def render(typeName: TypeName): String = {
    val name = TypeShape.simpleName(typeName.full)
    if (typeName.typeArguments.isEmpty) name
    else s"$name[${typeName.typeArguments.map(render).mkString(", ")}]"
  }

  // Keep v1.0 scope to products only; abort on sums for now
  def split[T](ctx: SealedTrait[Typeclass, T]): Typeclass[T] =
    throw new IllegalArgumentException(
      s"Shape supports case classes only for now (got sum type ${ctx.typeName.full})",
    )

  implicit def gen[T]: Shape[T] = macro Magnolia.gen[T]
}
