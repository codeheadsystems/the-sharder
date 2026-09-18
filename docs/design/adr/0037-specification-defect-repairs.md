# 0037. Specification defect repairs

Status: accepted, with the default of `n + 2` rehomed from `FAIL-022` to `CORE-048`
by [`0063`](0063-decision-api-surface-boundaries.md). Date: 2026-09-16.

Every repair below stands. Where the text attributes the attempt limit's default of `n + 2`
to `FAIL-022`, `CORE-048` states it now, so that the rule belongs to the `routing` surface. The
clamp to the length of the attempt sequence stays with `FAIL-022`, and applies to the attempt walk
alone. The limit `CORE-045` takes from the decision is unchanged, and so is every value.

## Context

Two stages executed [`../10-specification.md`](../10-specification.md) rather than reading it. The
conformance stage built a reference implementation and 58 vector files against it; the Java binding
stage rendered it into an API. Each produced a defect register, one in
[`../30-conformance.md`](../30-conformance.md) and one held as a working file, naming places where
the specification could not be implemented as written, could be implemented two ways, or said
nothing where a port had to choose. The unrepaired remainder of both is in
[`../90-open-questions.md`](../90-open-questions.md).

This record covers the repairs taken for those defects. The spread relaxation ladder is the largest
behaviour change among them and has its own record,
[`0036`](0036-spread-relaxation-ladder-direction.md).

Every repair was constrained by one rule: a requirement identifier is permanent, because the
conformance suite joins to the specification on it. No identifier was renumbered, withdrawn, or
reused, and every new requirement took a free number within an existing group or the new `HASH-*`
prefix.

## Decision

### Hash construction in the normative specification

`keyHash`, `ringToken`, `rvScore`, `slotScore`, and `rangeScore` were used by `RING-020`,
`SLOT-001`, `RV-003`, `RANGE-031`, `PROP-024`, and `OBS-042`, and defined nowhere in the
specification. The framing construction, the five domain tags, the derivation of the SipHash key
from `hash.seed`, and the textual form of a u64 lived in
[`0001`](0001-hash-function-and-key-encoding.md) and in a stage working file. A decision record is
not a normative document, so an implementer reading only the specification could not produce a
conforming implementation, and the hash vectors had no requirement identifier to name.

A `HASH-*` prefix now carries the construction, in the Core model section, where a reader meets it
before the first use in the Routing keys and placement section. `HASH-001` through `HASH-044` state
SipHash-2-4 and its parameters, the seed to key derivation, `u32be`, `frame`, the five
domain-tagged functions with their fields, the integer widths, the unsigned comparison rule, the
sixteen-digit hexadecimal text form, and the requirement that a port verify against the published
reference vectors before it runs a conformance vector.

Nothing computed changed. The construction is transcribed from
[`0001`](0001-hash-function-and-key-encoding.md) and from the stage working file without
alteration. The hash vector files change only in the requirement identifiers their cases name, which
replace a citation of [`0001`](0001-hash-function-and-key-encoding.md); every `framedMessage` and
every expected value in them is unchanged octet for octet.

[`0001`](0001-hash-function-and-key-encoding.md) keeps the decision and the rejected alternatives.
It is no longer the only place the construction appears.

### Closed set completeness

Three closed statements were incomplete. Two named a set with no member for a reachable case,
and one gave an order that did not cover every condition it had to order.

`ERR-021` gave `noCandidate` a closed cause set with no member for two reachable cases: a `ring`
under `explicit` token assignment in which no eligible node owns a token, and a `slot`, `range`, or
`directory` entry that matched the routing key and whose every named node lies outside the eligible
set. The suite wrote `"cause": null` for such a case. The set gains `noEligibleTokenOwner` and
`authoredListExcludedAll`, and `ERR-020` names the two cases alongside the five it already named.
No vector carries a null cause for `noCandidate` any longer.

