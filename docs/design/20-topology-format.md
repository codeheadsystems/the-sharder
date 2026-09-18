# Topology document format

A topology document is the canonical serialised form of a topology. It is the interchange format
between implementations in different languages, the input format for conformance vectors, and the
payload a topology provider delivers. The machine-readable schema is
[`topology-v1.schema.json`](topology-v1.schema.json).

The serialisation is JSON. A provider may accept another surface syntax, such as YAML, and converts
it to JSON before the sharder library validates or digests it.

Status: no implementation of the sharder library exists. The format, the schema, and the rules below
are stated for an implementation to satisfy, and the only code that reads them today is the
conformance generator under [`../../conformance/generator/`](../../conformance/generator/). The
tenant routing and cache cluster examples below are reproduced as suite topologies, which
`verify_schema.py` validates against the published schema; the storage cluster example is not.

## Document structure

A document is a JSON object whose possible members are given below, with the required ones marked.

| Member | Required | Type | Default |
|---|---|---|---|
| `formatVersion` | yes | string | |
| `topologyId` | yes | string | |
| `epoch` | yes | integer | |
| `strategy` | yes | object | |
| `nodes` | yes | array | |
| `hash` | no | object | `siphash-2-4` with a zero seed |
| `keyTransform` | no | object | `{"kind": "none"}` |
| `domainLevels` | no | array of string | `[]` |
| `replication` | no | object | factor 1, no spread |
| `overrides` | no | array | `[]` |
| `metadata` | no | object of string | `{}` |

`formatVersion` is a `major.minor` pair of decimal integers without leading zeros.

`topologyId` names a sequence of topologies over the same cluster. Epochs are ordered within one
`topologyId` and are incomparable across two.

`epoch` is a non-negative integer no greater than 9007199254740991, the largest integer every JSON
parser represents exactly. Any 64-bit value the format carries outside this range is carried as
hexadecimal text rather than as a JSON number.

`metadata` carries operator annotations. Placement never reads it, and it contributes to the
topology digest.

## Node objects

```json
{
  "id": "store-a1",
  "state": "active",
  "weight": 100,
  "domains": { "zone": "eu-west-1a", "rack": "r01" },
  "address": "10.2.0.11:7000",
  "tags": { "tier": "gold" },
  "tokens": ["3f1c0a5b9d2e4718"]
}
```

`id` is the node identity. It is unique within a document, stable across epochs, and the only node
attribute placement consumes.

`state` is the administrative state, one of `active`, `joining`, `draining`, or `leaving`. The
placement set at an epoch is the nodes whose state is `active` or `draining`. A `joining` node is
present so that data movement can be prepared before it owns anything, and a `leaving` node is
present so that cleanup can be sequenced after it has stopped owning anything. Neither appears in a
candidate ordering.

`weight` is the node's share of capacity in weight units. A node of weight 0 is in the placement set
but receives no keys from the hash strategies. Weight is advisory under `slot`, where the assignment
itself is authoritative.

`domains` holds one entry for each name in `domainLevels`, giving the node's failure domain path.

`address` and `tags` are opaque to placement. `tags` is readable by an override constraint.

`tokens` appears only under the `ring` strategy with `tokenAssignment` of `explicit`, and holds
16-character lowercase hexadecimal encodings of 64-bit token positions.

## Failure domain declaration

`domainLevels` is an ordered list of level names, coarsest first, with at most eight entries.

```json
"domainLevels": ["region", "zone", "rack"]
```

The names carry no meaning to the library beyond their order. A topology with an unusual hierarchy
declares its own names, such as `["datacentre", "hall", "row", "cabinet", "chassis"]`. A topology
with no failure domain structure declares an empty list or omits the member.

Two nodes share a failure domain at a level when their domain identifiers agree at that level and at
every coarser level of `domainLevels`. Identifiers are compared as byte sequences and are scoped to
their level, so a rack named `r01` in one zone and a rack named `r01` in another are distinct racks.

The span is `domainLevels` and never `replication.spread`. A topology declaring
`["region", "zone", "rack"]` and spreading over `["rack"]` alone still compares the whole triple, so
the two racks named `r01` above remain distinct under that spread.

## Replication settings

```json
"replication": { "factor": 3, "spread": ["zone"], "spreadPolicy": "relaxed" }
```

