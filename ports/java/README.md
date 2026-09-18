# Java port

Java is the first implementation of the sharder library.

Status: not started. No Gradle build, no source, no build check, and no published artifact exists
here, and [`../../docs/design/40-java-binding.md`](../../docs/design/40-java-binding.md) is the
design this directory renders. That document fixes the artifact set, the package layout, the public
type set, the JDK floor, the dependency policy, the thread-safety contracts, the conformance
harness, and the build gates.
[`../../docs/design/99-roadmap.md`](../../docs/design/99-roadmap.md#release-v01) gives what the
first release carries.

The build is rooted in this directory, and a contributor runs Gradle from here. The conformance
suite is read from [`../../conformance/`](../../conformance/), which
`sharder-conformance-vectors` packages as classpath resources, under
[`#vector-source-and-manifest`](../../docs/design/40-java-binding.md#vector-source-and-manifest).

The levels this port reaches, and the suite revision it ran, are published in
`../../conformance/declarations/java.json` once it reaches `hash`, `place`, `core`, and `scale`.
[`../../docs/design/35-port-conventions.md`](../../docs/design/35-port-conventions.md) states what
that file carries and what else a port carries whatever its language.
