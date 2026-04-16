# Industrial evidence pack

This file is deliberately separate from the artifact proof.

Purpose:
- mine `flowforge` for application-side usage, lessons, edge cases, and cautionary material
- keep motivation and industrial realism available
- avoid contaminating the paper with claims the clean artifact does not prove

## Source set used here

- `flowforge/README.md`
- `flowforge/modules/core/src/main/scala/com/flowforge/core/PipelineBuilder.scala`
- `flowforge/flowforge.g8/src/main/g8/src/main/scala/com/flowforge/app/PipelineApp.scala`
- `flowforge/flowforge.g8/src/main/g8/src/main/scala/com/flowforge/sample/advanced/MultiCloudDataLakePipeline.scala`
- `flowforge/docs/archive/design/GROUND_REALITY_REPORT_FULL.md`

## What FlowForge is good for in this paper

- motivation:
  why the late-drift problem matters in framework and platform design
- application-side realism:
  how typed builder APIs look in larger framework code
- caution:
  how easy it is to make a typed contract story look stronger than the actual execution path
- future direction:
  what a broader framework might add beyond the clean artifact

## What FlowForge is not good for in this paper

- closing artifact claims C1-C7
- proving industrial deployment scale
- proving incident reduction
- proving end-to-end compile-time enforcement across the actual data path

## Application-side usage patterns observed

### 1. Template app shows the main trap: typed sample path versus actual data path

Source:
- [PipelineApp.scala](../../../flowforge/flowforge.g8/src/main/g8/src/main/scala/com/flowforge/app/PipelineApp.scala)

Observed pattern:
- the typed source contract uses a sample `User(...)` reader for compile-time witness purposes
- the typed sink writer then writes `ds`, the dataset that was separately read earlier

Why this matters:
- the code demonstrates the typed builder surface
- but the typed witness path and the actual written dataset are not the same path
- this is exactly the kind of gap the clean `compile-time-data-contracts` repo avoids with end-to-end builder tests on the real DataFrame path

Safe paper use:
- motivation and design caution

Unsafe paper use:
- evidence that FlowForge already enforces the same guarantee on the actual runtime data path

### 2. Advanced sample repeats the same pattern at larger scale

Source:
- [MultiCloudDataLakePipeline.scala](../../../flowforge/flowforge.g8/src/main/g8/src/main/scala/com/flowforge/sample/advanced/MultiCloudDataLakePipeline.scala)

Observed pattern:
- `TypedSource(DataSource.memory(customers))` is paired with `_ => IO.pure(customers.head)` as the sample value
- the sink side logs a simplified write operation rather than validating and writing the actual transformed dataset through a fully checked engine path

Why this matters:
- the sample is useful for product demos and docs
- it is not strong enough to serve as proof of end-to-end enforcement

### 3. Core builder API carries the compile-time witness, but the comment itself admits runtime validation is still optional

Source:
- [PipelineBuilder.scala](../../../flowforge/modules/core/src/main/scala/com/flowforge/core/PipelineBuilder.scala)

Observed pattern:
- typed source and sink methods require `SchemaConforms`
- the sink comment says runtime validation “could be added here if needed”

Lesson:
- the design intent is aligned with the paper
- the actual framework implementation history still needs tighter builder-to-engine coupling to match the strongest marketing line

## What the ground-reality report already says

Source:
- [GROUND_REALITY_REPORT_FULL.md](../../../flowforge/docs/archive/design/GROUND_REALITY_REPORT_FULL.md)

High-signal findings:
- typed compile-time gates are not consistently enforced across the builder and engine boundary
- cloud connectors, Flink, Deequ, observability, and schema-evolution claims are only partial or absent
- docs and product claims are ahead of code in several areas

Paper consequence:
- use this report as an internal honesty check
- do not cite FlowForge as if it already proves the clean artifact’s guarantees

## What FlowForge teaches us

1. The product story is compelling.
   Compile-time contracts, typestate builders, and pure transforms give a strong systems narrative.

2. Application code can drift away from proof code very easily.
   Typed examples can still be disconnected from the real dataset path.

3. Contract proof is only one part of platform reality.
   Connectors, schema evolution, runtime observability, DQ, and operations still matter.

4. A clean artifact repo is worth the split.
   It gives the paper a stable proof surface that the larger framework repo could not provide yet.

## Motivation versus proof

| Material | Use as motivation? | Use as proof? | Notes |
|----------|--------------------|---------------|-------|
| FlowForge README positioning | yes | no | good for framing the problem and the intended framework direction |
| FlowForge typed builder signatures | yes | limited | useful to explain how framework APIs try to carry contract evidence |
| FlowForge template apps | yes | no | useful mainly as cautionary application-side examples |
| Ground reality report | yes | no | internal risk ledger, not external artifact evidence |
| Clean artifact repo tests and benchmarks | yes | yes | this is the actual proof base |

## Safe statements for the paper

- FlowForge informed the motivation and helped surface application-side risks and edge cases.
- The clean artifact was split out precisely because the larger framework repo mixed design intent, demos, and partially implemented platform features.
- The paper therefore evaluates the smaller artifact directly rather than claiming full-framework proof.

## Unsafe statements for the paper

- “FlowForge proves compile-time data contracts in production.”
- “The framework is deployed across teams and validates schemas end to end.”
- “The industrial framework already enforces contract safety across the real data path.”
- “The paper’s measurements or guarantees come from FlowForge.”

## If you want to close C4 later

You still need a separate safe pack with some combination of:

- sanitized architecture snapshots
- timeline of the clean-room split and why it was necessary
- specific non-proprietary examples of late-drift failures
- safe adoption notes or rollout stages
- metrics that are contractually safe to disclose

Until then, keep industrial value as context, lessons, and future work.
