# 0042. Domain path scope

Status: accepted. Date: 2026-09-17.

## Context

`SPREAD-011` compares two nodes by `domain_path(x, L)` and described it as "the tuple of `x`'s
domain identifiers from the coarsest level through `L`". The coarsest level of what was not stated.
Two readings exist.

Under the declared reading the tuple spans `domainLevels`, so a topology declaring
`["region", "zone", "rack"]` compares a triple at the rack level whatever `replication.spread`
names. Under the spread reading the tuple spans `replication.spread`, so a `spread` of `["rack"]`
compares the rack identifier alone.

The two agree whenever `spread` is a prefix of `domainLevels`, and diverge whenever `spread` skips a
declared level. An implementation review constructed the divergence: `domainLevels` of
`["region", "zone", "rack"]`, `spread` of `["rack"]`, factor 2, nodes `a` at `r1/z1/k1`, `b` at
`r1/z2/k1`, and `c` at `r2/z3/k9`, with a routing key whose candidate ordering is `[a, b, c]`. The
declared reading places `[a, b]`, because the rack paths `(r1, z1, k1)` and `(r1, z2, k1)` differ.
The spread reading places `[a, c]`, because `a` and `b` both carry the rack identifier `k1`. Two
ports, both reading the specification honestly, disagree about who owns the document.

No topology in the conformance suite separated the two. Of the 97 documents it shipped, only
`read-affinity` and `ring-zoned` carried a `spread` shorter than `domainLevels`, and in both
`spread` was a prefix of `domainLevels`, where the readings agree.

`READ-013` carried the same wording, saying the reordering partitions on entries "whose domain path
agrees with `path` at `level` and at every coarser level", with the same unstated scope.

## Decision

A domain path spans `domainLevels`. `SPREAD-006` states it: `domain_path(x, L)` is the tuple of
`x`'s domain identifiers at every level of `domainLevels` from index 0 through the index of `L`, in
`domainLevels` order, and every declared level within that span contributes an identifier whether or
not `replication.spread` names it. `SPREAD-011` and `READ-013` now name `SPREAD-006` rather than
restating the rule, and each says that a declared level `spread` omits still contributes.

`AffinityRequest.path` is indexed by `domainLevels` for the same reason, so a request at level
`zone` under `domainLevels` of `["region", "zone"]` carries two identifiers.

The reference implementation already read it this way, in `Node.domain_path`, so nothing the
reference computes changed and no expected value in the suite moved.

Two additions make the choice enforced rather than merely written down.
`conformance/topologies/spread-skipped-level.topology.json` declares
`["region", "zone", "rack"]`, spreads over `["rack"]` alone, and reuses the rack identifiers `k1`
and `k9` across zones and regions. `conformance/vectors/spread/skipped-level.json` routes nine keys
against it, and in four of them the replica prefix holds two nodes carrying the rack identifier
`k1`, which the spread reading cannot produce.
`conformance/vectors/read/affinity.json` gains eighteen cases at level `zone` over a topology whose
zone identifiers `a` and `b` repeat in three regions, which an implementation comparing the
identifier at `level` alone cannot reproduce.

## Consequences

Domain identifiers stay ancestor scoped everywhere, which is what
[`0006`](0006-failure-domain-model.md) locked and what [`../05-glossary.md`](../05-glossary.md)
already says: a failure domain path holds one identifier per declared level, and two nodes share a
domain at a level when their paths agree at that level and at every coarser one. A rack named `r01`
in one zone and a rack named `r01` in another are distinct racks under every spread a topology can
declare.

The choice is forced by [`0036`](0036-spread-relaxation-ladder-direction.md) rather than free. That
record rejected an alternative repair, comparing the identifier at `L` alone, on the ground that it
would treat two racks with one name as one rack. The spread reading is that same wrong comparison
reached by a different route: where `spread` names one level, scoping the path to `spread` compares
exactly the identifier at that level. Taking it would have undone `0036` while appearing to leave it
alone.

An operator naming a coarse level in `domainLevels` and omitting it from `replication.spread` gets a
spread requirement that is weaker to satisfy than the identifier count suggests. Ten racks named
`k1` through `k10` in each of three zones give thirty rack domains rather than ten, so a factor of
four spreads across four of the thirty. That is the behaviour a reader of
[`0006`](0006-failure-domain-model.md) expects, and it is the reason the declared reading is the one
worth having: the alternative would collapse thirty racks into ten and refuse placements that are
genuinely well spread.

The ladder property of `SPREAD-018`, that a stage's coarsest enforced level decides what it admits,
holds under the declared reading and holds under the spread reading, so it never disambiguated the
two and is unchanged by this record.

## Alternatives

Scoping the path to `replication.spread`. Rejected because it makes a domain identifier mean
different things in two topologies that declare the same levels, and because it reintroduces the
identifier-alone comparison that [`0036`](0036-spread-relaxation-ladder-direction.md) rejected.
It would also make `SPREAD-005`, which requires `spread` to list levels in the same relative order
as `domainLevels`, carry no weight: the order would matter only within `spread`.

Forbidding a `spread` that skips a declared level, so that the two readings can never diverge.
Rejected because the skipping topology is a reasonable one. A topology declaring region, zone, and
rack for reporting and spreading over racks alone is asking for the strongest spread it can get, and
refusing the document would push the operator to drop levels from `domainLevels` and lose the
ancestor scoping that makes rack names local.

Comparing the identifier at `L` alone and requiring globally unique identifiers at every level.
Rejected by [`0006`](0006-failure-domain-model.md), which locked ancestor scoping so that an
operator does not have to invent globally unique rack names. Nothing found here changes that.
