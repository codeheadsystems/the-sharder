# Java port

Java is the first implementation of the sharder library, and it is one Gradle module publishing one
artifact, `sharder`, under
[`adr/0081`](../../docs/design/adr/0081-single-java-module.md).

Status: under way. The port reaches the `hash` and `place` conformance levels and no other, so it
declares no conformance yet: a declaration waits for `core` and `scale` as well, which `CORE-110`
makes mandatory alongside the two it has.

| Written | Not written |
|---|---|
| the Gradle build, `module-info.java`, and the wrapper | the topology document pipeline and the snapshot lifecycle |
| `NodeId`, `ShardId`, `RoutingKey`, and `Digest` | the public `Router` and `RoutingDecision` |
| SipHash-2-4, the framing, and the three domain-tagged functions | the canonical form, the digest, and document validation |
| the three key transforms and the matcher precedence | the health view, the attempt walk, and the retry budget |
| `ring`, `rendezvous`, `slot`, and `directory` | the recipient check and the redirect walk |
| the override layer: pins, constraints, and per-entry factor | the handoff coordinator and rate control |
| the preference list builder and the spread relaxation ladder | the metrics, the events, and the explain record |
| `ErrorCode` and the no-candidate condition of `ERR-021` | the thirteen conditions no surface here raises |
| the conformance harness, driven from `manifest.json` | the levels above `place` |

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
