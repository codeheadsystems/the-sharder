# Roadmap

The staged plan for the sharder library: the stages the ports are implemented in, what the release
after them publishes, and which decisions cannot be revisited once that release exists.

The design is complete across the whole scope, including rebalancing, concurrent ownership, and
stale callers. The staging is the order in which a port renders that design, and not a reduction of
it.

Publication comes last, under [`adr/0083`](adr/0083-publication-as-the-last-stage.md). Nothing is
published until every port passes the conformance suite at the levels its surfaces commit it to, in
its own test harness, so every stage below is a milestone rather than a release.

Status: nothing is published and no release has been made. The Java port is finished, reaches
every stage, and declares every level; no other port has been started, and no date is attached to
anything below.

## Implementation stages

A stage is one conformance level of
[`30-conformance.md`](30-conformance.md#conformance-levels), and the order is the requires relation
the `levels` table of [`../../conformance/manifest.json`](../../conformance/manifest.json) carries
rather than a preference. A stage is finished when every port exposing the surface it tests runs it
with no failures, and a port that exposes no such surface passes over it.

| Stage | What a port implements |
|---|---|
| `hash` | SipHash-2-4, the framed domain-tagged construction, and the three key transforms |
| `place` | `ring`, `rendezvous`, `slot`, and `directory`, the overrides, the preference list, the failure domain spread and its relaxation ladder, shard enumeration, movement, and the tie-breaks |
| `core` | the document pipeline, the canonical form and the digest, validation, the snapshot lifecycle, the provider contract, the error taxonomy, the ownership delta, skew detection, and the observability inventories |
| `scale` | the same placement function over two documents of a thousand nodes, reported rather than bounded |
| `failover` | the five-state health machine, signal ingestion, outlier ejection, probation, attempt sequences, and retry budgets |
| `readAffinity` | `routeForRead` and the bounded reordering of the replica prefix |
| `fencing` | the token, its propagation, the recipient verdict, and the redirect walk |
| `migration` | the handoff coordinator, the eleven-state machine, the movement hooks, idempotence and recovery, plan rebase, abort and rollback, and rate control |

One stage carries no conformance level. The build gates of a port, which for Java are the unsigned
comparison check, the documentation checks, the dependency check that fails a published POM carrying
a compile or runtime dependency, and the allocation gate, are written before the release rather than
after it, because each of them is a rule the release publishes something under.

## Release v0.1

The release publishes the specification, the topology document format and its schema, the
conformance suite with its revision, and every port that has declared, together. It happens once
every port in the repository passes the suite at the levels its surfaces commit it to and carries a
declaration under [`../../conformance/declarations/`](../../conformance/declarations/).

| Surface | Contents |
|---|---|
| hash and key handling | SipHash-2-4, the framed domain-tagged construction, the three key transforms |
| placement | `ring`, `rendezvous`, `slot`, and `directory`, with derived and authored assignment |
| overrides | pins, constraints, per-entry replication factor, and the matcher precedence rule |
| replication | the preference list, distinctness, failure domain spread, and the relaxation ladder |
| health and failover | the five-state machine, signal ingestion, outlier ejection, probation, attempt sequences, retry budgets |
| read routing | `routeForRead` and the bounded reordering of the replica prefix |
| topology lifecycle | the load pipeline, monotonicity, snapshot installation, retention, and the ownership delta |
| fencing | the token, its propagation, the recipient verdict, and the redirect walk |
| migration | the handoff coordinator, the eleven-state machine, the movement hooks, recovery, rebase, abort, and rate control |
| errors | the closed set of sixteen conditions with their codes, names, and causes |
| observability | the metrics, the events, skew detection, and the explain record |
| configuration | every setting of the `CFG-*` group with its default |
| format | `formatVersion` 1.0, the JSON Schema, the canonical form, and the digest |
| providers | the in-memory reference provider and the static file provider |
| conformance | the vector files, topology documents, properties, and scenarios, with the manifest that counts and indexes them |
| scale | the placement function over a thousand nodes, reported rather than bounded |
| benchmarks | the JMH sources a regression is read from, run on demand rather than in `check` |

A declaration carries the wall time and the peak resident size the port observed at `scale`, which
the declaration rule of [`30-conformance.md`](30-conformance.md#declaring-conformance) requires and
the suite bounds by nothing.

An integrator who routes a tenant identifier to one of several clusters is served in full and
depends on `sharder` alone.

Orchestrated migration rests on one primitive the integrator supplies. `MOVE-321` requires
`commitCutover` to be a single-winner write over a store both the source and the destination read,
and the coordinator supports no weaker mode. An integrator whose store offers no such write moves a
shard outside the library.

One question in [`90-open-questions.md`](90-open-questions.md) is answered before the release
publishes, because the release publishes the document that leaves it open: `OQ-02`, the three
requirements with no owning interface. The provider contract's absence from the normative surface is
settled by `CORE-080` to `CORE-101` in
[`10-specification.md`](10-specification.md#topology-provider).

## Later releases

| Item | Shape of the change |
|---|---|
| a `range` strategy, withdrawn at v0.1 | a minor format version adding a fifth kind, under [`adr/0054`](adr/0054-range-strategy-withdrawal.md) |
| per-domain replication factors, `OQ-03` | a format member and a second preference list builder |
| a matcher kind beyond exact and prefix, `OQ-05` | a minor format version with a stated precedence rule |
| ports beyond Java | Go, Rust, Ruby, and Python, each under `ports/`, landing either side of the release and each declaring its levels against a suite revision |
| a control-plane provider adapter | third-party, written against the provider contract without forking |
| hinted handoff | a hook exists at v0.1 and the library implements no part of it |

An occupancy cap per failure domain, `OQ-04`, is not scheduled. `SPREAD-007` carries the cap in the
builder and every cap is 1, so the change is a minor format version adding the member that sets one
and it moves nothing until a document does. What stays open with it is the ladder's treatment of a
level carrying a cap above 1.

## One-way doors

These decisions are cheap now and expensive or impossible once the format is published, once a
second implementation exists, or once a deployment holds data placed under them.

Every door below stays open through the stages above, because publication comes after the last of
them under [`adr/0083`](adr/0083-publication-as-the-last-stage.md). A defect the migration surface
surfaces can still move a requirement, a vector, or the format, and the cost of moving one is a
re-run by the ports in this repository rather than a re-declaration by somebody outside it.

### Format and wire

The hash function and its framing. SipHash-2-4, the 128-bit key derived from `hash.seed`, the
`u32be` length framing, and the three domain tags fix every placement decision the library makes.
Changing any of them moves every key in every topology. Rotating the seed alone is already a full
data migration under [`adr/0027`](adr/0027-hash-seed-exposure-and-tenancy.md); changing the function
is that migration for every deployment at once, with no way to stage it. `HASH-001` to `HASH-044`
and [`adr/0001`](adr/0001-hash-function-and-key-encoding.md).

The topology document format at major version 1. The member names, the canonical form, the digest
algorithm, and the restriction of JSON numbers to the exactly representable integer range are fixed
for the life of the major version. The rule that an unknown member is a validation failure is what
makes every other addition a version bump, and removing it later would not help, because documents
already in flight were written against readers that refuse. `TOPO-001`,
[`20-topology-format.md`](20-topology-format.md#versioning-and-compatibility), and
[`adr/0008`](adr/0008-json-canonical-serialisation.md).

The fencing token shape. The pair of `topologyId` and `epoch`, with an optional digest, travels on
the wire from a sender to a recipient that may be a different implementation at a different version.
Both ends change together or not at all. `FENCE-011` and
[`adr/0005`](adr/0005-epoch-and-version-semantics.md).

The provider contract. `TopologyProvider`, `TopologySink`, the capability pair, the `load` result
union, and the source version of `CORE-084` are what a third-party adapter is written against, and
this roadmap schedules such an adapter below. Once one exists outside the repository, a member
cannot be added to `load` or to `onDocument` without breaking it, which is why conditional fetch
arrives at v0.1 rather than when a fleet first outgrows a 30 second poll of a 7.5 MB document.
`CORE-080` to `CORE-087`, [`adr/0004`](adr/0004-topology-provider-contract.md), and
[`adr/0048`](adr/0048-conditional-fetch-in-the-provider-contract.md).

The error taxonomy. Sixteen conditions, each with a permanent numeric code, a permanent name, and a
fixed retryable flag. The code and the name are what a metric label, a log line, a serialised error,
and a conformance vector join on, and a binding may group the conditions and may not add a leaf.
Renumbering one silently changes what a dashboard counts. `ERR-010`, `ERR-062`, and
[`adr/0024`](adr/0024-closed-numbered-error-taxonomy.md).

The strategy kind names and the closed core set. `ring`, `rendezvous`, `slot`, and `directory`
appear in documents an authority has already published. A fifth kind is a minor version, which is
how `range` returns if a deployment asks for it; renaming one of the four is not available.
[`adr/0002`](adr/0002-placement-strategy-set.md) and
[`adr/0054`](adr/0054-range-strategy-withdrawal.md).

Shard identifier rendering. A shard identifier appears in an ownership delta, an event, an explain
record, and a handoff plan, and it is the join key between two epochs. Changing a rendering renames
every shard in a topology and makes the delta between the epoch before the change and the epoch
after it report movement that did not happen. `PLACE-031`, `PLACE-034`, and
[`adr/0010`](adr/0010-shard-identifier-naming.md).

### Library semantics

The final tie-break. Every ordering the specification defines ends at node identity compared as
unsigned octets, ascending. Changing it changes candidate orderings wherever two nodes tie, and the
ties are exactly the cases a port is least likely to test on its own. `PLACE-020`, `CORE-003`.

Agreed ownership with local health. The candidate ordering is a pure function of the snapshot and
the routing key, and the health view filters the preference list and never reorders it. Two callers
therefore attempt different nodes and never compute different owners. Relaxing this is the one
change that converts a routing inefficiency into a data loss, and every guarantee the storage use
case rests on follows from it. `PLACE-011`, `FAIL-001`, and
[`adr/0007`](adr/0007-administrative-state-and-health-state.md).

The placement set. The nodes eligible for placement at an epoch are those whose administrative state
is `active` or `draining`. Admitting or excluding a state moves keys on every topology that carries
a node in it. `PLACE-001`.

Ancestor-scoped domain identifiers. Two nodes share a failure domain at a level when their paths
agree at that level and at every coarser level, so a rack named `r01` in one zone and a rack named
`r01` in another are distinct racks. Comparing the identifier alone instead would change which
replicas satisfy spread, and therefore which nodes are replicas. `SPREAD-002` and
[`adr/0006`](adr/0006-failure-domain-model.md).

The spread relaxation ladder. Stage `k` enforces node distinctness together with the finest `m-k`
levels, and the builder takes the smallest `k` that yields a full replica prefix. Changing the
direction moves replicas in every topology whose `replication.spread` names more than one level.
`SPREAD-010` and [`adr/0036`](adr/0036-spread-relaxation-ladder-direction.md).

The preference list builder's walk. `REPL-012` selects the replica prefix with one greedy forward
pass over the candidate ordering, admitting each entry that no enforced level has already filled to
its occupancy cap. The pass expresses a ceiling per failure domain and cannot express a floor, so
the per-domain replication factors of `OQ-03` are a second builder beside the first rather than a
document member alone, carrying their own relaxation behaviour, their own `PROP-*` statements, and
their own conformance surface. A published suite joins vectors to `REPL-012` and a port implements
it, and after that the walk is fixed. `REPL-012`, `SPREAD-011`, and
[`adr/0069`](adr/0069-per-level-occupancy-cap.md).

Integer-only placement arithmetic. No floating-point value reaches placement, validation, an
ordering, fencing, handoff admission, a step budget, or any threshold comparison. A single
floating-point operation on the placement path makes a result depend on a platform, which is the one
failure the conformance suite cannot repair after the fact. `HASH-043`, `OBS-002`, and
[`adr/0030`](adr/0030-unsigned-integer-discipline.md).

Requirement identifiers. An identifier names one requirement permanently, a withdrawn requirement
keeps a register row and never a second meaning, and a new requirement takes a free number. The
conformance suite joins to the specification on these identifiers, so a renumbered identifier
silently retargets a test rather than breaking it. The Conventions section of
[`10-specification.md`](10-specification.md#withdrawn-identifiers),
[`adr/0037`](adr/0037-specification-defect-repairs.md), and
[`adr/0053`](adr/0053-requirement-withdrawal-convention.md).

### Java binding

Artifact, module, and package names. `com.codeheadsystems` as the group, `sharder` as the artifact,
and `com.codeheadsystems.sharder` as the top package and the module appear in every consumer's build
file and in every `module-info`. Renaming one after publication splits the dependency graph. How
many artifacts carry the library is not through this door until one is published, and
[`adr/0081`](adr/0081-single-java-module.md) is what revisits it before that.
[`adr/0028`](adr/0028-java-module-and-artifact-layout.md).

The JDK floor. Java 21 is one-way downward: raising it later drops consumers, and lowering it later
is available but gives up every language feature the binding has already used.
[`adr/0031`](adr/0031-jdk-baseline.md).

The exception idiom. Unchecked exceptions with a sealed hierarchy of sixteen leaves, rather than a
result type, is the shape every call site is written against. Moving to a result type later is a
rewrite of every caller. [`adr/0029`](adr/0029-exception-idiom-for-the-taxonomy.md).

Opaque identifier value types. `NodeId`, `ShardId`, `RoutingKey`, and `Digest` as final value
classes, and `Optional` for the specification's `X | none` shapes, are binary interface. Replacing
one with an array or a nullable reference breaks every compiled consumer.
[`adr/0033`](adr/0033-opaque-identifier-value-types.md).

The near-zero dependency policy. `sharder` requires `java.base` and nothing else, and a build
check fails a published POM that gains a compile or runtime dependency. Adding one later puts it in
every consumer's dependency graph, and removing it again is a breaking change for anyone who came
to rely on it transitively. A test or build dependency sits outside the policy and outside this
door, because it reaches no consumer; the Bouncy Castle oracle of
[`adr/0040`](adr/0040-cryptographic-primitive-sourcing-policy.md) is one.
[`adr/0032`](adr/0032-dependency-free-json-and-canonicalisation.md).

### Reversible decisions

These decisions are reversible, and each may change in a minor release.

| Decision | Why it moves |
|---|---|
| every configuration default | `CFG-004` forbids a setting changing an ordering, a shard identifier, a preference list, a factor, or a delta |
| the health state machine's parameters | health state is caller-local and never serialised into a document |
| the attempt limit and retry budget defaults | they decide how far one caller walks and nothing two callers agree on |
| the set of reference providers | a provider delivers documents and the contract is what is fixed |
| the internal structure of a port | no vector observes it |

## Effect of the conformance suite

The suite changes the cost of a change in both directions, and which direction depends on what the
change touches.

Easier. A behaviour change is visible rather than inferred: regeneration produces a byte-identical
tree unless the reference changed, so the diff names exactly which vector files a change moved and a
file that moved unexpectedly is the defect. A port may be refactored freely, because no vector
observes anything but an output. A repair that was intended to change nothing is provable to have
changed nothing, which is how the `HASH-*` transcription was shown to move no computed value.

Harder. A requirement identifier is permanent, because the suite joins on it; withdrawing one means
entering it in the register rather than reclaiming the number. A behaviour change means regenerating
vectors, and every port that has declared conformance against a suite revision has to run the new
revision and declare again. The vector file format itself is close to fixed: a driver that meets a
`kind` it does not recognise fails rather than skips, so a new kind fails every existing driver
until that driver is updated. Adding a field to a case's `expect` object is safe, because a driver
compares only the fields a case carries; renaming or removing one is not.
[`30-conformance.md`](30-conformance.md#driver-contract) and
[`adr/0035`](adr/0035-manifest-driven-conformance-harness.md).
