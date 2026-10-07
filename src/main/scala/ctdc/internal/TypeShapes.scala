package ctdc.internal

import ctdc.internal.TypeShape.*

import scala.quoted.*

/** Reduction of a Scala type to its normalized [[TypeShape]] through quoted reflection.
  *
  * This is the only part of contract checking that needs a compiler. It answers one question - what shape is this
  * type - and rejects anything it cannot answer for, rather than guessing. Comparison itself lives in [[ShapeDiff]].
  */
private[ctdc] object TypeShapes:

  /** Leaf types a derived contract may use.
    *
    * The list is closed on purpose. Anything outside it is rejected at compile time instead of being treated as an
    * opaque primitive, because a type that Spark has no encoder for would otherwise pass the contract check and fail
    * at the sink.
    */
  val supportedLeafTypes: String =
    "String, Int, Long, Short, Byte, Double, Float, Boolean, BigDecimal, java.math.BigDecimal, java.sql.Date, java.time.LocalDate, java.sql.Timestamp, java.time.Instant, java.time.LocalDateTime"

  def of(using q: Quotes)(tpe: q.reflect.TypeRepr): TypeShape = shapeOf(tpe)

  /** Constructor parameters of a case class as `(name, type, hasDefault)`.
    *
    * Defaults are read from the primary constructor and field types from the case fields, because only the case
    * fields are guaranteed to be the actual data members: `paramSymss` also carries type parameters, which are not
    * fields and have no member type to speak of.
    */
  def params(using q: Quotes)(tpe: q.reflect.TypeRepr): List[(String, q.reflect.TypeRepr, Boolean)] =
    import q.reflect.*
    val symbol = tpe.typeSymbol
    val hasDefault = symbol.primaryConstructor.paramSymss.flatten
      .filterNot(_.isTypeParam)
      .map(param => param.name -> param.flags.is(Flags.HasDefault))
      .toMap
    symbol.caseFields.map(field => (field.name, tpe.memberType(field), hasDefault.getOrElse(field.name, false)))

  def isCaseClass(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
    import q.reflect.*
    val symbol = tpe.typeSymbol
    symbol.isClassDef && symbol.flags.is(Flags.Case)

  def isOption(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean = optionArg(tpe).isDefined

  private def shapeOf(using q: Quotes)(tpe: q.reflect.TypeRepr): TypeShape =
    import q.reflect.*
    val t = tpe.dealias

    optionArg(t)
      .map(inner => OptionalShape(shapeOf(inner)): TypeShape)
      .orElse(sequenceArg(t).map(elem => SequenceShape(shapeOf(elem))))
      .orElse(mapArgs(t).map((key, value) => MapShape(PrimitiveShape(atomicKeyName(t, key)), shapeOf(value))))
      .getOrElse(structOrLeaf(t))

  private def structOrLeaf(using q: Quotes)(tpe: q.reflect.TypeRepr): TypeShape =
    // Tuples are checked first because every `TupleN` is itself a case class. Reading one as a struct of `_1`, `_2`
    // would make positional junk look like a named schema, so a tuple leaf is rejected rather than reinterpreted.
    if isTuple(tpe) then unsupportedLeaf(tpe)
    else if isCaseClass(tpe) then StructShape(fieldsOf(tpe))
    else if isSupportedPrimitive(tpe) then PrimitiveShape(tpe.show)
    else unsupportedLeaf(tpe)

  private def fieldsOf(using q: Quotes)(tpe: q.reflect.TypeRepr): List[FieldShape] =
    params(tpe).map { case (name, fieldType, hasDefault) =>
      // Field-level optionality is recorded on the field and stripped from the shape, so that a nullable contract
      // field and a required producer field compare as the same column under the exact policies.
      val resolved                 = fieldType.dealias
      val (underlying, isOptional) = optionArg(resolved).fold(resolved -> false)(inner => inner -> true)
      FieldShape(name, shapeOf(underlying), hasDefault, isOptional)
    }

  private def appliedArgs(using q: Quotes)(tpe: q.reflect.TypeRepr): List[q.reflect.TypeRepr] =
    import q.reflect.*
    tpe match
      case AppliedType(_, args) => args
      case _                    => Nil

  private def optionArg(using q: Quotes)(tpe: q.reflect.TypeRepr): Option[q.reflect.TypeRepr] =
    import q.reflect.*
    if tpe.dealias <:< TypeRepr.of[Option[?]] then appliedArgs(tpe.dealias).headOption else None

  private def sequenceArg(using q: Quotes)(tpe: q.reflect.TypeRepr): Option[q.reflect.TypeRepr] =
    import q.reflect.*
    val isSequenceLike =
      tpe <:< TypeRepr.of[List[?]] || tpe <:< TypeRepr.of[Seq[?]] ||
        tpe <:< TypeRepr.of[Vector[?]] || tpe <:< TypeRepr.of[Array[?]] ||
        tpe <:< TypeRepr.of[Set[?]]
    if isSequenceLike then
      Some(appliedArgs(tpe).headOption.getOrElse(report.errorAndAbort(s"Missing type arg for sequence in ${tpe.show}")))
    else None

  private def mapArgs(using q: Quotes)(tpe: q.reflect.TypeRepr): Option[(q.reflect.TypeRepr, q.reflect.TypeRepr)] =
    import q.reflect.*
    if tpe <:< TypeRepr.of[Map[?, ?]] then
      appliedArgs(tpe) match
        case key :: value :: Nil => Some((key, value))
        case _                   => report.errorAndAbort(s"Map requires two type args: ${tpe.show}")
    else None

  /** The rendered key type of a map, rejecting keys Spark cannot represent as a map key. */
  private def atomicKeyName(using q: Quotes)(mapType: q.reflect.TypeRepr, key: q.reflect.TypeRepr): String =
    import q.reflect.*
    val atomic =
      key =:= TypeRepr.of[String] ||
        key =:= TypeRepr.of[Int] || key =:= TypeRepr.of[Long] ||
        key =:= TypeRepr.of[Short] || key =:= TypeRepr.of[Byte] || key =:= TypeRepr.of[Boolean]
    if atomic then key.show
    else
      report.errorAndAbort(
        s"Unsupported Map key type for ${mapType.show}. Allowed keys: String, Int, Long, Short, Byte, Boolean."
      )

  private def isTuple(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
    import q.reflect.*
    tpe <:< TypeRepr.of[Tuple]

  private def isSupportedPrimitive(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
    import q.reflect.*
    tpe =:= TypeRepr.of[String] ||
      tpe =:= TypeRepr.of[Int] ||
      tpe =:= TypeRepr.of[Long] ||
      tpe =:= TypeRepr.of[Short] ||
      tpe =:= TypeRepr.of[Byte] ||
      tpe =:= TypeRepr.of[Double] ||
      tpe =:= TypeRepr.of[Float] ||
      tpe =:= TypeRepr.of[Boolean] ||
      tpe =:= TypeRepr.of[BigDecimal] ||
      tpe =:= TypeRepr.of[java.math.BigDecimal] ||
      tpe =:= TypeRepr.of[java.sql.Date] ||
      tpe =:= TypeRepr.of[java.time.LocalDate] ||
      tpe =:= TypeRepr.of[java.sql.Timestamp] ||
      tpe =:= TypeRepr.of[java.time.Instant] ||
      tpe =:= TypeRepr.of[java.time.LocalDateTime]

  private def unsupportedLeaf(using q: Quotes)(tpe: q.reflect.TypeRepr): Nothing =
    import q.reflect.*
    report.errorAndAbort(
      s"Unsupported structural leaf type in SchemaConforms derivation: ${tpe.show}. Supported leaf types: $supportedLeafTypes. Supported container shapes: case classes, Option, List/Seq/Vector/Array/Set, and Map[atomic, _]."
    )
