package ctdc.internal

import ctdc.internal.TypeShape._

/**
 * Policy-aware comparison of two normalized shapes.
 *
 * Everything here is a pure function of its arguments. That is the point: each Scala version's macro only
 * turns the two types into [[TypeShape]]s and turns a report into a compile error, so the part of contract
 * checking that can actually be wrong is testable without invoking a compiler, and both compilers answer
 * alike because they answer through the same code.
 */
object ShapeDiff {

  /** A field the contract requires and the producer does not have. */
  final case class Missing(path: String, field: FieldShape)

  /** A field the producer has and the contract does not mention. */
  final case class Extra(path: String, name: String)

  /** A position where both sides have something, but not the same thing. */
  final case class Mismatch(
    path: String,
    expected: String,
    found: String)

  /** What a policy still considers drift, after its own tolerances are applied. */
  final case class Drift(
    missing: List[Missing],
    extra: List[Extra],
    mismatched: List[Mismatch]) {
    def isEmpty: Boolean  = missing.isEmpty && extra.isEmpty && mismatched.isEmpty
    def nonEmpty: Boolean = !isEmpty

    def ++(other: Drift): Drift =
      Drift(missing ++ other.missing, extra ++ other.extra, mismatched ++ other.mismatched)
  }

  object Drift {
    val empty: Drift = Drift(Nil, Nil, Nil)
  }

  /**
   * The three names a drift report prints, carried together because none of them is read.
   *
   * They were three adjacent `String` parameters on [[report]], beside a fourth that was also a `String`.
   * Two of them, `out` and `contract`, name the two sides of the comparison, so passing them the wrong way
   * round reverses what the whole message says while still typechecking and still reading plausibly. Nothing
   * in the engine can catch that, because nothing in the engine looks at them. Grouping them puts the two
   * sides under their own names at the one place a caller writes them.
   */
  final case class ReportNames(
    policy: String,
    out: String,
    contract: String)

  /**
   * The compile error for the drift between `out` and `contract`, or None when the producer conforms.
   *
   * The policy is passed twice over, as `rules` and as `names.policy`, because they answer different
   * questions: the rules decide the comparison, the name only appears in the message. Nothing here parses the
   * name, so a policy can never be mis-dispatched by how its type happens to print.
   */
  def report(
    names: ReportNames,
    rules: ComparisonRules,
    out: TypeShape,
    contract: TypeShape,
  ): Option[String] = {
    val drift = diff(rules, out, contract)
    if (drift.isEmpty) None else Some(renderReport(names, drift))
  }

  /** Every difference between producer and contract that the policy does not tolerate. */
  def diff(
    rules: ComparisonRules,
    out: TypeShape,
    contract: TypeShape,
  ): Drift = tolerate(rules.tolerance, compare(rules, "", out, contract))

  private def compare(
    rules: ComparisonRules,
    path: String,
    out: TypeShape,
    contract: TypeShape,
  ): Drift =
    (out, contract) match {
      case (OptionalShape(o), OptionalShape(c)) => compare(rules, path, o, c)

      // Optionality nested inside a collection or a map is load-bearing, so it is compared rather than
      // normalized away. Field-level optionality is unwrapped before it gets here and is handled by
      // `optionalityDrift`; these two cases are the same question about the other two carriers.
      case (OptionalShape(o), c) => nestedOptionalityDrift(rules, path, outOptional = true, o, c)
      case (o, OptionalShape(c)) => nestedOptionalityDrift(rules, path, outOptional = false, o, c)

      case (PrimitiveShape(o), PrimitiveShape(c)) =>
        if (o == c) Drift.empty else mismatchAt(path, c, o)

      case (SequenceShape(o), SequenceShape(c)) => compare(rules, s"$path[]", o, c)

      case (MapShape(keyOut, valueOut), MapShape(keyContract, valueContract)) =>
        val keyDrift =
          if (rules.sameName(keyOut.name, keyContract.name)) Drift.empty
          else mismatchAt(s"$path<key>", keyContract.name, keyOut.name)
        keyDrift ++ compare(rules, s"$path<value>", valueOut, valueContract)

      case (StructShape(o), StructShape(c)) => compareStructs(rules, path, o, c)

      case (o, c) => mismatchAt(path, render(c), render(o))
    }

