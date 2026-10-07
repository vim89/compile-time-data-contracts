package ctdc.internal

import ctdc.internal.TypeShape.*

import scala.quoted.*

/**
 * Turning a Scala 3 type into a [[TypeShape]].
 *
 * The two macros that need this, contract checking and `Shape` derivation, ask different questions of the
 * same type, so the reflection lives here and each of them keeps only its own job.
 */
object TypeShapes {

  /** The normalized shape of `tpe`. */
  def of(using q: Quotes)(tpe: q.reflect.TypeRepr): TypeShape = shapeOf(tpe)

  /**
   * Primary-constructor parameters of a case class, as (name, type, whether it has a default).
   *
   * Names and types come from the case fields and defaults from the constructor, because only the constructor
   * parameters carry the default flag.
   */
  def params(using q: Quotes)(tpe: q.reflect.TypeRepr): List[(String, q.reflect.TypeRepr, Boolean)] = {
    import q.reflect.*
    val sym = tpe.typeSymbol
    val hasDefault = sym.primaryConstructor.paramSymss.flatten
      .filterNot(_.isTypeParam)
      .map(p => p.name -> p.flags.is(Flags.HasDefault))
      .toMap
    sym.caseFields.map(f => (f.name, tpe.memberType(f), hasDefault.getOrElse(f.name, false)))
  }

  /** Whether `tpe` is a case class, and so has a struct shape rather than being opaque. */
  def isCaseClass(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean = {
    import q.reflect.*
    val sym = tpe.typeSymbol
    sym.isClassDef && sym.flags.is(Flags.Case)
  }

  /** Whether `tpe` is `Option[_]`. */
  def isOption(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
    tpe.asType match {
      case '[Option[t]] => true
      case _            => false
    }

  /**
   * The shape of a type, with every `Option` it carries kept as an [[TypeShape.OptionalShape]] layer.
   *
   * Every layer is kept, including the outermost. A caller that has already consumed one layer - which is
   * what a field does, on [[TypeShape.FieldShape.isOptional]] - must pass what is left after consuming it
   * and not ask this function to drop a second one. That is the distinction between `Option[A]` and
   * `Option[Option[A]]` in a field, and dropping it here made the two conform.
   */
  private def shapeOf(using q: Quotes)(tpe: q.reflect.TypeRepr): TypeShape = {
    import q.reflect.*
    val t = tpe.dealias
    t.asType match {
      case '[Option[a]] => OptionalShape(shapeOf(TypeRepr.of[a]))

      case '[Map[k, v]] =>
        val key = TypeRepr.of[k].dealias
        if (!isAtomicKey(key)) {
          report.errorAndAbort(
            s"Unsupported Map key type: ${key.show}. Allowed: String, Int, Long, Short, Byte, Boolean",
          )
        }
        MapShape(PrimitiveShape(TypeShape.simpleName(key.show)), shapeOf(TypeRepr.of[v]))

      case '[Seq[a]]   => SequenceShape(shapeOf(TypeRepr.of[a]))
      case '[Set[a]]   => SequenceShape(shapeOf(TypeRepr.of[a]))
      case '[Array[a]] => SequenceShape(shapeOf(TypeRepr.of[a]))

      case _ => structOrLeaf(t)
    }
  }

  private def structOrLeaf(using q: Quotes)(tpe: q.reflect.TypeRepr): TypeShape = {
    import q.reflect.*
    // Tuples are checked first because every TupleN is itself a case class. Reading one as a struct of
    // `_1`, `_2` would make positional junk look like a named schema, so a tuple is rejected rather than
    // reinterpreted.
    if (tpe <:< TypeRepr.of[Tuple]) unsupportedTuple(tpe)
    else if (isCaseClass(tpe)) StructShape(fieldsOf(tpe))
    else opaqueLeaf(tpe)
  }

  /**
   * A type this macro cannot look inside, compared by its name.
   *
   * There used to be a closed list of leaf types here, on the reasoning that a type outside it is one no sink
   * can write. That is not something a contract can know: a sink takes the writer as a function, so whether a
   * `UUID` or a domain enum can be written is decided by the writer the caller supplies, not by this list.
   * Rejecting an unlisted leaf therefore turned away pipelines that were fine. Comparing it by name still
   * catches the drift that is this macro's job, because a leaf that changes type changes its name.
   */
  private def opaqueLeaf(using q: Quotes)(tpe: q.reflect.TypeRepr): PrimitiveShape = {
    import q.reflect.*
    PrimitiveShape(TypeShape.simpleName(tpe.show))
  }

  private def unsupportedTuple(using q: Quotes)(tpe: q.reflect.TypeRepr): Nothing = {
    import q.reflect.*
    report.errorAndAbort(
      s"Unsupported tuple in SchemaConforms derivation: ${tpe.show}. " +
        "A tuple has no field names to compare, so use a case class instead.",
    )
  }

  private def fieldsOf(using q: Quotes)(tpe: q.reflect.TypeRepr): List[FieldShape] = {
    import q.reflect.*
    params(tpe).map {
      case (name, fieldType, hasDefault) =>
        val (underlying, isOptional) = fieldType.dealias.asType match {
          case '[Option[a]] => (TypeRepr.of[a], true)
          case _            => (fieldType, false)
        }
        // One layer of Option is consumed here, onto isOptional; whatever is left keeps its layers.
        FieldShape(name, shapeOf(underlying), hasDefault, isOptional)
    }
  }

  private def isAtomicKey(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean = {
    import q.reflect.*
    tpe =:= TypeRepr.of[String] || tpe =:= TypeRepr.of[Int] || tpe =:= TypeRepr.of[Long] ||
    tpe =:= TypeRepr.of[Short] || tpe =:= TypeRepr.of[Byte] || tpe =:= TypeRepr.of[Boolean]
  }
}