`REPL-021` classified a shortfall over the eligible node set, which misses a candidate ordering
shortened for a third reason: an authored node list or an override `pin` naming fewer than `n`
eligible identities, or a derived assignment in which some eligible nodes carry a virtual node count
of zero. In each of those the eligible set is large enough and the spread requirement is not what
limits the prefix. The requirement is restated over the candidate ordering, which covers every case
and agrees with the old wording wherever the old wording decided. The reference already classified
this way, so no vector changed.

`ERR-008` gave a report order for the five routing conditions and said nothing about the recipient
conditions, although a verdict can carry `relation` of `senderAhead` together with `ownership` of
`notOwner`. `FENCE-121` and `FENCE-141` then both require a refusal and direct the caller
differently: `notOwner` says to retry at the named owner, `epochMismatch` says to refresh and retry.
`ERR-045` states the precedence `identityMismatch`, `unready`, `notOwner`, `epochMismatch`, so the
caller is sent somewhere to go rather than told to refresh. `identityMismatch` leads because no
refresh and no redirect resolves it, and because the recipient's `currentOwner` is computed from an
unrelated topology and means nothing.

### One kind of value per error member

`ERR-004` typed `cause` as the closed sub-reason, and `ERR-021`, `ERR-022`, `ERR-050`, and `ERR-052`
each drew it from a closed set. `ERR-040` instead required `notOwner` to carry `currentOwner` in
`cause`, rendered as a node identity, which is neither closed nor a sub-reason. A binding typing
`cause` as an enumeration could not express `ERR-040`; one typing it as a string lost the closed set
everywhere else, and the Java binding worked around it with a typed accessor on one leaf.

`Error` gains a `currentOwner: NodeId | none` member, `ERR-040` carries the owner there, and `cause`
is stated to carry a member of a closed set and nothing else. This changes the error shape in the
recipient scenarios, which regenerate.

### Missing record and interface members

Five requirements constrained a value, or required an effect, that no declared record or interface
member carried.

`CORE-042` constrained `primary` and `READ-016` required the decision to identify it, and
`RoutingDecision` declared no such member. It gains `primary`, and `CORE-042` records that `entries`
is never empty on a decision, because an empty candidate ordering raises `noCandidate` instead.

`FAIL-021` put the attempt limit on the routing call and `FAIL-022` defaulted it to `n + 2`, which
is resolved during the call, while `Router.attempts(decision)` received only the decision.
`RoutingDecision` gains `attemptLimit`, and `CORE-045` states that `attempts` takes it from the
decision rather than resolving it again. The alternative, a router that remembers a limit per
decision, is per-decision state that `CORE-034` and `CORE-041` both push against.

`OBS-046` forbade `route` computing an explain record unless the caller asked for one, and
`RouteOptions` gave no way to ask. It gains `explain`, false by default.

`HEALTH-030` made the outlier comparison set the placement set at the epoch in force and
`HEALTH-034` made the ejection ceiling a percentage of its size, while `HealthView` had no member
through which a snapshot reaches a supplied implementation. `HEALTH-010` gains
`onSnapshotInstalled`, and `HEALTH-016` states that it is called before the snapshot is visible to a
routing call, that it is the only route by which a `HealthView` learns the placement set, and that a
view which ignores it runs no outlier ejection and refuses no transition. A notification was
preferred to passing the placement set to `advance` and `report`, because a view that does no
outlier ejection then pays nothing per signal.

`HEALTH-051` required each routing call reaching a node in preference list order to increment that
node's probe counter, through an interface offering only `stateOf`, which is a read. `CORE-040` put
`attemptable` on every preference entry, so computing a decision evaluated admission, and `OBS-041`
put the preference entries inside the explain record, which `OBS-045` forbids changing state. The
record could therefore neither carry `attemptable` nor omit it. `HEALTH-010` gains `admitProbe`, and
`HEALTH-017` states that `route` and `routeForRead` call it for each `probation` entry they reach,
that `explain` does not, and that `explain` reports the value a probe-free evaluation gives.

### Exact comparisons between products

