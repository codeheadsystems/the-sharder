# 0079. Repository layout for multiple ports

Status: accepted. Date: 2026-09-18.

## Context

The repository holds a specification, a topology format and its schema, a conformance suite with the
reference that computed it, and one binding document, for Java.
[`../99-roadmap.md`](../99-roadmap.md#later-releases) schedules implementations in further
languages, and a second implementation is what the determinism requirement exists for: a vector file
asserts that two implementations agree, and until a second one runs it the suite has only been read
by the reference that wrote it.

Three facts in the repository constrain where the source of an implementation can sit.

The suite is computed against the specification text. `conformance/generator/coverage.py` reads
`docs/design/10-specification.md` to extract every requirement identifier,
`conformance/generator/verify_withdrawals.py` reads that document and
`docs/design/90-open-questions.md` for the two withdrawal registers, and
`conformance/generator/build_manifest.py` records both paths in the manifest. Moving the suite to a
repository of its own means carrying the specification with it or fetching the specification across
a repository boundary to regenerate.

The suite revision joins the implementations to each other.
[`0061`](0061-suite-revision-identifier.md) makes the revision a digest of the suite's own content,
and the declaration rule of [`../30-conformance.md`](../30-conformance.md#declaring-conformance)
makes an implementation name it. A specification repair regenerates the tree, changes the revision,
and leaves every declaration naming a revision that is no longer current, in one commit.

The Java binding was written as the only implementation and placed its build at the repository root.
[`../40-java-binding.md`](../40-java-binding.md#gradle-project-structure) put `settings.gradle.kts`,
`buildSrc/`, and the `sharder-*` projects beside `conformance/` and `docs/`, and
`sharder-conformance-vectors` copies `conformance/` from the repository root. A second language has
nowhere to sit that does not nest inside the Java build or stand beside it asymmetrically.

Against one repository stands the shape of each language's tooling. A Go module in a subdirectory
carries that subdirectory in its module path and in every release tag. Cargo and RubyGems publish
from a subdirectory without objection, and draw their names from a global registry rather than from
a path. A continuous integration run that builds four toolchains for a change to one document is
waste unless the jobs are filtered by path.

## Decision

One repository, `codeheadsystems/the-sharder`, holds the specification, the suite, and every
implementation.

A port is an implementation of the sharder library in one language, as
[`../05-glossary.md`](../05-glossary.md) defines the term. Each port sits under `ports/<port>/`,
where `<port>` is the port identifier: the lower case name of the language, which also names the
port's declaration file and its continuous integration job. Each port's build is rooted in its own
directory and is the build its ecosystem expects, so `ports/java/` holds a Gradle build, `ports/go/`
a Go module, `ports/rust/` a Cargo workspace, and `ports/ruby/` a gem.

`docs/`, `conformance/`, and `bench/` stay at the repository root and belong to no port. A port
reads the suite at a relative path from its own root, and a published artifact of a port carries a
copy the port's build produces rather than one a contributor copied.

The Gradle build moves from the repository root to `ports/java/` whole, keeping every artifact
name, module name, and package name that [`0028`](0028-java-module-and-artifact-layout.md) fixes.
Those names are coordinates in a registry and a dependency graph; the directory is not.

A release tag carries the port's directory: `ports/<port>/v0.1.0`. Go requires that shape for a
module in a subdirectory, and the other ports take it too, so that one convention covers the
repository. Ports version independently of each other, and a version states nothing about
conformance, which the declaration states.

Continuous integration runs one job per port, so a regeneration reaches every port in the commit
that makes it. [`0082`](0082-continuous-integration-and-dependency-updates.md) is how: a matrix over
the ports the tree carries, with no path filter, because a required check a filter skipped never
reports.

[`../35-port-conventions.md`](../35-port-conventions.md) states what this layout obliges a port to
carry, [`0080`](0080-conformance-declaration-format.md) states the form of the declaration, and
[`0082`](0082-continuous-integration-and-dependency-updates.md) states how a port is built.

## Consequences

A specification repair and its effect on every port are one commit and one review. The suite
regenerates, the revision changes, each port's job runs against the new revision, and a port that
the repair breaks fails there rather than in a repository somebody visits next quarter. This is the
property the layout is chosen for, and it is the property the first port cannot demonstrate on its
own.

Go pays the visible cost. A consumer depends on
`github.com/codeheadsystems/the-sharder/ports/go`, which names a directory in an import path, and a
release is tagged `ports/go/v0.1.0`, because the language derives both from the module's directory.
A module proxy serves the files under that directory alone; a direct fetch, which `GOPROXY=direct`
and a private module setting both produce, clones the whole repository for one package.

Crate names and gem names are global and are taken by whoever registers them first, and neither is
reserved by a directory in this repository. A port fixes its coordinates in a decision record before
it publishes, and the interval between that record and the publication is exposure the repository
layout does nothing about.

The Java binding's paths move. `sharder-conformance-vectors` copies `../../conformance/`, the
documentation checks of [`../40-java-binding.md`](../40-java-binding.md#documentation-checks) walk
the repository tree rather than the Gradle root, and a contributor runs Gradle from `ports/java/`.
The artifact set, the module descriptors, and the package roots are untouched.

Every port's source is in every checkout, so a contributor to one port clones four. The tree is
small, and the alternative costs a submodule or a vendored copy of the suite in each port.

A port that gains maintainers, a release cadence, or a review culture of its own leaves by
extracting `ports/<port>/` with `git filter-repo`, and consumes the suite afterwards as the
repository-per-port alternative below describes. The suite stays here either way, because the
generator reads the specification.

## Alternatives

A repository per port, with the suite consumed as a submodule or as a published tarball pinned by
revision. Rejected for now because it converts one commit into one per port, and because a port that
nobody bumps looks conformant while asserting against a revision that no longer exists. It is the
right shape once a port has its own maintainers, and the extraction path above is what reaching it
costs.

The Java build stays at the repository root and further ports sit in subdirectories. Rejected
because the asymmetry is permanent and appears in every path, every job, and every document, in
exchange for one port avoiding a move that costs two paths and a working directory.

The suite in a repository of its own, with the specification staying here. Rejected on the fact that
the generator reads the specification to compute coverage and to check the withdrawal registers, so
the split costs a fetch across a boundary on every regeneration, and a suite generated against a
specification that has moved on.

One build tool across every port, driving Gradle, Cargo, and the rest beneath it. Rejected because a
port's build is read by the contributors and the consumers of that language, and a wrapper adds a
layer none of them expect while removing none of the underlying builds.

Directories named for the artifact rather than the language, such as `ports/sharder-java/`. Rejected
as a restatement: the repository is the sharder library, and the prefix would appear in the Go
import path and in every tag.
