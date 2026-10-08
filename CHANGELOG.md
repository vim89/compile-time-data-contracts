# Changelog

## 0.2.0

A new version rather than a re-publish of `0.1.0`: the changes below are breaking in both
directions, and an already-resolved artifact must keep meaning what it meant when it was
resolved.

### Breaking at compile time

Field-level optionality is now compared under every policy except `Full`. Code that
compiled against `v0.1.0` can fail to compile after this change.

What changed:

- The six `Exact*` policies now reject a pair where the producer and the contract
  disagree on `StructField` optionality, that is where one side is `Option[A]` and the
  other is `A`. Before this change the carrier was not read at all and such a pair
  compiled.
- `Backward` and `Forward` now reject the relaxing direction. A producer may promise more
  than the contract asks and never less, so a required contract field met by an optional
  producer field is drift.
- `ComparisonRules` gained a fourth axis, `Optionality`, with three cases: `MustAgree`,
  `AllowStricter`, and `Ignored`. It governs all three carriers of optionality, that is
  `StructField.nullable`, `ArrayType.containsNull` and `MapType.valueContainsNull`. Every
  matcher and every carrier reads it through one shared check, so a policy means the same
  thing whether fields are matched by name or by position and whether the carrier sits on
  a field, a sequence element or a map value.
- `Backward` and `Forward` now accept a producer whose sequence elements or map values are
  required where the contract allows them to be absent. Those two carriers used to be
  compared strictly whatever the policy, so `Backward` meant "the producer may be
  stricter" about a field and "the producer must agree" about an element.

### Changed at runtime

The runtime pin no longer compares any of the three carriers. It used to compare
`ArrayType.containsNull` and `MapType.valueContainsNull` while ignoring
`StructField.nullable`, which rejected valid data: reading `{"tags":["a","b"]}` as
`case class Nested(tags: List[String])` failed with a schema mismatch, because the JSON
reader returns `containsNull = true` for every array it is asked for.

Why all three go together: Spark's file readers return the permissive value for each
carrier on every format that does not record the claim, so a read schema cannot be
distinguished from a schema whose producer actually stated that nulls are possible. Every
candidate rule was tried and eliminated. `MustAgree` fails every pipeline that reads CSV
or JSON. `AllowStricter` rejects exactly the common case. Inverting the direction leaves
a check that can never fire. The information is gone before a `DataFrame` exists, so the
carriers are checked where they are still stated, in the macro against the Scala types,
and not where they are not.

- `SparkCore.SchemaCheck.assertNoForbiddenNulls[C]` is new, and is what replaces the
  dropped comparison rather than leaving it dropped. It asks the carriers' question of the
  rows: for every position the contract says is always present, is a value actually
  absent. One pass over the frame however deep the contract is, and opt-in because of that
  cost. A null a reader produced is drift; a bit a reader defaulted is not.
- `ComparisonRules.ignoringFieldOptionality` is now `ignoringOptionality`, and drops all
  three carriers rather than one.

How to migrate:

- If the drift the compiler now reports is real, change the producer or the contract so
  the two agree on `Option`.
- If the contract intends to accept either, make the contract field `Option[A]` and use
  `Backward` or `Forward` in the direction that matches the boundary.
- If the check is not wanted at this boundary, `Full` accepts all structural
  combinations, as before.

The compile error names the field path and the two optionalities, so the failing pair is
identifiable from the message without reading the derivation.
