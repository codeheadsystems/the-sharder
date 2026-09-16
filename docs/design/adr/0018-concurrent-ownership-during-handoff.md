# 0018. Concurrent ownership during handoff

Status: accepted. Date: 2026-09-16.

## Context

Moving a shard from one node to another takes time. During that time the destination accumulates a
copy while the source keeps serving. Two nodes therefore hold the shard's contents at once, and the
question is not whether that happens but what decides which of them answers.

The obvious answer is the topology epoch: the node that the epoch in force says owns the shard is
the owner. That answer does not survive contact with the deployment. Callers install a new epoch at
different instants, because they poll on different schedules and subscribe to different watch
streams, so there is no instant at which the cluster changes its mind together. A source and a
destination can hold different epochs for seconds. If each of them derives its authority from its
own epoch, both answer, and for a storage cluster that is data loss rather than inefficiency.

The library runs no consensus, so it cannot arbitrate between them itself.

## Decision

Two nodes may hold the contents of one shard at the same time. At most one of them is the
authoritative owner at any instant, and the cutover record decides which. The topology epoch decides
where a caller sends a request first; the cutover record decides who answers it.

The cutover record is a durable, single-winner write that the integrator's movement hooks perform,
against a store that both the source and the destination read. The library sequences the steps
around it and never writes it. `commitCutover` returns `committed`, `alreadyCommitted`, or `lost`,
so a repeated call is answerable and a losing destination learns that it lost.

Before the record exists the source answers and the destination refuses. After it exists the
destination answers and the source refuses. A refusal names the node that does own the shard, and
the caller retries there under a bounded redirect walk. The caller therefore observes at most one
redirect per topology change rather than an outage, and never observes both nodes answering.

The window in which neither node accepts a write is the interval between `quiesce` succeeding and
`commitCutover` returning. The refusal during that interval is retryable.

The hooks declare the strength of the primitive they implement, as `linearisable` or `advisory`. A
migration policy carries `requireLinearisableCutover`, defaulting to true, so an integrator whose
store cannot serialise the write opts in to the weaker guarantee explicitly rather than discovering
it later. Under `advisory` the coordinator waits a non-zero grace window after quiesce before
committing, which converts an exclusivity claim into a time bound and says so in every event.

The specification states separately which properties are real and which are best-effort.
Single ownership, the survival of acknowledged writes, and verification before cleanup are real
under a `linearisable` declaration. Landing a caller's first attempt on the owner, stopping a
partitioned source promptly, and completeness without a catch-up pass are best-effort always.

## Consequences

The safety of the storage use case rests on one integrator-supplied primitive rather than on the
library. That is the honest position for a library that opens no connection and runs no consensus,
and it is stated rather than implied: `MOVE-321` and `MOVE-401` make the dependency explicit, and
the guarantee level appears in the plan summary and in every handoff event.

A caller pays one redirect per moved shard around a topology change, and the redirect bound of 2
keeps a misconfigured cluster from turning that into a walk.

Ordering between the cutover and the publication of the target epoch stops mattering for
correctness. Committing first costs a redirect for callers still on the old epoch; publishing first
costs a redirect for callers already on the new one. Neither loses a write, so an authority may
publish on whatever schedule suits it.

An integrator whose store offers no serialised write still gets a sequenced migration, and gets told
in every event that mutual exclusion is not among its properties.

## Alternatives

Epoch as the sole authority, with no cutover record. Rejected because callers install epochs at
different instants, so two nodes hold different epochs during every change and both would believe
they own the shard.

A global barrier at the epoch install, cutting every shard over at once. Rejected because it
requires every caller to install simultaneously, which is consensus, and because a plan of a
thousand shards would hold every one of them in the concurrent window until the slowest finished.

Forwarding from the old owner to the new one. Rejected because the library would have to speak the
storage protocol to forward, which invariant 11 forbids, and because a forwarding chain across
several epochs is unbounded.

Refusing to overlap at all, by quiescing the source for the whole transfer. Rejected because the
shard is unavailable for writes for the length of a bulk copy, which for a storage node is minutes
to hours.

Leases from the library, with the library granting the destination a time-bounded ownership lease.
Rejected because issuing a lease requires a clock the library trusts and a party that both nodes
trust, which is an election.
