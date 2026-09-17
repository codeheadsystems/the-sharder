# Conformance suite

The sharder library specifies routing decisions that two implementations in two languages reproduce
octet for octet. The conformance suite is the mechanism that holds them to it. It is a tree of
language-neutral data files under [`../../conformance/`](../../conformance/), together with the
reference implementation that computed every expected value in them.

The suite tests [`10-specification.md`](10-specification.md). Requirement identifiers are the join
key: every vector, every property, and every scenario names the requirements it exercises, so a
maintainer asking what tests `SPREAD-013` reads the answer out of
[`../../conformance/manifest.json`](../../conformance/manifest.json) rather than out of a test
runner.

## Suite layout

| Path | Contents |
|---|---|
| `conformance/manifest.json` | every vector file, its coverage, and every topology digest |
| `conformance/coverage.json` | requirement coverage, computed from the specification |
| `conformance/topologies/` | the topology documents the suite routes against |
| `conformance/topologies/invalid/` | documents that fail a load-time rule |
| `conformance/vectors/` | the golden vectors |
| `conformance/properties/properties.json` | the property definitions |
| `conformance/scenarios/` | the simulation scenarios |
| `conformance/generator/` | the reference implementation and the generator scripts |

A topology document is stored once and referenced by path from the vector files that use it, so a
document appears in the suite in exactly one form and its digest is asserted in one place.

## Vector file format

A vector file is a JSON object holding one **vector set**: a group of cases sharing a purpose, a
topology, and a requirement list. Each entry of `cases` is one **case**, which is one input and the
output the reference computed for it.

```json
{
  "vectorSet": "ring-derived-walk",
  "kind": "routing",
  "description": "The ring walk under derived tokens.",
  "requirements": ["RING-020", "RING-021"],
  "topology": "topologies/ring-plain.topology.json",
  "topologyDigest": "…",
  "cases": [ { "name": "…", "requirements": ["RING-020"], "key": {…}, "expect": {…} } ]
}
```

`kind` selects what a driver does with the cases. The kinds are fixed, and a driver that meets an
unknown kind fails rather than skipping the file.

| `kind` | Input of a case | Output asserted |
|---|---|---|
| `siphash` | a key and a message | the 64-bit SipHash-2-4 output |
| `hash` | a domain tag and framed fields | the framed octets and the 64-bit result |
| `keyTransform` | a key | the routing key |
| `digest` | a document | the canonical form, its length, and the digest |
| `validation` | a document | validity, the rules broken, and the errors |
| `routing` | a key | the whole routing decision, or the condition raised |
| `shards` | a topology | `shards()`, `shardOf`, and `candidatesForShard` |
| `readAffinity` | a key and an affinity request | the preference list and the reordered list |
| `permutation` | a document and its permutation | the orderings, asserted equal |
| `collidingKeys` | two or more keys | the value they collide on and their orderings |
| `movement` | two documents | first candidates before and after, and the move counts |
| `tieBreak` | a topology built on a collision | the colliding value and the resulting ordering |
| `formula` | the named inputs of one formula | the formula's integer result |
| `stages` | a key under a spread policy | every relaxation stage and the one chosen |
| `defaults` | a document omitting defaults and one writing them out | the two orderings, asserted equal |
| `errorTaxonomy` | none | the closed condition set and the report order |
| `ownershipDelta` | two documents | the shards changed, with nodes gained and lost |
| `pinShard` | a key matched by a pin | the shard, the pinned ordering, and the shard's ordering |
| `propertyWitness` | a topology and a sample | the observed counts and the evaluated inequality |

`topologyDigest` carries the SHA-256 of the RFC 8785 canonical form of the referenced document. A
driver that checks it before running the cases separates a canonicalisation defect from a placement
defect, which are otherwise hard to tell apart from a failing ordering.

### Octet encoding

A key is octets, and `KEY-013` permits octets that are not valid text, so every key in the suite
carries its encoding in the shape the topology format already uses for a matcher value.

