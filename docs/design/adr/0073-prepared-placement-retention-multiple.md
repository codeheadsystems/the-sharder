# 0073. Prepared placement retention multiple

Status: accepted, with the claim below that metrics carry no assertion narrowed to their values
by [`0078`](0078-observability-contract-as-data.md). Date: 2026-09-17.

## Context

`TOPO-161` retains the snapshot in force together with a bounded number of previous snapshots for
the same `topologyId`, at a depth the integrator supplies and a default of 3. `TOPO-171` limits a
retained snapshot to recipient-side evaluation under `FENCE-091` and to plan basis checks, which
reads as a narrow use.

It is not a narrow structure. `FENCE-091` evaluates a recipient against the preference list at the
epoch the token carries, so a retained snapshot holds its whole `PreparedPlacement` under
`CORE-010`. At the ring size the Java binding benchmarks, a thousand nodes at 4096 tokens, a
prepared placement holds 4.1 million ring entries, and the default retention depth makes four of
them resident and turns them over on every epoch change.

`PLACE-070` states the resident size of one prepared placement, which is the figure an operator
sizing a fleet reads, and said nothing about the multiple. The `CFG-010` row for `retentionDepth`
read "previous snapshots retained for `FENCE-091` and plan checks", which names a use rather than a
cost. [`0039`](0039-placement-cost-model-and-warning-thresholds.md) records the multiple in a
consequence, and a decision record is not where an operator looks.

The design also had no metric for it. `OBS-010` carries `topology.epoch` and `topology.nodes` and
nothing that reports the structure the retention holds.

## Decision

The multiple is stated where the resident size is stated, where the setting is configured, and as
two gauges.

`PLACE-070` states, under the resident size table, that a caller holds one prepared placement per
retained snapshot, that a resident figure is multiplied by `retentionDepth` plus 1, and that the
default depth makes four resident and turns them over on every epoch change.

`TOPO-161` states that each retained snapshot holds its own `PreparedPlacement`, and why:
`FENCE-091` evaluates against the preference list at the token's epoch.

The `CFG-010` row for `retentionDepth` names the prepared placement and points at `TOPO-161`.

`OBS-010` gains two gauges. `topology.retained_snapshots` counts the snapshots held, including the
one in force. `placement.prepared_entries` reports the count of entries the resident size table of
`PLACE-070` names for the configuration in force. `OBS-013` states what each counts, and the product
of the two is the structure a caller is paying for.

The default retention depth stays at 3.

## Consequences

An operator sizing a fleet reads the multiple in the same table as the figure it multiplies, and
sees both numbers from a running process without sampling a heap.

`placement.prepared_entries` is a count rather than a byte figure. The entry size belongs to a
binding and the entry count belongs to the library, and a gauge the library states has to be the
same number in every port.

Two metric names are added to a published surface. `OBS-020` fixes an event name permanently and
`OBS-010` fixes a metric name the same way, so both are chosen to match the segments already in use:
`topology.` for a snapshot property and `placement.` for a prepared placement property, each
belonging to the `routing` surface under the rule the Conformance surfaces section states.

An implementation counts the entries of its own prepared placement, which the resident size table
already names per configuration, so the gauge asks for nothing a port does not already hold.

No behaviour changes and no vector moves. Metrics carry no assertion in the suite, for the reason
`30-conformance.md` gives.

## Alternatives

Defaulting `retentionDepth` to 1 under `ring`, where the resident figure is largest. Rejected
because 3 is the depth the fencing behaviour wants, a recipient that refuses a request two epochs
behind is a recipient that cannot redirect it, and the defect is that the cost was invisible rather
than that it was wrong. A setting per strategy would also make a default depend on a document
member, which `CFG-002` does not permit.

One gauge reporting the product. Rejected because the multiplication is the thing an operator is
being told about, and a product hides which of its two terms moved.

Reporting the resident size in bytes. Rejected because the figure is a binding's and not the
library's, and two ports would report different numbers for the same topology.

Bounding the retained structure rather than reporting it, by holding the preference lists
`FENCE-091` needs instead of the whole prepared placement. Rejected because the preference list of a
shard is derived from the ordering and a recipient checks an arbitrary key, so the structure that
answers `FENCE-091` for every key is the prepared placement.
