package ctdc.internal

import ctdc.internal.TypeShape.*

/** Policy-aware structural comparison of two normalized shapes.
  *
  * Everything here is a pure function of its arguments. That is the point: the macro in
  * [[ctdc.ContractsCore.CompileTime]] only reflects two types into [[TypeShape]] and turns a report into a compile
  * error, so the part of contract checking that can actually be wrong is testable without invoking a compiler.
  */
private[ctdc] object ShapeDiff:

  /** A field the contract requires and the producer does not have. */
  final case class Missing(path: String, field: FieldShape)

  /** A field the producer has and the contract does not mention. */
  final case class Extra(path: String, name: String)

  /** A position where both sides have something, but not the same thing. */
  final case class Mismatch(path: String, expected: String, found: String)

  final case class Drift(missing: List[Missing], extra: List[Extra], mismatched: List[Mismatch]):
    def isEmpty: Boolean  = missing.isEmpty && extra.isEmpty && mismatched.isEmpty
    def nonEmpty: Boolean = !isEmpty

    def ++(other: Drift): Drift =
      Drift(missing ++ other.missing, extra ++ other.extra, mismatched ++ other.mismatched)

  object Drift:
    val empty: Drift = Drift(Nil, Nil, Nil)

  /** The drift message for a producer that does not conform, or None when it conforms.
    *
    * The policy is passed twice over, as `rules` and as `policyName`, because they answer different questions: the
    * rules decide the comparison, the name only appears in the message. Nothing here parses the name, so a policy can
    * never be mis-dispatched by how its type happens to print.
    */
  def report(
      policyName: String,
      outName: String,
      contractName: String,
      rules: ComparisonRules,
      out: TypeShape,
      contract: TypeShape
  ): Option[String] =
    val drift = diff(rules, out, contract)
    if drift.isEmpty then None else Some(renderReport(policyName, outName, contractName, drift))

  /** Every difference between producer and contract that the policy does not tolerate. */
  def diff(rules: ComparisonRules, out: TypeShape, contract: TypeShape): Drift =
    tolerate(rules.tolerance, compare(rules, "", out, contract))

  private def compare(rules: ComparisonRules, path: String, out: TypeShape, contract: TypeShape): Drift =
    (out, contract) match
      case (OptionalShape(o), OptionalShape(c)) => compare(rules, path, o, c)

      // Optionality that is nested inside a collection or map is load-bearing, so it is compared rather than
      // normalized away. Only field-level optionality is ignored, and that is unwrapped before it gets here.
      case (OptionalShape(o), c) => mismatchAt(path, render(c), render(OptionalShape(o)))
      case (o, OptionalShape(c)) => mismatchAt(path, render(OptionalShape(c)), render(o))

      case (PrimitiveShape(o), PrimitiveShape(c)) =>
        if o == c then Drift.empty else mismatchAt(path, c, o)

      case (SequenceShape(o), SequenceShape(c)) => compare(rules, s"$path[]", o, c)

      case (MapShape(keyOut, valueOut), MapShape(keyContract, valueContract)) =>
        val keyDrift =
          if rules.sameName(keyOut.name, keyContract.name) then Drift.empty
          else mismatchAt(s"$path<key>", keyContract.name, keyOut.name)
        keyDrift ++ compare(rules, s"$path<value>", valueOut, valueContract)

      case (StructShape(o), StructShape(c)) => compareStructs(rules, path, o, c)

      case (o, c) => mismatchAt(path, render(c), render(o))

  private def compareStructs(
      rules: ComparisonRules,
      path: String,
      out: List[FieldShape],
      contract: List[FieldShape]
  ): Drift =
    rules.matching match
      case FieldMatching.ByName        => compareByName(rules, path, out, contract)
      case FieldMatching.ByNameOrdered => compareByNameOrdered(rules, path, out, contract)
      case FieldMatching.ByPosition    => compareByPosition(rules, path, out, contract)

  private def compareByName(
      rules: ComparisonRules,
      path: String,
      out: List[FieldShape],
      contract: List[FieldShape]
  ): Drift =
    val outByName     = out.map(f => rules.normalize(f.name) -> f).toMap
    val contractNames = contract.map(f => rules.normalize(f.name)).toSet

    val missing = contract.collect {
      case f if !outByName.contains(rules.normalize(f.name)) => Missing(pathOf(path, f.name), f)
    }
    val extra = out.collect {
      case f if !contractNames.contains(rules.normalize(f.name)) => Extra(pathOf(path, f.name), f.name)
    }
    val nested = contract.foldLeft(Drift.empty) { (acc, f) =>
      outByName
        .get(rules.normalize(f.name))
        .fold(acc)(o => acc ++ compare(rules, pathOf(path, f.name), o.shape, f.shape))
    }

    Drift(missing, extra, Nil) ++ nested

  private def compareByNameOrdered(
      rules: ComparisonRules,
      path: String,
      out: List[FieldShape],
      contract: List[FieldShape]
  ): Drift =
    val paired = out.zip(contract).zipWithIndex

    val nameMismatches = paired.collect {
      case ((o, c), index) if !rules.sameName(o.name, c.name) =>
        Mismatch(pathOf(path, s"@$index(name)"), c.name, o.name)
    }
    val nested = paired.foldLeft(Drift.empty) { case (acc, ((o, c), _)) =>
      acc ++ compare(rules, pathOf(path, c.name), o.shape, c.shape)
    }

    nested ++ Drift(
      missing = contract.drop(paired.length).map(f => Missing(pathOf(path, f.name), f)),
      extra = out.drop(paired.length).map(f => Extra(pathOf(path, f.name), f.name)),
      mismatched = nameMismatches
    )

  private def compareByPosition(
      rules: ComparisonRules,
      path: String,
      out: List[FieldShape],
      contract: List[FieldShape]
  ): Drift =
    val paired = out.zip(contract).zipWithIndex

    val nested = paired.foldLeft(Drift.empty) { case (acc, ((o, c), index)) =>
      acc ++ compare(rules, pathOf(path, s"@$index"), o.shape, c.shape)
    }

    // Names are not compared at all here, so a count difference can only be reported at the first unpaired index.
    val boundary = pathOf(path, s"@${paired.length}")
    val tail = Drift(
      missing = contract.drop(paired.length).map(f => Missing(boundary, f)),
      extra = out.drop(paired.length).map(f => Extra(boundary, f.name)),
      mismatched = Nil
    )

    tail ++ nested

  private def tolerate(tolerance: Tolerance, drift: Drift): Drift = tolerance match
    case Tolerance.Strict => drift

    case Tolerance.Backward =>
      // A contract field the producer omits is only acceptable when the contract itself says the value may be absent.
      drift.copy(
        missing = drift.missing.filterNot(m => m.field.hasDefault || m.field.isOptional),
        extra = Nil
      )

    case Tolerance.Forward => drift.copy(missing = Nil)

    case Tolerance.Permissive => Drift.empty

  private def renderReport(policyName: String, outName: String, contractName: String, drift: Drift): String =
    val missing    = drift.missing.map(m => s"${m.path} : ${renderField(m.field)}").mkString(", ")
    val extra      = drift.extra.map(_.path).mkString(", ")
    val mismatched = drift.mismatched.map(m => s"${m.path} expected ${m.expected}, found ${m.found}").mkString("; ")

    s"""Compile-time contract drift (policy: $policyName).
       |Out: $outName vs Contract: $contractName
       |Missing attributes: $missing
       |Extra attributes: $extra
       |Mismatch attributes: $mismatched
       |""".stripMargin

  private def mismatchAt(path: String, expected: String, found: String): Drift =
    Drift(Nil, Nil, List(Mismatch(path, expected, found)))

  /** A child path under `base`, with no leading separator at the root. */
  private def pathOf(base: String, segment: String): String = s"$base.$segment".stripPrefix(".")