```json
{ "encoding": "utf8",   "value": "acme:orders:99" }
{ "encoding": "base16", "value": "ff0001" }
```

A routing key, a hash input, a framed message, a token, and a digest are written as lowercase
hexadecimal. A node identity is written as the string the topology document spells, because
`PROP-002` fixes ordering equality over the JSON serialisation of an array of those strings. A u64
is sixteen lowercase hexadecimal digits, most significant first.

### Routing case expectations

A `routing` case carries either `expect` or `expectError`, never both.

```json
"expect": {
  "routingKey": "61636d65",
  "shard": "61636d65",
  "token": { "topologyId": "tenant-router", "epoch": 42 },
  "factor": 1,
  "replicaCount": 1,
  "candidates": ["cluster-us-2", "cluster-us-1"],
  "preferenceList": [ { "node": "cluster-us-2", "position": 0, "role": "replica" } ],
  "relaxedLevels": [],
  "spreadStage": 0,
  "shortfall": "none",
  "matchedOverride": { "index": 0, "mode": "pin" }
}
```

```json
"expectError": { "code": 101, "name": "noCandidate", "cause": "constraintExcludedAll" }
```

`spreadStage` is the relaxation stage `SPREAD-012` selects. It is not a member of `RoutingDecision`,
which reports the relaxed levels rather than the stage index, and it is carried in the vector
because a port that computes the right levels by the wrong ladder is a defect worth naming.

A `cause` of `null` marks a condition that defines no closed cause set, such as
`identityMismatch`. Every `noCandidate` case carries a cause, because `ERR-021` names one for each
case of `ERR-020`.

## Driver contract

A **driver** is the per-language harness that reads the suite and runs it. The suite is designed so
that a driver needs no parser beyond a JSON reader: the reference driver is five hundred lines
covering every kind, and a driver that starts at the `hash` and `routing` kinds alone is under a
hundred.

1. Read `manifest.json` and fail where any file it lists is absent.
2. For each vector file, dispatch on `kind`.
3. For a file naming a topology, load that document, validate it, install it, and assert the
   digest against `topologyDigest`.
4. For each case, decode the key by its `encoding`, run the call the kind names, and compare the
   result to `expect` field by field.
5. Report a failure by vector set, case name, field, and the requirement identifiers the case
   names.

A driver compares only the fields a case carries. A case that omits `relaxedLevels` makes no claim
about it. Every case the generator writes carries every field of the decision, so the omission rule
matters only for a suite a port extends locally.

Field comparison is exact. A list is compared element by element in order, an integer by value, and
an octet string by its hexadecimal spelling in lower case. A driver that compares node identity
lists as sets passes a suite it should fail.

[`../../conformance/driver/python/run_suite.py`](../../conformance/driver/python/run_suite.py) is
a driver written against the reference, and is the worked example a port copies. It reports no
failures against the suite as generated, which says that the contract above is implementable as
written; it says nothing about the suite's correctness, because it runs the implementation that
computed the expectations.

## Conformance levels

A **conformance level** names a set of vector files, properties, and scenarios that a port runs in
full. A port declares the levels it reaches, not a percentage.

| Level | Contents | A port needs it when |
|---|---|---|
| `hash` | the `siphash` and `hash` vector sets | always; every other level rests on it |
| `core` | `hash`, plus every vector set of a kind other than `readAffinity`, plus every property at level `core` | always |
| `failover` | the health and attempt-sequence scenarios, and the health formula vectors | the port exposes `attempts` or a health view |
| `fencing` | the recipient scenarios and the fencing formula vectors | the port exposes the recipient check |
| `migration` | the handoff scenarios, the split lineage vectors, and the properties at level `migration` | the port exposes the handoff coordinator |
| `readAffinity` | the `readAffinity` vector set | the port exposes `routeForRead` |

