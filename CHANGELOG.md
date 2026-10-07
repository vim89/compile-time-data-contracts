# Changelog

## Unreleased

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
- `ComparisonRules` gained a fourth axis, `FieldOptionality`, with three cases:
  `MustAgree`, `AllowStricter`, and `Ignored`. All three matchers read it through one
  shared check, so a policy means the same thing whether fields are matched by name or by
  position.

What did not change:

- Runtime pin behaviour. The runtime comparator still compares `ArrayType.containsNull`
  and `MapType.valueContainsNull` and still does not compare `StructField.nullable`.
  That is deliberate and is now explicit in the API: the runtime path sets the axis
  through `ComparisonRules.ignoringFieldOptionality`.

Why the runtime half is not changing with it: Spark's file readers return
`nullable = true` for every field of every format that does not record the claim, so a
read schema cannot be distinguished from a schema whose producer actually stated that
nulls are possible. Every candidate rule for the runtime check was tried and eliminated.
`MustAgree` fails every pipeline that reads CSV or JSON. `AllowStricter` rejects exactly
the common case. Inverting the direction leaves a check that can never fire. The
information is gone before a `DataFrame` exists, so the carrier is checked where it is
still stated and not where it is not.

How to migrate:

- If the drift the compiler now reports is real, change the producer or the contract so
  the two agree on `Option`.
- If the contract intends to accept either, make the contract field `Option[A]` and use
  `Backward` or `Forward` in the direction that matches the boundary.
- If the check is not wanted at this boundary, `Full` accepts all structural
  combinations, as before.

The compile error names the field path and the two optionalities, so the failing pair is
identifiable from the message without reading the derivation.
