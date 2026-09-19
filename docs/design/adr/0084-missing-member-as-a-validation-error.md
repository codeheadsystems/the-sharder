# 0084. Missing member as a validation error

Date: 2026-09-18

Status: accepted

## Context

`TOPO-001` stages a document's acceptance, and stage 2 is schema validation. The schema requires
five members: `formatVersion`, `topologyId`, `epoch`, `strategy`, and `nodes`. The semantic rules of
stage 3 read all five, and neither the reference implementation nor the suite said what happens to a
document carrying none of them.

The reference answered such a document with an empty error list, which reads as valid, and the
generator could not express a case for it: `build_validation_vectors` asserts that the reference
produced at least one error before it writes a case, so a document refused by the schema alone
produced no vector. The suite therefore carried no case for stage 2 at all, and a port that
implemented no schema check passed it.

The first port to meet the gap did so in a unit test rather than in the suite: the Java port read
`nodes` without checking it was there and raised a language-level exception rather than reporting a
validation error, which turns an operator's typo into a stack trace and loses the other errors the
document carries. `ERR-030` requires every error rather than the first, so a reader that stops at
the first absent member reports less than the requirement asks for.

Two repairs were available. The suite could gain a vector kind whose expectation is the schema
error, leaving the semantic validator silent about absence. Or the reference could report an absent
required member as a validation error of its own, which makes the case an ordinary row of the
existing validation vectors.

## Decision

The reference reports an absent required member as a validation error with the rule `missingMember`
and the path of the member, and answers with those errors alone rather than continuing into the
rules that read the missing member. `conformance/topologies/invalid/` gains `missing-nodes` and
`missing-strategy`, and `vectors/core/validation.vectors.json` carries a case for each.

A port therefore refuses such a document with `invalidTopology`, reports one error per absent
member, and reaches the same rule name a vector joins on.

## Consequences

The suite covers the stage it did not, and the coverage is joined to the specification through the
requirements the validation vectors already name.

An absent member shortens the error list rather than lengthening it: the rules below an absent
member are not evaluated, so a document missing `nodes` and carrying a duplicate override matcher
reports the absence alone. The alternative reports errors derived from a member that is not there,
which is noise rather than help, and a second run after the absence is repaired reports the rest.

A port whose language raises on an absent member rather than reporting it now fails a vector rather
than passing the suite and failing an operator. The Java port was the first to be repaired this way.

The suite revision changes, so every declaration is refreshed against it, by the procedure
[`../../../CONTRIBUTING.md`](../../../CONTRIBUTING.md) states.

## Alternatives

A vector kind for a schema-stage refusal. The generator would write a case whose expectation is
the schema error rather than a semantic rule, and the reference would stay silent about absence.
Rejected because the schema is an artefact a port may not read: `adr/0032` records that this
repository's own binding validates without a schema validator, so a vector that can only be
satisfied by running the schema is a vector that port cannot pass. A rule name every port computes
is the join key the suite is built on.

Reporting the absence and continuing. The validator would report `missingMember` and then
evaluate the remaining rules against the members that are there. Rejected because a rule reading an
absent member either guesses a default or fails, and both answers are worse than the absence itself:
the errors that follow are derived from a document nobody wrote.

Leaving the gap and relying on the schema. `run.sh` already validates every generated topology
against `topology-v1.schema.json`, so the repository's own documents cannot carry the defect.
Rejected because the suite is what a port is checked by, and the schema check runs over the
generator's output rather than over a port's reader.
