# sharder documentation

The sharder library answers one question: given a key and a view of the world, which nodes handle
that key, in what order, and what happens when those nodes are unavailable. It computes routing
decisions and sequences ownership changes. It moves no data, elects no leader, opens no connection,
and speaks no wire protocol.

The library is designed and not yet implemented. Every document here describes the design, and
nothing here describes a running system.

## Reading paths

Each path names sections in the order they are read, and nothing on a path assumes a section further
down it.

### Evaluating the library

For a reader deciding whether the library is worth adopting. The path stops short of the
specification.

1. [`../README.md`](../README.md#capabilities). What the library places, spreads, sequences, and
   reports, and the five things it does not do.
2. [`design/00-overview.md`](design/00-overview.md#component-model). The seven components, the three
   of them an integrator implements, and the four hooks that default to doing nothing.
3. The walkthrough nearest the case at hand:
   [`#storage-node-placement`](design/00-overview.md#storage-node-placement),
   [`#tenant-cluster-routing`](design/00-overview.md#tenant-cluster-routing), or
   [`#cache-affinity-routing`](design/00-overview.md#cache-affinity-routing).
4. The worked topology document for that same case:
   [`#storage-cluster-example`](design/20-topology-format.md#storage-cluster-example),
   [`#tenant-routing-example`](design/20-topology-format.md#tenant-routing-example), or
   [`#cache-cluster-example`](design/20-topology-format.md#cache-cluster-example). Each is a
   complete document carrying the settings its case turns on, and stands in for the integration
   guide that does not exist yet.
5. [`design/00-overview.md`](design/00-overview.md#conflicting-requirements). The four places the
   three use cases pull against each other, and how configuration resolves each.
6. [`design/00-overview.md`](design/00-overview.md#out-of-scope). What has to exist around the
   library.
7. [`design/99-roadmap.md`](design/99-roadmap.md#release-v01). What a first release carries.

Two further sections belong to the decision to commit rather than to the decision to look:
[`design/99-roadmap.md`](design/99-roadmap.md#one-way-doors) names what a later release cannot
revisit, and [`design/90-open-questions.md`](design/90-open-questions.md) opens with a table saying
which questions block a first release.

### Implementing a port

1. [`design/05-glossary.md`](design/05-glossary.md). The glossary. Every term in the specification
   carries the meaning it is given there.
2. [`design/35-port-conventions.md`](design/35-port-conventions.md). What every port carries
   whatever the language: where its source sits, how it reads the conformance suite, the
   declaration it publishes, and what it is free to choose.
3. [`design/10-specification.md`](design/10-specification.md#conventions). The requirement keywords,
   the identifier scheme, the conformance surfaces, the prefix table, and the pseudocode notation.
4. [`design/10-specification.md`](design/10-specification.md#core-model). The shared types, the hash
   construction, the routing surface, and the visibility guarantees. Implement the hash first and
   verify it against the published SipHash vectors before anything else.
5. [`design/20-topology-format.md`](design/20-topology-format.md) and
   [`design/topology-v1.schema.json`](design/topology-v1.schema.json). The document format, the
   validation rules, and the canonical form.
6. [`design/10-specification.md`](design/10-specification.md#routing-keys-and-placement), then
   [`#replication-and-failover`](design/10-specification.md#replication-and-failover), then
   [`#topology-change-and-rebalancing`](design/10-specification.md#topology-change-and-rebalancing).
   The three sections in the order a port implements them.
7. [`design/30-conformance.md`](design/30-conformance.md#driver-contract), then
   [`#conformance-levels`](design/30-conformance.md#conformance-levels), then
   [`#declaring-conformance`](design/30-conformance.md#declaring-conformance). The driver contract,
   the levels and the surfaces, and the rule by which a port declares what it reached.
8. [`../conformance/README.md`](../conformance/README.md). The suite itself, and which vector files
   a new driver runs first.
9. [`design/40-java-binding.md`](design/40-java-binding.md). One worked rendering of the
   language-neutral specification into a language, including the shapes it chose where the
   specification named none.

[`OQ-02`](design/90-open-questions.md#oq-02-owning-surfaces-for-three-requirements) names the one
place where the specification does not yet say enough for a port to proceed without choosing.

### Writing a provider or a strategy

1. [`design/10-specification.md`](design/10-specification.md#topology-provider). `CORE-080` to
   `CORE-101`, which are the provider contract: the two capabilities, the sink, the subscription,
   the adaptation of a provider offering one model, and the conditional fetch.
2. [`design/adr/0004-topology-provider-contract.md`](design/adr/0004-topology-provider-contract.md).
   Why the contract carries both models, and an adapter sketch against a key-value store.
3. [`10-specification.md`](design/10-specification.md#topology-loading-and-snapshot-lifecycle).
   The load pipeline, the acceptance outcomes for an invalid document and for an older epoch,
   snapshot installation, and retention.
4. [`design/10-specification.md`](design/10-specification.md#provider-failure-behaviour). What the
   library does when a provider is unreachable, and when the snapshot in force becomes stale.
5. [`design/10-specification.md`](design/10-specification.md#prepared-placement) for `CORE-010`, the
   placement extension point, and
   [`#thread-ownership`](design/10-specification.md#thread-ownership) for `CORE-063`, the rule that
   no lock is held across a call into one.
6. [`design/10-specification.md`](design/10-specification.md#conformance-surfaces). What a strategy
   surface is, and what exposing one commits an implementation to.
7. [`design/20-topology-format.md`](design/20-topology-format.md#strategy-extension). How a
   registered strategy of an implementation's own is validated, and how far it travels across ports.
8. [`design/adr/0002-placement-strategy-set.md`](design/adr/0002-placement-strategy-set.md). Which
   strategies the core set already covers, and what each is for.

### Operating a cluster

No operator guide exists yet. The material an operator needs is in the specification and the format
document, in these sections.

1. [`design/20-topology-format.md`](design/20-topology-format.md#document-structure), then
   [`#validation-rules`](design/20-topology-format.md#validation-rules). The members of a topology
   document, and the rules a document satisfies to load at all.
2. A worked document to start from:
   [`#storage-cluster-example`](design/20-topology-format.md#storage-cluster-example),
   [`#tenant-routing-example`](design/20-topology-format.md#tenant-routing-example), or
   [`#cache-cluster-example`](design/20-topology-format.md#cache-cluster-example).
3. [`design/20-topology-format.md`](design/20-topology-format.md#versioning-and-compatibility). What
   may change between two documents without breaking a reader.
4. [`design/10-specification.md`](design/10-specification.md#required-metrics), then
   [`#required-events`](design/10-specification.md#required-events), then
   [`#skew-detection`](design/10-specification.md#skew-detection). The metrics and their label sets,
   the events, and hot-shard and key-skew detection.
5. [`design/10-specification.md`](design/10-specification.md#explain-api). The explain record, which
   answers why one key routed where it did.
6. [`design/10-specification.md`](design/10-specification.md#configuration-surface). Every setting
   and its default.
7. [`design/10-specification.md`](design/10-specification.md#condition-table). The closed set of
   conditions and the response each expects, including the ones that call for operator action.
8. [`design/10-specification.md`](design/10-specification.md#topology-disclosure). What a routing
   decision discloses to whoever can read it, and
   [`#threat-model`](design/10-specification.md#threat-model) for what the library declines to
   defend against.

### Changing a document or the suite

The contribution that is possible today. A change to a document, to the specification, or to the
conformance suite through its generator.

1. [`../CONTRIBUTING.md`](../CONTRIBUTING.md). The whole procedure, assembled: what is fixed, how a
   document change is made, how a specification change reaches the suite, and how the suite is
   regenerated.
2. [`maintain/style.md`](maintain/style.md). Binding on every Markdown file in the repository.
3. [`design/10-specification.md`](design/10-specification.md#requirement-identifiers), then
   [`#withdrawn-identifiers`](design/10-specification.md#withdrawn-identifiers). Why an identifier
   is permanent, and what happens to one whose behaviour the specification stops stating.
4. [`../conformance/generator/README.md`](../conformance/generator/README.md). The reference
   implementation that computes every expected value, and what `run.sh` verifies before it generates
   anything.
5. [`design/adr/`](design/adr/). The reasoning behind any decision a change would reopen.

### Contributing to the Java implementation

The port is under way and reaches the `hash` and `place` conformance levels.
[`design/40-java-binding.md`](design/40-java-binding.md) is the design it renders, and its opening
status line says what has and has not been written. The port's directory is
[`../ports/java/`](../ports/java/), whose README says how to build it and what is written, and
[`design/35-port-conventions.md`](design/35-port-conventions.md) states what it carries that every
other port carries too.

1. [`design/40-java-binding.md`](design/40-java-binding.md#artifacts-and-modules). The Gradle
   projects, what each carries, and what each split lets a consumer avoid.
2. [`design/40-java-binding.md`](design/40-java-binding.md#public-interface-set). The public types,
   in the order the specification introduces them.
3. [`design/40-java-binding.md`](design/40-java-binding.md#integer-widths). The unsigned discipline
   and the build check that is to enforce it.
4. [`design/40-java-binding.md`](design/40-java-binding.md#conformance-harness). How the vectors
   reach JUnit.
5. [`design/40-java-binding.md`](design/40-java-binding.md#build-and-quality-gates). The `check`
   task, which of its gates fails a build, and which reports.
6. [`../CONTRIBUTING.md`](../CONTRIBUTING.md) and [`maintain/style.md`](maintain/style.md). Binding
   on the change whatever it touches.

## Document index

| Path | Contents |
|---|---|
| [`design/00-overview.md`](design/00-overview.md) | architecture: the component model, the two data flows, three use-case walkthroughs, and scope |
| [`design/05-glossary.md`](design/05-glossary.md) | the vocabulary every other document uses, defined once |
| [`design/10-specification.md`](design/10-specification.md) | the normative specification, one numbered requirement per rule, grouped by prefix |
| [`design/20-topology-format.md`](design/20-topology-format.md) | the topology document format, validation, versioning, worked examples |
| [`design/topology-v1.schema.json`](design/topology-v1.schema.json) | the JSON Schema for format version 1 |
| [`design/30-conformance.md`](design/30-conformance.md) | the conformance suite design, driver contract, levels, coverage |
| [`design/35-port-conventions.md`](design/35-port-conventions.md) | what every port carries: layout, suite access, the declaration, and the free surfaces |
| [`design/40-java-binding.md`](design/40-java-binding.md) | the Java rendering: artifacts, types, thread safety, harness, build gates |
| [`design/90-open-questions.md`](design/90-open-questions.md) | every unresolved question, its default, and the evidence that settles it |
| [`design/99-roadmap.md`](design/99-roadmap.md) | release staging, one-way doors, and what the suite makes easier or harder |
| [`design/adr/`](design/adr/) | the decision records, the only documents here where argument is the content |
| [`maintain/style.md`](maintain/style.md) | the register, emphasis, punctuation, and terminology rules |
| [`../CONTRIBUTING.md`](../CONTRIBUTING.md) | the constraints on every change, and how a document or the suite is changed |
| [`../conformance/README.md`](../conformance/README.md) | the suite tree, how to run it, and how to regenerate it |
| [`../ports/README.md`](../ports/README.md) | the ports, their directories, and what each has reached |
| [`../conformance/generator/README.md`](../conformance/generator/README.md) | the reference implementation, its scripts, and what each verifies |
| [`../bench/README.md`](../bench/README.md) | the standalone measurement sources a decision record cites |

## Decision records

| Subject | Records |
|---|---|
| hashing and determinism | [0001](design/adr/0001-hash-function-and-key-encoding.md), [0030](design/adr/0030-unsigned-integer-discipline.md), [0040](design/adr/0040-cryptographic-primitive-sourcing-policy.md) |
| placement and weights | [0002](design/adr/0002-placement-strategy-set.md), [0003](design/adr/0003-integer-node-weights.md), [0010](design/adr/0010-shard-identifier-naming.md), [0011](design/adr/0011-derived-assignment-virtual-nodes.md), [0013](design/adr/0013-range-bounds-over-routing-key.md), [0039](design/adr/0039-placement-cost-model-and-warning-thresholds.md), [0043](design/adr/0043-assignment-mode-defaults.md), [0054](design/adr/0054-range-strategy-withdrawal.md), [0055](design/adr/0055-slot-derived-assignment-withdrawal.md), [0071](design/adr/0071-candidate-ordering-over-a-placement-ring.md), [0072](design/adr/0072-preparation-cost-reported-before-preparation.md) |
| topology model and format | [0004](design/adr/0004-topology-provider-contract.md), [0005](design/adr/0005-epoch-and-version-semantics.md), [0006](design/adr/0006-failure-domain-model.md), [0008](design/adr/0008-json-canonical-serialisation.md), [0009](design/adr/0009-override-composition.md), [0038](design/adr/0038-provider-contract-in-the-specification.md), [0048](design/adr/0048-conditional-fetch-in-the-provider-contract.md) |
| replication and failover | [0007](design/adr/0007-administrative-state-and-health-state.md), [0012](design/adr/0012-balance-bound-tolerances.md), [0014](design/adr/0014-read-affinity-as-a-separate-call.md), [0015](design/adr/0015-spread-degradation-algorithm.md), [0016](design/adr/0016-node-health-state-machine.md), [0017](design/adr/0017-failover-depth-and-substitution.md), [0036](design/adr/0036-spread-relaxation-ladder-direction.md), [0042](design/adr/0042-domain-path-scope.md), [0045](design/adr/0045-attempt-limit-resolution-order.md), [0046](design/adr/0046-bounded-routing-decision-surface.md), [0063](design/adr/0063-decision-api-surface-boundaries.md), [0069](design/adr/0069-per-level-occupancy-cap.md), [0070](design/adr/0070-spread-stage-feasibility-from-a-domain-count.md), [0075](design/adr/0075-affinity-path-length-refusal.md) |
| change, handoff, and fencing | [0018](design/adr/0018-concurrent-ownership-during-handoff.md), [0019](design/adr/0019-handoff-coordination-and-recovery.md), [0020](design/adr/0020-recipient-side-fencing-verdicts.md), [0021](design/adr/0021-migration-backpressure-control.md), [0022](design/adr/0022-range-split-lineage.md), [0023](design/adr/0023-snapshot-visibility-and-thread-ownership.md), [0044](design/adr/0044-ownership-under-an-identity-mismatch.md), [0047](design/adr/0047-redirect-walk-under-the-retry-budget.md), [0049](design/adr/0049-ownership-delta-computed-on-demand.md), [0050](design/adr/0050-plan-rebase-onto-a-newer-snapshot.md), [0051](design/adr/0051-recovery-from-an-undetermined-cutover.md), [0056](design/adr/0056-advisory-cutover-withdrawal.md), [0057](design/adr/0057-step-budget-adjustment-withdrawal.md), [0073](design/adr/0073-prepared-placement-retention-multiple.md), [0074](design/adr/0074-quiesce-lease-margin-and-clock-assumption.md) |
| cross-cutting contracts | [0024](design/adr/0024-closed-numbered-error-taxonomy.md), [0025](design/adr/0025-observability-contract-and-explain-record.md), [0026](design/adr/0026-configuration-defaults-and-locality.md), [0027](design/adr/0027-hash-seed-exposure-and-tenancy.md), [0060](design/adr/0060-node-label-cardinality-ceiling.md), [0076](design/adr/0076-ordered-rows-in-a-precedence-table.md) |
| Java binding | [0028](design/adr/0028-java-module-and-artifact-layout.md), [0029](design/adr/0029-exception-idiom-for-the-taxonomy.md), [0031](design/adr/0031-jdk-baseline.md), [0032](design/adr/0032-dependency-free-json-and-canonicalisation.md), [0033](design/adr/0033-opaque-identifier-value-types.md), [0034](design/adr/0034-lazy-candidate-traversal-surface.md), [0041](design/adr/0041-exact-product-comparison-surface.md), [0081](design/adr/0081-single-java-module.md) |
| conformance | [0035](design/adr/0035-manifest-driven-conformance-harness.md), [0037](design/adr/0037-specification-defect-repairs.md), [0052](design/adr/0052-conformance-level-partition.md), [0058](design/adr/0058-conformance-surfaces.md), [0059](design/adr/0059-place-conformance-level.md), [0061](design/adr/0061-suite-revision-identifier.md), [0064](design/adr/0064-hash-verification-before-generation.md), [0065](design/adr/0065-level-coverage-inside-surface-boundaries.md), [0077](design/adr/0077-scale-conformance-level.md), [0078](design/adr/0078-observability-contract-as-data.md) |
| requirement identifiers | [0053](design/adr/0053-requirement-withdrawal-convention.md) |
| ports and declarations | [0079](design/adr/0079-repository-layout-for-multiple-ports.md), [0080](design/adr/0080-conformance-declaration-format.md), [0082](design/adr/0082-continuous-integration-and-dependency-updates.md) |
| release staging | [0083](design/adr/0083-publication-as-the-last-stage.md) |
| documentation | [0062](design/adr/0062-documentation-style-check-as-a-warning.md) |