`core` is not optional. A port that declines `core` is not a port of the sharder library, because
`core` is exactly the set of decisions that two callers in two languages have to agree on. The four
levels above it are optional because the specification makes their surfaces optional: an integrator
who routes a tenant identifier to one of five clusters never constructs a handoff coordinator, and a
port serving that integrator carries no coordinator to test.

A level is reached when every case in it passes. There is no partial credit, and the suite reports
no score. A port at `core` and `failover` that fails one migration scenario declares `core` and
`failover`, and says that `migration` is not implemented rather than that it is ninety per cent
implemented.

## Declaring conformance

A port declares conformance by publishing four things.

1. The suite revision it ran, named by the commit that produced `manifest.json`.
2. The levels it reaches.
3. The output of its driver, showing the case count per vector set and zero failures at each
   declared level.
4. Its deviations, as a list of case names with the reason for each.

A deviation is permitted only against a requirement carrying one of the RFC 2119 keywords
`SHOULD`, `SHOULD NOT`, or `MAY`, and the declaration names the requirement and the reason. A
deviation against a requirement carrying `MUST` or `MUST NOT` is a defect, and a port carrying one
declares no level.

A port that extends the suite with vectors of its own keeps them in a separate tree. The suite under
`conformance/` is generated, and a hand-edited file in it is lost at the next regeneration.

## Golden vectors

A golden vector pairs an input with the output the reference computed for it. The reference is
`conformance/generator/sharder_ref`, and
[`../../conformance/generator/README.md`](../../conformance/generator/README.md) gives its layout,
how to regenerate the suite, and how to verify its SipHash against the published vectors.

### Hash construction vectors

`vectors/hash/siphash-primitive.json` carries SipHash-2-4 alone, at the published key and messages.
`vectors/hash/construction.json` carries the framed, domain-tagged construction, and each case
carries the framed octets alongside the result so that a port failing a case reads off whether its
framing or its SipHash is wrong.

The construction these vectors test is specified by `HASH-001` through `HASH-044`, and each case
names the requirements it exercises. `vectors/hash/siphash-primitive.json` carries the reference
vectors `HASH-003` requires a port to verify against before it runs anything else.

### Strategy vectors

Every core strategy kind carries vectors for its ordinary path, its assignment modes, and its shard
naming. The ring sets cover the derived walk, an authored token set, the administrative state
filter, and a non-default hash seed. The rendezvous sets cover ordering, the virtual node cap, and
the two worked examples of [`20-topology-format.md`](20-topology-format.md), whose documented
outcomes the reference reproduces. The slot sets cover an authored assignment table at the Redis
Cluster slot count, a derived assignment, and a slot count that is not a power of two. The range
sets cover the half-open endpoints, the prefix rule, an octet above `0x7f`, and a derived
assignment. The directory sets cover the reachable clauses of matcher precedence and the no-match
result.

`vectors/spread/relaxation-stages.json` records every relaxation stage of `SPREAD-010` for a key,
the entries each admits, and the stage the builder chooses, which makes the ladder's behaviour data
rather than an assertion about it. `distinctStageOutcomes` counts the rungs that differ, and
`SPREAD-018` makes it `m + 1` where the topology carries a distinct domain at each named level.

`vectors/spread/skipped-level.json` fixes the scope of a domain path. Its topology declares three
levels, spreads over the finest one alone, and reuses rack identifiers across zones and regions, so
four of its nine cases place two replicas carrying one rack identifier under distinct rack paths.
A port that read `SPREAD-006` as spanning `replication.spread` rather than `domainLevels` cannot
produce those four. `vectors/read/affinity.json` fixes the same scope for `READ-013` with cases at
a level whose coarser level `replication.spread` does not name.

### Adversarial vectors

Each adversarial case the design calls for has a vector set.

