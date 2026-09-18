# 0081. Single Java module

Status: accepted, superseding the artifact set of [`0028`](0028-java-module-and-artifact-layout.md).
Date: 2026-09-18.

The module boundaries of [`0028`](0028-java-module-and-artifact-layout.md) stand as package
boundaries. What this record changes is how many artifacts carry them: one rather than eight.

## Context

[`0028`](0028-java-module-and-artifact-layout.md) settled eight Gradle projects, of which six
publish, to enforce one constraint mechanically: an integrator who routes a tenant identifier to one
of five clusters does not carry the migration state machine. That argument rests on a library that
exists and on consumers who have declared a dependency on part of it.

Neither is true yet. No Java source existed before this record, no artifact has been published, and
no consumer has a dependency to avoid. What exists is the specification, the conformance suite, and
a port that reaches one conformance level of it.

The cost of the split lands before any of its benefit does. Eight projects mean eight build files,
six POMs, a platform to align them, a module descriptor per artifact, and a dependency direction to
maintain, all of it in service of a boundary that no consumer can yet observe. Each is work the port
pays at every step from here to the first release, and the first release is where the boundary
starts to matter.

The suite is the other reason the shape can wait. A conformance level is reached by a port, not by
an artifact: `manifest.json` names levels, `CORE-110` names surfaces, and neither names a jar. A
port that reaches `core` as one module reaches it as six without a vector changing.

## Decision

The Java port is one Gradle project at `ports/java/`, publishing one artifact, `sharder`, in the
group `com.codeheadsystems`, carrying one module descriptor for `com.codeheadsystems.sharder`.

The package layout of [`0028`](0028-java-module-and-artifact-layout.md) is unchanged. The public
packages stay as that record and
[`../40-java-binding.md`](../40-java-binding.md#public-packages) name them, everything under
`com.codeheadsystems.sharder.core.internal` stays unexported, and the dependency direction the
artifacts expressed is expressed by which package refers to which. `module-info.java` exports the
public packages and no internal one, so what an integrator can call is what a jar boundary would
have given them.

The conformance harness is test source of that module rather than a published artifact, and the
vector tree is read from `conformance/` at test time rather than packaged as classpath resources.
`sharder.conformance.dir` names the tree, defaulting to the repository's own.

The near-zero dependency policy of [`0032`](0032-dependency-free-json-and-canonicalisation.md) binds
the one artifact as it bound four: it requires `java.base` and nothing else, and JUnit, AssertJ, and
Bouncy Castle stay at test scope, where they reach no consumer.

Benchmarks are not in this module. [`../99-roadmap.md`](../99-roadmap.md#release-v01) schedules them
with the first release, and the record that brings them back states where they sit.

## Consequences

The port carries one build file, one descriptor, and one artifact to publish, which is the work that
falls away between here and a library that routes a key. What the eight projects bought, a consumer
who cannot reach the handoff coordinator, is bought instead by a package they cannot import.

An integrator carries the whole library to use part of it. The migration surface, the file provider,
and the strategies an integrator never names are all in the jar, and the classpath carries them
whether or not a call reaches them. The jar is small and requires nothing, so the cost is size and
completion noise rather than a transitive dependency, which is what made the split affordable to
defer and not what made it unnecessary.

A third party cannot run the suite against their own build from published artifacts, because the
harness is test source here. A strategy or provider author runs it from a checkout.
[`0035`](0035-manifest-driven-conformance-harness.md) makes the manifest the entry point, so what
they need is the tree and a driver, both of which a checkout carries.

Splitting later is a breaking change for whoever has already declared a dependency on `sharder`. The
split is mechanical, since the packages are already partitioned, but the artifact a consumer names
changes, so the moment to take it is before the first publication rather than after. This record is
therefore revisited when the library is complete enough to publish, and
[`../99-roadmap.md`](../99-roadmap.md#one-way-doors) carries the door it closes.

The one-way door of [`0028`](0028-java-module-and-artifact-layout.md) is narrower than it was. The
group, the top package, and the module name still appear in every consumer's build file and are
still fixed; the artifact names `sharder-api` through `sharder-bench` are not published and are free
until they are.

## Alternatives

The eight projects of [`0028`](0028-java-module-and-artifact-layout.md), built now. Rejected because
the cost is paid from the first commit and the benefit arrives at the first publication, and because
a boundary maintained across six empty projects is a boundary nobody is checking.

Two projects, `sharder` and a conformance harness. Rejected because the harness is the only
consumer of the split and it is test source, which a source set already separates.

One project with several published jars, assembled from package subsets by the build. Rejected
because a jar assembled from a package subset is a module descriptor written by hand against a
layout no compiler checks, which is the split's maintenance cost without its clarity.

A multi-release or shaded artifact that hides the migration packages from completion. Rejected as a
build trick standing in for a package boundary that already exists.
