# Port conventions

A port is an implementation of the sharder library in one language. This document states what every
port carries whatever the language: where its source sits, how it reads the conformance suite, the
order in which it reaches the conformance levels, the declaration it publishes, the names its
ecosystem sees, and what it is free to choose. [`40-java-binding.md`](40-java-binding.md) is the
first worked rendering of it, and what that document settles for Java is what this one leaves to a
port.

The reader is a contributor starting a port, and a reviewer checking that a port has not quietly
changed a contract the suite does not reach.

Status: one port exists. `ports/java/` reaches the `hash` and `place` conformance levels and
declares no conformance yet, and no other port has been started, so most of what follows states
what a port does rather than what one has done.

## Port identifier

A port identifier is the lower case name of the implementation language, such as `java`, `go`,
`rust`, or `ruby`. It names three things: the port's directory under `ports/`, the port's
declaration file under `conformance/declarations/`, and the port's continuous integration job.

One language carries one port. A second implementation in a language already ported is a decision
somebody records under [`adr/`](adr/) before it is started.

## Directory layout

A port's build is rooted at `ports/<port>/` and is the build its ecosystem expects, so a contributor
who knows the language recognises the tree and a consumer who fetches the artifact reads a build
file in the ordinary place.

```
ports/<port>/
├── README.md                  the port's status, the levels it reaches, and the revision
├── build.sh                   executable: builds and tests the port, from this directory
├── <the build the ecosystem expects>
├── <the library source>
└── conformance/               the port's driver, run by the port's own test command
```

`build.sh` is the only thing the repository asks of a port's build, and it asks one question: build
yourself, and exit non-zero where you failed. [`../../build.sh`](../../build.sh) at the repository
root runs every port's script, and continuous integration runs the root one over a matrix it
discovers from the tree, so a port that carries its script is built by both without either one
changing. [`adr/0082`](adr/0082-continuous-integration-and-dependency-updates.md) records that
arrangement and the dependency updates that ride on it.

Nothing above `ports/<port>/` belongs to the port's build except the conformance suite it reads and
the documents it renders. [`adr/0079`](adr/0079-repository-layout-for-multiple-ports.md) records the
layout and what it costs each ecosystem.

