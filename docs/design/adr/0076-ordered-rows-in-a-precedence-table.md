# 0076. Ordered rows in a precedence table

Status: accepted. Date: 2026-09-17.

## Context

The specification states several decisions as a table of conditions against outcomes. Two of them,
in `ERR-008` and `ERR-045`, state their precedence in prose: "the first that holds in this order".
Five did not, and in each of the five two rows hold together for inputs a cluster produces.

`ERR-021` gave the `noCandidate` causes as a closed set with no ordering, and the conditions that
produce them sat in `ERR-020` in a different order again. A key matched by an override carrying both
a `pin` and a `constrain`, under `OVR-030`, over a `directory` whose matched entry excludes every
node, satisfies three of them.

`TOPO-061` listed an acceptance row for a first document above `minEpoch` before the row for a
foreign `topologyId`, so a first document under the wrong identifier matched both, and the row a
reader reached first was the wrong one.

`FENCE-071` listed the `identityMismatch` row before the `unknownEpoch` row, and a recipient holding
no snapshot holds no identifier to compare the token's against, so the first row was not evaluable
where the second held.

`HEALTH-043` gave three ejection triggers joined by "any of these holds". All three feed one
`trigger` value in the `health.transition` event of `OBS-020`, and a node failing every attempt
reaches all three together.

`MOVE-211` mapped an observation to a resumed state, with a row for "no record, `sourceQuiesced`
true" above a row for "no record, `destinationPrepared` true". A quiesced source was a prepared
destination first, so the two hold together whenever the source quiesced, and resuming at
`transferring` rather than at `cutover` repeats a transfer of a shard that had already stopped
taking writes. `MOVE-021` restates the same mapping as its five rows leaving `failed`.

In each case the reference generator picked an order, so the suite encodes one answer and the
specification admits several. A port that read the rows as an unordered set would report a different
cause, a different condition, a different relation, a different event payload, or a different
resumed state from the same input, and would still be conformant against the text.

## Decision

A table whose rows can both match states that they are evaluated in the order written and that the
first row that holds decides. The five tables above say so, and each carries a sentence naming the
overlap that makes the order load-bearing, so that a reader who reorders the rows knows what they
have changed.

Two tables are reordered rather than annotated, because the order that was written was not the order
the reference evaluates and the reference is what the published suite was generated against. The
identity row and the `minEpoch` row of `TOPO-061` move above its first-document row, and the
`unknownEpoch` row of `FENCE-071` moves above its `identityMismatch` row. Neither reordering changes
any value the suite carries.

`ERR-021` becomes a table of condition against cause rather than a set of cause names, because the
conditions that select among them were in a different requirement in a different order.
`emptyPlacementSet` appears in two rows, first for a document with no node to place on and last for
an ordering emptied for a reason no other row names, which is the shape the reference has.

Where a table's rows are mutually exclusive the table says nothing about order, because a statement
that cannot change an outcome is one more sentence to keep true.

## Consequences

Two ports now agree on which cause, condition, relation, trigger, and resumed state they report from
an input that satisfies more than one row, which is what a cross-language contract has to fix.

Nothing moves in the conformance suite for these five tables. Each order stated is the order the
reference generator already evaluated, so the choice recorded here is to describe the reference
rather than to change it.

A reordered table is an interface change for a reader who cited a row by position, and no document
does. Rows are cited by their condition throughout.

The convention is not enforced by a check. Whether two rows of a table can both hold is a question
about their conditions, which a regular expression cannot answer, so it stays with the reviewer in
the way [`0062`](0062-documentation-style-check-as-a-warning.md) describes for the style rules.

## Alternatives

Making the rows mutually exclusive by writing every condition with the negations of the rows above
it. Rejected because the conditions become long enough to be misread, and because the negations
restate the order in a form that a later edit can break silently. `ERR-021`'s third row would carry
the negation of two strategy tests that do not apply to it.

Leaving the reference as the tie-breaker and pointing a reader at it. Rejected because the reference
generator is an oracle for the suite and is not normative, and because a port is written from the
specification.

Stating the convention once in the Conventions section and applying it to every table. Rejected
because most tables in this document are not precedence tables: a table of metric names, of settings
and defaults, or of states and meanings has no order to state, and a blanket rule would invite a
reader to look for one.

Turning each precedence table into a numbered list. Rejected because the tables carry two and three
columns whose headers name what each column is, and a list would lose that.
