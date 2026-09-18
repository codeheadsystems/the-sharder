# Open questions

Every question the design of the sharder library leaves unresolved, with the default in force and
the evidence that would settle it. Each entry carries an identifier that
[`99-roadmap.md`](99-roadmap.md) refers to.

A question blocks v0.1 where the answer changes something v0.1 publishes: a requirement, a document
member, an error code, or an interface a second port reads. Everything else rides along and is
answered from a running deployment.

Status: no implementation of the sharder library exists. Several questions below name a running
deployment, a benchmark, or a second port as the evidence that settles them, and none of those has
been produced.

| Identifier | Question | Release |
|---|---|---|
| `OQ-02` | Owning surfaces for three requirements | blocks v0.1 |
| `OQ-03` | Per-domain replication factors | rides along |
| `OQ-04` | Occupancy cap per failure domain | rides along |
| `OQ-05` | Suffix and glob matchers | rides along |
| `OQ-06` | Directory table size ceiling | rides along |
| `OQ-07` | Zero hash seed as the default | rides along |
| `OQ-09` | Monotonicity floor across a restart | rides along |
| `OQ-10` | Weight clamping as an event | rides along |
| `OQ-11` | Reference implementation as its own oracle | settled by v0.1 |
| `OQ-13` | Requirement coverage below full | rides along |
| `OQ-14` | Contended writes on the routing path | rides along |

## Gaps in the normative surface

### OQ-02. Owning surfaces for three requirements

`TOPO-191` requires a validation entry point that produces a snapshot without installing it,
`TOPO-211` requires the ownership delta between two snapshots to be exposed, and `FENCE-061`
requires a recipient-side check with a stated signature. None of the three names an interface it
belongs to, and none appears on `Router` in `CORE-030` or on `TopologySnapshot` in `CORE-020`. A
port in a language where every function has a home invents one.

Recommended default: the shapes the Java binding chose, which are `TopologyLoader.validate`,
`OwnershipDelta.between` as a static factory, and `Router.recipient(selfId)` answering a recipient
with `check` and `admit`.

Evidence that settles it: a second port. Where a port in another language reaches the same three
shapes without being told, naming them in the specification costs nothing. Where it reaches
different ones, the difference names a constraint the Java shapes encode and the specification does
not.

## Format extensions

### OQ-03. Per-domain replication factors

Whether the format expresses a count of replicas per failure domain, in the manner of Cassandra's
`NetworkTopologyStrategy`, rather than a total count with a distinctness requirement.
`replication.factor` with `replication.spread` asks for distinct domains at a named level and cannot
ask for three replicas in each of two regions. Read affinity under `READ-010` prefers a replica in a
named domain and guarantees none, which is the same requirement's other half.

Recommended default: omit from format version 1.0, and add `replication.byDomain` as a minor version
whose absence reproduces the current behaviour.

Evidence that settles it: a deployment whose durability requirement cannot be written with `factor`
and `spread`. Multi-region storage with a quorum inside each region is the shape that produces one.

### OQ-04. Occupancy cap per failure domain

Whether spread degradation relaxes all or nothing per level, as `SPREAD-010` does, or caps occupancy
per domain and raises the cap one step at a time. A topology at factor 4 across three zones drops
the zone requirement entirely under the ladder and may place three replicas in one zone, where a cap
would place two, one, and one.

Recommended default: keep the ladder. [`adr/0015`](adr/0015-spread-degradation-algorithm.md)
rejected the cap, and [`adr/0036`](adr/0036-spread-relaxation-ladder-direction.md) reopened the
ladder's direction without reopening its shape.

Evidence that settles it: a deployment at a replication factor above its domain count whose
durability argument the all-or-nothing relaxation breaks. Changing the ladder changes which nodes
are replicas, so the change regenerates every spread vector and every witness that rests on one.

### OQ-05. Suffix and glob matchers

Whether a matcher kind beyond `exact` and `prefix` enters the format. Regular expressions are
refused permanently under [`adr/0009`](adr/0009-override-composition.md), because a matcher has to
be evaluable identically and in bounded time in five languages.

Recommended default: `exact` and `prefix` alone in version 1.0. A later kind enters as a minor
version and carries its precedence rule against a prefix match of equal length.

