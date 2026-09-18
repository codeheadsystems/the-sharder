# 0039. Placement cost model and warning thresholds

Status: accepted, with `PLACE-071` extended by
[`0046`](0046-bounded-routing-decision-surface.md), the derived threshold withdrawn by
[`0055`](0055-slot-derived-assignment-withdrawal.md), the routing figures extended by
[`0070`](0070-spread-stage-feasibility-from-a-domain-count.md), the emission point and the ring
threshold amended by [`0072`](0072-preparation-cost-reported-before-preparation.md), the
retention multiple stated by [`0073`](0073-prepared-placement-retention-multiple.md), and the claim
below that no vector enforces the model superseded by
[`0077`](0077-scale-conformance-level.md) and [`0078`](0078-observability-contract-as-data.md).
Date: 2026-09-16.

The third threshold this record added, `derivedWarnEvaluations` with the
`sharder.topology.derived_large` event, existed for `slot` and `range` with derived assignment. Both
configurations are withdrawn, so no surviving configuration can cross it and the setting, the event
name, and the `PLACE-072` escape clause go with them. The two remaining thresholds are sums rather
than products, so `PLACE-073` compares totals and `PLACE-074` is restated over an accumulator. The
cost model itself, its derivation from the requirements, and the argument against asymptotic
notation stand.

`PLACE-071` as written here described where laziness helps and did not say what bounds `p`. The
routing decision consumed the whole ordering, so `p` was the eligible node count under `ring` as
well as under `rendezvous`. `0046` bounds what the decision materialises, and `PLACE-071` now names
that bound.

Three figures this record states have since moved. The routing table gives the cost of one candidate
ordering, and `PLACE-076` multiplies its walk term by the relaxation stages a builder evaluates. The
resident size table gives the size of one prepared placement, and `PLACE-070` now states the
multiple `retentionDepth` applies to it. `PLACE-073` emitted its event at publication, after stage 6
had paid the cost it measures, and `PLACE-077` moves the computation ahead of the preparation.

## Context

The specification states 645 requirements about what the library computes and, before this record,
none about what computing it costs. `SEC-033` carried the only statement of a bound on a routing
call, and it bounded that call by `maxKeyBytes` alone. That bound is true for `ring`, for
`directory`, and for `slot` and `range` with `explicit` assignment. It is false for `rendezvous`.
`RV-003` makes a node's score the largest `rvScore` over its virtual node indices and `RV-010`
orders by that score, so every eligible node is scored at every index before the first candidate is
known. The cost is the summed virtual node count and has nothing to do with the key.

The same arithmetic appears at load time under `slot` and `range` with `derived` assignment.
[`0011-derived-assignment-virtual-nodes.md`](0011-derived-assignment-virtual-nodes.md) records that
the work at publication is `slotCount` times the summed virtual node count. `SLOT-001` permits
`slotCount` up to 1048576 and nothing bounds the sum, so stage 6 of `TOPO-001` reaches 10^9 hash
evaluations, serialised under `TOPO-041`, on a thread the integrator supplied.

Where the cost model was stated at all, it was stated where nobody choosing a strategy reads it.
[`0002-placement-strategy-set.md`](0002-placement-strategy-set.md) says in one clause that `ring`
carries large node counts at a bounded lookup cost and `rendezvous` carries small ones. An
integrator picks a strategy from [`../20-topology-format.md`](../20-topology-format.md) and never
opens a decision record. That document's own cache example uses `virtualNodesPerWeightUnit` of 8,
which at a thousand nodes of weight 1 is eight thousand hash evaluations per routing call, and said
nothing about it.

The design already has a pattern for a configuration that is legitimate but expensive: `PLACE-052`
clamps a virtual node count and emits `topology.weight_clamped`, and `directoryWarnEntries` emits
`topology.directory_large` above its threshold. The two largest cost products in the design had no
equivalent.

The figures below were measured on 16 September 2026 as part of the architecture review of this
design: a tuned Java SipHash-2-4 with `VarHandle` word loads, on OpenJDK 25, over the 67-octet
`rvScore` frame of `HASH-030`, four thousand calls after warm-up. One hash evaluation costs 29
nanoseconds. A reference C implementation of the same function is within about eight per cent of
that, so the figure is not an artefact of the binding.

## Decision

Three things are added to [`../10-specification.md`](../10-specification.md).

`SEC-033` is repaired. It bounds the cost of one routing call by `maxKeyBytes` and by the eligible
node set, and names the dominant term for each strategy. The `exact` matcher indexing clause is
unchanged.

