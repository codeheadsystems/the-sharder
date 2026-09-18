# 0046. Bounded routing decision surface

Status: accepted, with the default of `n + 2` rehomed from `FAIL-022` to `CORE-048`
by [`0063`](0063-decision-api-surface-boundaries.md). Date: 2026-09-17.

The bound on `entries` stands, and so does its length. Where the text below attributes the attempt
limit's resolution, or its default of `n + 2`, to `FAIL-022`, `CORE-048` states it now, so that the
rule belongs to the `routing` surface and an implementation exposing no attempt walk still
materialises the prefix. `FAIL-022` keeps the clamp to the length of the attempt sequence. No value
changes.

## Context

`CORE-040` typed `entries` on a `RoutingDecision` as the preference list, `REPL-013` and `REPL-014`
made the preference list the replica prefix followed by the whole remaining candidate ordering, and
`FAIL-013` required a caller to be able to read the unfiltered list off the decision. Under `ring`
and `rendezvous` the candidate ordering names every eligible node, so `entries` held one record per
eligible node on every routing call. Each record carries a health state read from an
integrator-supplied health view, so a call over a thousand-node cluster allocated a thousand records
and made a thousand calls out of the library. The shipped vectors show the shape at small sizes: a
seven-entry `preferenceList` at factor 2 over seven nodes in
`vectors/rendezvous/cache-hash-tags.json`.

That made two statements elsewhere in the corpus false.
[`0034`](0034-lazy-candidate-traversal-surface.md) justified the candidate cursor by saying that a
builder at factor 3 over a thousand-node ring "computes nothing beyond" the entries it needs, and
`PLACE-071` said the lazy prefix property removes the cost of the ordering a caller does not
consume. Neither held while the decision consumed
all of it. The cursor was lazy and its only routing-path consumer drained it.

The decision is public API and a caller destructures it, so the shape is settled before a port
exists rather than after.

What the decision is for bounds what it has to carry. A caller walks the attempt sequence, whose
depth is the attempt limit of `FAIL-022`, defaulting to `n + 2`. A caller performing a substitution
under `FAIL-041` reads the roles of the entries it walked. A quorum writer reads the replica prefix,
which is `r` entries. Diagnosis is the explain record of `OBS-041`, which `OBS-046` keeps off the
routing path precisely because its cost grows with the node set. The fallback tail beyond the
attempt limit is consumed only where the health filter skips entries, which is the degraded case,
and by a caller doing something the attempt limit is the knob for.

## Decision

`entries` holds a prefix of the preference list rather than the whole of it. `CORE-046` fixes the
length of that materialised prefix at the lesser of the length of the preference list and the
greater of `r` and the attempt limit resolved before the clamp of `FAIL-022`. The replica prefix is
therefore always complete, a caller that raises its attempt limit gets more entries, and the length
depends on no health state, so two callers over one snapshot materialise the same entries and a
golden vector asserts the bound without modelling a health view.

`CORE-047` adds `preferenceList(decision)`, which answers the whole list on demand. It recomputes
from the snapshot the decision was taken against and the decision's `routingKey`, which `REPL-016`
makes sufficient, so it changes nothing, needs no lock, and may be called from any number of units
of execution. A routing call never makes it. The decision retains its snapshot, which `CORE-053`
already required it to stay consistent with.

`FAIL-014` keeps the attempt sequence drawn from the whole preference list. Where the health filter
skips an entry of the materialised prefix, the walk continues past it until the sequence holds as
many attemptable entries as the limit permits or the list is spent. Failover depth is unchanged: a
caller still reaches a healthy node deep in the tail, and `FAIL-012` still fails open over the whole
list. What changed is that the cost of reaching it is paid by the walk that needs it rather than by
every call.

`ERR-022`, `FAIL-025`, and `FAIL-026` carry the materialised prefix where they carried the
preference list, and the `sharder.routing.preference_list_length` histogram records its length.
`PLACE-071` now states what bounds `p`.

## Consequences

A routing call at factor 3 with the default limit materialises five entries and reads five health
states, whatever the cluster size. The figure `0034` claims for the cursor is the figure the surface
produces, and the allocation gate in [`../40-java-binding.md`](../40-java-binding.md) gains a
thousand-node ring shape, where a decision that quietly materialised the whole ordering would show
up and at a hundred nodes would not.

A caller that wants the whole ordering has three ways to it and picks by what it is for. It raises
the attempt limit where it means to attempt further, calls `preferenceList` where it means to reason
about the tail, and calls `explain` where it means to diagnose. The first two are routing calls, the
third is not.

Holding the snapshot on the decision keeps a snapshot reachable for as long as a caller holds a
decision. A snapshot is immutable and shared, `CORE-055` already keeps one readable until every call
that acquired it completes, and `CORE-034` already forbids `close` invalidating a decision, so the
cost is one reference. A caller that parks decisions indefinitely retains the snapshots they name,
which `retentionDepth` does not bound.

`sharder.routing.preference_list_length` changes meaning under an unchanged name. It answers how
deep a decision offered to go rather than how many eligible nodes there are, which is the number an
operator reading it beside `sharder.routing.replica_count` is looking for, and the eligible node
count is already reported by `sharder.topology.nodes`.

The bound is conformance-visible, so every routing vector gains the count of entries the decision
materialises. A port that returns the whole ordering fails on that field rather than passing
quietly.

## Alternatives

Stating the cost honestly in `CORE-040` and dropping the laziness claim from `0034`. Rejected
because the cursor then buys nothing: its only routing-path consumer would drain it, and the
specification would be describing a routing cost rather than a routing library at the sizes
`0034`'s context sets out.

Bounding `entries` and dropping the tail accessor, leaving `explain` as the only way to the whole
list. Rejected because `explain` takes a key and a decision carries none under `CORE-043`, so a
caller holding a decision could not reach the list at all; because the explain record computes the
eligible set, the exclusions, and the strategy inputs, which is a great deal more than the tail; and
because `FAIL-013` would have to be withdrawn rather than met.

Making `entries` lazy, so that reading past the prefix extends it. Rejected because `CORE-041` makes
the decision immutable and `CORE-056` makes it usable by any number of units of execution, and a
memoising list is neither without a lock that `CORE-057` refuses to pay for on the routing path. A
recomputing accessor gives the same answer with no shared mutable state.

Bounding the prefix at the attempt limit alone, without the replica count. Rejected because a caller
that supplies an attempt limit of 1 at factor 3 would see one entry and a `replicaCount` of 3, and a
quorum writer reads the replica prefix rather than the attempt depth.

Bounding it at the count of attemptable entries the limit permits. Rejected because the length would
then depend on the caller's health view: two callers over one snapshot would hold decisions of
different lengths, every routing vector would have to carry a health view to be reproducible, and
`entries` would stop being the caller-invariant object `FAIL-001` and `FAIL-011` describe.

Dropping `health` and `attemptable` from `PreferenceEntry` and moving them to the attempt surface.
Rejected because it does not solve the problem it appears to: the entries would still be
materialised, the saving would be only the health reads, and a caller diagnosing an exhaustion would
lose the states the decision was taken under. Bounding the prefix removes the records and the health
reads together.
