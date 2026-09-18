# 0003. Integer node weights

Status: accepted. Date: 2026-09-16.

## Context

Nodes in a real cluster are not identical. A storage cluster acquires larger disks over time, a
cache cluster runs two node sizes, and a tenant router sends more traffic to a cluster with more
capacity. Placement has to honour that, and it has to honour it identically in every language.

The published weighted form of rendezvous hashing computes a score of `-weight / ln(h / 2^64)` and
takes the maximum. It is exactly proportional and it is floating point, which makes it unusable
here: two languages computing a natural logarithm are not guaranteed to agree in the last bits, and
a disagreement in the last bits is a different node for some key.

## Decision

A node's capacity is an integer `weight` in weight units, in the range 0 to 1000000, defaulting
to 1. Weight units have no dimension and are meaningful only in ratio to the other weights in the
same topology. A weight of 0 keeps a node in the placement set while giving it no keys from a hash
strategy.

Weight is honoured by replicating a node in the hash strategy's input space, in proportion to its
weight, and never by scaling a score.

Under `rendezvous`, a node of weight `w` contributes `v = min(w * virtualNodesPerWeightUnit,
maxVirtualNodesPerNode)` scoring slots, indexed 0 to `v - 1`, and the node's score for a routing key
is the largest of its slots' scores. Each slot's score is uniform over the 64-bit range and
independent of the others, so the probability that a node wins a key is `v_i / sum(v)`, which is
exactly proportional to weight where no node is clamped.

Under `ring`, a node of weight `w` owns `t = min(w * tokensPerWeightUnit, maxTokensPerNode)` tokens.
The expected share of the ring a node owns is proportional to `t`, with the variance that consistent
hashing always carries.

Under `slot` and `range` with explicit assignment, the assignment is authoritative and weight is
advisory: the sharder library reports the resulting balance against weight and changes no placement.
Under those kinds with derived assignment, weight is honoured through the rendezvous rule above,
applied to the shard identifier.

Clamping at the cap is deterministic and observable. Where `w * perWeightUnit` exceeds the cap, the
node is clamped and an event naming the node and both values is emitted at snapshot publication.

All of this is unsigned 32-bit integer multiplication with a cap, and no step divides.

## Consequences

Cost is linear in the total virtual node count. A rendezvous topology of 50 nodes at weight 100 and
one virtual node per weight unit performs 5000 hashes per routing call, which is too slow for a hot
path. The defaults avoid it: `virtualNodesPerWeightUnit` defaults to 1, so weights are expressed in
the smallest integers that give the intended ratio. A cluster of one standard and one double node
uses weights 1 and 2, not 100 and 200.

Weight ratios are exact only in the small integers. Expressing a 1.5 times larger node needs weights
2 and 3, which doubles the virtual node count for every node in the topology.

Clamping distorts a ratio silently as far as placement is concerned. The event makes it visible, and
validation does not reject it, because a cluster-wide cap on virtual node count is a legitimate cost
control.

A weight change moves keys. Raising a node's weight from 1 to 2 adds virtual nodes and takes keys
from every other node, and the movement is bounded by the added share rather than by the whole
keyspace.

## Alternatives

Floating-point weighted rendezvous, as published by Resch. Exactly proportional and simple. Rejected
because it requires `ln` and division in floating point, and cross-language bit-identical results
from a transcendental function cannot be assumed.

Comparison of `h_a * w_b` against `h_b * w_a` using a 64-by-64 to 128-bit multiplication. Exact
integer arithmetic, one hash per node, no replication cost. Rejected because the resulting
distribution is not proportional to weight, because a 128-bit product is awkward in languages
without a native wide multiply, and because the proportionality it does give is hard to state as a
testable bound.

Normalised weights that sum to a fixed total, as a percentage or a per-mille share. Rejected because
normalisation requires division and rounding, and because adding a node forces every other node's
weight to change, which makes a diff between two topology documents unreadable.

Capacity expressed in bytes, requests per second, or another dimensioned unit. Rejected because the
library cannot interpret the dimension, and because two operators would disagree about whether the
unit describes the node's total or its free capacity. The ratio is the only thing placement can use.

Weight 0 meaning "remove from the topology". Rejected because a node at weight 0 is still an owner
under explicit assignment, still receives a drain, and still needs to appear in a fencing decision.
Removal is removal from `nodes`.
