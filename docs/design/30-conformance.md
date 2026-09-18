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

Status: the suite, the reference generator, and the reference driver exist and run. No
implementation of the sharder library exists, so no port has yet run the suite, and every level,
kind, and driver rule below states what a port is required to do rather than what one has done. The
only implementation the suite has run against is the reference that computed its expectations.

## Suite layout

| Path | Contents |
|---|---|
| `conformance/manifest.json` | the suite revision, the conformance levels, the strategy surfaces, every vector file with its level and coverage, and every topology digest |
| `conformance/coverage.json` | requirement coverage, computed from the specification |
| `conformance/topologies/` | the topology documents the suite routes against |
| `conformance/topologies/invalid/` | documents that fail a load-time rule |
| `conformance/vectors/` | the golden vectors |
| `conformance/properties/properties.json` | the property definitions |
| `conformance/scenarios/` | the simulation scenarios |
| `conformance/generator/` | the reference implementation and the generator scripts |
| `conformance/declarations/` | one conformance declaration per port, checked against the manifest |

A topology document is stored once under `conformance/topologies/` and referenced by path from the
vector files that use it. A vector file at the `place` level carries a copy of every document it
names, because a port at that level has no document pipeline to read one with; `build_manifest.py`
refuses a copy that differs from the document it was taken from, so the two cannot drift.

## Vector file format

A vector file is a JSON object holding one **vector set**: a group of cases sharing a purpose, a
topology, and a requirement list. Each entry of `cases` is one **case**, which is one input and the
output the reference computed for it.

```json
{
  "vectorSet": "ring-derived-walk",
  "kind": "routing",
  "level": "place",
  "description": "The ring walk under derived tokens.",
  "requirements": ["RING-020", "RING-021"],
  "topology": "topologies/ring-plain.topology.json",
  "topologyDigest": "…",
  "topologyDocuments": { "topologies/ring-plain.topology.json": {…} },
  "cases": [ { "name": "…", "requirements": ["RING-020"], "key": {…}, "expect": {…} } ]
}
```

`kind` selects what a driver does with the cases. The kinds are fixed, and a driver that meets an
unknown kind fails rather than skipping the file.

`level` names the conformance level the file belongs to, one of the levels the `levels` table of
`manifest.json` states. A driver that meets an unknown level fails, as it does for an unknown kind.

`topologyDocuments` maps each document path the file names to the document itself. It appears on a
file at the `place` level and on no other, and the Placement before a document pipeline section
below states what a port does with it.

| `kind` | Input of a case | Output asserted |
|---|---|---|
| `siphash` | a key and a message | the 64-bit SipHash-2-4 output |
| `hash` | a domain tag and framed fields | the framed octets and the 64-bit result |
| `keyTransform` | a key | the routing key |
| `digest` | a document | the canonical form, its length, and the digest |
| `validation` | a document | validity, the rules broken, and the errors |
| `routing` | a key | the whole routing decision, the preference list behind it, or the condition raised |
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
| `scale` | a topology of a thousand nodes | the placement total, the shard cardinality, and a prefix of each ordering |
| `observabilityInventory` | none | the metric and event names, labels, severities, and payload members |
| `publicationEvents` | a document | the events one publication of it emits |