`factor` is the replication factor. `spread` names the levels across which replicas are placed in
distinct failure domains, given in the same relative order as `domainLevels`. `spreadPolicy` of
`relaxed` permits the documented degradation order when no placement satisfies every spread
requirement; `strict` refuses to produce a preference list shorter than `factor` under those
conditions.

An override entry carries its own `factor`, which supersedes the document-level factor for the keys
that entry matches.

## Key transforms

A key transform derives the routing key that placement and override matching consume. Its input and
output are octet sequences. Bytes given in a transform are written as two lowercase hexadecimal
digits.

```json
"keyTransform": { "kind": "none" }
"keyTransform": { "kind": "braceTag", "open": "7b", "close": "7d" }
"keyTransform": { "kind": "prefixFields", "separator": "3a", "count": 1 }
```

`none` returns the key unchanged.

`braceTag` finds the first `open` byte, then the first `close` byte strictly after it. Where both
exist and at least one byte lies between them, the routing key is the bytes between them; otherwise
the routing key is the whole key. With the default bytes `7b` and `7d`, which are `{` and `}`, the
rule matches the Redis Cluster hash tag.

`prefixFields` returns the bytes preceding the `count`-th occurrence of `separator`. Where the key
holds fewer than `count` occurrences, the routing key is the whole key. With `separator` of `3a`,
which is `:`, and `count` of 1, the key `acme:orders:99` gives the routing key `acme`.

## Strategy configuration

The `strategy` member selects one of four kinds and configures it. Placement arithmetic for each
kind is specified in [`10-specification.md`](10-specification.md); the fields are given here.

Two kinds carry a member naming their assignment, and each has its own default.

| Kind | Member | Modes | Mode where the member is absent |
|---|---|---|---|
| `ring` | `tokenAssignment` | `derived`, `explicit` | `derived` |
| `slot` | `assignment` | `explicit` | `explicit` |

A `ring` document requires `kind` alone, so an absent `tokenAssignment` names no owners and the
owners are derived. A `slot` document names its owners in `assignments`, which is the only
assignment the kind carries, so the member is present for symmetry with `ring` and takes one
value.

