# 0010. Shard identifier naming

Status: accepted. Date: 2026-09-16.

## Context

The placement interface exposes `shardOf`, `shards`, and `candidatesForShard`. Every one of the
three carries a shard identifier, and the handoff coordinator names a migration by it. The topology
document supplies an identifier for one strategy only: `range` carries an authored `shardId`. The
other four kinds name their shards implicitly or not at all, so two ports would otherwise invent two
naming schemes and produce ownership deltas that could not be compared.

A shard identifier has to be unique within a topology, stable across epochs that do not change the
shard, and derivable from the document without consulting an authority.

## Decision

Each core strategy names its shards as follows.

| Kind | Shard identifier | Enumeration order |
|---|---|---|
| `ring` | the owning token, sixteen lowercase hexadecimal digits | ascending ring order |
| `rendezvous` | the routing key octets in lowercase hexadecimal | none; the strategy enumerates no shards |
| `slot` | the slot index in decimal ASCII without leading zeros | ascending slot index |
| `range` | the authored `shardId` | document order |
| `directory` | the matcher, as `<kind>:<base16 of the value>` | `entries` array order |

The `rendezvous` row was written as the routing key itself and amended to its hexadecimal encoding
by [`0037`](0037-specification-defect-repairs.md), which records why. `PLACE-032` and `RV-022`
require `candidatesForShard` to decode it before routing.

Under `ring` the token is unique across the document, under `explicit` by validation and under
`derived` by the framed hash over node identity and index, with collisions broken in the ring order.
`RING-031` enumerates distinct token values, so two ring entries that collide on one token are one
shard.

Under `directory` two entries with identical matchers make the document invalid, so the matcher is a
unique name, and it survives an edit that inserts an entry ahead of it, which an array index would
not.

`shardOf` is computed over the placement set rather than over the eligible set, so an override
constraint narrows who serves a shard and never renames it.

## Consequences

A `ring` topology has as many shards as it has tokens, which is the node count times the tokens per
node. A six node cluster at weight 100 with four tokens per weight unit has 2400 shards, and
`shards` enumerates all of them. That is the correct granularity for handoff, because a token range
is the unit that moves, and it makes `shards` an expensive call to materialise in full.

A `rendezvous` topology names the key as its own shard, so its ownership delta is unbounded and bulk
handoff is unavailable under it. That restriction is the reason the strategy is recommended for
caches rather than for stores.

Renaming a `range` shard in the document is a new shard rather than a rename, and the ownership
delta shows the old identifier losing every node and the new one gaining them.

## Alternatives

An array index for `directory` and for `range`. Rejected because inserting an entry renumbers every
entry after it, so an ownership delta between two epochs would report movement that did not happen.

A synthetic identifier assigned by the library at snapshot publication, such as a counter. Rejected
because it is not derivable from the document, so two ports and two loads of the same document would
not agree.

A digest of the shard's defining fields. Rejected because it is opaque in an event log and in an
explain record, where an operator reads the identifier and needs to find the shard in the document.

Naming `ring` shards by the owning node rather than by the token. Rejected because a node owns many
token ranges and they move independently.
