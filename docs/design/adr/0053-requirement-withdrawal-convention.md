# 0053. Requirement withdrawal convention

Status: accepted. Date: 2026-09-17.

## Context

The Conventions section of [`../10-specification.md`](../10-specification.md#conventions) has
required from its first revision that an identifier name one requirement permanently, and that a
requirement be withdrawn by marking it withdrawn rather than by reassigning its identifier. The mark
itself was never defined. Nothing said where a withdrawn identifier is recorded, whether it stays
listed or vanishes, or what a reader does on meeting one in a document written before the
withdrawal.

Thirteen specification defects have been repaired without a withdrawal, because a repair restates a
requirement and keeps its identifier. Four withdrawals land together in this batch,
[`0054`](0054-range-strategy-withdrawal.md), [`0055`](0055-slot-derived-assignment-withdrawal.md),
[`0056`](0056-advisory-cutover-withdrawal.md), and
[`0057`](0057-step-budget-adjustment-withdrawal.md), and between them they withdraw sixty
identifiers and two prefixes. The format has to be decided before they are applied rather than
invented while applying them.

The conformance suite joins to the specification on these identifiers.
`conformance/generator/coverage.py` reads every identifier the specification states,
`coverage.py --check` fails where the suite names one the specification does not state, and a port
declares its conformance levels against a suite revision. Whatever the register looks like, it
cannot make a withdrawn identifier look stated.

## Decision

A requirement is withdrawn when this specification stops stating the behaviour it required. Three
rules follow.

The identifier stays listed. Its definition paragraph is removed, and a row naming it and the record
that withdrew it is entered in a register under the Conventions section. A reader resolves an
identifier by reading one table rather than by searching the corpus for an absence.

The identifier is never reused. No later requirement takes its number, and a prefix every one of
whose identifiers is withdrawn is itself withdrawn and admits no further number. `RANGE` and `SPLIT`
leave the prefix table under that rule.

A live document cites live identifiers alone, and two kinds of document keep an older reference. A
decision record states what was decided on the date it carries, so it is amended with a status line
and a paragraph naming the withdrawal rather than rewritten, and it keeps every identifier it argued
about. A conformance suite revision published before a withdrawal keeps the identifiers it was
generated against, because a port declared its levels against that revision. A reader who meets
either reference reads the register row for the identifier, and the record that row names states
what the identifier required and what, if anything, carries the behaviour now.

The register sits in the Conventions section beside the permanence rule it implements, and it is a
table rather than a list of struck-through paragraphs. `coverage.py` reads a requirement as a
backticked identifier followed by a full stop at the start of a line, and a register row is a table
cell, so a withdrawn identifier is not a stated requirement and the coverage figure is computed over
the live surface without a change to the script.

## Consequences

The specification's stated requirement count falls by the number withdrawn, and the register grows
by that number. The two figures together are the count the specification has ever stated, which is
the figure a reader needs to know that `RANGE-011` was a requirement rather than a typing error.

A withdrawal is visible at review. Removing a definition paragraph without adding a register row
leaves an identifier that resolves to nothing, and adding a row without removing the paragraph
leaves one that is both stated and withdrawn. Each is a one-line check against the specification,
and the count of definition lines against the count of register rows catches both.

A decision record that argued for a withdrawn requirement stays readable as the argument it was.
A reader who finds `SPLIT-171` in [`0051`](0051-recovery-from-an-undetermined-cutover.md) reads why
the carve-out existed, and the record's status line says it no longer does. Rewriting the record
would have lost the reasoning that made the carve-out necessary, which is what a later reader
proposing to add split lineage back needs.

The register is not a changelog. It names the identifier and the record and nothing else, so it does
not grow a second account of why each withdrawal happened, which is the record's content.

## Alternatives

Deleting the identifier outright, leaving a gap in the numbering. Rejected because the Conventions
already say a gap carries no meaning, so a deleted identifier would be indistinguishable from a
number that was never assigned, and a reader meeting `RANGE-011` in an older suite revision would
have nothing to resolve it against.

Keeping the definition paragraph and marking it withdrawn in place, struck through or prefixed.
Rejected because `coverage.py` would then count it as stated, and teaching the script to recognise a
marker makes the coverage figure depend on a formatting convention. It would also leave the
specification stating text that no implementation is to satisfy, which is the one thing a normative
document must not do.

Recording withdrawals in [`../99-roadmap.md`](../99-roadmap.md) or in a document of their own.
Rejected because the identifier is a feature of the specification, and a reader who meets one is
reading the specification. The roadmap names the rule as a one-way door and points at the
Conventions section, which is the division the corpus already uses for every other permanent
surface.

Renumbering the survivors so that each prefix stays contiguous. Rejected outright. The suite joins
on the identifier, so a renumbering silently retargets a test rather than breaking it, and
[`../99-roadmap.md`](../99-roadmap.md) already names that as the reason the permanence rule exists.