  private def compareStructs(
    rules: ComparisonRules,
    path: String,
    out: List[FieldShape],
    contract: List[FieldShape],
  ): Drift = rules.matching match {
    case FieldMatching.ByName        => compareByName(rules, path, out, contract)
    case FieldMatching.ByNameOrdered => compareByNameOrdered(rules, path, out, contract)
    case FieldMatching.ByPosition    => compareByPosition(rules, path, out, contract)
  }

  private def compareByName(
    rules: ComparisonRules,
    path: String,
    out: List[FieldShape],
    contract: List[FieldShape],
  ): Drift = {
    val collisions = collisionDrift(rules, path, out) ++ collisionDrift(rules, path, contract)
    // Collisions first, and nothing else when there are any. The index below is keyed by normalized name,
    // so two fields that normalize alike make it lossy: one of them disappears and the comparison answers
    // about whichever survived. The runtime comparator refuses such a struct outright, so this has to refuse
    // it too; otherwise a producer passes the macro and then fails the pin the macro was supposed to prove.
    if (collisions.nonEmpty) collisions else matchByName(rules, path, out, contract)
  }

  private def matchByName(
    rules: ComparisonRules,
    path: String,
    out: List[FieldShape],
    contract: List[FieldShape],
  ): Drift = {
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
        .fold(acc) { o =>
          val at = pathOf(path, f.name)
          acc ++ optionalityDrift(rules, at, o, f) ++ compare(rules, at, o.shape, f.shape)
        }
    }