Evidence that settles it: a tenant table that cannot be written as exact entries plus prefix
entries. A proposal answers the precedence question before it answers anything else, because
`PLACE-065` is a total order and a new kind has to keep it total.

### OQ-06. Directory table size ceiling

Whether a `directory` table carries a hard upper bound on its entry count. Canonicalisation and
digesting cost grow with the table on every load, and the matcher index grows with it in memory.
`directoryWarnEntries` defaults to 10000 and emits an event above it, and no rule refuses a larger
table.

Recommended default: no hard limit, the warning event, and the expectation that a table too large
for a document is published by a control plane as a `slot` map instead.

Evidence that settles it: a measured load time and a measured resident size for a directory of
100000 entries. The cost falls on the load path rather than the routing path, so a table an
authority republishes hourly tolerates a size that one republished every minute does not.

## Determinism and hashing

### OQ-07. Zero hash seed as the default

[`adr/0027`](adr/0027-hash-seed-exposure-and-tenancy.md) settled the exposure: the seed is a secret,
`seedIsDefault` is on the snapshot, and an event fires once per accepted snapshot whose seed is
sixteen zero octets and whose document carries an override entry or a `directory` strategy. Two
questions survive it. Whether a later major version refuses the zero seed outright, and whether the
event's condition catches a multi-tenant deployment that expresses tenancy through neither an
override nor a directory.

Recommended default: keep the zero default, because conformance vectors are reproducible only
against a known seed, and keep the event's condition as it stands.

Evidence that settles it: a count of deployments that separate tenants without an override entry and
without a `directory` strategy. A population above a handful widens the condition; a population of
none leaves it alone.

## Operational defaults

### OQ-09. Monotonicity floor across a restart

`minEpoch` is unset by default under `CFG-010`, so monotonicity begins at the first accepted
document and a restarted caller accepts an epoch below the one it was serving before the restart.
The library takes the value and the integrator persists it.

Recommended default: unset. A library that persisted the floor itself would write to a store it was
not given.

Evidence that settles it: whether a storage deployment tolerates a restarted caller routing against
an older epoch for the interval before its provider catches up. A provider reading from a follower
of a replicated store is the case that produces the answer, because a leader-only provider never
serves a lower epoch.

### OQ-10. Weight clamping as an event

`PLACE-052` clamps a virtual node count that exceeds its cap and emits `topology.weight_clamped`
naming the requested and the granted count, rather than failing validation. Two nodes at weights
2000 and 4000 under a cap of 4096 tokens therefore place in a ratio the operator did not author.

Recommended default: an event rather than a validation failure. A cluster-wide cap on virtual nodes
is a legitimate cost control, and refusing the document would make the cap unusable.

Evidence that settles it: operators reporting a distribution they did not ask for. The event carries
both counts, so the question is whether anything reads it.

## Conformance confidence

### OQ-11. Reference implementation as its own oracle

Every expected value in the suite was computed by `conformance/generator/sharder_ref`, and
`conformance/driver/python/run_suite.py`, which reports no failures, runs that same implementation.
The result shows that the vectors are self-consistent and that the driver contract is implementable.
It does not show that the reference reads the specification the way the specification is written.
SipHash is verified against the published reference vectors and against OpenSSL before generation,
so the hash primitive has an external oracle and nothing above it does.

Recommended default: treat the first independent port as the check, and regenerate rather than argue
wherever the two disagree.

Evidence that settles it: the Java implementation, written from
[`10-specification.md`](10-specification.md) rather than from the reference, reaching `core` without
forcing a vector to move. Every vector it does force to move names a place where the reference and
the specification disagreed.

### OQ-13. Requirement coverage below full

