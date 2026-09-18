# 0072. Preparation cost reported before preparation

Status: accepted, with the claim below that no vector moves and that events carry no assertion
superseded by [`0077`](0077-scale-conformance-level.md) and
[`0078`](0078-observability-contract-as-data.md). Date: 2026-09-17.

Extends the warning thresholds of
[`0039`](0039-placement-cost-model-and-warning-thresholds.md), whose cost model and whose defaults
stand.

## Context

[`0039`](0039-placement-cost-model-and-warning-thresholds.md) added `PLACE-073`, which compares a
total against a threshold and emits an event "at snapshot publication". Publication is stage 7 of
`TOPO-001`, and the cost the total measures is paid in stage 6. An operator whose document asks for
a preparation that takes a minute therefore learns the figure after waiting the minute, on a
pipeline that `TOPO-041` has serialised, which is the same information a stall already carries.

The `ring` row of the table named `ring` with `derived` alone. Under `derived` a node's token count
is bounded by `maxTokensPerNode`, which the schema bounds at 65536. Under `explicit` a node's
`tokens` array has no `maxItems` in `topology-v1.schema.json` at all, so the authored configuration
reaches a token total the derived one cannot, and pays the same sort in stage 6 and the same
resident size afterwards, with no threshold to cross.

## Decision

`PLACE-077` fixes the point at which a total is computed and its event emitted: before stage 6 of
`TOPO-001` begins, and before the preparation the total measures. Every total is a sum over the node
weights and the authored counts the document carries, so none of them needs a token derived, a score
framed, or an ordering sorted. A document that reaches no stage 6 emits no such event, because a
document rejected at stages 1 through 5 is abandoned and a document accepted as a no-op prepares
nothing.

`PLACE-073` drops the timing phrase and points at `PLACE-077`, and its `ring` row covers both token
assignment modes. `PLACE-074` names the authored token count alongside the derived one as a term
that makes a total large.

`topology.prepare_duration_millis` under `OBS-010` keeps its meaning and reports the preparation
after it has been paid. The two figures answer different questions: the total says what a document
is about to ask for, and the duration says what it cost.

## Consequences

An operator pushing a document that asks for tens of millions of ring entries is told before the
installing thread spends the time, so a bad document is identified from the event rather than from a
pipeline that has stopped accepting documents.

The threshold now fires on an explicit ring, where a deployment authoring its own tokens is the
deployment most likely to author a great many of them. `ringWarnTokens` keeps its default of
1000000 and its meaning under `CFG-014`, and the value 0 still disables the event.

Nothing refuses a document. A large deployment stays legitimate, and `CFG-002` and `CFG-004` keep
the set of acceptable documents independent of a caller-local setting, which is the reason
[`0039`](0039-placement-cost-model-and-warning-thresholds.md) chose a warning over a validation
rule and this record does not revisit.

No vector moves. The suite's largest topology is eleven nodes and crosses no threshold, and events
carry no assertion in the suite.

An implementation computes the totals in one pass over the document's nodes, which it performs
whatever the outcome, so the cost of the check is a term no topology makes significant.

## Alternatives

Refusing a document whose total exceeds a ceiling, as a `maxRingEntries` setting would.
Rejected for the reason [`0039`](0039-placement-cost-model-and-warning-thresholds.md) gives: the set
of acceptable documents would then depend on a caller-local setting, and two callers would disagree
about whether a published topology exists.

Leaving the emission at publication and adding a second event before stage 6. Rejected because the
two would carry the same payload and an operator would have to know which one to alert on.

Adding a threshold for `slot`, whose `slotCount` reaches 1048576 index entries. Rejected because the
count is a document member an author reads directly, and because the entries are an index over an
authored table rather than a derived structure: the preparation is a pass and not a sort.

Bounding a node's authored token array in the schema. Rejected here because it changes which
documents are valid, which is a format version question rather than a cost question, and a warning
tells an author the same thing without refusing their document.
