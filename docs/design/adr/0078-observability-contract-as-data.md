# 0078. Observability contract as data

Status: accepted. Date: 2026-09-17.

## Context

`OBS-010` names every metric with its label set and `OBS-020` names every event with the payload it
carries. Both are normative, both are permanent under the withdrawal register of
[`../10-specification.md`](../10-specification.md#withdrawn-identifiers), and neither was touched by
any vector, property, or scenario. A port could emit nothing at all, or emit every event under a
name of its own, or attach a different label set to every metric, and pass every level it declared.

[`../30-conformance.md`](../30-conformance.md) accounted for the whole group under Observability on
the grounds that a metric value is permitted to be floating point under `OBS-002` and reaches no
decision. That is true of the values. It is not true of the names, the labels, the severities, or
the payload members, each of which is a string two implementations have to agree on, of exactly the
kind the suite already carries for the closed condition set of `ERR-010`.

Three things stood in the way of carrying them.

`OBS-021` required every event to carry a severity from the closed set `info`, `warning`, and
`error`, and nothing said which. Two ports reading the same table would have chosen differently, and
an operator alerting on severity would have got different answers from each.

`OBS-020` stated its payload column in prose: "digest, node counts, prepare duration", "the epoch",
"node, prior state, new state, trigger". The requirement said an implementation "MUST carry at least
the payload given", and what was given was a description rather than a set of names. Two ports would
have spelled the members differently and both would have conformed.

Three rows disagreed with the requirements that state the same event. `REPL-022`, `SPREAD-016`, and
`FAIL-026` each require the fencing token on the event they name, and the row for each omitted it,
so the table and the requirement described different payloads.

## Decision

`OBS-020` gains a `Severity` column and states one severity per event. `OBS-021` says that the
column gives the member of its closed set each event carries, and that an implementation does not
vary it with the payload.

The payload column of `OBS-020` becomes member names rather than a description. `OBS-020` states
that an implementation carries at least those members, named exactly as given, and may carry
further members beyond them. Two payload members are renamed away from a collision with the common
members of `OBS-021`: the epoch a rejected document carried is `rejectedEpoch`, and the epoch whose
arrival supersedes or marks a plan is `installedEpoch`, because `epoch` is already the epoch in
force on every event. Three rows gain a `token` member, because `REPL-022`, `SPREAD-016`, and
`FAIL-026` each require the fencing token on the event they name and the table omitted it, so a
port reading the requirement and a port reading the table carried different payloads.

The suite gains a kind `observabilityInventory` and four files under
`conformance/vectors/observability/`, one per surface that owns rows of the two tables. A metric and
an event belong to the surface the first segment of its name gives, which the Conformance surfaces
section of [`../10-specification.md`](../10-specification.md#conformance-surfaces) already states,
so the `routing` inventory sits at `core`, the `failover` inventory at `failover`, and so on. A port
runs the inventory of each surface it exposes and no other, which keeps a mandatory level from
binding a port that declined a surface.

The inventories are read out of the specification's tables by `generate_observability.py` rather
than transcribed beside them, and the script refuses a table it cannot parse rather than writing a
shorter inventory. The closed label vocabularies of `OBS-011` are the one part still written in the
generator, because the requirement states them in prose; each value is checked against the `OBS-011`
paragraph before it is written. An event row carries a `deduplication` member for the four events
`OBS-024` names and for no other, because the requirement says nothing about the rest and a default
written into a vector would be an assertion the specification does not make. A case names `OBS-011`
and `OBS-024` only where its surface owns something they state.

The suite gains a kind `publicationEvents` and one file asserting the events a publication of a
document emits. The reference gains `sharder_ref/observability.py`, which implements the two totals
of `PLACE-073` at the point `PLACE-077` fixes, the clamp report of `PLACE-052`, the domain count of
`SPREAD-022` and the infeasible level of `SPREAD-024`, and the seed evidence of `SEC-011`. Every one
of those is decided before stage 6 of `TOPO-001` or from a scan of the accepted document, so the
file asserts the events of a thousand-node topology without preparing its placement.

## Consequences

A port that renames a metric, drops a label, changes a severity, or spells a payload member
differently fails a case that names `OBS-010` or `OBS-020`. A metric added to the specification
reaches the suite at the next regeneration rather than being transcribed by hand.

`OBS-001`, `OBS-003`, `OBS-011`, `OBS-020`, `OBS-021`, `OBS-022`, `OBS-024`, and `OBS-025` become
covered, along with `PLACE-050`, `SPREAD-024`, and `SEC-011` through the publication events. The
values behind the names stay uncovered, and
[`../30-conformance.md`](../30-conformance.md#observability) says so.

Most of the events in the table are still emitted by nothing the suite drives. The reference
installs no snapshot over time, holds no provider, follows no redirect, and drives no coordinator
through a sink, so `sharder.topology.installed`, `sharder.routing.shortfall`,
`sharder.fencing.refused`, and the `migration.` events are named by the inventory and produced by no
artefact. A port witnesses those against its own sink. The inventory is what fixes the names and the
payloads it witnesses them by, which is the part two ports have to agree on.

The severities are a judgement, and they are now a published contract that the register makes
permanent. A condition that failed is `error`, a shape an operator chose that will cost them is
`warning`, and a lifecycle event is `info`. An operator who disagrees configures their sink; an
implementation does not.

`sharder.attempts.total`, `sharder.attempts.exhausted`, and `sharder.attempts.retries_refused` sit
in the `routing` inventory at `core`, because the surface rule of `OBS-010` gives `health.` to
`failover` and every other segment to `routing`. An attempt sequence is a `failover` construct, so a
port at `core` alone registers three counters it never increments. This record does not move them:
the surface rule is stated once, in the specification, and changing which surface a published metric
belongs to is a change to what a declaration means rather than a correction to a table.

## Alternatives

Asserting metric values. Rejected for the reason `OBS-002` gives: a value may be floating point and
reaches no decision, so two conforming ports report different numbers from the same run and a vector
asserting one would assert the reference's arithmetic rather than the library's contract.

Transcribing the two tables into the generator, as the closed condition set of `ERR-010` is
transcribed. Rejected because the transcription would be a second copy of a normative table, and a
copy that drifted would weaken the check silently. The condition set is transcribed because the
suite predates this record; the tables are parsed because they can be.

One inventory file at `core` carrying every surface. Rejected because it would bind a port with no
coordinator to emit the `migration.` events, which
[`0065`](0065-level-coverage-inside-surface-boundaries.md) records as the defect to avoid in both
directions.

Leaving the severities unstated and asserting only the names and payloads. Rejected because
`OBS-021` already requires a severity, so leaving it unstated published a requirement two ports
satisfy differently. Naming them is a tightening of a contract that was incomplete rather than a
change to one that was settled.

Driving the reference through a full snapshot lifecycle so that every event is produced. Rejected as
out of scope here: it is a reference implementation of a routing library, not of a caller, and the
events it does not produce are the ones that depend on a provider, a clock, and a sink an integrator
supplies.
