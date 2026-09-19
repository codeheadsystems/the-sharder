# 0091. Lineage classification as a signal

Date: 2026-09-19

Status: accepted

## Context

The motivation for [`0086`](0086-shard-lineage-derived-from-extent.md) was that splitting and
merging should be the ordinary way to change capacity, in preference to republishing a topology that
redistributes everything. That raises a question the lineage does not answer by itself: whether the
library should do anything about an authority that reshards wholesale when a split would have done.

Three positions were available. Refuse the plan where the lineage shows a cheaper change existed.
Emit a warning. Do neither, and let the classification be reported.

The library authors no topology document, assigns no epoch, and edits nothing.
[`0022`](0022-range-split-lineage.md) was firm that the decision to divide belongs to the authority,
and nothing in `0086` changes that.

## Decision

The library refuses `unaligned` and refuses nothing else.

`LIN-022` refuses a plan over a lineage that classifies any shard `unaligned`, because a boundary
that moves without either dividing or folding an extent whole has no correspondence an
implementation can name. That is a correctness refusal over an ambiguous input, not a policy one,
and the requirement states the remedy, which is `0022`'s and still the right one: publish the change
as two epochs, the first dividing every extent the change crosses and the second folding the pieces
into their destinations.

The classification is reported through the `migration.lineage` event of `OBS-020`, which carries the
count per class. The planner needs the classification to choose a source, so reporting it costs
nothing.

No refusal and no warning is raised for a change that could have been expressed as a division. The
preference is carried by documentation, which states when a division is the right shape, when a
wholesale republication is, and what each costs.

## Consequences

An operator reads from `migration.lineage` whether an epoch was a refinement or a redistribution,
and decides what to do about it. The library states the facts and takes no position.

The strongest expression of the preference is not a rule at all. Before `0086` an authority that
added one node to a ring got a plan of self-handoffs, and an authority that republished everything
got the same, so nobody preferred anything because neither worked. Making a division correct and
cheap is most of what "preferred" can honestly mean here.

An authority that reshards wholesale where a division would have served pays for it in movement and
is told nothing by the library. That is the accepted cost of not guessing.

## Alternatives

Refusing a plan where the lineage shows a division was available. Rejected. The library cannot
distinguish a deliberate rebalance, a seed rotation, or a failure domain re-layout from a lazy
reshard, and a false refusal blocks an epoch the authority has already published to routing, which
is a worse failure than the one it would prevent.

A warning event for the same condition. Rejected for the same reason at lower value: it needs a
threshold for what counts as wholesale, which is a judgement the library has no basis to make, and
an operator who cannot act on it learns to ignore it.

A configurable policy selecting among the three. Rejected. It is a knob for a policy nobody has
asked for, and it would oblige every port to implement all three positions.
