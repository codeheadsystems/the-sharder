# 0083. Publication as the last stage

Status: accepted, superseding the release staging of
[`../99-roadmap.md`](../99-roadmap.md). Date: 2026-09-18.

## Context

[`../99-roadmap.md`](../99-roadmap.md) staged two releases: a first carrying the routing surfaces
and a second carrying migration. No decision record argued that split. The records written after it,
[`0052`](0052-conformance-level-partition.md), [`0058`](0058-conformance-surfaces.md), and
[`0077`](0077-scale-conformance-level.md), describe what a first implementation would declare and
take the staging as given, so the judgement was made once, in a document that states rather than
argues, and inherited everywhere after.

A staged publication buys one thing: a partial surface in the hands of adopters, whose use answers
questions the design left open. This project has no adopters. Nothing is published, nothing resolves
from a registry, and the decision in force is that nothing will until the ports pass the conformance
suite in their own harnesses.

What the split costs is visible in the design. The first release carried `fencing`, which is the
recipient check and the redirect walk, and the second carried the handoff those most protect. An
integrator on the storage path, where a wrong decision is data loss rather than latency, would have
had the guard before the thing it guards.

The larger cost is the one-way doors. The hash construction, the topology format at major version 1,
the error taxonomy, the level partition, the provider contract, and the strategy kind names all stop
being revisable the moment something outside this repository rests on them. A release before the
whole surface has been implemented closes those doors on evidence the implementation has not yet
produced, and the migration surface is the part of the design most likely to produce it: it is the
only surface with a protocol, a clock, and an external effect.

## Decision

One release, and it comes last.

Nothing is published until every port in the repository passes the conformance suite at the levels
its surfaces commit it to, in its own test harness, and carries a declaration under
`conformance/declarations/`. Published means anything a consumer can resolve: an artifact in a
registry, a release tag a build file can name, or a suite revision offered as stable.

The release then publishes the specification, the topology document format and its schema, the
conformance suite with its revision, and every port that has declared, together. The migration
surface is part of that release rather than of one after it.

The order the work is done in does not change, because it was never the release boundary that set
it: `migration` requires `failover`, which requires `core`, which requires `place`, which requires
`hash`, and the `levels` table of `manifest.json` is what states that. What changes is that the
stages are milestones rather than releases, and that the boundary sits after the last of them
rather than between `fencing` and `migration`.

## Consequences

Every one-way door stays open until the last stage. A defect found while implementing `migration`
can still move a requirement, a vector, the format, or the level partition, because nothing outside
this repository rests on any of them. A suite revision that changes costs a re-run by the ports in
this repository rather than a re-declaration by somebody who has already published one.

Nothing ships until everything works. There is no partial value in the meantime and no feedback from
outside the repository at all, so an interface that is awkward to call shows up in a port's harness
or nowhere. That is the cost, and it is accepted because the alternative buys feedback from adopters
who do not exist.

`fencing` and `migration` are implemented before either is published, so the recipient check and the
handoff it guards arrive together.

`OQ-02` still blocks the release, and the evidence its entry names is a second port. Answering it by
the recommended default, which is the shapes the Java binding chose, remains available and is the
only route where the release comes before a second port. This record does not answer it and does not
require a second port; the release gate is every port that exists.

The records written under the earlier staging keep their reasoning. The level partition of
[`0052`](0052-conformance-level-partition.md), the surfaces of
[`0058`](0058-conformance-surfaces.md), and the `scale` level of
[`0077`](0077-scale-conformance-level.md) each describe what a first implementation declares, which
is now every level rather than seven of the eight. Nothing any of them decided moves, and none is
rewritten, because a record states what was decided on the date it carries.

## Alternatives

The two releases as staged. Rejected because the split's benefit is early adoption, there is none to
be had, and the split publishes the fencing surface before the handoff it protects.

Publishing the specification and the suite before the ports, and the ports afterwards. Rejected
because a published suite revision invites a declaration against a revision that is still moving,
and because the suite is generated from the specification, so publishing one fixes the other.

A snapshot or pre-release artifact per stage, so that something resolves early. Rejected because an
artifact that resolves is an artifact something depends on, whatever it is called, and the doors
close on the first one rather than on the first that calls itself stable.

Requiring a second port before the release. Not taken here. Whether the three requirements of
`OQ-02` are settled by a second port or by the recorded default is that question's to answer, and
this record leaves the gate at every port that exists.
