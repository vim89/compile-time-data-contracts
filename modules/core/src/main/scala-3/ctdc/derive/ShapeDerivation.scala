package ctdc.derive

import ctdc.internal.{ TypeShape, TypeShapes }

import scala.quoted.*

/** Scala 3 derivation of [[Shape]], via a quotes macro. Mixed into `object Shape`. */
trait ShapeDerivation {
  inline given gen[T]: Shape[T] = ${ ShapeDerivation.genImpl[T] }
}

object ShapeDerivation {

  def genImpl[T: Type](using q: Quotes): Expr[Shape[T]] = {
    import q.reflect.*

    val tpe = TypeRepr.of[T].dealias
    // Keep v1.0 scope to products only; reject everything else, including sums
    if (!TypeShapes.isCaseClass(tpe)) {
      report.errorAndAbort(s"Shape supports case classes only for now (got ${tpe.show})")
    }

    val fieldExprs = Expr.ofList(TypeShapes.params(tpe).map {
      case (name, fieldType, hasDefault) =>
        // Read off the type out here: inside the quote a different Quotes instance is in scope, so a
        // `TypeRepr` from this one does not typecheck there.
        val rendered   = Expr(TypeShape.simpleName(fieldType.show))
        val isOptional = Expr(TypeShapes.isOption(fieldType.dealias))
        '{
          Field(
            name = ${ Expr(name) },
            tpe = $rendered,
            hasDefault = ${ Expr(hasDefault) },
            isOptional = $isOptional,
          )
        }
    })

    '{
      new Shape[T] {
        val fields: List[Field] = $fieldExprs
      }
    }
  }
}