`PLACE-051` already required a wide multiplication for `weight * perWeightUnit`. Four further
threshold comparisons are a product compared against a product, and the Java binding found each of
them: `HEALTH-034`, `FAIL-031`, `OBS-031`, and `SPLIT-041`. Two of the four exceed 64 bits within
the declared range of their operands. `SPLIT-021` types every member of a `ShardReport` as a u64, so
`shardRequests * shardCount * 100` under `OBS-031` and `hottestKeyRequests * 100` under `SPLIT-041`
are both unrepresentable for values an integrator may legitimately supply. A threshold that inverts
under overflow is worse than one that is never reached, because it fires in the wrong direction.

`CORE-005` states that a comparison between two products holds over the exact products, and names
the requirements it governs. It permits any rule that agrees with the exact comparison, so a port
with 128-bit multiplication, one with arbitrary-precision integers, and one that divides instead are
all conforming. The four requirements each cross-reference it.

### Unreachable and ambiguous clauses

`RING-031` enumerated the tokens of the placement set in ring order, and `RING-011` and `RING-012`
both admit two ring entries carrying one token value. `shards()` therefore yielded one shard
identifier twice, while `TOPO-211` computes an ownership delta over a set of shards, and whether the
enumeration deduplicates was unstated. It now enumerates distinct token values, each once, because
`RING-032` answers identically for either entry of a collision, so the two entries are one shard by
every observable measure. The `ring-token-collision` case reports the collapse as data: three ring
entries, two shards.

`PLACE-031` rendered a shard identifier as text under every strategy while `CORE-001` typed
`ShardId` as octets, leaving the encoding between them unstated. `PLACE-034` states that the octets
are the UTF-8 encoding of that rendering, which is the ASCII encoding under `ring`, `slot`, and
`directory`, and is what `HASH-031` frames into `rangeScore`.

`RV-020` said `shardOf` returns the routing key, "rendered as the lowercase hexadecimal encoding of
its octets wherever a shard identifier is required to be textual", while `PLACE-031` said the shard
identifier is the hexadecimal. `PLACE-032` and `RV-022` then required `candidatesForShard(s, E)` to
equal `candidates(s, E)`, which holds only if `s` is the routing key octets rather than the
hexadecimal. The reference decodes the hexadecimal before routing, and no requirement said to.
`RV-020` now names the hexadecimal alone, and `PLACE-032` and `RV-022` state the decode. This defect
appears in neither register.

`PLACE-065` clause 3 gives the lower array index to entries still tied after clauses 1 and 2. Two
entries of one table that both match a routing key and survive those clauses carry the same `kind`
and the same decoded octets, which `PLACE-067` makes a validation failure, so the clause is
unreachable in a valid document. It is retained, and the specification now says so, because
`TOPO-191` exposes evaluation without installation and a matcher table can therefore be evaluated
without having been validated. `RANGE-014` and `RANGE-015` are retained for the same reason and
already said so.

`REPL-011` illustrated its distinctness rule with a candidate ordering offering one identity twice,
"which an override `pin` may do". `OVR-012` deduplicates a pinned ordering under `PLACE-013` and the
schema marks the `pin` array `uniqueItems`, so no ordering the specification defines reaches that
case. The parenthetical is replaced by a statement of where deduplication happens.

### Remaining bounds and document rules

`PROP-023` requires the range count times `v_min` to be at least `10000 * V` before its bound says
anything, which needs at least 20000 authored ranges on the smallest topology where it is
meaningful. A range is a document entry rather than a derived quantity, so no topology of a workable
size reaches the precondition. `PROP-026` states that `PROP-023` is a bound with no executable test
and that a conformance suite reports it as uncovered by a witness rather than as covered. Weakening
the bound was rejected: the multiplier and the precondition are a coupled pair, and choosing a new
pair is a statistical argument nobody has made.

The property section drew its sample "independently and uniformly" and named no generator, so two
ports drawing different samples both satisfied the statement and a bound failing in one was not
reproducible in the other. `PROP-006` requires the deterministic sample that
[`../30-conformance.md`](../30-conformance.md) fixes, which is SplitMix64 seeded with
`5348415244455201` and is already what the suite draws.