| Case | Vector set |
|---|---|
| an empty topology, valid, routing to nothing | `adversarial-empty-topology`, and the ring form |
| a single node | `replication-single-node` |
| a replica count above the node count | `replication-factor-exceeds-node-count` |
| a replica count above the available failure domains | `spread-one-domain-relaxed`, and the strict form |
| every node in one failure domain | `spread-one-domain-relaxed`, and the strict form |
| duplicate node identities, invalid at load | `validation-documents`, case `duplicate-node-id` |
| colliding keys | `adversarial-colliding-keys` |
| weight zero | `adversarial-weight-zero`, `adversarial-weight-zero-all`, `ring-weight-zero`, `overrides-pin-admits-weight-zero` |
| every key transform edge case | the six `keytransform` vector sets |

`adversarial-colliding-keys` covers collision at each level the specification makes it observable:
two keys that transform to one routing key, two keys that reduce to one slot index, two keys owned
by one ring token range, and two keys with an identical 64-bit key hash. The last was found by a
Pollard rho search over the `sharder/key/v1` domain and verified through the reference before it
was written.

### Determinism vectors

`vectors/determinism/node-array-permutation.json` asserts that reversing the `nodes` array, changing
the epoch, changing the topology identifier, and adding `metadata` leave every candidate ordering
unchanged.

`vectors/determinism/tie-breaks.json` reaches the tie-break paths. A ring token collision and an
equal maximum score are 64-bit collisions that a random input never produces, so each was found by
the collision search in `conformance/generator/rho_search.c`, which iterates the hash on its own
output and recovers the collision by Brent's cycle detection at roughly 2^33 evaluations. The
topology built on each collision names the two identities, and the expected ordering is the one the
node identity tie-break of `PLACE-020` produces.

The file holds a ring token collision, an equal maximum score under `rendezvous`, and an equal
maximum score under `range`. It holds no `slot` case, because the search for one has not returned
a collision. The three scoring strategies, `rendezvous`, `slot`, and `range`, share one comparator,
ordering by score descending and then by node identity ascending, so the two cases present
exercise the path a `slot` case would. `conformance/generator/README.md` says how to add it.

A third tie-break case, equal-length competing prefixes in an override table, cannot exist in a
valid document, because two entries surviving the first two clauses of `PLACE-065` carry identical
matchers and `PLACE-067` refuses them. The case is covered instead by the validation vectors that
refuse such a document at load.

## Properties

A property is a claim over a sample rather than over one key.
[`../../conformance/properties/properties.json`](../../conformance/properties/properties.json)
states each one as data: its identifier, its name, the requirements it proves, its conformance
level, its statement, the quantifier it ranges over, the sample it draws, and its check.

Every bound and every sample size is the specification's own. `PROP-015`, `PROP-020`, `PROP-021`,
`PROP-022`, and `PROP-023` state integer inequalities with preconditions on the sample size, and the
property file carries those inequalities and those preconditions unchanged.

### Key sample

A bound over a sample needs a sample, and a conformance suite cannot draw at random and stay
reproducible, so the suite fixes one and `PROP-006` requires every sampled bound to be evaluated
over it. Keys are sixteen octets, drawn two `u64` values at a time from SplitMix64 seeded with
`5348415244455201`, each value written most significant octet first.

SplitMix64 is four lines of integer arithmetic in every language the suite targets, needs no
library, and uses no floating point. It reaches no placement decision; it chooses which keys a
property is evaluated over and nothing else. The first eight draws are a case in
`vectors/properties/witnesses.json`, so a port checks its sample generator before it attributes a
failing bound to its placement.

### Witnesses

A **witness** is the observed result of evaluating a property against a fixed topology and a fixed
sample. Because the sample is deterministic, a witness is a golden vector: a port reproduces the
counts exactly rather than approximately, and a port that disagrees with a witness has a defect
rather than an unlucky draw.

Each witness carries the per-node observed count, the virtual node or token count, both sides of the
inequality, whether it holds, and whether the sample size satisfies the precondition.

### Property catalogue

