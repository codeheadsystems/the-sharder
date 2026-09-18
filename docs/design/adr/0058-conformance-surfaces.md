# 0058. Conformance surfaces

Status: accepted. Date: 2026-09-17.

## Context

[`0052`](0052-conformance-level-partition.md) partitioned the conformance suite into levels and left
the document the suite tests against unpartitioned.
[`../30-conformance.md`](../30-conformance.md#conformance-levels) then claimed that "the four levels
above it are optional because the specification makes their surfaces optional", and
[`../10-specification.md`](../10-specification.md) did not.

Every requirement in the specification was unconditional. `CFG-001` required an implementation to
accept every setting named in its section, which includes the `MigrationPolicy` of `CFG-050` and the
health parameters of `CFG-030`. `MOVE-001` required a handoff to be modelled with exactly the states
it gives, and seventy `MOVE-*` requirements followed it, none of them conditional. A port that
declared `core` and `failover`, ran every case of both green, and exposed no coordinator was
non-conforming by the letter of the document it claimed to implement. The suite said one thing and
the specification said another, and the specification is the normative one.

The mirror of the same omission sat inside `core`. The level carried every placement strategy, so a
port serving an integrator who routes a tenant identifier to one of five clusters had to implement
`ring` and `slot`, their validation rules, their shard enumeration, and their vectors before it
could declare anything at all. Measured against the suite as it stands, a port exposing `rendezvous`
and `directory` runs 304 of the 442 cases a declaration of `core` reaches, and the remaining 138
exist for strategies that integrator never configures.

The scale of the document is what makes the repair a design question rather than an edit. The
specification states 636 requirements. Annotating each one with the surface it belongs to is 636
decisions to make and 636 places for the annotation to rot.

## Decision

The specification gains a conformance surface, and a requirement declares its surface through its
prefix.

A conformance surface is a named part of the behaviour the specification states that an
implementation either exposes or does not. There are nine: `routing`, the four placement strategies
`ring`, `rendezvous`, `slot`, and `directory`, and `failover`, `readAffinity`, `fencing`, and
`migration`. A requirement belongs to exactly one, and it binds an implementation that exposes that
surface. A requirement of a surface an implementation does not expose is neither satisfied nor
violated by it.

The mapping is structural. The `Surface` column of the requirement prefix table in the Conventions
section names the surface every requirement under a prefix belongs to, which covers twenty of the
twenty-three prefixes outright. The three that carry requirements of more than one surface state
their rule once each: a `CFG-*` setting belongs to the surface of the behaviour it configures, named
by a six-row table of identifier groups; an `OBS-*` requirement belongs to `routing` while each row
of the metric and event tables belongs to the surface the first segment of its name gives, so
`health.` is `failover` and `migration.` is `migration`; and the
`ERR-*` taxonomy is exposed whole by every implementation, with the requirements that state when a
condition is raised belonging to the surface that raises it. Twenty-three rows and three rules
classify 636 requirements, and a requirement added later is classified by the prefix it takes.

Four requirements state what an implementation does about a surface. `CORE-110` requires it to
expose `routing` and at least one placement strategy surface, and to state the surfaces it exposes
wherever it declares conformance. `CORE-111` requires a surface to be exposed whole, so that a
surface is a unit of declaration rather than a list of parts. `CORE-112` requires a document naming
a strategy whose surface the implementation does not expose to be refused at stage 3 of `TOPO-001`,
as `invalidTopology` carrying the rule `unsupportedStrategy`. `CORE-113` requires the set of
surfaces an implementation exposes to change no routing key, shard identifier, candidate ordering,
preference list, or effective replication factor.

`CFG-001` and `OBS-001` are scoped to the surfaces an implementation exposes, and `OBS-020` with
them. No other requirement paragraph changes, because the prefix rule reaches the rest.

The suite selects strategies on an axis of its own rather than by adding levels. Each vector file
carries the strategy surfaces the documents it names outside its cases carry, in `strategies`, and
the surfaces its cases name for themselves, in `caseStrategies`. `build_manifest.py` derives both
from the documents, so nothing is authored and nothing can drift from what a file actually needs. A
port runs the files whose `strategies` it exposes and, within them, the cases whose own documents it
exposes. A declaration names the strategy surfaces the port exposes beside the levels it reaches.

## Consequences

`30-conformance.md`'s existing claim is true as written. The levels above `core` are optional
because `CORE-110` leaves their surfaces to the implementation, and `hash`, `place`, and `core` are
not optional because each tests part of `routing`, which `CORE-110` requires.

A v0.1 Java implementation that exposes `routing`, all four strategies, `failover`, `readAffinity`,
and `fencing` is conforming while `sharder-migrate` is empty. It refuses a `MigrationPolicy` at
construction under `CFG-003` and `CORE-111` rather than accepting one it will not honour, which is
the behaviour an integrator can act on.

A port serving one strategy is now expressible. A cache vendor's port that exposes `rendezvous`
alone refuses a `ring` document under `CORE-112` with a named rule, rather than placing keys under a
strategy it has not implemented, and it declares `core` having run every case its surfaces reach.

The strategy axis is orthogonal to the level axis, so the level partition of `0052` is untouched:
every artefact still names exactly one level, and no case moved between levels to make strategies
selectable.

Two surfaces are not selectable and should not be. `routing` carries the document pipeline, because
`CORE-083` forbids a provider delivering a prepared snapshot and `TOPO-061` needs the digest to
detect an authority that republished different content at one epoch. The error taxonomy is exposed
whole because `ERR-001` closes the set, and a partial closed set is not a closed set.

The cost is one more thing a declaration says and one more column in the prefix table. The rule is
mechanical enough that `verifyDocLinks` and the coverage report keep working unchanged, because
neither reads the surface.

## Alternatives

Annotating each requirement with its surface. Rejected because 636 annotations are 636 places to be
wrong, and because the prefix already partitions the document by subject: outside `CFG-*`, `OBS-*`,
and `ERR-*`, which are 99 requirements between them, the annotation would restate the prefix on all
537 of the rest.

Softening `30-conformance.md` to match the specification, by saying that a level above `core` is
optional as a matter of what the suite asks rather than of what the library requires. Rejected
because it makes conformance mean less than a port claims: a port would run the suite in full and
still fail the document it says it implements.

Making each placement strategy a conformance level of its own, as the suite's own level mechanism
would allow. Rejected because the level axis and the strategy axis are independent. A port exposing
`rendezvous` still needs `failover`, so per-strategy levels multiply into eight levels and then
sixteen, and a file that mixes documents of four kinds, such as the canonical form vectors, belongs
to all of them at once. The derived per-file and per-case tags express the same selection without
splitting a single file.

Deriving a file's strategies from a table of file paths in the generator. Rejected for the reason
`0052` rejected the same shape: the table becomes a second place the suite's structure is written
down, and it can disagree with the documents the file actually names.

Making `routing` the name of the base surface rather than reusing `core`. Adopted, and worth
recording: `core` names a conformance level of the suite and `routing` names a surface of the
library, and the two partitions are not the same. `hash`, `place`, and `core` all test `routing`.
