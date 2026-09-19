# Java port

Java is the first implementation of the sharder library, and it is one Gradle module publishing one
artifact, `sharder`, under
[`adr/0081`](../../docs/design/adr/0081-single-java-module.md).

Status: the port reaches `hash`, `place`, `core`, and `scale`, which `CORE-110` makes mandatory of
every port, and `failover` and `readAffinity` above them, and exposes all four placement strategy
surfaces. It declares them in
[`../../conformance/declarations/java.json`](../../conformance/declarations/java.json), against the
suite revision that declaration names, with no deviations. It exposes `fencing` and `migration` not
at all, so it declares neither of their levels.

| Written | Not written |
|---|---|
| the Gradle build, `module-info.java`, and the wrapper | the public `Router`, `RoutingDecision`, and the configuration surface |
| `NodeId`, `ShardId`, `RoutingKey`, and `Digest` | the topology provider contract and the poll schedule |
| SipHash-2-4, the framing, and the three domain-tagged functions | the health view, the attempt walk, and the retry budget |
| the three key transforms and the matcher precedence | the recipient check and the redirect walk |
| `ring`, `rendezvous`, `slot`, and `directory` | the handoff coordinator and rate control |
| the override layer: pins, constraints, and per-entry factor | the explain record and the metrics registry |
| the preference list builder and the spread relaxation ladder | the exception leaves no surface here raises |
| the five-state health machine, ejection, and probation | |
| the attempt walk, the health filter, and the retry budget | |
| `routeForRead` and the bounded reordering of `READ-013` | |
| the RFC 8785 canonical form, the digest, and document validation | the levels above `core` |
| the snapshot lifecycle and the acceptance table of `TOPO-061` | |
| the ownership delta, skew detection, and the observability inventory | |
| `ErrorCode`, the closed condition set of `ERR-010` | |
| the conformance harness, the scenario runner, and the run report | |

The candidate ordering is a cursor rather than a list, under `PLACE-015`: a routing call consumes
the prefix `CORE-046` bounds it at, and the whole ordering and the whole preference list are
answered on demand under `CORE-047`. A thousand-node ring therefore costs a routing call a walk of
about `T / N` entries per candidate rather than a walk of every token.

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

## Declaring conformance

The run writes a report, which an ordinary run puts under `build/` and a declaration names:

```sh
./gradlew test --rerun-tasks -Dsharder.conformance.report="$PWD/conformance/report.txt"
```

[`conformance/report.txt`](conformance/report.txt) is that output, and
[`../../conformance/declarations/java.json`](../../conformance/declarations/java.json) is the
declaration it belongs to. The wall time and the peak resident size it carries are reported rather
than asserted: the suite bounds neither, and both are comparable against this port on another
machine and against nothing else.
