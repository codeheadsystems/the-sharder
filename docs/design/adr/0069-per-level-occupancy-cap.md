# 0069. Per-level occupancy cap

Status: accepted. Date: 2026-09-17.

Generalises the admission test of [`0015`](0015-spread-degradation-algorithm.md), whose ladder and
whose rejection of a ladder over caps both stand.

## Context

`REPL-012` and `SPREAD-011` fix one greedy forward walk over the candidate ordering. It admits an
entry that does not conflict with an entry already admitted and halts at the effective replication
factor. `shares_domain` was the conflict test: an entry conflicts where some enforced level finds an
admitted entry with the same domain path.

That shape expresses a ceiling and cannot express a floor. "At most one replica per zone" is the
ceiling, and it is what a spread requirement is. "Three replicas in each of two regions", which is
what `NetworkTopologyStrategy` provides and what multi-region quorum storage asks for, is a floor,
and a floor needs per-domain sub-walks or a round robin over domains rather than a filter over one
pass. [`../90-open-questions.md`](../90-open-questions.md) carried that difference as `OQ-03` and
recommended deferring the whole question on the grounds that the format member is additive, which is
true of the member and false of the builder.

Between the ceiling the walk states and the floor it cannot state lies a third thing: a ceiling
above 1. "At most three replicas in any one region" is a walk-shaped constraint, and
[`../90-open-questions.md`](../90-open-questions.md) carries it as `OQ-04`, where it is recorded as
a change to the ladder rather than as a document member, because the builder had no place to put a
count. [`../99-roadmap.md`](../99-roadmap.md) therefore treated `OQ-04` as a behaviour change that
regenerates every spread vector.

[`0015`](0015-spread-degradation-algorithm.md) rejected an occupancy cap for two reasons. One is
that [`0006`](0006-failure-domain-model.md) locked drop semantics for degradation, which remains
true and is not reopened here. The other is that "the ladder over caps has no obvious total order
once two levels are both capped", which is an objection to relaxing by raising caps a step at a
time, and not to a fixed cap that the ladder drops whole levels of.

`REPL-012` is close to being frozen. A published conformance suite joins vectors to it and a shipped
port implements it, and after that its walk is a one-way door.

## Decision

The admission test is a per-level occupancy cap, and today every cap is 1.

`SPREAD-007` defines the occupancy cap `c(L)` of a level as the greatest number of replica prefix
entries permitted to share one failure domain at `L`. No member of the topology document format sets
a cap, so `c(L)` is 1 at every level, and `SPREAD-001` is that case: a prefix in which no two
entries share a domain at `L` is a prefix holding at most one entry per domain at `L`.

`SPREAD-011`'s pseudocode replaces `shares_domain` with `exceeds_cap`, which counts the admitted
entries sharing the candidate's domain path at an enforced level and refuses the candidate where the
count has reached that level's cap. At a cap of 1 the count reaches the cap exactly where an
admitted entry shares the domain, so the two tests admit the same entries in the same order.

`SPREAD-018` gains the condition its collapse rests on. A stage admits exactly what its coarsest
enforced level admits because the count of admitted entries sharing a domain path does not rise as
the level grows finer, and because every level carries the same cap. Both hold today, and the second
is what a document member setting a cap would change.

The reference implements the counting test, and the suite declares `SPREAD-007` on the six vector
files whose cases turn on an admission decision.

`OQ-04` is restated as the document member that sets a cap above 1, which is additive, together with
the ladder question a capped level raises, which is not settled here. `OQ-03` is restated to record
the cost of the floor accurately and to leave the decision open.

## Consequences

No candidate ordering, preference list, relaxed level list, chosen stage, or shortfall cause
changes. The whole conformance suite regenerates byte for byte, which is the check that the
generalisation is a restatement rather than a change. Checked more widely on 17 September 2026, over
the topology documents the suite ships and four hundred generated ones: the counting test and the
existence test admit the same entries, in the same order, at every stage of every key sampled.

`OQ-04` moves from a behaviour change to a document member. A later minor version that sets a cap
above 1 changes placement for the keys the document governs and for no others, and a document that
sets none moves nothing, which is the compatibility rule
[`../20-topology-format.md`](../20-topology-format.md) already states for an optional member.

The count in `exceeds_cap` costs more than the existence test it replaces, by a factor bounded by
the replica prefix length, which `REPL-012` bounds by the effective replication factor. A prefix
holds at most `n` entries and `n` is small, so the term is a constant against the walk `PLACE-076`
bounds.

`SPREAD-018`'s collapse is now conditional. A document member setting different caps at two levels
would let a finer level bind before a coarser one, so the requirement names the condition rather
than leaving a port to discover it when the member arrives. No vector's assertion changes, because
every cap is 1.

`OQ-03` stays open, and this record does not answer it. A cap above 1 bounds a domain's share of the
replicas and guarantees nothing inside a domain, so it is not a floor and does not make one
unnecessary for a deployment that needs a quorum in each region.

## Alternatives

Leaving the admission test as it is and adding a second builder later. Rejected because the cost of
the generalisation now is one counting loop and no moved vector, and the cost later is a change to a
requirement a conformance suite and a port both rest on.

A ladder over caps, relaxing by raising a cap one step at a time rather than by dropping a level.
Rejected for the reason [`0015`](0015-spread-degradation-algorithm.md) gives, which this record does
not reopen: the order in which two capped levels are raised is arbitrary, and the ladder's value is
that its order is not.

Adding the document member that sets a cap in the same change. Rejected because the member carries
the ladder question `OQ-04` states, and because a member with no reader is a member with no
validation rule anybody has tested. The builder is the part with a freeze date.

Expressing the floor as well, by admitting a second walk now. Rejected because a floor changes what
the relaxation ladder means for a level that carries one, and that question has no answer until a
deployment states which of the floor and the spread yields.
