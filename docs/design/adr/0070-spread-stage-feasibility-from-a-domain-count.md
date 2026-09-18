# 0070. Spread stage feasibility from a domain count

Status: accepted. Date: 2026-09-17.

## Context

`SPREAD-012` chooses `select(k)` for the smallest `k` whose replica prefix reaches the effective
replication factor. Establishing that `select(k)` does not reach it requires walking the candidate
ordering to its end, because the next entry is the one that might have filled the prefix. Under
`spreadPolicy` of `relaxed` the builder does that once per stage it evaluates, and `SPREAD-017`
bounds the stage count at `m + 1`, which `domainLevels` bounds at nine.

[`0034`](0034-lazy-candidate-traversal-surface.md) rests on the observation that a preference list
of three should read three entries from an ordering over a thousand nodes, and that materialising
the ordering per call "is the difference between a routing library and a routing cost". The degraded
path loses that property completely: the walk runs to the end of the ring order, up to nine times,
for every routing call.

Two ordinary conditions produce it.

A zone outage. At factor 3 with a `zone` spread over three zones, one zone moving to `leaving` takes
its nodes out of the placement set under `PLACE-001`. Stage 0 can then never reach 3, so every
routing call for every key walks the ordering to its end before relaxing. The routing cost of the
fleet rises by orders of magnitude at the moment the fleet is absorbing a zone's worth of redirected
traffic, which is a metastable shape: the response to the outage makes the fleet slower, and the
slowness makes the outage worse.

A single-zone cluster. [`0015`](0015-spread-degradation-algorithm.md) chose `relaxed` as the default
so that "a first-time integrator whose test cluster has one zone" can route at all. Routing works
and pays a full walk on every call, permanently, in the environment where nobody is watching
latency.

The specification stated neither the multiplier nor the condition that triggers it, so the cost
model gave a routing figure that was correct for the healthy path alone.

## Decision

A stage that cannot reach the factor is identified from a count computed once per snapshot, and the
condition that makes a fleet pay for the ladder is reported at publication.

`SPREAD-022` defines the domain count `d(L)`: the number of distinct domain paths at `L` over the
placement set. It costs one pass over the placement set per snapshot. The eligible node set of a
routing key is a subset of the placement set, so its own count of distinct paths at `L` is at most
`d(L)`.

`SPREAD-023` states the bound and the permission. Stage `k`, for `k` below `m`, admits at most
`c(spread[k]) * d(spread[k])` entries, because it admits at most `c(spread[k])` per distinct domain
path at that level under `SPREAD-007` and the eligible node set offers at most `d(spread[k])` of
them. Where that product is below the effective replication factor an implementation may treat
`length(select(k))` as short without evaluating the stage. Stage `m` enforces no level, so the
permission never removes it, and `strict` evaluates `select(0)` whatever its length, so the
permission applies under `relaxed` alone.

`SPREAD-024` emits `sharder.topology.spread_infeasible` at snapshot publication where the product
falls below `replication.factor` for some named level, carrying the coarsest such level, its domain
count, the factor, and the lowest stage the check leaves. An override `factor` is not evaluated,
because an override `constrain` gives its keys an eligible node set the placement set does not
describe.

`SPREAD-017` and `PLACE-076` state the cost of the unskipped path. The walk term of the routing
table of `PLACE-070` is multiplied by the count of stages evaluated, the terms that produce the
ordering are paid once, and the multiplier and the walk reach their maxima together, on exactly the
topology whose coarsest named level cannot be satisfied.

The suite declares `SPREAD-022` and `SPREAD-023` on
`vectors/spread/all-nodes-one-domain-relaxed.json`, whose every node sits in one failure domain, and
on `vectors/spread/relaxation-stages.json`, which records each stage's `reachesFactor` separately.

## Consequences

Both triggers above are removed. In each the shortfall is a property of the placement set rather
than of one key's eligible subset, so the bound rules the stage out and no walk happens.

The residual case is an override `constrain` whose eligible subset is domain-poor while the
placement set is not. It still pays the walk per stage, and the cost is irreducible without
computing a domain count over the eligible node set inside the routing call, which is the O(nodes)
term the permission exists to avoid. The cost is bounded at `m + 1` walks, which `SPREAD-017`
bounds at nine, and `PLACE-076` states it.

No output changes, so no vector moves. A stage the bound rules out is a stage `SPREAD-012` could not
have chosen, and `SPREAD-013` falls back to stage `m`, which is never ruled out.

Measured on 17 September 2026, over the topology documents the conformance suite ships and four
hundred generated ones: of the two hundred and two generated topologies under `relaxed`, one hundred
and fifty-one carry a stage the domain count rules out, and for every key sampled the preference
list, the chosen stage, and the relaxed level list are the same with those stages evaluated and with
them skipped.

An implementation that keeps `d(L)` keeps one integer per named level per snapshot, which is not a
term of the resident size table of `PLACE-070` at any topology size.

An operator whose topology cannot satisfy its coarsest spread level learns it from an event at
publication rather than from a latency graph. The event fires on a single-zone test cluster, which
is a deployment the library serves, so it names the stage that is reachable rather than reporting a
fault.

## Alternatives

Computing the domain count over the eligible node set inside the routing call, which would be exact
for the residual case too. Rejected because it is a pass over the eligible node set per call, which
costs what the walk costs and is paid whether or not any stage is short.

Making the skip a MUST rather than a MAY. Rejected because the skip changes no output, so a port
that evaluates every stage is conforming and slow rather than wrong, and because a MUST would put
`d(L)` into the prepared placement, which `PLACE-012` makes part of the structure a port must
reproduce.

Refusing a document whose coarsest named level cannot reach the factor. Rejected for the reason
[`0015`](0015-spread-degradation-algorithm.md) gives for not failing the routing call: `relaxed` is
the default precisely so that a cluster with one zone routes.

Caching the chosen stage per snapshot rather than per key. Rejected because the chosen stage is a
function of the routing key under `SPREAD-020`, and two keys over the same snapshot reach different
stages wherever the ordering presents different nodes first.

Leaving the cost unstated and relying on the ladder being short. Rejected because the trigger is an
outage, the multiplier is nine, and the term it multiplies is the whole ordering rather than the
prefix a caller reads.
