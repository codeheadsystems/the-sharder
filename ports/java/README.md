# Java port

Java is the first implementation of the sharder library, and it is one Gradle module publishing one
artifact, `sharder`, under
[`adr/0081`](../../docs/design/adr/0081-single-java-module.md).

Status: under way. The port reaches the `hash` conformance level and no other, so it declares no
conformance yet: a declaration waits for `hash`, `place`, `core`, and `scale`, which `CORE-110`
makes mandatory.

| Written | Not written |
|---|---|
| the Gradle build, `module-info.java`, and the wrapper | the topology document pipeline and the snapshot lifecycle |
| `NodeId`, `ShardId`, `RoutingKey`, and `Digest` | the four placement strategies and the preference list builder |
| `ErrorCode`, the closed condition set of `ERR-010` | the exception hierarchy the conditions raise |
| SipHash-2-4, the framing, and the three domain-tagged functions | the key transforms |
| a strict JSON reader | the canonical form and the digest |
| the conformance harness, driven from `manifest.json` | health, fencing, observability, and migration |

[`../../docs/design/40-java-binding.md`](../../docs/design/40-java-binding.md) is the design this
directory renders. It fixes the package layout, the public type set, the JDK floor, the dependency
policy, the thread-safety contracts, the harness, and the build gates.
[`../../docs/design/99-roadmap.md`](../../docs/design/99-roadmap.md#implementation-stages) gives
the stages, of which this port has finished the first, and
[`#release-v01`](../../docs/design/99-roadmap.md#release-v01) gives what the release after them
publishes. Nothing is published before that release, under
[`adr/0083`](../../docs/design/adr/0083-publication-as-the-last-stage.md).

## Building

```sh
cd ports/java
./gradlew build
```

The build compiles against the Java 21 API whatever JDK runs it, treats a warning as an error, and
runs the unit tests and the conformance suite. Nothing it needs is published: the dependencies are
JUnit, AssertJ, and Bouncy Castle, all at test scope, and the artifact requires `java.base` alone.

## Running the conformance suite

The suite is the tree at [`../../conformance/`](../../conformance/), which the harness reads
directly rather than from a packaged copy. `sharder.conformance.dir` names another tree.

```sh
./gradlew test --tests 'com.codeheadsystems.sharder.conformance.ConformanceSuite'
./gradlew test -Dsharder.conformance.dir=/path/to/another/conformance
```

Each file is checked against the `sha256` the manifest holds for it before its cases run, and a
vector kind the driver does not implement fails rather than being skipped, under the driver contract
of [`../../docs/design/30-conformance.md`](../../docs/design/30-conformance.md#driver-contract).

`SipHash24Test` runs the sixty-four vectors published with the algorithm and cross-checks them
against a second implementation, which `HASH-003` requires of a port before it evaluates any
conformance vector.
