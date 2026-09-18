# 0060. Node label cardinality ceiling

Status: accepted. Date: 2026-09-17.

## Context

`OBS-003` bounds every label value to a bounded set, and it reasons about the one unbounded
identifier it found: a shard identifier under `ring` or `rendezvous` is never a label, and one under
`slot` or `directory` is a label while the shard count is at or below `shardLabelLimit`. `OBS-006`
reasons about `topology_id` and refuses `epoch` as a label, because an epoch sequence is unbounded.

`OBS-007` then gives `sharder.health.ejections` and `sharder.health.failure_percent` a `node` label
with no ceiling at all, and `sharder.balance.observed_share` carries one beside `topology_id`. The
deployment [`0048`](0048-conditional-fetch-in-the-provider-contract.md) reasons about has 50000
nodes.

The node label is worse than the node count suggests, and that is the part `OBS-007` did not
account for. `HEALTH-011` requires `report` to accept a signal for any node identity, including one
absent from the snapshot in force, and `HEALTH-006` keys health entries by identity and keeps them
across an epoch change. The identities a health metric would label are therefore bounded by what a
caller reports over the life of the process, not by the node count of one document. A fleet that
replaces its nodes weekly grows that set without bound, and the metric that reports an ejection is
the one an operator is least able to drop.

`OBS-026` named a further gap while this was being repaired. It requires an implementation to "emit
one event naming the suppression and the bound" when the shard deduplication structure reaches
`shardLabelLimit`, and `OBS-020` fixes event names exactly and lists no such event. The requirement
asked for an event that did not exist.

## Decision

`OBS-008` gives the node label the treatment the shard label already has. A node identity may be a
label value while the number of distinct identities the metric would carry is at or below
`nodeLabelLimit`, and is dropped above it. The count is taken over the identities the implementation
holds health entries for, under `HEALTH-006`, rather than over the node count of the snapshot in
force, because that is the set that grows.

`CFG-064` adds `nodeLabelLimit` to the observability settings, defaulting to 1024 as
`shardLabelLimit` does, with 0 meaning that a node identity is never a label value. `CFG-004` holds
for it: it changes no candidate ordering.

What happens above the ceiling differs by instrument. `sharder.health.ejections` is a counter and is
reported without the label, carrying the sum over the identities it held. The gauges
`sharder.health.failure_percent` and `sharder.balance.observed_share` are not reported at all,
because a gauge has no value over a set of nodes. `OBS-035`, which said the balance gauge is "per
node and always available", now says it is per node while `OBS-008` admits the label.

One event name is added rather than two. `sharder.observability.bound_reached` carries what was
bounded, the setting that bounds it, the bound, and the count observed. `OBS-008` emits it when the
node label is dropped and `OBS-026` emits it when the deduplication structure reaches its bound,
which closes the gap `OBS-026` left as well as serving the new rule.

## Consequences

A 50000-node deployment reports health as three unlabelled counters and an event naming the bound,
rather than as 50000 label values a metrics backend charges for and an operator cannot read. An
operator who wants the per-node series raises `nodeLabelLimit` for the fleet they have, which is the
same lever `shardLabelLimit` already gives them.

`OBS-035` loses a guarantee it should not have made. Where no `ShardMetricsSource` is supplied, the
shard label is dropped, and the node label is dropped too, the library reports neither a hot shard
nor a per-node share. That is the honest position at that cardinality: the library reports totals
and the events that name the bounds it reached, and per-node attribution comes from the integrator's
own measurement.

One permanent event name is spent, on a surface `OBS-020` fixes permanently. It is spent on the
class of thing rather than on the instance, so the next bound the library reaches costs a payload
value rather than a name.

## Alternatives

Bounding the node label by the node count of the snapshot in force. Rejected because `HEALTH-011`
accepts a signal for an identity the snapshot does not name and `HEALTH-006` retains it, so the
count that matters is the one the implementation holds rather than the one the document declares.

Leaving `sharder.balance.observed_share` outside the ceiling, on the ground that it is bounded by
the placement set. Rejected because a placement set of 50000 nodes is the case that motivated the
ceiling, and because one rule for one label is cheaper to implement and to read than two.

Dropping the label without emitting anything. Rejected because an operator who sees a counter lose
its labels has no way to tell a configuration change from a bound being reached, and because
`OBS-026` already required the event and had no name for it.

Adding a second event name for the metric label, separate from the deduplication bound. Rejected
because `OBS-020` fixes names permanently and the two are the same event with a different payload.