A cost model is stated under Placement rules, at `PLACE-070` through `PLACE-072`. It gives, per
configuration, the preparation cost, the cost of one routing call, and the resident size of the
prepared placement, as figures in `N`, `V`, `T`, `slotCount`, `rangeCount`, and `entryCount`. Those
are the symbols [Placement properties](../10-specification.md#placement-properties) already uses,
extended with the counts of the authored tables. Each figure is derived from the requirements that
govern the strategy and cites them, so the table is checkable against the specification rather than
against a benchmark.

`PLACE-071` records where the lazy prefix property of `PLACE-015` is nominal.
[`0034-lazy-candidate-traversal-surface.md`](0034-lazy-candidate-traversal-surface.md) is built on
the observation that a preference list of three should read three entries from an ordering over a
thousand nodes, and under `ring` the cursor delivers exactly that: the walk stops after three
distinct owners and the rest of the ring order is never touched. Under `rendezvous` the cursor skips
the sort and not the scoring, because the first candidate is unknown until every node has been
scored. Laziness there removes an `N log N` term and leaves the `V` term, which is the dominant one.
That distinction is not obvious from the cursor's shape and is easy to read the wrong way round.

`PLACE-073` and `PLACE-074` add three warning thresholds on the `directoryWarnEntries` model, with
three events on the `topology.directory_large` model. Each event names the computed product and the
threshold it crossed. Crossing one changes no ordering, refuses no document, and clamps no count.

| Product | Setting | Default | Event |
|---|---|---|---|
| `V` under `rendezvous` | `rendezvousWarnVirtualNodes` | 4096 | `topology.rendezvous_large` |
| `T` under `ring` with `derived` | `ringWarnTokens` | 1000000 | `topology.ring_large` |
| the derived product | `derivedWarnEvaluations` | 100000000 | `topology.derived_large` |

The derived product is `slotCount * V` under `slot` and `rangeCount * V` under `range`. The defaults
follow from the measured 29 nanoseconds per hash evaluation.

A rendezvous summed virtual node count of 4096 is about 119 microseconds of hashing per routing
call. A routing decision's budget is one to ten microseconds, so the threshold sits an order of
magnitude past the point at which the routing call costs more than the request it routes. It is not
the point at which the cost becomes noticeable, which is nearer 350; a threshold there would fire on
the default `rendezvous` configuration at a few hundred nodes and be turned off.

A derived preparation product of 100000000 is about 2.9 seconds of hashing inside stage 6 of
`TOPO-001`, serialised under `TOPO-041` on a thread the integrator supplied. The permitted maximum
of 10^9 is about 29 seconds. The threshold therefore fires an order of magnitude before a topology
push stalls for a minute, and does not fire on Redis Cluster's shape of 16384 slots over a hundred
nodes, which is 1.6 million and entirely ordinary.

A ring token total of 1000000 is about 29 milliseconds of hashing, which is not the binding cost.
The resident size is: a ring entry carries a token, an owner, and an index, so a million entries is
tens of megabytes, and `retentionDepth` of 3 holds four prepared placements at once. The thousand
nodes at 4096 tokens that the Java binding benchmarks is 4.1 million entries, which is four times
that again.

Each threshold accepts the value 0, which disables its event, under `CFG-014`. A large deployment is
legitimate; the point is that it is visible before production rather than after.

[`../00-overview.md`](../00-overview.md) and [`../20-topology-format.md`](../20-topology-format.md)
both recommend `rendezvous` for cache routing and both now state the cost and point at the table.
Neither is normative, so neither carries an RFC 2119 keyword.

## Consequences

An integrator choosing a strategy reads the cost of each one in the document that specifies them,
and an operator sizing a topology is told at publication when a product crosses a threshold rather
than when a routing call misses its budget in production.

Three settings and three events are added to a published surface. Settings are reversible under
[`0026-configuration-defaults-and-locality.md`](0026-configuration-defaults-and-locality.md), so the
defaults can be recalibrated. An event name is not: `OBS-020` fixes names permanently, so the three
names are chosen to match `topology.directory_large` and to survive a change of threshold.

The cost model is a bound rather than a measurement, so a port can satisfy it and still be slow by a
constant factor. The conformance suite carries no timing assertion and gains none from this record.
The suite's largest topology is eleven nodes, so nothing here is enforced by a vector; a port that
materialises the candidate ordering per routing call still passes.

`PLACE-070` states a bound on resident size, which constrains the shape of a prepared placement more
than the rest of the specification does. A `slot` implementation that precomputes every slot's full
ordering pays `slotCount * N` node references, which at the permitted maxima is not a structure
anybody can hold. `PLACE-072` is what makes that a choice rather than an obligation: the ordering
may be computed at preparation or at the routing call, and both produce the ordering `PLACE-012`
requires.

Naming `rangeCount` and `entryCount` introduces two symbols the specification did not have. Both
name a count of authored entries rather than a document member, and both are defined in the table
that uses them.

## Alternatives

Stating no cost model and leaving the choice of strategy to measurement. Rejected because the
measurement is not available to the person making the choice: it requires a thousand-node topology,
a warmed binding, and a load generator, and the choice is made while writing a topology document.

Refusing a document whose product exceeds a threshold, rather than warning. Rejected because a large
deployment is a legitimate deployment, and because a validation rule on a cost figure would make the
set of acceptable documents depend on a caller-local setting, which `CFG-002` and `CFG-004` forbid
between them.

Capping the summed virtual node count the way `PLACE-050` caps a node's own count. Rejected because
a cap on the sum is not expressible per node: which nodes lose virtual nodes, and in what order,
would have to be specified, and every such rule changes placement as the node set changes.
`PLACE-050` clamps per node precisely because a node's count depends on nothing but its own weight.

One threshold over a single normalised cost figure rather than three over three products. Rejected
because the three costs are paid in different places, by different parties, and are read by
different people: a per-call cost falls on every caller, a preparation cost falls on whoever
supplied the pipeline's thread, and a resident cost falls on the caller's heap four times over under
`retentionDepth`.

Expressing the figures in asymptotic notation. Rejected because the specification defines no such
notation and uses none anywhere else, and because the constant matters here: the difference between
one hash evaluation and `V` of them is the whole finding, and both are constant in the key.

Recording the per-hash figure in the specification so that a reader can do the arithmetic. Rejected
because the specification states what an implementation does and not what a benchmark measured. The
figure lives in this record, where the conditions it was measured under can be stated with it.