The driver is the port's own, in the port's language, written to the driver contract of
[`30-conformance.md`](30-conformance.md#driver-contract). A port runs it from the command its
ecosystem uses for tests, so that a contributor who runs the port's tests runs the suite.

## Suite access

A port reads the suite from `conformance/` in the repository, at a relative path from its own root,
and takes that root from one setting it documents, so that a maintainer runs the suite against an
edited tree without rebuilding anything. `sharder.conformance.dir` is the Java rendering of that
setting, under [`40-java-binding.md`](40-java-binding.md#vector-source-and-manifest).

A published artifact of a port carries a copy of the suite that the port's build produces, and the
copy is verbatim. A build that copies the tree file by file and refuses a file that differs from its
source is what keeps the packaged suite and the repository suite the same bytes, and the copy
carries `manifest.json`, so the revision it holds is the revision it was taken at.

No vector file, topology document, property definition, or scenario is copied into a port's source
tree by hand. The tree under `conformance/` is generated, and a copy made by hand is a copy that
drifts.

## Implementation order

The order a port implements the levels in is the requires relation of the `levels` table of
[`../../conformance/manifest.json`](../../conformance/manifest.json), which
[`30-conformance.md`](30-conformance.md#conformance-levels) states and explains. The first vector
files a new driver runs are named in
[`../../conformance/README.md`](../../conformance/README.md#running-the-suite).

`hash`, `place`, `core`, and `scale` are not optional, so a port carries no declaration until it
reaches all four. Until then `ports/<port>/README.md` states which levels pass, and the absence of a
declaration is what says the port is unfinished.

## Conformance declaration

A port declares conformance in a JSON file at `conformance/declarations/<port>.json`, which carries
the seven things [`30-conformance.md`](30-conformance.md#declaring-conformance) requires a port to
publish. The file is written by hand and is a claim the port's maintainer makes.

| Member | Contents |
|---|---|
| `port` | the port identifier, which is also the file's name |
| `version` | the release the declaration describes, or `unreleased` |
| `revision` | the `id` of the `revision` object of the `manifest.json` the driver ran |
| `strategySurfaces` | the placement strategy surfaces the port exposes, of which `CORE-110` requires at least one |
| `levels` | one entry per level of the manifest's `levels` table, each `reached` or `excluded`, an excluded entry naming the conformance surface the port does not expose |
| `run` | the command that runs the driver, the path of its stored output, the vector file and case counts, and the failure count |
| `scale` | the wall time in milliseconds, the peak resident size in bytes, the machine, and the runtime, observed at the `scale` level |
| `deviations` | one entry per deviation, naming the requirement, the reason, and the case names the deviation shows up in, which may be none |

A deviation is permitted only against a requirement carrying `SHOULD`, `SHOULD NOT`, or `MAY`, which
is the rule [`30-conformance.md`](30-conformance.md#declaring-conformance) states and the check
below enforces.

The shape of a declaration, with the digest abbreviated:

```json
{
  "port": "java",
  "version": "unreleased",
  "revision": "…",
  "strategySurfaces": ["ring", "rendezvous", "slot", "directory"],
  "levels": {
    "hash": { "state": "reached" },
    "place": { "state": "reached" },
    "core": { "state": "reached" },
    "scale": { "state": "reached" },
    "failover": { "state": "reached" },
    "readAffinity": { "state": "reached" },
    "fencing": { "state": "reached" },
    "migration": { "state": "excluded", "surface": "migration" }
  },
  "run": {
    "command": "./gradlew :sharder-conformance:test",
    "report": "ports/java/conformance/report.txt",
    "vectorFiles": 0,
    "vectorCases": 0,
    "failures": 0
  },
  "scale": {
    "wallTimeMs": 0,
    "peakResidentBytes": 0,
    "machine": "…",
    "runtime": "…"
  },
  "deviations": []
}
```

### Declaration verification

`conformance/generator/verify_declarations.py` checks every declaration against `manifest.json` and
[`10-specification.md`](10-specification.md), and `run.sh` runs it. It fails on a claim either
document contradicts, such as a level the suite does not carry, a reached level whose required
levels are not reached, a strategy surface the manifest does not list, a case count above the
manifest's total, or a deviation against a requirement that carries none of the three keywords a
deviation is permitted against.

A declaration naming an earlier revision than the manifest holds is reported as lagging rather than
failed, because regenerating the suite is what makes a declaration lag.
[`adr/0080`](adr/0080-conformance-declaration-format.md) records the format, the whole check list,
and what the check does not establish.

## Release tags and versions

A port's release is tagged `ports/<port>/vX.Y.Z`. Go requires that shape for a module in a
subdirectory and every other port takes it, so one convention covers the repository.

No port publishes anything before the release, under
[`adr/0083`](adr/0083-publication-as-the-last-stage.md): a port that has not passed the suite at the
levels its surfaces commit it to has nothing to tag, and an artifact a consumer can resolve closes
the one-way doors of [`99-roadmap.md`](99-roadmap.md#one-way-doors) whatever it is called.

A port versions independently of every other port, and a version states nothing about conformance.
What a release conforms to is the declaration, which names a suite revision, and the topology
document format carries its own version under
[`20-topology-format.md`](20-topology-format.md#versioning-and-compatibility).

## Ecosystem coordinates

A port fixes its coordinates in a decision record before it publishes anything, because a registry
name, a module path, and a package root appear in every consumer's build file and are expensive to
move afterwards. [`adr/0028`](adr/0028-java-module-and-artifact-layout.md) and
[`adr/0081`](adr/0081-single-java-module.md) are those records for Java, the second fixing how many
artifacts carry the library.

Two rules bind every ecosystem. An artifact, a crate, a gem, a module, or a package carries the name
`sharder` rather than the name of the repository, and the `the-` prefix belongs to the GitHub
repository alone. A Go module path follows the port's directory, under
[`adr/0079`](adr/0079-repository-layout-for-multiple-ports.md), because the language derives the
import path from it.

A registry name is global and is held by whoever registers it first. Registering the name is part of
the record that fixes it, rather than a step left to the first release.

## Independence from the reference

No port shares source with `conformance/generator/sharder_ref`. The reference computed every
expected value in the suite, so an implementation that calls it agrees with it by construction and
the vectors it passes assert nothing.

[`../../conformance/driver/python/run_suite.py`](../../conformance/driver/python/run_suite.py)
drives that reference. It is the worked example of the driver contract and is not a port of the
library, and a Python port implements the specification from the specification.

## Fixed and free choices

What two ports agree on is fixed by [`10-specification.md`](10-specification.md), and the suite is
what holds them to it. What a port renders in its own idiom is everything the suite cannot observe.

| Fixed | Free |
|---|---|
| every ordering, tie-break, and shard identifier | the names and the casing of types and functions |
| the error codes, the error names, and the retryable flags of `ERR-010` | whether a condition is an exception, a result, or an error value |
| the level names and the requires relation of the `levels` table | the test framework the driver runs under |
| the topology document format, the canonical form, and the digest | the JSON reader, where the language's policy admits one |
| the conformance surfaces of `CORE-110` and what exposing one commits a port to | which optional surfaces a port exposes at all |
| integer discipline on the placement path, under `HASH-043` and `OBS-002` | the integer type the language offers to express it |
| the fencing token shape of `FENCE-011` | the transport a caller carries it on |

The internal structure of a port is free, because no vector observes it, which
[`99-roadmap.md`](99-roadmap.md#reversible-decisions) records as a reversible decision.

## Port documentation

A port carries `ports/<port>/README.md`, which states the port's status, the levels it reaches, and
the suite revision it ran, and a binding document under `docs/design/`, numbered in the forties in
the order the ports start. [`40-java-binding.md`](40-java-binding.md) is the first.

A binding document fixes what the specification leaves to a language: the artifact set, the public
types, the error idiom, the concurrency contracts, the harness, and the build gates. A judgement
inside it that somebody could reasonably make the other way carries a decision record, as every
other judgement in this repository does.

[`../maintain/style.md`](../maintain/style.md) binds every Markdown file under `ports/`, as it
binds every other Markdown file in the repository.

## Decision records

| Record | Subject |
|---|---|
| [`adr/0079-repository-layout-for-multiple-ports.md`](adr/0079-repository-layout-for-multiple-ports.md) | one repository, `ports/<port>/`, and the release tag shape |
| [`adr/0080-conformance-declaration-format.md`](adr/0080-conformance-declaration-format.md) | the declaration file, its checks, and what they do not establish |
| [`adr/0061-suite-revision-identifier.md`](adr/0061-suite-revision-identifier.md) | the revision a declaration names |
| [`adr/0028-java-module-and-artifact-layout.md`](adr/0028-java-module-and-artifact-layout.md) | the coordinates of the first port |
| [`adr/0081-single-java-module.md`](adr/0081-single-java-module.md) | one artifact for the first port, and what that defers |
| [`adr/0082-continuous-integration-and-dependency-updates.md`](adr/0082-continuous-integration-and-dependency-updates.md) | how a port is built, and how a dependency update merges |
