# 0017. Failover depth and substitution

Status: accepted. Date: 2026-09-16.

## Context

A preference list is longer than the replication factor, so a caller that cannot reach a replica has
somewhere to go. Three questions follow, and none of them has an answer the library can take alone.

How far down the list a caller walks is a policy question about the caller's own latency budget. The
library cannot know how long an attempt costs or how long the caller's own client is willing to
wait.

What happens when the walk runs out has to be distinguishable from the case where there was nothing
to walk. A key with no route at all and a key whose replicas are all unreachable are different
conditions with different operator responses, and collapsing them into one error loses that.

Whether walking further is safe for the cluster is a question the caller cannot answer either,
because the amplification is collective. Every caller retrying twice on a partial failure triples
the request rate against the nodes that are still up, which is how a partial outage becomes a total
one.

There is also a fourth question that Dynamo answers and the library does not have to. When a
replica is unreachable, a caller may write to a substitute and replay to the replica later. That is
hinted handoff, and [`00-overview.md`](../00-overview.md) already records it as a hook rather than a
feature. `FAIL-042` gives the hook its shape.

## Decision

The caller decides the depth, within a limit the library enforces and a budget the library accounts.

The attempt limit is a parameter of the routing call, counted in attempts rather than in preference
list positions, and defaulting to the effective replication factor plus two. A topology at factor 1
therefore has two fallback attempts by default, and a topology at factor 3 has two beyond its
replicas.

The retry budget is a sliding window over the router instance, permitting retries as a percentage of
first attempts plus a small floor, compared in unsigned integer arithmetic. It is held per router
instance and spans every key, shard, and node, because a per-key budget permits a storm assembled
from many keys. A first attempt is always permitted, so the budget can never make a key unroutable.
When the budget refuses a retry, the attempt sequence is exhausted and names the budget as the
cause.

Exhaustion and no-candidate are separate conditions. An empty eligible node set yields the
no-candidate condition. An attempt sequence that runs out after at least one attempt yields the
exhaustion condition, carrying the preference list, the nodes attempted in order, the outcome
recorded for each, and the fencing token.

The health filter is forbidden from producing an empty attempt sequence from a non-empty preference
list. Where every entry is skipped, the whole preference list is attemptable and the decision
records that the filter failed open.

Hinted handoff and sloppy quorum stay out of core. The fallback tail is the substitution mechanism,
and the role labelling on each preference list entry is what tells a caller which entries are
replicas and which are substitutes. The library exposes a `HintObserver` hook with two callbacks:
one fired when a success is recorded against a fallback entry while a replica entry was skipped,
naming the skipped replicas, and one fired when a node reaches `available` from `probation`. The
library stores no hint and replays nothing.

The specification carries this as `FAIL-020` through `FAIL-045`.

## Consequences

Failover depth is bounded twice, once per call and once across the instance, and the two bounds fail
in different directions. The attempt limit protects the caller's latency budget; the budget protects
the cluster. A caller that raises its attempt limit during an outage still cannot raise the load it
puts on the surviving nodes past the budget.

The default limit of factor plus two is a guess. It is defensible for a factor 1 tenant router,
where the fallback tail is the whole failover story, and for a factor 3 storage cluster, where two
attempts past the replicas is already a sloppy quorum. It is not defensible as a universal answer,
and it is configuration rather than a guarantee.

Failing the filter open means a caller whose health view believes the whole preference list is dead
still attempts it. Those attempts mostly fail, and they are the only way a caller discovers that its
health view is wrong, because the library sends no probe of its own.

Separating exhaustion from no-candidate means the error taxonomy carries two codes where one would
have done. The operator response differs: no-candidate is a topology problem, and exhaustion is a
reachability problem.

A retry budget shared across an instance makes one key's behaviour depend on another key's. A burst
of failures on one shard can exhaust the budget and refuse a retry that an unrelated key would have
been granted. That coupling is the mechanism, not a defect of it.

The hint hook computes the skipped replica list only when an observer is registered, so a caller
that does not implement hinted handoff pays nothing for it. A caller that does gets the two facts
the library actually knows, and owns the hint store, the payload, and the replay.

## Alternatives

A fixed failover depth, such as "walk the whole preference list". Rejected because a preference list
is as long as the eligible node set, so a large cluster would attempt hundreds of nodes for one key
under a correlated failure.

The library performing retries itself, with backoff. Rejected because the library opens no
connection and speaks no protocol, and because a retry is only meaningful against the caller's own
transport and timeout.

A per-node or per-shard retry budget rather than a per-instance one. Rejected because the
amplification that matters is the total rate against the cluster, and a caller routing a million
keys through per-key budgets has no aggregate bound at all.

An adaptive budget that learns the failure rate. Rejected as a floating-point estimator in a library
whose placement arithmetic is deliberately integer, and as a mechanism whose behaviour under a
correlated failure is hard to state as a bound a test can check.

Collapsing exhaustion into no-candidate. Rejected because a caller cannot then tell a topology
defect from an outage, and because the exhaustion condition carries the attempt history, which
no-candidate has nothing to say about.

Returning an empty attempt sequence when health skips everything. Rejected because it makes a
caller's local, possibly wrong observation into an unroutable key, which is the outcome
[`0007`](0007-administrative-state-and-health-state.md) separates the two state vocabularies to
avoid.

Hinted handoff in core, with the library holding hints. Rejected because the library stores no data
and has no durable medium; a hint that does not survive the caller's restart is worse than no hint,
and a hint that does survive it is a storage engine.

Sloppy quorum as a configured behaviour, where the library extends the replica prefix into the tail
when a replica is skipped. Rejected because that changes the replica count a caller was given
without changing the replica count the topology declared, so a durability argument stated against
the topology would no longer hold. Extending into the tail stays the caller's explicit decision.