| Identifier | Proves | Witness |
|---|---|---|
| `P-DETERMINISM-001` | `PROP-001`, `PROP-002`, `PROP-003`, `PLACE-010`, `PLACE-012` | every routing vector file |
| `P-DETERMINISM-002` | `PROP-004`, `PLACE-015`, `REPL-015` | none |
| `P-DETERMINISM-003` | `PROP-005`, `CORE-002`, `RING-013`, `RV-013` | yes |
| `P-PURITY-001` | `PROP-040`, `PROP-041`, `PROP-045`, `FAIL-001`, `FAIL-011`, `REPL-016` | a scenario |
| `P-PURITY-002` | `PROP-042`, `PROP-043`, `PROP-044`, `PLACE-011`, `CORE-004` | none |
| `P-MOVEMENT-001` | `PROP-010`, `PROP-011` | yes |
| `P-MOVEMENT-002` | `PROP-013`, `PROP-015` | yes |
| `P-MOVEMENT-003` | `PROP-014`, `PROP-015` | yes |
| `P-MOVEMENT-004` | `PROP-012` | yes |
| `P-MOVEMENT-005` | `PROP-019` | none |
| `P-MOVEMENT-006` | `PROP-016`, `PROP-017`, `PROP-018`, `SLOT-004` | yes |
| `P-BALANCE-001` | `PROP-020`, `PROP-030`, `PROP-031`, `PROP-032` | yes |
| `P-BALANCE-002` | `PROP-021` | yes |
| `P-BALANCE-003` | `PROP-022`, `PROP-024`, `PROP-033` | yes |
| `P-BALANCE-004` | `PROP-023` | none; `PROP-026` states that the bound carries no executable test |
| `P-BALANCE-005` | `PROP-017`, `PROP-025`, `PLACE-044` | yes |
| `P-REPLICA-001` | `REPL-011`, `REPL-020`, `SPREAD-002`, `PLACE-013` | yes |
| `P-SPREAD-001` | `SPREAD-001`, `SPREAD-010`, `SPREAD-011`, `SPREAD-020` | yes |
| `P-SPREAD-002` | `SPREAD-012`, `SPREAD-013`, `SPREAD-015`, `SPREAD-017` | yes |
| `P-SPREAD-003` | `SPREAD-014`, `REPL-020`, `REPL-021` | yes |
| `P-PREFERENCE-001` | `REPL-012`, `REPL-013`, `REPL-014`, `REPL-017`, `SPREAD-021` | yes |
| `P-EXEMPT-001` | `PROP-050`, `PROP-052`, `OVR-010`, `OVR-013` | yes |
| `P-EXEMPT-002` | `PROP-051`, `OVR-020`, `OVR-032` | none |
| `P-EPOCH-001` | `TOPO-051`, `TOPO-061`, `TOPO-071`, `TOPO-081`, `TOPO-091` | a scenario |
| `P-EPOCH-002` | `TOPO-061`, `ERR-031`, `ERR-034` | a scenario |
| `P-HANDOFF-001` | `MOVE-151`, `MOVE-161`, `MOVE-171` | a scenario |
| `P-HANDOFF-002` | `MOVE-201`, `MOVE-211`, `MOVE-221`, `MOVE-231` | a scenario |
| `P-HANDOFF-003` | `MOVE-011`, `MOVE-021`, `MOVE-031`, `MOVE-441`, `MOVE-491` | a scenario |
| `P-FENCE-001` | `FENCE-101`, `FENCE-071`, `FENCE-081`, `FENCE-091` | a scenario |
| `P-ATTEMPT-001` | `FAIL-002`, `FAIL-003`, `FAIL-004`, `FAIL-012` | a scenario |

## Simulation scenarios

A scenario is a sequence of actions a port replays, each carrying the result the reference computed.
Scenarios are deterministic: every clock reading is an explicit instant in the file, every health
signal is an explicit action, and no step draws a random value.

A step names an `action`, its arguments, and an `expect` object. A driver dispatches on `action` and
compares the result field by field, as it does for a vector case.

