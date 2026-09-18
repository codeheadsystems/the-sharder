# 0052. Conformance level partition

Status: accepted, with the split lineage vectors withdrawn by
[`0054`](0054-range-strategy-withdrawal.md), a `place` level added by
[`0059`](0059-place-conformance-level.md), a strategy axis added by
[`0058`](0058-conformance-surfaces.md), and the `scale` level this record anticipated added by
[`0077`](0077-scale-conformance-level.md). Date: 2026-09-17.

The partition holds. `migration` loses `vectors/split/lineage.json` and keeps the rate control
formulas and the handoff scenarios, which is a set of artefacts shrinking rather than a level
moving, and no other level gains or loses a file because of it. The two prerequisite edges above
`core` and the rule that each artefact names its own level are unchanged.

`0059` adds the level this record said a later level would be: 44 files move from `core` to `place`
and `core` requires it, which is the data change this record's Consequences describe. `0058` selects
placement strategies on an axis orthogonal to the levels rather than by adding levels, so every
artefact still names exactly one level and no case moved to make a strategy selectable.

## Context

[`../30-conformance.md`](../30-conformance.md#conformance-levels) states the rule by which a port
declares conformance, and it states that rule in terms of levels. The levels were defined over
vector kinds and by exclusion: `hash` was the `siphash` and `hash` vector sets, `core` was `hash`
plus every vector set of a kind other than `readAffinity`, and `failover`, `fencing`, and
`migration` named subsets of what `core` had already swallowed. Nothing carried a level in the data.
The reference driver held a `kind` to level table in its own source, and
[`0035`](0035-manifest-driven-conformance-harness.md) described a harness that discovers every
level from `manifest.json`, which the manifest did not carry.

Two consequences followed, and the second is the material one.

A level could not be finer than a vector kind. All six files of kind `formula` mapped to one level,
and they do not share a subject: `vectors/formulas/health.json` is the health state machine,
`vectors/formulas/failover.json` is the retry budget and the attempt limit,
`vectors/placement/identity-comparison.json` is the final tie-break of every ordering,
`vectors/formulas/detection-and-fencing.json` was hot shard detection, key skew detection, and the
fencing token encoding in one file, `vectors/formulas/migration-rate.json` was the migration step
budget together with the virtual node count of `PLACE-050`, and `vectors/split/lineage.json` is
range split and merge lineage.

So `core` contained migration rate control and split lineage. [`../99-roadmap.md`](../99-roadmap.md)
puts the handoff coordinator, rate control, and lineage in v0.2, and
[`../40-java-binding.md`](../40-java-binding.md) puts the lineage classifier and `MigrationPolicy`
in `sharder-migrate`, which v0.1 does not implement. The v0.1 Java implementation was therefore
scoped to declare a level it could not reach, and the roadmap already claimed it would.

Levels are published. A port declares its levels against a suite revision, three further ports are
scheduled, and a declaration that named a level whose contents later moved would be a claim about a
different thing under the same word.

## Decision

The levels partition the suite, each artefact names its own level, and the manifest publishes the
structure.

A vector file carries a `level` member beside `kind`. A scenario carries one in
`scenarios/index.json`. A property already carried one in `properties/properties.json`. No artefact
carries two, and `build_manifest.py` refuses a generated file that names no level or an unknown one,
so a vector family added without a level fails the regeneration rather than defaulting into `core`.

`manifest.json` gains a `levels` table: each level with the levels it requires, the surface it
covers, and what it carries. The reference driver reads that table and holds no level table of its
own. `--level` runs the named level together with the levels it requires, transitively, which is
what a port declaring that level runs.

The six level names are unchanged and their contents are disjoint:

| Level | Requires | What moved into it |
|---|---|---|
| `hash` | | unchanged |
| `core` | `hash` | the skew detection formulas and the virtual node count, both split out of files that mixed subjects |
| `failover` | `core` | the health and retry budget formulas, which `core` had also contained |
| `readAffinity` | `core` | unchanged |
| `fencing` | `failover` | the fencing token encoding, split out of the detection file |
| `migration` | `failover` | the rate control formulas and the split lineage vectors, which `core` had contained |

Two vector files split by subject, and no expected value moves with them.
`vectors/formulas/detection-and-fencing.json` becomes `vectors/formulas/skew-detection.json` at
`core` and `vectors/formulas/fencing-token.json` at `fencing`.
`vectors/formulas/migration-rate.json` sheds its six `virtualNodeCount` cases to
`vectors/placement/virtual-node-count.json` at `core`. The suite goes from 59 vector files to 61 at
an unchanged 640 cases, and every case body is byte-identical across the split.

`coverage.json` gains a `byLevel` section naming, for each level, the requirement identifiers its
own artefacts name and the count a declaration of that level reaches once the required levels are
added. A declaration therefore says what it proves in the same identifiers the specification states.

A declaration names every level of the suite revision, marked reached or excluded, rather than
naming the levels reached alone.

## Consequences

The v0.1 Java implementation declares `hash`, `core`, `failover`, `fencing`, and `readAffinity`,
which is what [`../99-roadmap.md`](../99-roadmap.md) already claimed and could not previously mean.
`core` was 53 vector files, 462 cases, one scenario, and 24 properties, and nothing in it reaches
`sharder-migrate`. v0.2 adds `migration` without moving anything already declared.

A port may now declare `core` while implementing no health view, which the old definition made
impossible by folding the health formulas into `core`. The declaration rule is what makes that
visible rather than silent: a declaration lists `failover` as excluded and names the surface it does
not expose, and `0035` already requires the harness to report a level out of scope as a declared
exclusion rather than as a pass.

A level added later is a data change. The suite's largest topology was eleven nodes, so a `scale`
level over larger documents is a plausible addition; it arrives as new vector files carrying
`"level": "scale"` and one row in the `levels` table, and it changes nothing an existing level
contains, because the levels are disjoint rather than nested.

The two prerequisite edges above `core` are real rather than decorative. `fencing` requires
`failover` because `FENCE-231` bounds the redirect walk by the retry budget of `FAIL-031`, which
`scenarios/redirect-walk-depth-limit.json` exercises. `migration` requires it because
`scenarios/node-dies-mid-migration.json` ejects a destination through the health view and expects
the coordinator to compensate. A port cannot reach either level without the failover surface.

The manifest's shape changes, which breaks every harness written against the previous one. There is
no harness yet outside the reference driver, so the cost falls now rather than after a port exists,
which is the reason to take the change before v0.1 publishes rather than after.

`vectors/formulas/detection-and-fencing.json` is named by
[`0041`](0041-exact-product-comparison-surface.md) and by
[`../40-java-binding.md`](../40-java-binding.md), and both now name
`vectors/formulas/skew-detection.json`. The cases they cite keep their names and their expected
values, so the claims those documents make about them still hold.

## Alternatives

Leaving the levels nested, with `core` containing every other level's vectors. Rejected because it
is what makes v0.1 undeclarable: the smallest honest unit a port can claim is then the whole suite
minus read affinity, and a port that ships routing without migration has nothing to say.

Keeping one level per vector kind and moving the whole `formula` kind to `migration`. Rejected
because `vectors/formulas/migration-rate.json` carried the virtual node count of `PLACE-050` to
`PLACE-052`, which every strategy that weights a node evaluates, and a `core` that does not test it
tests less than the old one did. The kind describes what a driver does with a case; the level
describes which surface the case belongs to, and the two are not the same partition.

Adding the level to the manifest alone, computed by `build_manifest.py` from a table of file paths.
Rejected because the manifest is regenerated from the files, so the table would be a second place
the suite's shape is written down, and a vector file read on its own would not say what it belongs
to.

Giving the level a requirement identifier in [`../10-specification.md`](../10-specification.md).
Rejected because the specification states what an implementation does and the levels state what a
suite revision asks a port to run. A level is a property of the suite, and moving one is a change to
the suite rather than to the library.

Making `migration` require `fencing`. Rejected because no migration scenario and no lineage vector
names a `FENCE-*` requirement: the coordinator's states are driven by the integrator's durable
observations under `MOVE-211`, not by a recipient verdict. The edge would be an unearned constraint
on a port that implements handoff without exposing the recipient check.
