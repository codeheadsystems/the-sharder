# sharder documentation

The sharder library answers one question: given a key and a view of the world, which nodes handle
that key, in what order, and what happens when those nodes are unavailable. It computes routing
decisions and sequences ownership changes. It moves no data, elects no leader, opens no connection,
and speaks no wire protocol.

The library is designed and not yet implemented. Every document here describes the design, and
nothing here describes a running system.

## Reading paths

### Evaluating the library

1. [`design/00-overview.md`](design/00-overview.md). The architecture, the glossary the rest of the
   corpus uses, the component model, and a walkthrough of each of the three use cases.
2. [`design/00-overview.md`](design/00-overview.md#out-of-scope). What the library does not do, and
   what has to exist around it.
3. [`design/00-overview.md`](design/00-overview.md#conflicting-requirements). The four places the
   three use cases pull against each other, and how configuration resolves each.
4. [`design/20-topology-format.md`](design/20-topology-format.md#storage-cluster-example). Three
   worked topology documents, one per use case.
5. [`design/99-roadmap.md`](design/99-roadmap.md). What a first release carries and which decisions
   cannot be revisited afterwards.
6. [`design/90-open-questions.md`](design/90-open-questions.md). Everything unresolved, with the
   default in force.

### Implementing a port

1. [`design/00-overview.md`](design/00-overview.md#glossary). The glossary. Every term in the
   specification carries the meaning it is given there.
2. [`design/10-specification.md`](design/10-specification.md#conventions). The requirement keywords,
   the identifier scheme, the prefix table, and the pseudocode notation.
3. [`design/10-specification.md`](design/10-specification.md#core-model). The shared types, the hash
   construction, the routing surface, and the visibility guarantees. Implement the hash first and
   verify it against the published SipHash vectors before anything else.
4. [`design/20-topology-format.md`](design/20-topology-format.md) and
   [`design/topology-v1.schema.json`](design/topology-v1.schema.json). The document format, the
   validation rules, and the canonical form.
5. [`design/10-specification.md`](design/10-specification.md#routing-keys-and-placement), then
   [`#replication-and-failover`](design/10-specification.md#replication-and-failover), then
   [`#topology-change-and-rebalancing`](design/10-specification.md#topology-change-and-rebalancing).
   The three sections in the order a port implements them.
6. [`design/30-conformance.md`](design/30-conformance.md#driver-contract). The driver contract, the
   conformance levels, and the rule by which a port declares conformance.
7. [`../conformance/README.md`](../conformance/README.md). The suite itself, and which vector files
   a new driver runs first.
8. [`design/40-java-binding.md`](design/40-java-binding.md). One worked rendering of the
   language-neutral specification into a language, including the shapes it chose where the
   specification named none.

`OQ-02` in [`design/90-open-questions.md`](design/90-open-questions.md) names the one place where
the specification does not yet say enough for a port to proceed without choosing.

### Writing a provider or a strategy

1. [`design/10-specification.md`](design/10-specification.md#topology-provider). `CORE-080` to
   `CORE-101`, which are the provider contract: the two capabilities, the sink, the subscription,
   the adaptation of a provider offering one model, and the failure behaviour.
2. [`design/adr/0004-topology-provider-contract.md`](design/adr/0004-topology-provider-contract.md).
   Why the contract carries both models, and an adapter sketch against a key-value store.
3. [`design/10-specification.md`](design/10-specification.md#topology-change-and-rebalancing). The
   load pipeline, the acceptance outcomes, and what happens when a provider is unreachable, delivers
   an invalid document, or delivers an older epoch.
4. [`design/10-specification.md`](design/10-specification.md#core-model). `CORE-010` for the
   placement extension point, and `CORE-063` for the rule that no lock is held across a call into
   one.
5. [`design/20-topology-format.md`](design/20-topology-format.md#strategy-extension). How a
   registered strategy of an implementation's own is validated, and how far it travels across ports.
6. [`design/adr/0002-placement-strategy-set.md`](design/adr/0002-placement-strategy-set.md). Which
   strategies the core set already covers, and what each is for.

### Operating a cluster

No operator guide exists yet. The material an operator needs is in the specification and the format
document, in these sections.

1. [`design/20-topology-format.md`](design/20-topology-format.md). Authoring a topology document,
   and the validation rules a document has to satisfy.
2. [`design/20-topology-format.md`](design/20-topology-format.md#versioning-and-compatibility). What
   may change between two documents without breaking a reader.
3. [`design/10-specification.md`](design/10-specification.md#observability). The metrics, their
   label sets, the events, hot-shard and key-skew detection, and the explain record that answers why
   a key routed where it did.
4. [`design/10-specification.md`](design/10-specification.md#configuration-surface). Every setting
   and its default.
5. [`design/10-specification.md`](design/10-specification.md#error-taxonomy). The sixteen conditions
   and the response each expects, including the ones that call for operator action.
6. [`design/10-specification.md`](design/10-specification.md#security-and-multi-tenancy). What the
   library declines to defend against, and what a routing decision discloses to whoever can read it.

### Contributing to the Java implementation

1. [`design/40-java-binding.md`](design/40-java-binding.md#artifacts-and-modules). The eight Gradle
   projects, what each carries, and what each split lets a consumer avoid.
2. [`design/40-java-binding.md`](design/40-java-binding.md#public-interface-set). The public types,
   in the order the specification introduces them.
3. [`design/40-java-binding.md`](design/40-java-binding.md#integer-widths). The unsigned discipline
   and the build check that enforces it.
4. [`design/40-java-binding.md`](design/40-java-binding.md#conformance-harness). How the vectors
   reach JUnit.
5. [`maintain/style.md`](maintain/style.md). Binding on every document under `docs/`.
6. [`design/adr/`](design/adr/). The reasoning behind any decision a change would reopen.

## Document index

| Path | Contents |
|---|---|
| [`design/00-overview.md`](design/00-overview.md) | architecture, glossary, component model, data flows, three use-case walkthroughs, scope |
| [`design/10-specification.md`](design/10-specification.md) | the normative specification, 645 requirements across 25 prefixes |
| [`design/20-topology-format.md`](design/20-topology-format.md) | the topology document format, validation, versioning, worked examples |
| [`design/topology-v1.schema.json`](design/topology-v1.schema.json) | the JSON Schema for format version 1 |
| [`design/30-conformance.md`](design/30-conformance.md) | the conformance suite design, driver contract, levels, coverage |
| [`design/40-java-binding.md`](design/40-java-binding.md) | the Java rendering: artifacts, types, thread safety, harness, build gates |
| [`design/90-open-questions.md`](design/90-open-questions.md) | every unresolved question, its default, and the evidence that settles it |
| [`design/99-roadmap.md`](design/99-roadmap.md) | release staging, one-way doors, and what the suite makes easier or harder |
| [`design/adr/`](design/adr/) | 40 decision records, the only documents here where argument is the content |
| [`maintain/style.md`](maintain/style.md) | the register, emphasis, punctuation, and terminology rules |
| [`../conformance/README.md`](../conformance/README.md) | the suite tree, how to run it, and how to regenerate it |

## Decision records

| Subject | Records |
|---|---|
| hashing and determinism | [0001](design/adr/0001-hash-function-and-key-encoding.md), [0030](design/adr/0030-unsigned-integer-discipline.md), [0040](design/adr/0040-cryptographic-primitive-sourcing-policy.md) |
| placement and weights | [0002](design/adr/0002-placement-strategy-set.md), [0003](design/adr/0003-integer-node-weights.md), [0010](design/adr/0010-shard-identifier-naming.md), [0011](design/adr/0011-derived-assignment-virtual-nodes.md), [0013](design/adr/0013-range-bounds-over-routing-key.md), [0039](design/adr/0039-placement-cost-model-and-warning-thresholds.md) |
| topology model and format | [0004](design/adr/0004-topology-provider-contract.md), [0005](design/adr/0005-epoch-and-version-semantics.md), [0006](design/adr/0006-failure-domain-model.md), [0008](design/adr/0008-json-canonical-serialisation.md), [0009](design/adr/0009-override-composition.md), [0038](design/adr/0038-provider-contract-in-the-specification.md) |
| replication and failover | [0007](design/adr/0007-administrative-state-and-health-state.md), [0012](design/adr/0012-balance-bound-tolerances.md), [0014](design/adr/0014-read-affinity-as-a-separate-call.md), [0015](design/adr/0015-spread-degradation-algorithm.md), [0016](design/adr/0016-node-health-state-machine.md), [0017](design/adr/0017-failover-depth-and-substitution.md), [0036](design/adr/0036-spread-relaxation-ladder-direction.md) |
| change, handoff, and fencing | [0018](design/adr/0018-concurrent-ownership-during-handoff.md), [0019](design/adr/0019-handoff-coordination-and-recovery.md), [0020](design/adr/0020-recipient-side-fencing-verdicts.md), [0021](design/adr/0021-migration-backpressure-control.md), [0022](design/adr/0022-range-split-lineage.md), [0023](design/adr/0023-snapshot-visibility-and-thread-ownership.md) |
| cross-cutting contracts | [0024](design/adr/0024-closed-numbered-error-taxonomy.md), [0025](design/adr/0025-observability-contract-and-explain-record.md), [0026](design/adr/0026-configuration-defaults-and-locality.md), [0027](design/adr/0027-hash-seed-exposure-and-tenancy.md) |
| Java binding | [0028](design/adr/0028-java-module-and-artifact-layout.md), [0029](design/adr/0029-exception-idiom-for-the-taxonomy.md), [0031](design/adr/0031-jdk-baseline.md), [0032](design/adr/0032-dependency-free-json-and-canonicalisation.md), [0033](design/adr/0033-opaque-identifier-value-types.md), [0034](design/adr/0034-lazy-candidate-traversal-surface.md), [0041](design/adr/0041-exact-product-comparison-surface.md) |
| conformance | [0035](design/adr/0035-manifest-driven-conformance-harness.md), [0037](design/adr/0037-specification-defect-repairs.md) |
