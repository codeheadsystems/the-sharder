# 0038. Provider contract in the specification

Status: accepted. Date: 2026-09-16.

## Context

[`../10-specification.md`](../10-specification.md) named `TopologyProvider` twice and declared it
nowhere. `CORE-063` listed it among the extension points across which no lock is held, and `CFG-010`
made it the one required setting. The members, the two capabilities, the sink, the subscription, the
rule that at least one retrieval model is present, and the adaptation of a provider offering only
one of them were stated in [`0004`](0004-topology-provider-contract.md) and rendered in Java in
[`../40-java-binding.md`](../40-java-binding.md).

A decision record is not a normative document. A port written from the specification alone reached
the provider setting and invented a shape, which is the position the hash construction was in before
`HASH-001` through `HASH-044`. [`../90-open-questions.md`](../90-open-questions.md) carried the
defect as `OQ-01` and recommended the same repair the hash received: transcription into the
normative surface, consuming free numbers in an existing prefix.

## Decision

The contract is stated in the Core model section of
[`../10-specification.md`](../10-specification.md), under a Topology provider heading, in three
groups.

| Group | States |
|---|---|
| `CORE-080` to `CORE-083` | the interfaces, the capability rule, member presence, delivery |
| `CORE-090` to `CORE-093` | the pull-only, push-only, and dual adaptations, and sink delivery |
| `CORE-100` to `CORE-101` | the failure behaviour, the backoff, and close ordering |

The group is appended after `CORE-074` rather than inserted beside `CORE-010` and `CORE-020`, so
that `CORE-*` still ascends in document order. A new prefix was considered and rejected below.

Three things the transcription had to settle, because
[`0004`](0004-topology-provider-contract.md) and the Java binding each state part of them.

`0004` types the delivered value as a `TopologyDocument` and the Java binding types it as `byte[]`,
while stage 1 of `TOPO-001` decodes octets as JSON and `TOPO-002` rejects a duplicate member name
and an unpaired surrogate at that stage. `CORE-083` permits both forms and requires a parsed form to
have been produced under `TOPO-002`, so a binding that accepts one cannot lose the two rejections
that only a decoder can make.

Neither document said what happens when `capabilities` declares a member the provider does not
supply. The Java binding renders both optional members as declared methods, so absence is a runtime
failure rather than a compilation failure, and `ERR-063` already routes any failure a provider
raises to `providerError`. `CORE-082` states that, and states that an undeclared member is never
called, which makes the capability pair the only thing the library reads.

`0004` says retries back off exponentially from 1 second to a 60 second cap with jitter, and
`CFG-010` names `providerRetryBaseMillis`, `providerRetryCapMillis`, and `providerRetryJitter`
without an arithmetic rule. `CORE-100` takes the form `RATE-051` already uses for the step retry
backoff, over the provider settings.

`TOPO-061` keeps the acceptance outcomes for a document that is delivered. `CORE-100` covers a
failure to deliver one and says so, so the two halves of the closed failure set in
[`0004`](0004-topology-provider-contract.md) each have one owner.

Three parts of [`0004`](0004-topology-provider-contract.md) stay where they are. The two reference
providers are a packaging decision that [`../40-java-binding.md`](../40-java-binding.md) renders as
modules. Staleness is `CFG-010`, `TOPO-141`, and `ERR-024`. The adoption of a `topologyId` from the
first accepted document is `TOPO-091`.

## Consequences

The specification states 645 requirements, up from 635, and `CORE-*` carries 51, up from 41. The
prefix set is unchanged, so the prefix table in the Conventions section gains only the provider
contract in the `CORE` row, and the section map in `conformance/generator/coverage.py` is unchanged.

Nothing computed changed. No vector file, no property witness, no scenario, and no topology document
moved, because the repair adds no rule that a data file can carry. `conformance/coverage.json`
moves: the ten new requirements are uncovered, so the suite names 424 of 645 rather than 424 of 635.
The coverage table and the accounting in [`../30-conformance.md`](../30-conformance.md) move with
it, and the new requirements join the provider group there, which already held the timing settings a
data file cannot witness.

[`0004`](0004-topology-provider-contract.md) keeps the decision, the etcd adapter sketch, and the
rejected alternatives, and names the requirements that now carry the contract. `OQ-01` moves to the
settled table in [`../90-open-questions.md`](../90-open-questions.md), leaving `OQ-02` as the one
question that blocks v0.1.

## Alternatives

A `PROV-*` prefix, inserted between `Prepared placement` and `Topology snapshot` as `HASH-*` was
inserted between `Shared types` and `Prepared placement`. Rejected because the hash construction is
a subject area of its own, used by six requirement groups, while the provider is one extension point
among the several `CORE-*` already declares, and because `OQ-01` recommended free numbers in an
existing prefix. A new prefix also moves the prefix table and the section map for no gain.

`CORE-080` placed beside `CORE-010` and `CORE-020`, where a reader meets the extension points
together. Rejected because the numbers would then descend and ascend within one section, and a
reader who navigates the specification by identifier pays for that on every visit, not only on the
one that reads the provider contract.

Leaving the contract in [`0004`](0004-topology-provider-contract.md) and citing the record
from `CFG-010`. Rejected for the reason the same alternative was rejected for the hash construction
in [`0037`](0037-specification-defect-repairs.md): a port that reads only the specification is the
reader the conformance suite exists to serve, and a citation of a record that a port is not required
to read leaves the shape optional.
