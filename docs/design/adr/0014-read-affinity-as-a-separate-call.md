# 0014. Read affinity as a separate call

Status: accepted. Date: 2026-09-16.

## Context

A storage caller reading from a three-replica shard would rather read from the replica in its own
availability zone than from one two zones away. The cross-zone read costs latency and, on a public
cloud, egress charges. The requirement is real for every deployment whose replicas span zones.

The obvious expression of it is to sort the preference list by proximity to the caller. That breaks
[`0007`](0007-administrative-state-and-health-state.md): the preference list is what defines
ownership, and a list sorted by proximity is a different list for every caller. Two callers in two
zones would then disagree about which node is the primary for the same key at the same epoch, which
for a storage cluster is a correctness problem rather than a latency one.

Refusing the requirement outright is also unattractive. A library that cannot express "read locally"
forces the caller to reimplement the preference list, at which point the caller owns a second
placement function and the determinism guarantee is worth nothing.

## Decision

Read affinity is a separate call, `routeForRead(key, affinity)`, alongside the routing call that
serves writes. Reads and writes share one preference list; the read call reorders a bounded window
of it.

The window is the replica prefix. Read affinity reorders the first `r` entries, where `r` is the
achieved replica count, and never moves an entry across the boundary between the replica prefix and
the fallback tail. A caller may narrow the window further and may not widen it past `r`.

The reordering is a stable partition: entries whose failure domain path agrees with the requested
path at the requested level, in preference list order, followed by the rest in preference list
order. It is a pure function of the preference list and the affinity request, and reads no health
state.

The routing decision carries both lists. The primary is the head of the unreordered list, whatever
the read call returns, and the specification forbids using a read-affinity result to select a write
target.

The pipeline order is fixed: build the preference list, apply read affinity where requested, then
apply the health filter. Health therefore still filters a list it never reordered, and invariant 4
holds unchanged.

The specification carries this as `READ-001` through `READ-023`.

## Consequences

Ownership has one answer per epoch and latency has one answer per caller, which is the same
separation [`0007`](0007-administrative-state-and-health-state.md) draws between administrative and
health state. A caller that never requests read affinity sees no behaviour change at all.

Read affinity is available only to a caller whose consistency model tolerates reading a
non-primary replica. The library cannot detect that tolerance, so the specification states the
constraint and enforces nothing. A caller that requires read-your-writes from a single replica does
not request read affinity.

Bounding the window at the replica prefix means a caller with no local replica gets the preference
list unchanged rather than a local node promoted from the fallback tail. Promoting a tail entry
would be a read from a node that holds no copy of the shard.

Two calls rather than one is a larger surface. The alternative, a flag on the single call, was
rejected below for the same reason the separate call was chosen: a flag is easy to set by accident
on a write path.

The decision does not address read quorum. A caller assembling a quorum from several replicas reads
the whole replica prefix and orders its own concurrency; the library states which entries are
replicas and stops there.

## Alternatives

Sorting the preference list by proximity for every caller. Rejected because it makes the preference
list caller-dependent, so two callers compute different owners for the same key at the same epoch.
This is the alternative [`0007`](0007-administrative-state-and-health-state.md) rejects, restated
here because the read path is where the temptation arises.

A per-caller `locality` field in the topology document, with the strategy consuming it. Rejected
because the topology document is agreed between callers and a caller's own location is not; a
document carrying one caller's zone routes every other caller wrongly.

A boolean flag on the routing call rather than a second call. Rejected because a flag defaulting to
false is invisible in a code review, and a flag set on a write path silently produces a write to a
secondary. A separate method name appears in every stack trace and every call site.

Per-domain replication factors, so that a local replica is guaranteed rather than preferred. Left
unresolved in [`0006`](0006-failure-domain-model.md) and not adopted in format version 1. Read
affinity and a guaranteed local replica solve different halves of the problem, and the second half
needs a format change rather than a routing call.

Weighting the candidate ordering by proximity inside the strategy. Rejected because the candidate
ordering is a pure function of the snapshot and the routing key, and proximity is neither.
