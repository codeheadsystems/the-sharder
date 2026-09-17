# 0035. Manifest-driven conformance harness

Status: accepted. Date: 2026-09-16.

## Context

The conformance suite is what keeps the Java, Go, Rust, and Python ports producing the same ordered
node list for the same inputs. Its vectors are language-neutral data files, and its value depends on
every port running every vector that applies to it.

A harness can be coupled to the vector tree in two ways. It can name files, either literally in
source or by globbing a directory, or it can read an index the vector tree publishes and discover
everything from there. Naming files puts the vector tree's shape into each port's source, so adding
a vector family means a change in four languages before any port runs it. Globbing is worse in a
specific way: a vector file added to a directory the glob does not cover is silently not run, and a
port reports a green suite it has not earned.

Two failure modes matter more than the coupling. A vector family the harness does not understand
must fail rather than be skipped, because a skipped family is a conformance claim without evidence.
A conformance level the port does not implement must be recorded as a declared exclusion rather than
as a pass, because the rule by which a port declares conformance is stated in terms of levels.

JUnit 5 offers two shapes for data-driven tests. `@ParameterizedTest` with an arguments provider
fixes the parameter set at the method it annotates, so the suite shape is source. `@TestFactory`
returns a tree of dynamic nodes built at run time, so the suite shape is data.

The vectors also have to be reachable. A harness that reads the repository working tree cannot run
from published artifacts, which a third-party author of a registered placement strategy needs in
order to run the suite against their own build.

The property suites need one thing the specification does not give. `PROP-*` states that a sample of
`M` routing keys is drawn independently and uniformly from the sixteen-octet sequences, and names no
generator. Two ports drawing different samples both satisfy the statement, and a balance failure in
one is then not reproducible in the other.

## Decision

The harness reads one entry point, `conformance/manifest.json`, and discovers every suite, level,
vector file, and vector kind from it. No Java source names a vector file and no path is globbed. A
vector added to the tree and listed in the manifest runs without a change to the harness.

`ConformanceSuite` is a JUnit 5 `@TestFactory` returning a tree of `DynamicContainer` and
`DynamicTest`, one container per suite and per level and one test per vector. A display name is the
vector identifier followed by the requirement identifiers the vector asserts, so a failure report
names `PLACE-065` rather than a file and a line.

A manifest entry naming a suite kind, a vector kind, or a conformance level the harness does not
recognise produces one failing test naming the unrecognised value. It is never skipped.

A vector for a level the binding declares out of scope is recorded as a declared exclusion in the
conformance report, with the level and the declaration, and is not reported as a pass.

Vectors reach the harness through a `VectorSource`. The default reads them from the classpath, from
a resources-only artifact `sharder-conformance-vectors` whose `processResources` copies
`conformance/` from the repository root verbatim. A system property substitutes a directory, so a
developer runs against an edited tree without rebuilding the artifact.

`sharder-conformance` is published and carries the JUnit dependency, so no consumer's runtime
classpath does and a third-party strategy author runs the suite against their own build.

The harness reads vector files through the JSON reader of `core.internal.json`, which
`sharder-core` exports to the conformance module alone, so the harness has no JSON dependency of its
own and the internals reach no consumer.

Property bounds are evaluated exactly as the specification writes them, in `BigInteger` so that no
product overflows, with no tolerance of the harness's own. Sample keys come from a deterministic
generator the vector names and seeds, so a failure in one port reproduces on the same keys in
another. The specification states the distribution and not the generator, and that gap is recorded.

A simulation scenario is a list of steps, each mapping to one library call, interpreted by a switch
over the step kind. The harness supplies a settable `MonotonicClock` and drives every timer itself,
which the library's refusal to read a wall clock under `CORE-004` and to start a thread under
`CORE-060` is what makes possible.

`ConformanceReport` writes a machine-readable summary after a run: the manifest revision, each suite
and level, vectors passed and failed, declared exclusions, and the union of requirement identifiers
the run exercised.

## Amendment, 2026-09-17

This record described a harness that discovers every level from `manifest.json` at a time when the
manifest carried no level on a vector file or a scenario, and the reference driver held a table
mapping a vector kind to a level. [`0052`](0052-conformance-level-partition.md) puts the level in
the data: a vector file carries a `level` member, a scenario carries one in its index entry, and the
manifest carries a `levels` table stating what each level requires. The decision this record states
is unchanged, and is now buildable as written. The suite kind of the container tree remains a
property of the vector kind, stated in the suite kind table of
[`../40-java-binding.md`](../40-java-binding.md#suite-kinds) rather than in the manifest.

## Consequences

A vector family added by the conformance author runs in the Java port with no Java change, and a
family the Java port does not understand fails loudly in the commit that adds it. That is the
intended asymmetry: silence is never the outcome.

The manifest becomes a contract in its own right, and a change to its shape breaks every port at
once. That is the cost of having one index rather than four conventions, and it is the reason the
manifest's own shape belongs to `30-conformance.md` rather than to any port.

Dynamic tests report less well in some tooling than parameterised tests do. An IDE cannot run one
dynamic test by name before the factory has run, and a build scan groups them by container rather
than by method. The display naming is what makes a failure legible in exchange.

Packaging the vectors as a resources artifact means two copies of the tree exist during a build, the
working tree and the jar. The copy is verbatim and the property override exists so that a developer
never debugs against a stale one.

Publishing `sharder-conformance` exposes the harness's own API as a compatibility surface, which a
test-scoped artifact does not usually carry. The gain is that a registered strategy from outside the
core set can be held to the same vectors.

Requiring the vectors to name the sample generator puts a requirement on a document this decision
does not own. Until it lands, the Java port's property runs are reproducible within the port and not
across ports, which is exactly the gap the requirement closes.

## Alternatives

Naming vector files in Java source. Rejected because it puts the vector tree's shape into four
languages and makes adding a family a four-port change.

Globbing a directory. Rejected because a file outside the glob is silently not run, which produces a
green suite with less evidence behind it than it appears to have. Every coupling failure in this
design is arranged to be loud.

`@ParameterizedTest` with a custom arguments provider. Rejected because the parameter set is fixed
at the annotated method, so the suite and level structure would be source rather than data, and
because an unrecognised entry has no natural way to fail rather than be skipped.

JUnit's `@Disabled` or `Assumptions` for a level the port does not implement. Rejected because both
report as skipped, and a skipped test in a conformance suite is indistinguishable from an oversight.
A declared exclusion in a report is a claim somebody made.

Reading vectors from the repository working tree by relative path. Rejected because the harness then
runs only inside a checkout, which excludes the third-party strategy author the published artifact
exists for.

Giving the harness its own JSON dependency, since it is test scope and the dependency policy does
not bind it. Rejected because the harness and the library would then parse vector documents with two
different readers, and a disagreement between them would look like a library failure.

Generating the sample keys in the harness from a seeded `java.util.Random`. Rejected because the
algorithm is a Java detail that no other port reproduces, so the sample would differ across ports
and a balance failure would not be reproducible where it matters.
