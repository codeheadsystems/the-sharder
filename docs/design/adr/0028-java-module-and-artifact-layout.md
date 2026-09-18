# 0028. Java module and artifact layout

Status: accepted, with the split advice and the split lineage withdrawn by
[`0054`](0054-range-strategy-withdrawal.md), and the artifact set superseded by
[`0081`](0081-single-java-module.md). Date: 2026-09-16.

The module boundaries stand as package boundaries, and the eight projects below are one project
under [`0081`](0081-single-java-module.md), which states why the split waits for a publication to
enforce. Two of the premises below do not stand either: `SPLIT-021` to `SPLIT-051`, the split
advice, and `SPLIT-061`, the lineage, are withdrawn identifiers that the register of
[`../10-specification.md`](../10-specification.md#withdrawn-identifiers) resolves, and the strategy
set is four kinds rather than five under [`0002`](0002-placement-strategy-set.md). The boundaries
the withdrawn material argued for are unchanged, because each rests on other premises too.

## Context

The brief sets one structural constraint above the others: an integrator who wants only to hash a
tenant identifier to one of five clusters does not pay for the migration state machine. In a
language with a single published artifact that constraint is unenforceable, because everything on
the classpath is a dependency whether or not it is called. A Java library enforces it by shipping
more than one artifact and by letting a build file choose.

Against that pull sits the cost of a split. Every artifact is a version to align, a POM to publish,
a module descriptor to keep consistent, and a boundary that a later refactor cannot cross without a
release note. A library of eight artifacts where three would do is a library whose users read a
dependency table before they read the routing call.

The specification also creates two boundaries of its own. The extension points of `CORE-060` and
`CORE-072` are implemented by third parties, so a third-party topology provider for etcd or a
control-plane API has to compile against something. The conformance harness of the brief carries
JUnit, which no consumer's runtime classpath should see.

Three facts about the specification decide where the cuts fall. The ownership delta of `TOPO-211` is
computed and published at installation under `MOVE-101`, by integrators who never build a plan. The
split advice of `SPLIT-021` to `SPLIT-051` and the skew detection of `OBS-030` to `OBS-035` are the
same integer comparison over the same `ShardReport`, and neither changes anything. The split lineage
of `SPLIT-061` onwards exists only to decide whether a plan is admissible.

## Decision

Eight Gradle projects, of which six publish: `sharder-bom`, `sharder-api`, `sharder-core`,
`sharder-migrate`, `sharder-provider-file`, `sharder-conformance`, and the resources-only
`sharder-conformance-vectors`. `sharder-bench` does not publish.

`sharder-api` holds the core model types, the extension point interfaces, the error taxonomy, and
the configuration records, and holds no algorithm and no state. A third-party provider, health view,
or placement strategy compiles against it alone.

`sharder-core` holds everything a routing integrator needs and nothing they do not: the hash, the
document pipeline, the five strategies, the preference list builder, the health state machine, the
fencing recipient, the ownership delta, observability, and the in-memory reference provider.

`sharder-migrate` holds the handoff coordinator, the movement hooks, the migration policy, the
pressure gauge, and the split lineage. `MovementHooks` is declared there rather than in
`sharder-api`, because only a consumer of `sharder-migrate` implements it.

`sharder-provider-file` holds the static file provider, which is the only first-party code that
touches a filesystem or needs a poll schedule.

No package is split between two modules. Each artifact owns its package roots outright, so the
modular build and the classpath build agree and the JPMS descriptors need no workaround.

`sharder-migrate` depends on `sharder-core` rather than on `sharder-api` alone, because plan
construction evaluates preference lists over two snapshots.

## Consequences

An integrator on the tenant-routing path declares one dependency, `sharder-core`, and never sees a
handoff type in an IDE completion list. That is the constraint the brief set, made mechanical.

The api and core split costs a second artifact for a library whose implementation has exactly one
supplier. The gain is that a third-party adapter's transitive closure is the types and nothing else,
and that a core internal can change without recompiling the adapter. The cost is that a type has to
be classified before it is written, and a type placed wrongly is a breaking move later.

Holding the in-memory provider in `sharder-core` rather than beside the file provider dilutes the
symmetry of "reference providers". It buys a core artifact that is useful on its own, because the
in-memory provider is what a control plane pushes into and what every test uses, and it costs the
core artifact nothing, because that provider reads nothing and schedules nothing.

Putting the ownership delta in core and the split lineage in migrate means one specification section
lands in two artifacts. A reader following `SPLIT-*` crosses a boundary in the middle of it. The
alternative, keeping all of `SPLIT-*` together, would have put the delta behind the migration
dependency for integrators who only want the event.

Six published artifacts mean six POMs and a BOM to keep aligned. The BOM exists for that reason and
is the only artifact whose purpose is other artifacts.

## Alternatives

One artifact, `sharder`, holding everything. Rejected because it makes the brief's constraint
unenforceable: the migration state machine would be on the classpath of every tenant router, and the
only thing standing between an integrator and it would be discipline.

Two artifacts, `sharder-core` and `sharder-conformance`. Rejected for the same reason as one, with
the conformance problem solved and the migration problem untouched.

Folding `sharder-api` into `sharder-core` and giving third parties the core artifact. Rejected
because a provider adapter would then depend on the routing engine, the JSON reader, and the whole
internal surface, and would recompile against changes it cannot observe. The precedent for the split
is every SPI-bearing library that publishes an api artifact separately.

A separate `sharder-provider-memory`. Rejected as an over-split: the provider is a few dozen lines
with no dependency, no I/O, and no schedule, and an artifact boundary around it buys a consumer
nothing to avoid.

A separate artifact per placement strategy, so that a tenant router does not carry the ring
arithmetic. Rejected because the five strategies together are a small fraction of core, because the
conformance suite covers all five as one level, and because a topology document can name any of them
at any epoch, so an artifact missing one would fail at load rather than at build.

Discovering strategies and providers through `ServiceLoader`, which would make the artifact
boundaries dynamic. Rejected because what is on a classpath would then change how a key routes,
which is the opposite of the determinism the corpus is built on.
