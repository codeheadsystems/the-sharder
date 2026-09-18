# 0061. Suite revision identifier

Status: accepted. Date: 2026-09-17.

## Context

[`../30-conformance.md`](../30-conformance.md#declaring-conformance) requires a port to publish "the
suite revision it ran, named by the commit that produced `manifest.json`", and `manifest.json`
carried no revision. The rule asked a port to name something the artefact did not state.

The Conventions section of [`../10-specification.md`](../10-specification.md) rests on the same
notion: a conformance suite revision published before a withdrawal keeps the identifiers it was
generated against, because a port that ran it declared its levels against that revision. A
declaration that names no revision names nothing a reader can go back to.

A commit identifier is available and is the wrong thing to name. It changes when a document changes,
when a generator comment changes, and when nothing a port runs changes at all, and it is absent
wherever the suite is vendored into a port's own tree without its history.

## Decision

`manifest.json` carries a `revision` object whose `id` is the SHA-256 of the RFC 8785 canonical form
of a basis the generator computes:

- the SHA-256 of every file under `vectors/`, `topologies/`, `scenarios/`, and `properties/`, by the
  path the suite names it by,
- the level table, as each level with the levels it requires,
- the strategy surfaces.

The digest is computed the way a topology digest is computed, by
`conformance/generator/sharder_ref/jcs.py`, so the suite identifies itself with the construction it
already specifies for identifying a document.

A revision changes when any file a port runs changes, when the level structure changes, and when the
strategy surfaces change. It changes at no other time. `coverage.json` and `manifest.json` are
derived from those files rather than run, so neither is in the basis, and a coverage figure that
moves because the specification gained a requirement does not make a port's declaration stale.

`build_manifest.py` prints the revision, the reference driver prints the revision it ran, and the
declaration rule names the `revision` of the manifest a port ran rather than a commit.

## Consequences

A declaration is checkable. A reader with a suite tree computes the revision and compares it with
the one a port declared, without a repository, a checkout, or a history.

The revision is a content digest, so two trees generated from the same reference on two machines
carry the same revision, and a hand-edited vector file changes it. The suite is generated and a
hand-edited file in it is lost at the next regeneration, so a changed revision over an unchanged
generator is evidence of exactly that.

Nothing is bumped by hand, so nothing is forgotten. The alternative every project reaches for, a
version number in a file, is a number somebody remembers to change and eventually does not.

The revision changed when the `place` level landed, which is correct.
[`0059`](0059-place-conformance-level.md) moved 44 files between levels, and a port that declared
`core` against the previous revision declared it against a different partition of the same cases.

## Alternatives

A monotonic integer bumped by a maintainer. Rejected because it is a number that has to be
remembered, and a suite whose revision is stale is worse than one with no revision, since a
declaration then names a revision that never existed.

The commit that produced the manifest. Rejected because it changes for reasons a port cannot
observe and is absent where the suite is vendored.

Digesting `manifest.json` itself. Rejected because the manifest carries counts and coverage figures
derived from the specification, so a requirement added to the specification would change the
revision of a suite that had not changed.

Naming a revision per level, so that a port declaring `core` names the revision of what it ran.
Rejected because the levels share files and share the reference, and a port that reaches two levels
would then publish two revisions that can only ever move together.
