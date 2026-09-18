# 0025. Observability contract and explain record

Status: accepted. Date: 2026-09-16.

## Context

A routing library is opaque in the way that matters most. When a key lands on an unexpected node,
the caller sees a node identity and nothing that explains it, and the chain that produced it runs
through a key transform, an override table, a strategy's arithmetic, a spread ladder, and a health
filter. Reconstructing that chain from a log line is guesswork, and guesswork about placement ends
with an operator editing a topology to see what happens.

The reporting surface also has to be portable. A metric name that is right for Prometheus is wrong
for OpenTelemetry and meaningless in a binding that has neither, and a library that depends on a
particular metrics client makes that client a transitive dependency of everything that embeds it.

Cardinality is the trap. A shard identifier is the natural label for almost every metric here, and
under `ring` a shard is a token, so a six node cluster at the default token count has 2400 shards
and a naive shard label produces 2400 time series per metric. Under `rendezvous` the shard is the
key, and the cardinality is the keyspace.

The floating-point rule cuts across this. No placement, validation, ordering, or fencing arithmetic
uses floating point, and the reason is that two ports formatting or rounding differently would route
differently. A balance ratio and a latency percentile are floating point in every metrics system
there is.

Hot-shard and key-skew detection have nowhere else to live. The library sees every routing decision,
which is exactly the population a skew measurement needs, and it sees no request volume at all,
which is exactly what a skew measurement is about. The split-advice machinery of the rebalancing
section already takes shard measurements from the integrator, and that is the same input.

## Decision

Metrics are named, their label sets are fixed, and their values may be floating point. That is the
single exception to the no-floating-point rule, and it is bounded by a requirement rather than by
convention: a metric value never reaches placement, validation, an ordering, fencing, handoff
admission, a step budget, or any threshold comparison. Skew detection reads integer counters and
compares them with integer arithmetic even though it reports a ratio.

Names follow the glossary. A metric about a preference list is named for the preference list, one
about an attempt sequence is named for attempts, and one about the achieved replica count is named
for the replica count, so an operator who has read the overview can guess a metric name and be
right.

Cardinality is bounded by rule rather than by advice. A key and a routing key are never label
values. A shard identifier is a label only under `slot`, `range`, and `directory`, and only while
the shard count is at or below `shardLabelLimit`, which defaults to 1024. Above that the label is
dropped rather than the metric. An epoch is never a label, because an epoch sequence is unbounded;
the epoch in force is a gauge instead.

Metrics go to a registry the integrator supplies and events go to a sink the integrator supplies.
Where neither is supplied the library holds counters internally and exposes them through one call,
and it starts nothing to export them. Events are delivered synchronously on the unit of execution
that produced them, and a sink that fails is counted and ignored rather than allowed to fail the
routing call that produced the event.

No event carries a key or a routing key, under any setting. The failover section already required
that of the shortfall event; it holds for every event, and the diagnostics setting that permits keys
in an error's `detail` does not reach events.

The explain record is a first-class part of the contract rather than a debugging convenience. It
carries the routing key, the key transform and its fields, the matched override entry, the shard,
the strategy's own arithmetic, the eligible set, the candidate ordering, the preference list, and
every excluded node with the stage that excluded it. Every member of the eligible set appears
exactly once, either as a candidate or as an exclusion, so the record accounts for the whole set
rather than narrating the happy path.

`explain` answers the same routing key, override, shard, candidate ordering, and preference list
that `route` answers, changes no state, and is never computed by `route` unless a caller asks for
it. Its cost grows with the node set, so it does not belong on the routing path.

## Consequences

An implementation carries two code paths that must agree: the routing path and the explain path. A
conformance vector can check that agreement directly by asserting both against one topology.

Dropping the shard label above a threshold means a large `slot` topology loses per-shard metrics at
the moment an operator most wants them. The alternative is a metrics backend that falls over, which
loses every metric, and the per-node balance gauge remains available at any size.

Naming metrics after glossary terms ties the metric names to the glossary. Renaming a glossary term
is now a metric rename, which is an operator-visible change, and that is a reasonable brake on
renaming glossary terms.

Synchronous event delivery puts the sink's latency on the calling path. An integrator whose sink is
slow makes their routing calls slow, which is visible and attributable, whereas a queue inside the
library would be invisible, unbounded, and owned by nobody.

Forbidding keys in events means an operator correlating a skew event with a particular tenant has to
do it through the shard identifier or through their own logging. That is the cost of a rule with no
exception, and a rule with an exception would be a rule that one code path forgets.

## Alternatives

Emitting metrics through a named client library, such as Micrometer or a Prometheus client. Rejected
because it makes a metrics stack a transitive dependency of a placement library, and because the
five ports in view have five different answers.

A single opaque diagnostic string on a routing decision. Rejected because it is unparseable, because
it invites a port to write whatever it likes, and because the questions an operator asks are
structural: which entry matched, which node was excluded, at which stage.

Computing an explain record on every routing call. Rejected because its cost is proportional to the
eligible node set while a routing decision's cost is proportional to the preference list, and
placing the two on one path would make every call pay for the rare one.

Sampling explain records automatically, at a configured rate. Rejected because the interesting key
is not a random key, and because a sampled record arrives after the incident. `explain` is cheap to
call deliberately on the key that misbehaved.

Detecting hot shards by sampling routing calls inside the library. Rejected because the library sees
decisions and not requests, so its sample would measure the caller's routing rate rather than its
traffic. The integrator's shard report is the population that answers the question.

Reporting skew as a floating-point ratio compared against a floating-point threshold. Rejected
because a threshold comparison is a decision, and the rule that keeps two ports agreeing is that no
decision reads a floating-point value. The integer comparison the specification gives says the same
thing and says it identically everywhere.