`REPL-024` and `SPREAD-016` deduplicate an event per combination of epoch, shard identifier, and
cause, which needs a table keyed by shard identifier for the life of an epoch. `OBS-003` refuses a
shard identifier as a label under `ring` and `rendezvous` precisely because it is unbounded, and
bounds it elsewhere by `shardLabelLimit`. `OBS-026` bounds the deduplication table by the same
limit, and above it deduplicates per epoch and cause alone while emitting one event that names the
suppression.

A JSON string can carry a lone surrogate through a `\uD800` escape and a JSON object can carry two
members of one name. Neither has a defined RFC 8785 canonical form, so two ports would digest such a
document differently or fail differently, and neither the specification nor
[`../20-topology-format.md`](../20-topology-format.md) said whether the document is valid.
`TOPO-002` rejects both at stage 1, and the document rules say so.

`MOVE-061` passes a `MonotonicClock` to `step` and to `recover` while `CORE-004` makes the monotonic
source something the binding supplies at construction, and nothing said which wins. `MOVE-062` makes
the supplied clock supersede for the duration of the call and forbids mixing readings from the two
sources. The parameter is kept rather than dropped because a plan advanced from several units of
execution then has one reading per call rather than one shared source.

`sharder.health.ejections` and `sharder.health.failure_percent` carry `node` alone while `OBS-006`
requires `topology_id` on every metric describing work done against a snapshot. `OBS-007` exempts
both, because `HEALTH-006` keys health entries by node identity and makes them survive an epoch
change, so neither value describes work against one snapshot.

### Requirement count

The specification stated 602 distinct requirement identifiers before these repairs, with no
duplicate and no gap that carries meaning. The figure of 603 in the stage brief was wrong, and the
conformance register recorded the discrepancy. The register now records the settled count.

## Consequences

The specification carries 635 requirements across 25 prefixes, up from 602. Nineteen of the
thirty-three new ones are the `HASH-*` prefix, which moves no decision and changes no computed
value; the other fourteen are appended within existing prefixes at numbers not previously used.

Three of the repairs change what a port observes. The spread ladder is the largest and
[`0036`](0036-spread-relaxation-ladder-direction.md) records it. `RING-031` changes `shards()` under
a ring token collision, which is a 64-bit collision a random topology never produces and which the
suite reaches only through a searched-for pair. Splitting `currentOwner` out of `cause` changes the
error shape a recipient reports, and the two new members of the `noCandidate` cause set change the
cause a directory entry with no eligible node reports from absent to `authoredListExcludedAll`.

Two of the repairs add a member to an extension point an integrator implements.
`HealthView.onSnapshotInstalled` and `HealthView.admitProbe` are new, and a binding declares them
with a default so that an existing implementation stays valid: ignoring the snapshot means running
no outlier ejection, and answering true from `admitProbe` means admitting every probe. Both defaults
are stated in the specification rather than left to the binding.

The repairs taken here are the ones whose shape the registers made unambiguous. Where a requirement
was homeless rather than unimplementable, and where naming a home would have invented an interface
that three ports could reasonably shape three ways, the question is left to
[`../90-open-questions.md`](../90-open-questions.md) instead. `TOPO-191`, `TOPO-211`, and
`FENCE-061` each require an implementation to expose something and name no owning surface, and they
stay that way for now, as `OQ-02`.

## Alternatives

Fixing the registers rather than the specification, by recording each defect as a known deviation
that a port reads before it implements. Rejected because a defect register is not a normative
document either, and a port that reads only the specification is exactly the reader the conformance
suite exists to serve.

Withdrawing and renumbering the requirements whose meaning changed, so that a port cannot pass an
old vector against a new requirement. Rejected because the conformance suite joins to the
specification on requirement identifiers, a renumbered identifier silently breaks a test, and the
suite regeneration already shows which vectors a repair moved.

Deferring every repair with an API consequence to the open-questions register, and repairing only
the arithmetic. Rejected because five of them are requirements that cannot be implemented at all as
written, and leaving those unrepaired means each port invents a shape and the bindings diverge in
exactly the place the specification exists to prevent.