| Scenario | Covers |
|---|---|
| `topology-rollback` | a reverted assignment arriving as a higher epoch, an equal epoch with a differing digest, and a foreign identifier |
| `caller-three-epochs-stale` | every recipient relation, both policies, a retained and an unretained token epoch, an unfenced request, and a sender ahead |
| `split-topology-view` | half the cluster on one epoch and half on another, disagreeing about a replica set |
| `redirect-walk-depth-limit` | the redirect bound and a redirect naming an already attempted node |
| `handoff-happy-path` | the ownership delta and every state of `MOVE-021` |
| `node-dies-mid-migration` | a destination that stops answering during `transferring` |
| `abort-during-catching-up` | an abort in `catchingUp`, and a second abort |
| `coordinator-death-and-recovery` | death in each non-terminal state, against each observation |
| `handoff-failure-kinds` | each of the four kinds of `MOVE-011` and the terminal state rule |
| `plan-superseded-by-new-epoch` | a third epoch arriving with three handoffs in flight |
| `migration-rate-control` | the concurrency bounds and both backpressure levels |
| `failover-and-recovery` | ejection, probation admission, and return to `available` |
| `health-filter-fails-open` | every replica ejected, and the filter returning the whole list |
| `ejection-ceiling` | the ceiling refusing an ejection that would empty the attemptable set |

The handoff scenarios drive a coordinator whose transition table is `MOVE-021` transcribed as data
and whose recovery mapping is `MOVE-211` transcribed as data, so a state sequence in a scenario file
is the table's output rather than an author's reading of it.

## Requirement coverage

[`../../conformance/coverage.json`](../../conformance/coverage.json) is computed by extracting every
requirement identifier from [`10-specification.md`](10-specification.md) and comparing it against
the identifiers the suite names. The table below is transcribed from it.

| Section | Prefix | Stated | Covered | Uncovered |
|---|---|---|---|---|
| Configuration surface | `CFG-*` | 30 | 5 | 25 |
| Core model | `CORE-*` | 51 | 8 | 43 |
|  | `HASH-*` | 19 | 15 | 4 |
| Error taxonomy | `ERR-*` | 36 | 26 | 10 |
| Observability | `OBS-*` | 32 | 4 | 28 |
| Replication and failover | `FAIL-*` | 29 | 15 | 14 |
|  | `HEALTH-*` | 40 | 19 | 21 |
|  | `READ-*` | 12 | 9 | 3 |
|  | `REPL-*` | 20 | 15 | 5 |
|  | `SPREAD-*` | 18 | 18 | 0 |
| Routing keys and placement | `DIR-*` | 11 | 11 | 0 |
|  | `KEY-*` | 24 | 22 | 2 |
|  | `OVR-*` | 28 | 27 | 1 |
|  | `PLACE-*` | 45 | 39 | 6 |
|  | `PROP-*` | 36 | 36 | 0 |
|  | `RANGE-*` | 21 | 19 | 2 |
|  | `RING-*` | 19 | 18 | 1 |
|  | `RV-*` | 11 | 11 | 0 |
|  | `SLOT-*` | 17 | 16 | 1 |
| Security and multi-tenancy | `SEC-*` | 20 | 7 | 13 |
| Topology change and rebalancing | `FENCE-*` | 25 | 22 | 3 |
|  | `MOVE-*` | 51 | 29 | 22 |
|  | `RATE-*` | 15 | 10 | 5 |
|  | `SPLIT-*` | 22 | 13 | 9 |
|  | `TOPO-*` | 26 | 18 | 8 |
| Total | | 658 | 432 | 226 |

The suite names 432 of the 658 requirements the specification states. The section below
names what the remaining 226 are and why no data file carries them.

## Requirements without an executable test

A requirement is not covered by an executable test when no language-neutral data file can carry its
output. Naming those is part of the suite, because a coverage figure that counts them as covered
tells a maintainer nothing.

### Hash construction