`topologyDigest` carries the SHA-256 of the RFC 8785 canonical form of the referenced document. A
driver that checks it before running the cases separates a canonicalisation defect from a placement
defect, which are otherwise hard to tell apart from a failing ordering. On a file at `place` it
names the document rather than asking for a digest: a port that reaches `core` computes and checks
it, and a port at `place` alone has no canonical form to check it with.

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
  "materialisedEntries": 2,
  "relaxedLevels": [],
  "spreadStage": 0,
  "shortfall": "none",
  "matchedOverride": { "index": 0, "mode": "pin" }
}
```

```json
"expectError": { "code": 101, "name": "noCandidate", "cause": "constraintExcludedAll" }
```

`preferenceList` is the whole list of `REPL-014`, which `CORE-047` answers on demand, and
`materialisedEntries` is the count of entries `CORE-046` bounds the decision's own `entries` at. A
port compares `preferenceList` against the accessor and `materialisedEntries` against the length of
the decision's `entries`, so a port that materialises the whole ordering on every call fails the
second while passing the first.

`spreadStage` is the relaxation stage `SPREAD-012` selects. It is not a member of `RoutingDecision`,
which reports the relaxed levels rather than the stage index, and it is carried in the vector
because a port that computes the right levels by the wrong ladder is a defect worth naming.

A `cause` of `null` marks a condition that defines no closed cause set, such as
`identityMismatch`. Every `noCandidate` case carries a cause, because `ERR-021` names one for each
case of `ERR-020`.

## Driver contract

A **driver** is the per-language harness that reads the suite and runs it. The suite is designed so
that a driver needs no parser beyond a JSON reader, and a driver that starts at the `hash` and
`routing` kinds alone reaches the rest by adding a branch per kind.

1. Read `manifest.json`, note the `revision` a declaration names, and fail where any file it lists
   is absent.
2. Read its `levels` table, and fail where a vector file or a scenario names a level the table does
   not list.
3. Read its `strategySurfaces` list, and skip a vector file whose `strategies` name a surface the
   port does not expose, and a case whose own documents name one.
4. For each vector file at a declared level, dispatch on `kind`.
5. For a file carrying `topologyDocuments`, prepare placement over the document it carries. For a
   file naming a topology and carrying none, load that document, validate it, install it, and
   assert the digest against `topologyDigest`.
6. For each case, decode the key by its `encoding`, run the call the kind names, and compare the
   result to `expect` field by field.
7. Report a failure by vector set, case name, field, and the requirement identifiers the case
   names, and report the vector files and cases run at each level.

A driver compares only the fields a case carries. A case that omits `relaxedLevels` makes no claim
about it. Every `routing` case the generator writes carries every field of the decision. A case at
the `scale` level carries a prefix of each ordering instead of the whole of it, and the Placement at
a thousand nodes section below states why.

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
full, together with the levels that set rests on. A port declares the levels it reaches, not a
percentage.

The levels partition the suite. A vector file names its level in its `level` member, a scenario
names one in `scenarios/index.json`, a property names one in `properties/properties.json`, and no
artefact carries two. The `levels` table of `manifest.json` carries the structure below, so a
harness reads a level rather than inferring one from a file's path or its `kind`.

Each level tests one conformance surface of the specification, named in the Conformance surfaces
section of [`10-specification.md`](10-specification.md#conformance-surfaces), and every requirement
identifier the level's vector files, properties, and scenarios name belongs to that surface. Four
levels test `routing` between them, because that surface is large and a port reaches it in stages.

The rows of `OBS-010` and `OBS-020` are the one exception the specification states: a metric and an
event belong to the surface the first segment of its name gives, whatever surface the requirement
that tables them belongs to. A file carrying the rows of one surface therefore names `OBS-010` and
`OBS-020` at the level that tests that surface, which is where the observability inventories sit.

| Level | Requires | Surface | Contents | A port needs it when |
|---|---|---|---|---|
| `hash` | | `routing` | SipHash-2-4 and the framed domain-tagged construction | always; every other level rests on it |
| `place` | `hash` | `routing` | the key transforms, the four placement strategies, overrides, replication and spread, shard enumeration, movement, the tie-breaks, the identity comparator, the virtual node count, and the attempt limit a decision resolves under `CORE-048`, each over a topology the file carries already valid | always |
| `core` | `place` | `routing` | the canonical form and the digest, document validation, the error taxonomy, the ownership delta, skew detection, the observability inventories and publication events, the placement properties, and the rollback scenario | always |
| `scale` | `core` | `routing` | the same placement function over two documents of a thousand nodes, each crossing the threshold `PLACE-073` compares its total against | always |
| `failover` | `core` | `failover` | the health arithmetic, the retry budget, the attempt walk and the clamp `FAIL-022` applies to it, and the health scenarios | the port exposes `attempts` or a health view |
| `readAffinity` | `core` | `readAffinity` | `routeForRead` and the bounded reordering of the replica prefix | the port exposes `routeForRead` |
| `fencing` | `failover` | `fencing` | the fencing token encoding, the order in which a recipient reports a condition, the recipient scenarios, and the redirect walk | the port exposes the recipient check |
| `migration` | `failover` | `migration` | the rate control formulas, the refused plan under a strategy that enumerates no shard, and the handoff scenarios | the port exposes the handoff coordinator |

A declared level carries the levels it requires, transitively, so a port at `fencing` runs `hash`,
`place`, `core`, `failover`, and `fencing`. `fencing` requires `failover` because `FENCE-231` bounds
the redirect walk by the retry budget of `FAIL-031`, and `migration` requires it because a
coordinator observes the health state of a destination that stops answering. `scale` requires
`core` and nothing above it: a port reaches it with the document pipeline and the placement engine,
and the health view, the recipient check, and the coordinator reach no document of that size.

The size of each level is computed rather than stated here. The `levels` table of
[`../../conformance/manifest.json`](../../conformance/manifest.json) carries one row per level, and
each row holds the artefact counts in `vectorFiles`, `vectorCases`, `scenarios`, and `properties`,
and the number of requirement identifiers the level's own artefacts name in `requirementsNamed`.

[`../../conformance/coverage.json`](../../conformance/coverage.json) lists those identifiers under
`byLevel`. Each row there holds `requirementsAtLevel`, which is the level's own count, and
`requirementsWhenDeclared`, which is the larger count a declaration of that level reaches once the
levels it requires are added. `run_suite.py` prints both the file and the case count per level as it
runs, against the totals the manifest holds.

`hash`, `place`, `core`, and `scale` are not optional. Each tests part of the `routing` surface,
which `CORE-110` requires every implementation to expose, and that surface is exactly the set of
decisions two callers in two languages have to agree on. A port that declines any of the four is
not a port of the sharder library. The four optional levels each test a surface `CORE-110` leaves
to the implementation: an integrator who routes a tenant identifier to one of five clusters never
constructs a handoff coordinator, and a port serving that integrator carries no coordinator to
test.

A level is reached when every case in it that the port's strategy surfaces admit passes. There is no
partial credit, and the suite reports no score. A port at `core` and `failover` that fails one
migration scenario declares `core` and `failover`, and says that `migration` is not implemented
rather than that it is ninety per cent implemented.

### Placement before a document pipeline

`place` is the level a port reaches before it can read a topology document. A file at `place`
carries every document it names in `topologyDocuments`, already validated. A port prepares
placement over it, which is stage 6 of `TOPO-001`, and performs no stage 1 through 5: it needs no
strict JSON reader for the document format, no schema validation, no semantic validation, no
RFC 8785 canonical form, no SHA-256, no provider, and no snapshot lifecycle. What it needs is a JSON
reader for the vector file, the hash construction of `hash`, and the placement engine.

A file at `place` carries `topologyDigest` as provenance, so that the document it carries can be
matched against the copy under `topologies/`. Step 5 of the driver contract asserts that member only
for a file naming a topology and carrying none, so a run confined to `place` compares no digest.
[`../../conformance/driver/python/run_suite.py`](../../conformance/driver/python/run_suite.py)
runs `--level place` without entering its canonicaliser or its SHA-256.

`core` is what the document pipeline adds. A port reaches `place` with the placement engine alone
and reaches `core` once a document becomes a snapshot, which is the order the work is done in.
[`adr/0059`](adr/0059-place-conformance-level.md) records the partition and what moved into it.

### Placement at a thousand nodes

`scale` runs the placement function of `place` and the document pipeline of `core` over two
topologies a maintainer cannot check by hand. `scale-ring-1000` carries a thousand nodes under
`ring` with derived tokens, and some of them ask for more tokens than `maxTokensPerNode` grants.
`scale-rendezvous-1000` carries the same thousand nodes under `rendezvous`. Each is configured so
that the total `PLACE-073` measures is above the threshold `CFG-010` sets for it, and each case
states that total, the setting it was compared against, and the shard cardinality, in the `total`,
`totalSetting`, and `shardCount` members of its expectation. The ring document measures what
`PLACE-070` charges for preparation and the rendezvous document what it charges for one routing
call, which are the two halves of the cost model that rise with the node count rather than with
what a document spells.

The level asserts no timing and no resident size. A wall time is a property of a machine, a
language, and a runtime rather than of an answer, so a data file carries none. What the level
carries is the ordinary exact expectations of every other level, over inputs large enough that a
port which built the wrong structure arrives at them slowly. A port that materialises a candidate
ordering on every routing call, stores the ring as a list it scans, or rebuilds the ring per call,
finishes `scale` in a time its author notices.

A `scale` case asserts a prefix of each ordering rather than the whole of it. It carries the first
eight entries of the candidate ordering, the first eight of the preference list, the shard
cardinality, and the decision's own fields, of which `materialisedEntries` is the one `CORE-046`
bounds. The candidate ordering under either document is a thousand entries long, `CORE-047` answers
the whole of it on demand, and `PLACE-015` makes the prefix a caller consumes the cost a routing
call pays under `ring`, `slot`, and `directory`, so a case asserting the thousand would ask for the
materialisation this level exists to discourage.

The suite carries a scale document for `ring` and for `rendezvous` and not for `slot` or
`directory`. Under those two the figures of `PLACE-070` are the `slotCount` and the `entryCount`
the document spells, so a large one is a large file rather than a large computation, and
`directoryWarnEntries` under `CFG-010` already reports the shape. A port exposing neither `ring`
nor `rendezvous` therefore runs no file at `scale`, which is the strategy surface rule of the
section below applied unchanged.

[`adr/0077`](adr/0077-scale-conformance-level.md) records the level, what it carries, and what it
declines to assert.

### Strategy surface selection

`CORE-110` makes the four placement strategies selectable surfaces, and the suite selects on them
along an axis of its own rather than by adding levels. `manifest.json` lists the surfaces in
`strategySurfaces`. Each vector file carries the surfaces the documents it names outside its cases
carry, in `strategies`, and the surfaces its cases name for themselves, in `caseStrategies`. Both
are derived from the documents by `build_manifest.py` rather than authored.

A port runs a file when it exposes every surface in the file's `strategies`, and within that file
the cases whose own documents name surfaces it exposes. A file naming no document, and a case naming
none, is run by every port. A file whose cases name documents of four kinds, such as the canonical
form vectors, is therefore run by a port exposing one kind, for the cases naming that kind.

A port exposing a subset of the four kinds therefore runs a subset of the cases a level reaches. The
cases it does not run place keys under strategies it never configures and refuses a document that
names one, under `CORE-112`. `run_suite.py --level core --strategy rendezvous,directory` reports the
subset for that port against the totals the manifest holds.

## Declaring conformance

A port declares conformance by publishing seven things.

1. The suite revision it ran, named by the `id` of the `revision` object in the `manifest.json` it
   ran.
2. Every level the `levels` table of that manifest lists, each marked as reached or excluded.
3. For an excluded level, the conformance surface the port does not expose.
4. The placement strategy surfaces it exposes, of which `CORE-110` requires at least one.
5. The output of its driver, showing the case count per vector set and zero failures at every
   reached level.
6. The wall time and the peak resident size its driver observed at the `scale` level, with the
   machine and the runtime they were observed on.
7. Its deviations, each naming the requirement deviated from, the reason, and the cases the
   deviation shows up in.

A port publishes the seven in `conformance/declarations/<port>.json`.
[`35-port-conventions.md`](35-port-conventions.md#conformance-declaration) states the member set,
and `conformance/generator/verify_declarations.py` checks a declaration against `manifest.json` and
[`10-specification.md`](10-specification.md), failing on a claim either one contradicts and
reporting a declaration that names an earlier revision as lagging.
[`adr/0080`](adr/0080-conformance-declaration-format.md) records the format and what the check does
not establish.

The sixth is a reported figure rather than an asserted one. The suite states no bound for it, no
level is reached or missed by it, and a port whose figure is large has passed `scale` exactly as one
whose figure is small has. It is comparable against the same port on another machine and against
nothing else, and a routing call that materialises a thousand entries appears in it and in nothing
else the suite reports.
[`../../conformance/driver/python/run_suite.py`](../../conformance/driver/python/run_suite.py)
prints both figures after the level table.

A declaration names every level of the suite revision, so a level a port passed over is a claim it
made rather than an omission. A port that reaches a level whose required levels it does not reach
declares neither.

The revision is a digest of the suite's own content rather than a number a maintainer bumps: it
changes when any file a port runs changes, when the level structure changes, and when the strategy
surfaces change. [`adr/0061`](adr/0061-suite-revision-identifier.md) states what it covers and what
it leaves out.

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
Cluster slot count and a slot count that is not a power of two. The directory sets cover the
reachable clauses of matcher precedence and the no-match result.

`vectors/spread/relaxation-stages.json` records every relaxation stage of `SPREAD-010` for a key,
the entries each admits, and the stage the builder chooses, which makes the ladder's behaviour data
rather than an assertion about it. `distinctStageOutcomes` counts the rungs that differ, and
`SPREAD-018` makes it `m + 1` where the topology carries a distinct domain at each named level.

`vectors/spread/skipped-level.json` fixes the scope of a domain path. Its topology declares three
levels, spreads over the finest one alone, and reuses rack identifiers across zones and regions, so
some of its cases place two replicas carrying one rack identifier under distinct rack paths. A port
that read `SPREAD-006` as spanning `replication.spread` rather than `domainLevels` cannot produce
them. `vectors/read/affinity.json` fixes the same scope for `READ-013` with cases at a level whose
coarser level `replication.spread` does not name, and carries the refusals of `READ-011` and
`READ-017` so that a port answering a mismatched path rather than refusing it fails there.

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
| every key transform edge case | the `keytransform` vector sets |

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

The file holds a ring token collision and an equal maximum score under `rendezvous`, which are the
two orderings a score ties in. `conformance/generator/README.md` says how each collision was
searched for.

A third tie-break case, equal-length competing prefixes in an override table, cannot exist in a
valid document, because two entries surviving the first two clauses of `PLACE-065` carry identical
matchers and `PLACE-067` refuses them. The case is covered instead by the validation vectors that
refuse such a document at load.

### Observability vectors

`OBS-010` names every metric with its label set and `OBS-020` names every event with its severity
and the payload members it carries beyond the common members of `OBS-021`. Both are cross-language
string contracts, and the withdrawal register of [`10-specification.md`](10-specification.md) makes
each name permanent, so a port that emits nothing, spells a name differently, or drops a payload
member has broken a published contract. The suite carries them as data.

`vectors/observability/inventory-*.json` holds one file per surface that owns rows of those two
tables: `routing` at `core`, `failover` at `failover`, `fencing` at `fencing`, and `migration` at
`migration`. A metric and an event belong to the surface the first segment of its name gives, which
the Conformance surfaces section of the specification states, so a port runs the inventory of each
surface it exposes and no other. Each file carries a `metric-inventory` case with
the names, the instruments, the label sets, and the closed label vocabularies `OBS-011` states for
that surface, and an `event-inventory` case with the names, the severities, and the payload members.
An event row carries a `deduplication` member for the four events `OBS-024` names and for no other,
because the requirement says nothing about the rest. The `routing` file carries a third case for the
common members `OBS-021` states.

The inventories are read out of the specification's own tables by `generate_observability.py` rather
than transcribed beside it, and the script refuses a table it cannot parse rather than writing a
shorter inventory. A metric added to `OBS-010` therefore reaches the suite at the next regeneration,
and a renamed one fails a port rather than going unnoticed.

`vectors/observability/publication-events.json` asserts the events one publication of a document
emits. Every event it covers is decided before stage 6 of `TOPO-001` under `PLACE-077`, or from a
scan of the accepted document, so a case asserts a large topology's events without preparing its
placement: the two totals of `PLACE-073`, the clamp of `PLACE-052`, the infeasible level of
`SPREAD-024`, and the seed evidence of `SEC-011`. One case carries an empty list, which is the
assertion that a port warning about an ordinary topology fails.

A port compares the inventory against its own registry and its own sink. A driver holds neither, so
[`../../conformance/driver/python/run_suite.py`](../../conformance/driver/python/run_suite.py)
checks the inventory's internal consistency and its surface assignment, as it does for the closed
condition set of `errorTaxonomy`. The events a running library emits are asserted where the suite
already drives one: the health scenarios carry `sharder.health.ejection_refused` in their
expectations. [`adr/0078`](adr/0078-observability-contract-as-data.md) records what is asserted and
what is not.

## Properties

A property is a claim over a sample rather than over one key.
[`../../conformance/properties/properties.json`](../../conformance/properties/properties.json)
states each one as data: its identifier, its name, the requirements it proves, its conformance
level, its statement, the quantifier it ranges over, the sample it draws, and its check.

Every bound and every sample size is the specification's own. `PROP-015`, `PROP-020`, and
`PROP-021` state integer inequalities with preconditions on the sample size, and the property file
carries those inequalities and those preconditions unchanged.

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

| Identifier | Level | Proves | Witness |
|---|---|---|---|
| `P-DETERMINISM-001` | `core` | `PROP-001`, `PROP-002`, `PROP-003`, `PLACE-010`, `PLACE-012` | every routing vector file |
| `P-DETERMINISM-002` | `core` | `PROP-004`, `PLACE-015`, `REPL-015` | none |
| `P-DETERMINISM-003` | `core` | `PROP-005`, `CORE-002`, `RING-013`, `RV-013` | yes |
| `P-PURITY-001` | `failover` | `PROP-040`, `PROP-041`, `PROP-045`, `FAIL-001`, `FAIL-011`, `REPL-016` | a scenario |
| `P-PURITY-002` | `core` | `PROP-042`, `PROP-043`, `PROP-044`, `PLACE-011`, `CORE-004` | none |
| `P-MOVEMENT-001` | `core` | `PROP-010`, `PROP-011` | yes |
| `P-MOVEMENT-002` | `core` | `PROP-006`, `PROP-013`, `PROP-015` | yes |
| `P-MOVEMENT-003` | `core` | `PROP-014`, `PROP-015` | yes |
| `P-MOVEMENT-004` | `core` | `PROP-012` | yes |
| `P-MOVEMENT-005` | `core` | `PROP-019` | none |
| `P-MOVEMENT-006` | `core` | `PROP-016`, `PROP-017`, `PROP-018`, `SLOT-004` | yes |
| `P-BALANCE-001` | `core` | `PROP-006`, `PROP-020`, `PROP-030`, `PROP-031`, `PROP-032` | yes |
| `P-BALANCE-002` | `core` | `PROP-021` | yes |
| `P-BALANCE-005` | `core` | `PROP-017`, `PROP-025`, `PLACE-044` | yes |
| `P-REPLICA-001` | `core` | `REPL-011`, `REPL-020`, `SPREAD-002`, `PLACE-013` | yes |
| `P-SPREAD-001` | `core` | `SPREAD-001`, `SPREAD-010`, `SPREAD-011`, `SPREAD-018`, `SPREAD-020` | yes |
| `P-SPREAD-002` | `core` | `SPREAD-012`, `SPREAD-013`, `SPREAD-015`, `SPREAD-017` | yes |
| `P-SPREAD-003` | `core` | `SPREAD-014`, `REPL-020`, `REPL-021` | yes |
| `P-PREFERENCE-001` | `core` | `REPL-012`, `REPL-013`, `REPL-014`, `REPL-017`, `SPREAD-021` | yes |
| `P-EXEMPT-001` | `core` | `PROP-050`, `PROP-052`, `OVR-010`, `OVR-013` | yes |
| `P-EXEMPT-002` | `core` | `PROP-051`, `OVR-020`, `OVR-032` | none |
| `P-EPOCH-001` | `core` | `TOPO-051`, `TOPO-061`, `TOPO-071`, `TOPO-081`, `TOPO-091` | a scenario |
| `P-EPOCH-002` | `core` | `TOPO-061`, `ERR-031`, `ERR-034` | a scenario |
| `P-HANDOFF-001` | `migration` | `MOVE-151`, `MOVE-161`, `MOVE-171` | a scenario |
| `P-HANDOFF-002` | `migration` | `MOVE-201`, `MOVE-211`, `MOVE-221`, `MOVE-231` | a scenario |
| `P-HANDOFF-003` | `migration` | `MOVE-011`, `MOVE-021`, `MOVE-031`, `MOVE-441`, `MOVE-491` | a scenario |
| `P-FENCE-001` | `fencing` | `FENCE-101`, `FENCE-071`, `FENCE-081`, `FENCE-091` | a scenario |
| `P-ATTEMPT-001` | `failover` | `FAIL-002`, `FAIL-003`, `FAIL-004`, `FAIL-012`, `FAIL-014` | a scenario |

## Simulation scenarios

A scenario is a sequence of actions a port replays, each carrying the result the reference computed.
Scenarios are deterministic: every clock reading is an explicit instant in the file, every health
signal is an explicit action, and no step draws a random value.

A step names an `action`, its arguments, and an `expect` object. A driver dispatches on `action` and
compares the result field by field, as it does for a vector case.

| Scenario | Level | Covers |
|---|---|---|
| `topology-rollback` | `core` | a reverted assignment arriving as a higher epoch, an equal epoch with a differing digest, and a foreign identifier |
| `caller-three-epochs-stale` | `fencing` | every recipient relation, both policies, a retained and an unretained token epoch, an unfenced request at an owner and at a non-owner, and a sender ahead |
| `split-topology-view` | `fencing` | half the cluster on one epoch and half on another, disagreeing about a replica set |
| `redirect-walk-depth-limit` | `fencing` | the redirect bound and a redirect naming an already attempted node |
| `handoff-happy-path` | `migration` | the ownership delta and every state of `MOVE-021` |
| `quiesce-lease-expiry` | `migration` | a commit horizon the lease no longer covers, and a lease too short to carry one |
| `node-dies-mid-migration` | `migration` | a destination that stops answering during `transferring` |
| `abort-during-catching-up` | `migration` | an abort in `catchingUp`, and a second abort |
| `coordinator-death-and-recovery` | `migration` | death in each non-terminal state, against each observation |
| `handoff-failure-kinds` | `migration` | each of the four kinds of `MOVE-011` and the terminal state rule |
| `plan-superseded-by-new-epoch` | `migration` | a third epoch arriving with three handoffs in flight, comparable and not |
| `rebalance-survives-unrelated-epoch` | `migration` | a node joining mid-rebalance, the rebase-pending interlock, and a rebase |
| `rebase-drops-a-handoff` | `migration` | an epoch reversing one move of two, and the three rebase refusals |
| `undetermined-resolves-both-ways` | `migration` | a cutover outcome the library never established, resolved later each way |
| `migration-rate-control` | `migration` | the concurrency bounds and both backpressure levels |
| `failover-and-recovery` | `failover` | ejection, probation admission, and return to `available` |
| `health-filter-fails-open` | `failover` | every replica ejected, and the filter returning the whole list |
| `ejection-ceiling` | `failover` | the ceiling refusing an ejection that would empty the attemptable set |
| `probation-ramp` | `failover` | a replica set in `probation`, the filter holding it, and one attempt in `probationDivisor` |
| `outlier-comparison-set` | `failover` | signals for identities outside the placement set, which are peers of nothing |
| `health-reset-on-reentry` | `failover` | a node leaving the placement set and returning, under both values of `resetOnPlacementReentry` |

The handoff scenarios drive a coordinator whose transition table is `MOVE-021` transcribed as data
and whose recovery mapping is `MOVE-211` transcribed as data, so a state sequence in a scenario file
is the table's output rather than an author's reading of it. The rebase classification of
`MOVE-096` reads the replica sets the reference computes from the two snapshots, so a handoff that
rebases in a scenario file rebases because the placement says so.

## Requirement coverage

[`../../conformance/coverage.json`](../../conformance/coverage.json) holds the coverage figures. It
is computed by extracting every requirement identifier from
[`10-specification.md`](10-specification.md) and comparing that set against the identifiers the
suite names. `statedRequirements`, `coveredRequirements`, and `uncoveredRequirements` are the
totals, and `coveragePercent` is their ratio.

The comparison is reported twice over: once by requirement prefix under `byPrefix`, which lists the
uncovered identifiers of each prefix by name, and once by conformance level under `byLevel`, so a
declared level names the requirements it proves. `coveredBy` maps each covered identifier to the
files that cover it, which is the join a maintainer asking what tests one requirement reads.

`coverage.py` writes the file, and `run.sh` prints the totals at the end of a generation run. A
requirement added to the specification and named by nothing shows up there as uncovered rather than
going unnoticed.

The section below names what the uncovered requirements are, group by group, and why no data file
carries them.

## Requirements without an executable test

A requirement is not covered by an executable test when no language-neutral data file can carry its
output. Naming those is part of the suite, because a coverage figure that counts them as covered
tells a maintainer nothing.

### Hash construction

`vectors/hash/` names most of the `HASH-*` requirements. The four it does not are `HASH-012`, which
fixes the key for the lifetime of a snapshot, `HASH-041`, which fixes the integer widths,
`HASH-042`, which makes the slot remainder the only division in placement, and `HASH-043`, which
forbids a floating-point value in hash arithmetic. Each constrains how a value is produced
rather than what it is. A port that departs from one of them produces a different value wherever the
departure reaches a computation, and fails a hash vector or a routing vector there rather than a
case that names the requirement.

### Surface exposure

`CORE-110` through `CORE-113` state which surfaces an implementation exposes, that a surface is
exposed whole, that a document naming a strategy an implementation does not expose is refused, and
that the surfaces exposed change no value a routing call computes. No suite artefact can carry
their outputs, because the reference exposes every surface and a suite generated from it never
reaches the refusal of `CORE-112`. A port witnesses them in its own tests, with a document naming a
strategy it declines, and the suite witnesses `CORE-113` indirectly: a port exposing `rendezvous`
and `directory` computes the same orderings on the cases it runs as a port exposing all four.

### Concurrency and visibility

`CORE-050` through `CORE-065` and `TOPO-101` through `TOPO-141` constrain memory visibility, lock
placement, and thread ownership. A vector carries an output, and these requirements constrain how an
output is produced rather than what it is. They are reviewed per binding and, where a language
offers one, exercised by a race detector rather than by the suite. `TOPO-121`, which requires a
routing call to read the snapshot reference once at entry, is among them: a single-threaded driver
observes the same answer whether the reference is read once or twice.

### Provider behaviour

`CORE-080` through `CORE-101` state the provider contract, its two adaptation models, its
conditional fetch, and its failure behaviour. The polling and reconciliation settings of `CFG-010`,
the backoff of `CORE-100` and `ERR-033`, and the executor rule of `CFG-012` describe interaction
with a provider over time. A provider is an interface an integrator implements and a vector carries
no interface, so the suite carries the documents and the acceptance outcomes, which is the part that
is agreed between callers; the shape and the timing are local to one caller and are tested per
binding. The source version of `CORE-084` is opaque by construction: its octets are a provider's
own, two conforming providers choose different ones for the same document, and a case asserting one
would assert a provider's internals rather than a library's output.

Stage 2 of `TOPO-001`, schema validation, is covered: each case of `validation-documents` carries
a `stage` of `schema` or `semantic`, and `conformance/generator/verify_schema.py` runs the
published schema over every document the suite ships. Stage 1, decoding the octets as JSON, is the
JSON reader's own behaviour and carries no vector.

### Placement cost

`PLACE-070` through `PLACE-077` state the cost of each strategy, the totals above which a warning
event is emitted, the integer width a total is computed in, the cost of one ownership delta, the
multiple a relaxation ladder applies to a walk, and the point in the load pipeline at which a total
is computed. `CFG-014` carries the two thresholds.

A total is an output and is covered. The two scale documents cross the thresholds of `PLACE-073`,
their totals are asserted at the `scale` level and the events they emit at `core`, and `PLACE-077`
is what makes the second possible without preparing the first: the total is a sum over the node
weights, available before a token is derived. `PLACE-074` is covered by the same totals, which are
above what a signed 32-bit accumulator holds under the cap `PLACE-050` applies.

A cost is not an output, and `PLACE-075` and `PLACE-076` stay uncovered. Each states a bound to
within a constant factor rather than a value, and only a profiler separates a library that met one
from a library that did not. `TOPO-212`, which keeps the ownership delta off the installation path,
is uncovered for the same reason: a driver that installs a snapshot and then asks for a delta reads
the same answer whether the library computed it eagerly or on the call. The `scale` level is what
the suite offers in place of a bound, and the Placement at a thousand nodes section above states
what it does and does not assert.

`SPREAD-023` permits a stage the domain count of `SPREAD-022` rules out to go unevaluated, and it is
covered rather than uncovered: the stage it removes is one `SPREAD-012` cannot choose, so a port
that skips a stage it should have evaluated produces a preference list the spread vectors already
pin. `vectors/spread/all-nodes-one-domain-relaxed.json` carries a topology whose every node sits in
one failure domain, which is the case the permission exists for, and
`vectors/spread/relaxation-stages.json` records each stage's `reachesFactor` separately.

### Observability

`OBS-001` through `OBS-025` name metrics, labels, and events. The names, the label sets, the
severities, and the payload members are covered by the inventories the Observability vectors
section above describes, because a name is a string a data file carries and a port that spells one
differently has broken the contract. What stays uncovered is the value behind a name. A metric
value is permitted to be a floating-point number under `OBS-002` and is explicitly forbidden from
reaching any decision, so asserting one adds no cross-language guarantee, and `OBS-012` and
`OBS-013` state what two of the gauges hold. `OBS-004`, `OBS-023`, and `OBS-026` govern delivery to
a registry and a sink the integrator supplies, which no data file holds. `OBS-005` states that
recording a metric changes nothing, which a vector cannot witness the absence of.

The suite covers the arithmetic that feeds the skew detectors, `OBS-031` through `OBS-033`, because
those are integer comparisons that change what is reported. The explain record of `OBS-040` through
`OBS-048` is covered only through `OBS-044`, which requires it to agree with `route`, and a port
checks that against its own routing vectors. `OBS-036` is the shape of a measurement source the
integrator supplies, so nothing the suite generates carries one. `OBS-008` bounds the node label by
`nodeLabelLimit`, and the cardinality a metric carries is a property of a running process rather
than of a routing decision.

The events a running library emits are covered where the suite drives one and uncovered elsewhere.
The publication events of `PLACE-073`, `PLACE-052`, `SPREAD-024`, and `SEC-011` are asserted by
`vectors/observability/publication-events.json`, and the health scenarios assert
`sharder.health.ejection_refused` in their expectations. The rest are emitted at points the
reference does not reach: it installs no snapshot over time, holds no provider, follows no redirect,
and drives no coordinator through a sink, so no artefact carries `sharder.topology.installed`,
`sharder.routing.shortfall`, `sharder.fencing.refused`, or the `migration.` events. A port witnesses
those against its own sink, and the inventory is what fixes the names and the payloads it witnesses
them by.

### Rate control and measurement

`RATE-121` through `RATE-141` say what an implementation measures and what it refuses to infer.
They are negative requirements about sources of information, and a data file cannot witness the
absence of a wall-clock read. `RATE-021`, `RATE-051`, `RATE-071`, `RATE-081`, `RATE-091`, and
`RATE-101` are covered, because each is an integer rule with an output.

### Movement hooks

`MOVE-111` through `MOVE-141` define the hook interface and the rule that the library interprets
none of `budgetUnit`, `unitsMoved`, `bulkRemaining`, `residue`, or the opaque member of a cutover
record. `MOVE-281` through `MOVE-391` describe the concurrent-holding window, which is a property of
the integrator's storage rather than of the library. The suite covers the sequencing rules around
them, `MOVE-151` through `MOVE-238`, because a state sequence is an output. `MOVE-091` through
`MOVE-103` are the same case: a rebase is a classification of each handoff against a snapshot, and
both the classification and the state it leaves are outputs.

### Configuration and security

`CFG-001` through `CFG-007`, `CFG-060` through `CFG-062`, and `CFG-064` govern how settings are
accepted, validated, and exposed. `CFG-004` is the load-bearing one and is covered by construction:
no vector carries a setting, so a port whose settings change a candidate ordering fails every
routing vector under one of its configurations. What the library declines to defend and what it
discloses are stated by `SEC-001` through `SEC-004`, `SEC-014`, and `SEC-020` through `SEC-034`.
The non-default seed vectors cover `SEC-010` and `SEC-012`, and the key hash collision
covers `SEC-001`.

## Specification defects and their repairs

Building the suite against [`10-specification.md`](10-specification.md) surfaced the defects below.
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
| the range balance bound was untestable | withdrawn with the `range` strategy | none |
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

Every requirement identifier the specification states is introduced as a backticked identifier
followed by a full stop at the start of a line, with no duplicate. `coverage.py` extracts them and
`run.sh` fails where the suite names one the specification does not state.

The ladder repair changes behaviour and therefore changes vectors.
`vectors/spread/relaxation-stages.json` records a higher `distinctStageOutcomes` on the
`spread-ladder` topology under the repaired ladder, and `vectors/spread/degradation-ladder.json`
moves with it. The suite carries one topology whose `replication.spread` names more than one level,
so a port that implements the old ladder fails those two files and passes the rest.

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