    Drift(missing, extra, Nil) ++ nested
  }

  /**
   * Drift from one side carrying two fields whose names are the same name under the policy's casing.
   *
   * Only by-name matching asks this question, because only it builds an index keyed by the normalized name.
   * The ordered and positional matchings pair by position and never collapse two fields into one slot, so a
   * case-colliding pair is a type difference to them rather than a lost field.
   *
   * Reported as a mismatch at the colliding name rather than as an extra field, because neither of the two is
   * the surplus one: the struct is unusable under this policy whichever of them a reader would have kept.
   */
  private def collisionDrift(
    rules: ComparisonRules,
    path: String,
    fields: List[FieldShape],
  ): Drift = {
    val collisions = fields.groupBy(f => rules.normalize(f.name)).toList.collect {
      case (normalized, colliding) if colliding.lengthCompare(1) > 0 =>
        Mismatch(
          pathOf(path, normalized),
          "one field with this name under this policy's name matching",
          s"${colliding.length} fields: ${colliding.map(_.name).mkString(", ")}",
        )
    }
    // Sorted because `groupBy` returns a Map, and an error message whose lines move between compiles of
    // unchanged sources is not one anybody can diff.
    Drift(Nil, Nil, collisions.sortBy(_.path))
  }

  private def compareByNameOrdered(
    rules: ComparisonRules,
    path: String,
    out: List[FieldShape],
    contract: List[FieldShape],
  ): Drift = {
    val paired = out.zip(contract).zipWithIndex

    val nameMismatches = paired.collect {
      case ((o, c), index) if !rules.sameName(o.name, c.name) =>
        Mismatch(pathOf(path, s"@$index(name)"), c.name, o.name)
    }
    val nested = paired.foldLeft(Drift.empty) {
      case (acc, ((o, c), _)) =>
        val at = pathOf(path, c.name)
        acc ++ optionalityDrift(rules, at, o, c) ++ compare(rules, at, o.shape, c.shape)
    }

    nested ++ Drift(
      missing = contract.drop(paired.length).map(f => Missing(pathOf(path, f.name), f)),
      extra = out.drop(paired.length).map(f => Extra(pathOf(path, f.name), f.name)),
      mismatched = nameMismatches,
    )
  }

  private def compareByPosition(
    rules: ComparisonRules,
    path: String,
    out: List[FieldShape],
    contract: List[FieldShape],
  ): Drift = {
    val paired = out.zip(contract).zipWithIndex

    // The paired prefix is compared even when the counts differ, so one compile reports every problem
    // rather than only the count and then the next one on the following compile.
    val nested = paired.foldLeft(Drift.empty) {
      case (acc, ((o, c), index)) =>
        val at = pathOf(path, s"@$index")
        acc ++ optionalityDrift(rules, at, o, c) ++ compare(rules, at, o.shape, c.shape)
    }

    // Names are not compared at all here, so a count difference can only be reported at the first
    // unpaired index.
    val boundary = pathOf(path, s"@${paired.length}")
    val tail = Drift(
      missing = contract.drop(paired.length).map(f => Missing(boundary, f)),
      extra = out.drop(paired.length).map(f => Extra(boundary, f.name)),
      mismatched = Nil,
    )

    tail ++ nested
  }

  /** Drop the differences the policy tolerates. */
  private def tolerate(tolerance: Tolerance, drift: Drift): Drift = tolerance match {
    case Tolerance.Strict => drift

    case Tolerance.Backward =>
      // A contract field the producer omits is only acceptable when the contract itself says the value may
      // be absent.
      drift.copy(
        missing = drift.missing.filterNot(m => m.field.hasDefault || m.field.isOptional),
        extra = Nil,
      )

    case Tolerance.Forward => drift.copy(missing = Nil)

    case Tolerance.Permissive => Drift.empty
  }

  private def renderReport(names: ReportNames, drift: Drift): String = {
    val missing = drift.missing.map(m => s"${m.path} : ${renderField(m.field)}").mkString(", ")
    val extra   = drift.extra.map(_.path).mkString(", ")
    val mismatched =
      drift.mismatched.map(m => s"${m.path} expected ${m.expected}, found ${m.found}").mkString("; ")

    s"""Compile-time contract drift (policy: ${names.policy}).
       |Out: ${names.out} vs Contract: ${names.contract}
       |Missing attributes: $missing
       |Extra attributes: $extra
       |Mismatch attributes: $mismatched
       |""".stripMargin
  }

  private def mismatchAt(
    path: String,
    expected: String,
    found: String,
  ): Drift = Drift(Nil, Nil, List(Mismatch(path, expected, found)))

  /**
   * Drift from a field that disagrees with the contract about whether its value can be absent.
   *
   * Reported separately from the shape mismatch at the same path, because the two say different things to
   * whoever reads the error: the shape says the value is of the wrong type, this says the value may not be
   * there at all. A reader who sees only "expected String, found String" has been told nothing.
   */
  private def optionalityDrift(
    rules: ComparisonRules,
    path: String,
    out: FieldShape,
    contract: FieldShape,
  ): Drift =
    if (rules.optionalityConforms(out.isOptional, contract.isOptional)) Drift.empty
    else mismatchAt(path, describeOptionality(contract.isOptional), describeOptionality(out.isOptional))

  /**
   * Drift from a sequence element or a map value where one side can be absent and the other cannot.
   *
   * These are the other two carriers of optionality, and they read the same [[ComparisonRules.optionality]]
   * axis the field carrier reads. They used to be compared strictly here whatever the policy, which made
   * `Backward` say "the producer may be stricter" about a field and "the producer must agree" about a
   * sequence element, a split neither name states and nothing argues for.
   *
   * The two inner shapes are compared whether or not the carrier difference is tolerated, because tolerating
   * a carrier is not tolerating a type change underneath it.
   */
  private def nestedOptionalityDrift(
    rules: ComparisonRules,
    path: String,
    outOptional: Boolean,
    out: TypeShape,
    contract: TypeShape,
  ): Drift = {
    val carrier =
      if (rules.optionalityConforms(outOptional, !outOptional)) Drift.empty
      else if (outOptional) mismatchAt(path, render(contract), render(OptionalShape(out)))
      else mismatchAt(path, render(OptionalShape(contract)), render(out))

    carrier ++ compare(rules, path, out, contract)
  }

  private def describeOptionality(optional: Boolean): String =
    if (optional) "an optional field" else "a required field"

  /** A child path under `base`, with no leading separator at the root. */
  private def pathOf(base: String, segment: String): String = s"$base.$segment".stripPrefix(".")
}