The suite names some of the requirements the specification states and not all of them.
[`../../conformance/coverage.json`](../../conformance/coverage.json) gives the two totals and names
every uncovered identifier, and
[`30-conformance.md`](30-conformance.md#requirements-without-an-executable-test) accounts for the
uncovered ones by group: concurrency and visibility, provider timing, observability values, the
negative requirements of rate control, movement hook opacity, and configuration acceptance.

Recommended default: accept the figure and the accounting. A requirement in those groups constrains
how a value is produced rather than what the value is, and a language-neutral data file carries
outputs.

Evidence that settles it: a defect found in a port that a data file could have caught. Each one
names a group that deserves a per-binding check, a race detector, or a benchmark instead.

### OQ-14. Contended writes on the routing path

`HEALTH-051` makes an attempt that reaches a node in `probation` increment that node's probe
counter, and `FAIL-030` makes an attempt account against a sliding window held per router. Both are
writes to state shared by every unit of execution that routes, while `CORE-052` forbids a lock to
read the snapshot and `CORE-064` forbids a routing call blocking on anything but the health view. No
requirement is violated, and a port that implements either counter with a lock pays a contended
write per attempt.

Both writes are now on the attempt walk rather than on the routing call. `HEALTH-017` moves probe
admission to `next` of `FAIL-023`, so `route` writes nothing and a node in `probation` is
incremented once per attempt that reaches it rather than once per entry a routing call examines.
[`adr/0067`](adr/0067-probe-admission-at-the-attempt.md) records that decision. The budget window
was already accounted at the attempt, so the two counters now contend under the same rate.

Recommended default: no change to the specification. The Java binding uses an atomic increment for
the probe counter and an array of adders for the budget window, and neither takes a lock.

Evidence that settles it: a benchmark in which attempt latency at high concurrency is dominated by
one of the two counters. The same benchmark shows whether a requirement forbidding a lock on them
would change anything.

## Withdrawn questions

A question is withdrawn where the surface it asked about leaves the design. Its identifier is listed
here and is never reused, under the convention the Conventions section of
[`10-specification.md`](10-specification.md#withdrawn-identifiers) states for a requirement
identifier. A reader who meets a withdrawn identifier in an older document reads the record named
here.

| Identifier | Question | Record |
|---|---|---|
| `OQ-08` | Slot tie-break vector absent | [`adr/0055`](adr/0055-slot-derived-assignment-withdrawal.md) |
| `OQ-12` | Balance bound without an executable test | [`adr/0054`](adr/0054-range-strategy-withdrawal.md) |

## Questions settled in the design

These questions were open during the design and are answered. Each answer is normative in the
requirement named.

| Question | Answer | Where |
|---|---|---|
| read preference against agreed ownership | a separate `routeForRead` reordering the replica prefix alone | `READ-010` to `READ-016` |
| routing key size limit | `maxKeyBytes`, 65536 by default, refusing rather than truncating | `CFG-013`, `KEY-005` |
| Unicode normalisation of text keys | none, ever; a key is opaque octets | `KEY-001`, `KEY-002` |
| a power-of-two constraint on `slotCount` | none; the remainder is exact for any count | `SLOT-003` |
| the hash construction's normative home | the `HASH-*` prefix, transcribed from ADR 0001 | `HASH-001` to `HASH-044` |
| the provider contract's normative home | the Core model section, transcribed from ADR 0004 | `CORE-080` to `CORE-101` |
| asking for an explain record | `explain` on `RouteOptions`, false by default | `CORE-030`, `OBS-046` |
| the attempt limit reaching `attempts` | `attemptLimit` on the decision | `CORE-040`, `CORE-045` |
| the primary of a decision | `primary` on the decision | `CORE-040`, `CORE-042` |
| the placement set reaching a supplied health view | `onSnapshotInstalled` | `HEALTH-016` |
| probation admission separated from a read | `admitProbe`, called by the attempt walk alone | `HEALTH-017` |
| the current owner carried by `notOwner` | its own member, leaving `cause` closed | `ERR-004`, `ERR-040` |
| cardinality of the event deduplication table | bounded by `shardLabelLimit` | `OBS-026` |
| the property sample generator | SplitMix64 seeded with `5348415244455201` | `PROP-006` |
| the clock supplied to a migration step | it supersedes for the duration of the call | `MOVE-062` |
| unpaired surrogates and duplicate member names | rejected at stage 1 of the load pipeline | `TOPO-002` |
| the octets of a shard identifier | the UTF-8 encoding of the rendering | `PLACE-034` |
| ordering of the recipient conditions | `identityMismatch`, `unready`, `notOwner`, `epochMismatch` | `ERR-045` |
