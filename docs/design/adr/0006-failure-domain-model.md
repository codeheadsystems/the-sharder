# 0006. Failure domain model

Status: accepted, with the degradation direction superseded by
[`0036`](0036-spread-relaxation-ladder-direction.md). Date: 2026-09-16.

This record states that a distinct region implies a distinct path at every finer level, and
then describes degradation as dropping the finest named level first. The two do not
compose: the coarsest enforced level is the only one that binds, so dropping the finest
first relaxes nothing. [`0036`](0036-spread-relaxation-ladder-direction.md) reverses the
direction. The level model, the ancestor scoping, the eight-level ceiling, and the `strict`
rule stand unchanged.

## Context

Replicas placed on three nodes in one rack survive a disk failure and not a rack failure. Spreading
replicas over failure domains is the reason replication factor buys availability rather than only
durability.

Real deployments do not agree on what the domains are. A public cloud deployment has regions and
availability zones and no visible racks. A colocation deployment has datacentres, halls, rows, and
cabinets. A single-rack deployment has power feeds. A hierarchy hard-coded to region, zone, and rack
fits the first case and forces the other two to lie.

## Decision

A topology declares an ordered list of level names in `domainLevels`, coarsest first, with at most
eight entries. The names are arbitrary and carry no meaning to the sharder library beyond their
order.

```json
"domainLevels": ["region", "zone", "rack"]
"domainLevels": ["datacentre", "hall", "row", "cabinet"]
"domainLevels": []
```

Each node carries a `domains` object with exactly one entry per declared level, giving its failure
domain path: the tuple of its domain identifiers from coarsest to finest. A node missing a level, or
carrying a level that is not declared, makes the document invalid.

Domain identifiers are scoped by their ancestors. Two nodes share a failure domain at a level when
their paths agree at that level and at every coarser level, so a cabinet named `c3` in one hall and
a cabinet named `c3` in another are different cabinets and no operator has to invent globally unique
cabinet names. Identifiers are compared as byte sequences.

Node identity is the implicit finest level. Replica distinctness at the node level is always
enforced, without appearing in `domainLevels`, so a preference list never names the same node twice.

Spread is requested by naming levels in `replication.spread`, in the same relative order as
`domainLevels`. A topology asking for `["zone"]` places its replicas in distinct zones and says
nothing about racks. A topology asking for `["region", "zone"]` places them in distinct regions, and
therefore in distinct zones, since a distinct region implies a distinct path at every finer level.

Where no placement satisfies every spread requirement, `spreadPolicy` decides. Under `relaxed`, the
default, requirements are dropped from the finest named level upwards, one level at a time, until a
placement exists, and the preference list records which requirements were relaxed. Under `strict`,
the preference list is shorter than the replication factor rather than less well spread. The exact
algorithm is normative and belongs in [`10-specification.md`](../10-specification.md).

A topology with an empty `domainLevels` has no failure domain structure, and node distinctness is
the only constraint on a preference list.

## Consequences

Eight levels is an arbitrary ceiling, chosen because the deepest real hierarchy observed is five and
because a bound makes the path a fixed-size tuple that an implementation can compare without
allocation.

Requiring every node to declare every level is stricter than making levels optional per node. A
cluster that spans a cloud region and an on-premises datacentre has to invent a name for the
on-premises nodes at the `zone` level. The alternative, a node with a missing level, would need a
rule for whether such a node shares a domain with anyone, and every such rule is surprising.

Scoping identifiers by ancestors means a topology can be written with short local names, and it also
means a typo in a coarse level silently creates a new domain rather than failing validation.

Relaxing from the finest level upwards preserves the coarsest guarantee longest, which is the one
that protects against the largest correlated failure.

## Alternatives

A fixed three-level hierarchy of region, zone, and rack, as Cassandra's `NetworkTopologyStrategy`
assumes in its datacentre and rack model. Rejected because it does not fit deployments with more or
fewer tiers, and because the names would be wrong for most of them.

A free-form tag set per node, with spread expressed over a tag key. Rejected because tags are not
ordered, so a degradation order could not be derived, and because nothing would prevent two tags
that are not actually hierarchical from being used as if they were.

The CRUSH hierarchy with typed buckets and per-bucket selection algorithms. Its hierarchical model
is borrowed here in simplified form. The bucket types, the `straw2` selection, and the tunables are
rejected as a large specification surface whose benefit, incremental change under reweighting, is
already provided by the strategy set.

Per-domain replication factors, as `NetworkTopologyStrategy` provides with a factor per datacentre.
Not adopted in format version 1. The requirement is real for multi-region deployments and the field
that would express it can be added in a minor version, since minor versions add optional members
with a default that reproduces the previous behaviour.

A graph rather than a tree, so that a node could belong to a power domain and a network domain
independently. Rejected because degradation has no single order over a graph, and because expressing
one hierarchy correctly is worth more than expressing two ambiguously.
