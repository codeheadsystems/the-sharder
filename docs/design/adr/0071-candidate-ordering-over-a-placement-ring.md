# 0071. Candidate ordering over a placement ring

Status: accepted. Date: 2026-09-17.

## Context

`RING-005` required an implementation to "derive tokens from the eligible node set when building a
candidate ordering". Read literally it requires the ring order to be rebuilt whenever the eligible
node set differs from the placement set, which an override `constrain` under `PLACE-003` makes true
per routing call. Rebuilding means deriving up to `maxTokensPerNode` tokens for every eligible node
and sorting them, inside the call.

The affordable implementation builds one ring order over the placement set at preparation and skips
the entries whose owner is not eligible while walking it. `RING-025` blesses linear scan, binary
search, and a precomputed lookup table, and says nothing about which node set the ring was built
over, so the only reading available to a careful implementer was the expensive one.

The cost model added by [`0039`](0039-placement-cost-model-and-warning-thresholds.md) already
assumes the affordable implementation. Its preparation figures are stated over the placement set and
its routing figures over the eligible node set, and it says so: "an override `constrain` changes the
per-call figures and leaves the preparation figures unchanged". `PLACE-070` and `RING-005`
contradicted each other, and `PLACE-070` described what an implementation would be built to do.

## Decision

`RING-005` states the outcome and `RING-026` states the freedom.

`RING-005` now reads that a candidate ordering is the one the ring order of the eligible node set
produces and that `shardOf` and `shards` are the ones the ring order of the placement set produces.
It fixes which node set decides each answer and stops naming a construction.

`RING-026` states the equivalence, in the manner `RING-025` states its own. The candidate ordering
does not depend on whether an implementation builds a ring order over the eligible node set or walks
a ring order built over the placement set and skips every entry whose owner is not eligible.
Removing entries preserves the relative order of the entries that remain, and the first entry at or
above a key hash whose owner is eligible is the same entry in both orders, so the two walks emit the
same owners in the same order.

`PLACE-070` gains the term the skip costs: under a `constrain` the walk visits the entries of
ineligible owners as well, so the walked term is `T` over the placement set while the ordering it
produces is over the eligible node set.

The suite declares `RING-026` on `vectors/ring/administrative-states.json`, whose topology carries
`joining` and `leaving` nodes and therefore pins an ordering over a proper subset of the document's
nodes.

## Consequences

An implementation builds one ring order per snapshot, in stage 6 of `TOPO-001`, and a routing call
under an override constraint costs a longer walk rather than a rebuild. That is the difference
between a term in `T` and a term in `T log T` paid per call.

`RING-005` keeps its identifier and the two vectors joined to it, because what it requires of an
observable answer is unchanged. An implementation that already built the eligible ring stays
conforming.

The walked term under a `constrain` is now stated over the placement set, which is the one place the
cost model's rule that routing figures are stated over the eligible node set does not hold. Stating
the exception is cheaper than stating a second table.

`RING-032` already builds the ring order over the eligible node set for `candidatesForShard`, and
the same equivalence covers it, because the walk it performs is the walk of `RING-021` from a
different starting position.

## Alternatives

Leaving `RING-005` and adding the permission to `RING-025` alone. Rejected because two requirements
would then state contradictory things about the same construction, and a port reading in order would
implement the first.

Withdrawing `RING-005` and stating the whole rule under a new identifier. Rejected because the
requirement's meaning for an observable answer does not change, and the withdrawal convention of
[`0053`](0053-requirement-withdrawal-convention.md) is for behaviour the specification stops
stating.

Requiring the placement-set ring rather than permitting it. Rejected because a topology whose
placement set is large and whose eligible sets are small and stable is a topology where an
implementation may reasonably cache a ring per constraint, and the specification has no business
choosing between two constructions that produce the same answer.