`vectors/hash/` names fifteen of the nineteen `HASH-*` requirements. The four it does not are
`HASH-012`, which fixes the key for the lifetime of a snapshot, `HASH-041`, which fixes the integer
widths, `HASH-042`, which makes the slot remainder the only division in placement, and `HASH-043`,
which forbids a floating-point value in hash arithmetic. Each constrains how a value is produced
rather than what it is. A port that departs from one of them produces a different value wherever the
departure reaches a computation, and fails a hash vector or a routing vector there rather than a
case that names the requirement.

### Concurrency and visibility

`CORE-050` through `CORE-065` and `TOPO-101` through `TOPO-141` constrain memory visibility, lock
placement, and thread ownership. A vector carries an output, and these requirements constrain how an
output is produced rather than what it is. They are reviewed per binding and, where a language
offers one, exercised by a race detector rather than by the suite. `TOPO-121`, which requires a
routing call to read the snapshot reference once at entry, is among them: a single-threaded driver
observes the same answer whether the reference is read once or twice.

### Provider behaviour

`CORE-080` through `CORE-101` state the provider contract, its two adaptation models, and its
failure behaviour. The polling and reconciliation settings of `CFG-010`, the backoff of `CORE-100`
and `ERR-033`, and the executor rule of `CFG-012` describe interaction with a provider over time. A
provider is an interface an integrator implements and a vector carries no interface, so the suite
carries the documents and the acceptance outcomes, which is the part that is agreed between callers;
the shape and the timing are local to one caller and are tested per binding.

Stage 2 of `TOPO-001`, schema validation, is covered: each case of `validation-documents` carries
a `stage` of `schema` or `semantic`, and `conformance/generator/verify_schema.py` runs the
published schema over every document the suite ships. Stage 1, decoding the octets as JSON, is the
JSON reader's own behaviour and carries no vector.

### Placement cost

`PLACE-070` through `PLACE-074` state the cost of each strategy, the products above which a warning
event is emitted, and the integer width a product is computed in. `CFG-014` carries the three
thresholds. A vector carries an output, and a cost is not one: the figures `PLACE-070` gives are
bounds to within a constant factor rather than values, and the suite's largest topology is eleven
nodes, so no document it ships crosses a threshold. The events of `PLACE-073` are outputs, and they
are uncovered for the reason every other event is, which the section below gives.

### Observability

`OBS-001` through `OBS-025` name metrics, labels, and events. A metric value is permitted to be a
floating-point number under `OBS-002` and is explicitly forbidden from reaching any decision, so
asserting one adds no cross-language guarantee. The suite covers the arithmetic that feeds the
skew detectors, `OBS-031` through `OBS-033`, because those are integer comparisons that change what
is reported. The explain record of `OBS-040` through `OBS-048` is covered only through `OBS-044`,
which requires it to agree with `route`, and a port checks that against its own routing vectors.

### Rate control and measurement

`RATE-121` through `RATE-141` say what an implementation measures and what it refuses to infer.
They are negative requirements about sources of information, and a data file cannot witness the
absence of a wall-clock read. `RATE-021`, `RATE-041`, `RATE-051`, `RATE-071`, `RATE-081`,
`RATE-091`, and `RATE-101` are covered, because each is an integer rule with an output.

### Movement hooks

`MOVE-111` through `MOVE-141` define the hook interface and the rule that the library interprets
none of `budgetUnit`, `unitsMoved`, `bulkRemaining`, `residue`, or the opaque member of a cutover
record. `MOVE-281` through `MOVE-401` describe the concurrent-holding window, which is a property of
the integrator's storage rather than of the library. The suite covers the sequencing rules around
them, `MOVE-151` through `MOVE-231`, because a state sequence is an output.

### Configuration and security

`CFG-001` through `CFG-007` and `CFG-060` through `CFG-063` govern how settings are accepted,
validated, and exposed. `CFG-004` is the load-bearing one and is covered by construction: no vector
carries a setting, so a port whose settings change a candidate ordering fails every routing vector
under one of its configurations. `SEC-001` through `SEC-004`, `SEC-014`, and `SEC-020` through
`SEC-034` state what the library declines to defend and what it discloses. `SEC-010` and `SEC-012`
are covered by the non-default seed vectors, and `SEC-001` by the key hash collision.

