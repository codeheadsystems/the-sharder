# 0087. Lineage capability derived rather than declared

Date: 2026-09-19

Status: accepted

## Context

[`0086`](0086-shard-lineage-derived-from-extent.md) adds a lineage. A question it does not settle is
how an implementation knows whether a lineage is available for a given pair of snapshots, and the
obvious answer is wrong in a way worth recording.

The obvious answer is a capability method on the placement extension point, defaulted to answer that
no lineage is offered. It reads as though it follows `MOVE-261`, and it does not. `MOVE-241` derives
support for orchestrated migration from observable geometry: a strategy supports it exactly when
`shards()` is non-empty and `shardOf` returns a shard identifier rather than the routing key.
`MOVE-261` binds a registered strategy to that same geometric rule and forbids inferring support
from the strategy's name. The design derives capability; a defaulted capability method declares it.

The difference is not stylistic. [`../30-conformance.md`](../30-conformance.md) says a port declares
the levels it reaches, not a percentage. A port that declared the `migration` level while answering
that it offers no lineage for `ring` would be indistinguishable from a port that had simply not done
the work, and the configuration that produces is exactly the one that emits the self-handoffs
`0086` describes.

Underneath the mistake were two different questions fused into one flag.

## Decision

The two questions are separated, and each is answered in its own way.

Whether a **strategy** has a lineage is derived and never declared. A strategy has one exactly when
it supports orchestrated migration under `MOVE-241` and its shard extents are determined by the
topology document. `ring`, `slot`, and `directory` have one; `rendezvous` does not, because it
enumerates no shard. There is no opt-out, because the extents exist whether or not an implementation
has computed them. Lineage support is therefore not a property of the design at all, it is a
property of an implementation's completeness, and completeness is what the level system exists to
police.

Whether the **integrator's storage** can divide a shard in place is declared, in
`HookDeclaration`. That is a statement about a system the library cannot observe, and
[`0085`](0085-hook-declarations-and-refused-aborts.md) is the precedent: `supportsRollback` and
`supportsVerify` are read as the integrator's statement about their own system, and the library
refuses rather than calling a hook that was never implemented. Nothing about it is a statement about
a port's completeness, so it does not collide with the level system.

A registered strategy outside the core set is the one case where the library cannot derive the
answer, and it supplies its lineage rather than declaring a capability. `LIN-015` therefore forbids
deriving a registered strategy's extents by sampling and forbids inferring a lineage from the
strategy's name, and where a registered strategy supplies none and the two snapshots enumerate
different shard sets, the plan is refused with the cause `lineageUnsupported`. The method survives
but its meaning changes: it does not say "I support lineage", it says "here is my lineage", and its
absence has a stated, observable consequence rather than a silent one.

## Consequences

Per-strategy scoping does not need a new mechanism, because the suite already has one. The strategy
surface axis of [`../30-conformance.md`](../30-conformance.md) selects vector files on the surfaces
a port exposes, and `run_suite.py --strategy` already drives it. A port that exposes `rendezvous`
and `directory` and declares `migration` implements directory lineage and no ring lineage, and that
is expressible today.

Staging within this repository is done with a stated refusal rather than a silent absence.
`LIN-013` refuses a `directory` pair whose shard sets differ, with the cause `unalignedLineage`,
until directory extents are defined; a later change restates that requirement and removes the
refusal, keeping its identifier under [`0053`](0053-requirement-withdrawal-convention.md). A refusal
is a conforming, testable behaviour that a vector pins, so no port can be green on a hole.

This improves `directory` immediately rather than deferring it. A prefix refinement today produces
the same silent self-handoff as a ring token addition. Under the staged refusal it produces a
refused plan naming a cause, which is a correct answer in place of a wrong one.

## Alternatives

A defaulted capability method meaning "I offer no lineage". Rejected above: it makes an incomplete
port indistinguishable from a complete one, against the rule that a port declares levels rather than
a percentage.

A per-strategy capability flag in the conformance declaration. Rejected. It duplicates the strategy
surface axis that already exists, and it would let a port declare `migration` and `ring` together
while opting out of the one thing that makes the pair meaningful.

Recording directory lineage under "Requirements without an executable test" so it could ship before
its vectors. Rejected. A directory extent is decidable over a prefix trie, so it is testable, and
recording something as untestable to avoid writing its vectors is the one dishonest move available
here.