The four kinds differ in what the first candidate costs and in whether the rest of the ordering is
paid for with it. Under `ring`, `slot`, and `directory` a routing call reads the entries a caller
consumes and stops, so a preference list of three over a thousand nodes costs three entries and the
search that found the first. Under `rendezvous` the first candidate is the highest score over the
whole eligible node set, so every eligible node is scored before any candidate is known and the
whole ordering is paid for whatever the caller reads. `PLACE-071` states that distinction and
`PLACE-070` tabulates what each kind costs, in
[`10-specification.md`](10-specification.md#placement-cost-model).

### Ring strategy

```json
"strategy": {
  "kind": "ring",
  "tokenAssignment": "derived",
  "tokensPerWeightUnit": 4,
  "maxTokensPerNode": 4096
}
```

Under `derived` assignment, a node of weight `w` owns `min(w * tokensPerWeightUnit,
maxTokensPerNode)` tokens, each computed from the node identity and the token index. Under
`explicit` assignment, the `tokens` member of each node is authoritative, the two sizing fields are
absent, and every node in the placement set with a non-zero weight carries at least one token.

### Rendezvous strategy

```json
"strategy": {
  "kind": "rendezvous",
  "virtualNodesPerWeightUnit": 1,
  "maxVirtualNodesPerNode": 1024
}
```

A node of weight `w` contributes `min(w * virtualNodesPerWeightUnit, maxVirtualNodesPerNode)`
scoring slots, and its score for a routing key is the largest score among them.

### Slot strategy

```json
"strategy": {
  "kind": "slot",
  "slotCount": 16384,
  "assignment": "explicit",
  "assignments": [
    { "slots": ["0-5460"], "nodes": ["shard-a", "shard-b"] },
    { "slots": ["5461-10922"], "nodes": ["shard-b", "shard-c"] },
    { "slots": ["10923-16383"], "nodes": ["shard-c", "shard-a"] }
  ]
}
```

A slot identifier is the key hash reduced modulo `slotCount`. A slot range string is either a single
slot index or an inclusive `low-high` pair. Each slot appears in exactly one entry, and that entry's
`nodes` array is the candidate ordering for the slot. An authority outside the library owns the map
and republishes it as a new epoch.

### Directory strategy

```json
"strategy": {
  "kind": "directory",
  "entries": [
    { "match": { "kind": "exact", "value": "acme" }, "nodes": ["cluster-eu-1"] },
    { "match": { "kind": "prefix", "value": "gov-" }, "nodes": ["cluster-gov-1"] }
  ]
}
```

A directory is an exhaustive table. A routing key that matches no entry has no route, and the
routing call answers with no candidate. Matching follows the rules in the next section.

## Override entries

An override matches a routing key and either pins it to an explicit node list or constrains the node
set the strategy chooses from.

```json
"overrides": [
  {
    "match": { "kind": "exact", "value": "acme" },
    "pin": ["cluster-us-2", "cluster-us-1"],
    "note": "contract 8841"
  },
  {
    "match": { "kind": "prefix", "value": "eu-" },
    "constrain": { "domains": { "region": ["eu-west", "eu-central"] } },
    "factor": 2
  }
]
```

A matcher is `exact` or `prefix` over the routing key. Its `value` is interpreted under `encoding`,
which is `utf8` by default and `base16` for keys that are not text.

Matching is deterministic. An exact match takes precedence over every prefix match. Among prefix
matches, the longest matching prefix wins, measured in bytes. Where two entries would otherwise tie,
the entry at the lower array index wins, and a document containing two entries with identical
matchers is invalid.

A `pin` replaces the candidate ordering with the given node list, in the given order. Pinned keys
are exempt from the balance bound and from the minimal movement bound.

A `constrain` restricts the eligible node set before the strategy runs. Its three members intersect:
a node is eligible when its domain identifiers are among the listed values at every named level, its
tags are among the listed values at every named key, and its identity is in `nodes` where that
member is present. Keys subject to a constraint keep the balance and movement properties of the
strategy within the constrained node set.

An entry may carry both `pin` and `constrain`, in which case the pin applies and the constraint
filters the pinned list.

## Canonical form and digest

The canonical form of a document is its RFC 8785 JSON Canonicalization Scheme encoding, in UTF-8.
The topology digest is the SHA-256 of that encoding, in lowercase hexadecimal. The validation rules
refuse a duplicate member name and an unpaired surrogate before this point, so every document that
reaches canonicalisation has exactly one UTF-8 encoding.

The digest identifies document content. Two documents with the same `topologyId` and the same
`epoch` are the same topology exactly when their digests match; a mismatch is a topology authority
defect and the library refuses the later arrival.

The restriction of JSON numbers to the exactly representable integer range, and the encoding of
64-bit values as hexadecimal text, together ensure that canonicalisation never depends on
floating-point formatting.

## Versioning and compatibility

`formatVersion` is `major.minor`. An implementation declares the highest version it supports within
each major it supports.

- A reader refuses a document whose major version it does not support.
- A reader refuses a document whose minor version exceeds the minor version it supports.
- A member unknown to the reader is a validation failure rather than an ignorable extension.
- A minor version adds optional members only, and each added member has a default whose behaviour
  reproduces the preceding minor version. A reader of the newer minor therefore routes an older
  document identically to a reader of the older minor.
- A change to the arithmetic of an existing strategy, to the hash function, to the canonical form,
  or to the meaning of an existing member is a major version.
- A new strategy kind, a new key transform kind, and a new override member are minor versions.

Unknown members are refused because two implementations that disagree about whether a member affects
placement produce different routes from the same document. Extension without a version bump is
available through `metadata` and node `tags`, neither of which placement reads.

The media type of a topology document is `application/vnd.sharder.topology+json`. The conventional
file extension is `.topology.json`.

### Strategy extension

The published schema covers the four core strategy kinds. An implementation that registers a
strategy of its own validates the `strategy` member against a schema extended locally with that
kind's branch, and a topology naming a kind the implementation has not registered is invalid.
Conformance vectors use core kinds only, so a custom kind is compatible across two ports only as far
as its author makes it so.

## Validation rules

Schema validation is necessary and not sufficient. A document is valid when it satisfies the schema
and every rule below.

Document rules.

- `formatVersion` is supported by the reader.
- `topologyId` is between 1 and 128 bytes.
- `epoch` is between 0 and 9007199254740991.
- `hash.algorithm` is `siphash-2-4` and `hash.seed` is 32 lowercase hexadecimal digits.
- No member outside the schema appears anywhere in the document.
- No JSON object carries two members of the same name. A duplicate member name is invalid rather
  than resolved to the first or the last occurrence, because two parsers resolve it differently and
  the two digests then differ.
- No JSON string holds an unpaired surrogate code point, written literally or through a `\uD800`
  through `\uDFFF` escape. Such a string has no UTF-8 encoding, so the canonical form over it is
  not defined.

Node rules.

- Node identities are unique. A repeated `id` is invalid rather than deduplicated.
- Every node carries exactly one `domains` entry for each declared level, and no others.
- A node list that is empty is valid. Routing against it yields no candidate.

Failure domain rules.

- Level names in `domainLevels` are unique and the list holds at most eight entries.
- `replication.spread` names only declared levels, holds no duplicates, and lists them in the same
  relative order as `domainLevels`.

Strategy rules.

- `strategy.kind` names a strategy the reader exposes. A reader that does not expose a kind refuses
  the document under `CORE-112` rather than placing a key under another kind, and the refusal names
  the rule `unsupportedStrategy` at `strategy.kind`.
- `ring` with `tokenAssignment` of `explicit` carries no `tokensPerWeightUnit` and no
  `maxTokensPerNode`, and every placement-set node of non-zero weight carries at least one token.
  Token values are unique across the whole document.
- `ring` with `tokenAssignment` of `derived` carries no `tokens` member on any node.
- `slot` covers every slot index from 0 to `slotCount - 1` exactly once, and every slot range has
  `low` no greater than `high` and `high` below `slotCount`.
- Every node identity referenced by an assignment, a directory entry, or an override exists in
  `nodes`.

Matcher rules.

- No two matchers within `overrides` are identical, and no two matchers within a directory's
  `entries` are identical. Identity is compared over the decoded byte value and the kind.
- A `base16` matcher value has an even number of lowercase hexadecimal digits.
- A `constrain` names only declared domain levels.

A document that fails any rule is rejected whole. The library never repairs a document, never drops
an offending node, and never merges a partially valid document into the snapshot in force.

## Storage cluster example

Six storage nodes across three availability zones, three replicas spread by zone, ring placement
with derived tokens, and one node joining ahead of a later epoch that gives it ownership.

```json
{
  "formatVersion": "1.0",
  "topologyId": "objects-prod",
  "epoch": 118,
  "hash": { "algorithm": "siphash-2-4", "seed": "9e3779b97f4a7c159e3779b97f4a7c15" },
  "domainLevels": ["zone", "rack"],
  "replication": { "factor": 3, "spread": ["zone"], "spreadPolicy": "relaxed" },
  "strategy": {
    "kind": "ring",
    "tokenAssignment": "derived",
    "tokensPerWeightUnit": 4,
    "maxTokensPerNode": 4096
  },
  "nodes": [
    { "id": "store-a1", "weight": 100, "domains": { "zone": "eu-west-1a", "rack": "r01" },
      "address": "10.2.0.11:7000" },
    { "id": "store-a2", "weight": 100, "domains": { "zone": "eu-west-1a", "rack": "r02" },
      "address": "10.2.0.12:7000" },
    { "id": "store-b1", "weight": 100, "domains": { "zone": "eu-west-1b", "rack": "r11" },
      "address": "10.2.1.11:7000" },
    { "id": "store-b2", "weight": 200, "domains": { "zone": "eu-west-1b", "rack": "r12" },
      "address": "10.2.1.12:7000" },
    { "id": "store-c1", "weight": 100, "domains": { "zone": "eu-west-1c", "rack": "r21" },
      "address": "10.2.2.11:7000" },
    { "id": "store-c2", "weight": 100, "domains": { "zone": "eu-west-1c", "rack": "r22" },
      "address": "10.2.2.12:7000" },
    { "id": "store-c3", "state": "joining", "weight": 200,
      "domains": { "zone": "eu-west-1c", "rack": "r23" }, "address": "10.2.2.13:7000" }
  ],
  "metadata": { "owner": "storage-platform", "changeTicket": "CHG-20431" }
}
```

`store-b2` carries twice the weight of its peers and receives twice the tokens. `store-c3` is in the
document and out of the placement set, so the ownership delta for epoch 118 is empty and the handoff
that populates it is prepared against epoch 119.

## Tenant routing example

Four service clusters in three regions, no replication, a tenant identifier extracted from a
compound key, one tenant pinned by contract, and a residency constraint on a tenant prefix.

```json
{
  "formatVersion": "1.0",
  "topologyId": "tenant-router",
  "epoch": 42,
  "keyTransform": { "kind": "prefixFields", "separator": "3a", "count": 1 },
  "domainLevels": ["region"],
  "replication": { "factor": 1 },
  "strategy": { "kind": "rendezvous", "virtualNodesPerWeightUnit": 1 },
  "nodes": [
    { "id": "cluster-eu-1", "weight": 1, "domains": { "region": "eu-west" },
      "address": "https://eu1.internal", "tags": { "tier": "standard" } },
    { "id": "cluster-eu-2", "weight": 1, "domains": { "region": "eu-central" },
      "address": "https://eu2.internal", "tags": { "tier": "standard" } },
    { "id": "cluster-us-1", "weight": 2, "domains": { "region": "us-east" },
      "address": "https://us1.internal", "tags": { "tier": "standard" } },
    { "id": "cluster-us-2", "weight": 1, "domains": { "region": "us-east" },
      "address": "https://us2.internal", "tags": { "tier": "dedicated" } }
  ],
  "overrides": [
    { "match": { "kind": "exact", "value": "acme" }, "pin": ["cluster-us-2", "cluster-us-1"],
      "note": "contract 8841, dedicated tier" },
    { "match": { "kind": "prefix", "value": "eu-" },
      "constrain": { "domains": { "region": ["eu-west", "eu-central"] } },
      "note": "residency" }
  ]
}
```

The key `acme:orders:99` transforms to the routing key `acme`, matches the exact override, and
routes to `cluster-us-2` with `cluster-us-1` as its fallback. The key `eu-bank:ledger:7` transforms
to `eu-bank`, matches the prefix override, and is placed by rendezvous across the two European
clusters only. Every other tenant is placed by rendezvous across all four clusters, with
`cluster-us-1` receiving twice the share of the others.

## Cache cluster example

Eight cache nodes of mixed size, two replicas, no failure domain structure, and Redis-compatible
hash tags so that related keys land together.

```json
{
  "formatVersion": "1.0",
  "topologyId": "session-cache",
  "epoch": 7,
  "keyTransform": { "kind": "braceTag" },
  "replication": { "factor": 2 },
  "strategy": {
    "kind": "rendezvous",
    "virtualNodesPerWeightUnit": 8,
    "maxVirtualNodesPerNode": 512
  },
  "nodes": [
    { "id": "cache-01", "weight": 1, "address": "10.4.0.1:11211" },
    { "id": "cache-02", "weight": 1, "address": "10.4.0.2:11211" },
    { "id": "cache-03", "weight": 1, "address": "10.4.0.3:11211" },
    { "id": "cache-04", "weight": 1, "address": "10.4.0.4:11211" },
    { "id": "cache-05", "weight": 2, "address": "10.4.0.5:11211" },
    { "id": "cache-06", "weight": 2, "address": "10.4.0.6:11211" },
    { "id": "cache-07", "weight": 2, "address": "10.4.0.7:11211" },
    { "id": "cache-08", "weight": 0, "state": "draining", "address": "10.4.0.8:11211" }
  ]
}
```

The keys `session:{u-9912}:profile` and `session:{u-9912}:cart` both transform to the routing key
`u-9912` and share a preference list. `cache-08` is draining at weight 0, so it holds no keys at
epoch 7 while remaining in the document for the epoch in which it is removed.

`virtualNodesPerWeightUnit` of 8 sets the cost of a routing call as well as the granularity of the
weighting. A rendezvous call scores every eligible node at every virtual node index, so this
topology costs eighty hash evaluations per call, which is eight times the summed weight of the seven
nodes that hold keys. The same multiplier over a thousand nodes of weight 1 costs eight thousand
hash evaluations per call, and the library emits `sharder.topology.rendezvous_large` at publication
once the sum crosses `rendezvousWarnVirtualNodes`. The cost of each strategy is tabulated in
[`10-specification.md`](10-specification.md#placement-cost-model).