### Split execution

`SPLIT-021` through `SPLIT-051` accept integrator measurements and emit advice, and `SPLIT-171`
through `SPLIT-211` describe in-flight requests across a split, which needs two nodes and a
transport. Lineage, classification, the plan verdict, and the step decomposition are covered,
because `SPLIT-061` through `SPLIT-161` are pure functions of two documents.

## Specification defects and their repairs

Building the suite against [`10-specification.md`](10-specification.md) surfaced eleven defects.
Each is repaired in the specification, and the repairs are recorded in
[`adr/0036-spread-relaxation-ladder-direction.md`](adr/0036-spread-relaxation-ladder-direction.md)
and
[`adr/0037-specification-defect-repairs.md`](adr/0037-specification-defect-repairs.md). The table
joins each defect to the requirement that repairs it and to the vectors that now exercise it.

| Defect | Repaired by | Exercised by |
|---|---|---|
| the hash construction appeared only in ADR 0001 | `HASH-001` to `HASH-044` | `vectors/hash/` |
| the relaxation ladder collapsed to two rungs | `SPREAD-010`, `SPREAD-015`, `SPREAD-018`, `SPREAD-019` | `vectors/spread/` |
| clause 3 of the matcher precedence was unreachable | `PLACE-065` | `vectors/validation/documents.json` |
| the shortfall classification missed a third cause | `REPL-021` | `vectors/replication/` |
| the no-candidate cause set missed two cases | `ERR-020`, `ERR-021` | `vectors/directory/no-match.json` |
| the recipient condition precedence was unstated | `ERR-045` | `scenarios/split-topology-view.json` |
| `shards()` could enumerate one ring shard twice | `RING-031` | `vectors/determinism/tie-breaks.json` |
| the range balance bound was untestable | `PROP-026` | none, by construction |
| `RoutingDecision` declared no `primary` | `CORE-040`, `CORE-042` | every routing vector |
| the pin duplication parenthetical contradicted `OVR-012` | `REPL-011` | `vectors/overrides/` |
| the requirement count was reported as 603 | none; the figure was wrong | none |

Two further defects were found while repairing these, and neither register names them.
`REPL-020` classified a shortfall over the eligible node set for the same reason `REPL-021` did, and
is restated over the candidate ordering with it. `RV-020` and `PLACE-031` disagreed about whether a
`rendezvous` shard identifier is the routing key octets or their hexadecimal text, and `PLACE-032`
and `RV-022` required an equality that holds only under the first reading while the reference
implements the second. `RV-020` now names the hexadecimal and `PLACE-032` and `RV-022` state the
decode.

The specification states 658 requirement identifiers, each introduced as a backticked identifier
followed by a full stop at the start of a line, with no duplicate. `coverage.py` extracts them and
`run.sh` fails where the suite names one the specification does not state.

The ladder repair changes behaviour and therefore changes vectors.
`vectors/spread/relaxation-stages.json` moves from `distinctStageOutcomes` of 2 to 4 on the
`spread-ladder` topology, and `vectors/spread/degradation-ladder.json` moves with it. The suite
carries one topology whose `replication.spread` names more than one level, so a port that
implements the old ladder fails those two files and passes the rest.

## Regeneration

```sh
cd conformance/generator
./run.sh
```

The script verifies SipHash against the published vectors and against OpenSSL before it generates
anything, runs each generator, rebuilds the manifest, and prints requirement coverage. It exits
non-zero where verification fails, where a vector file names a topology that is not present, or
where the suite names a requirement identifier the specification does not state.

Regenerating produces a byte-identical tree unless the reference changed. A diff in a vector file
that a change was not meant to touch is what the script exists to surface.
