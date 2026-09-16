# 0012. Balance bound tolerances

Status: accepted. Date: 2026-09-16.

## Context

A balance property stated as an adjective is untestable. The conformance suite needs a bound it can
evaluate, which means a tolerance band, a sample size at which the band holds, and a rule for which
topologies the band applies to at all.

The two hash strategies have different variance. Rendezvous placement assigns each key to the node
whose maximum virtual node score is largest, so a node's share of keys is binomial with success
probability equal to its share of virtual nodes, and the relative standard deviation of a node's
observed share falls as the sample grows. Ring placement assigns a key to the node owning the
enclosing token range, so a node's share is the summed length of its token ranges, and the relative
standard deviation of that sum is governed by the node's token count rather than by the sample size.
A ring node with four tokens has a relative standard deviation near one half whatever the sample
size, which is why Cassandra's default vnode count is 256 rather than 4.

## Decision

Two bands, each with a precondition that makes it meaningful.

Rendezvous placement, and `slot` and `range` with derived assignment, carry a band of five per cent
relative to the node's weighted expected share, applying when the sample is large enough that the
least expected count is at least 10000. At an expected count of 10000 the binomial standard
deviation is near 100, so the band is roughly five standard deviations and a conforming
implementation fails it only for a real defect.

Ring placement carries a band of twenty five per cent relative to the node's expected share,
applying only where every node in the placement set holds at least 256 tokens. At 256 tokens the
relative standard deviation is near one sixteenth, so the band is again roughly four standard
deviations. Below 256 tokens per node the specification states no balance bound for `ring`.

Explicit assignment under `slot` and `range`, and the `directory` strategy, carry no balance bound.
The assignment is authored and the library reports the observed balance without changing placement.

## Consequences

The `ring` default of four tokens per weight unit means a topology of weight 1 nodes has no balance
bound. A topology that wants one raises weights so that every node holds at least 256 tokens, which
is what the storage example does with weight 100 and four tokens per weight unit.

The bands are wide enough that they do not detect a small distortion. A port that clamps a weight it
should not clamp, or that uses the wrong domain tag, fails them; a port that computes a virtual node
count one too low for a large node does not. The determinism vectors catch the second class, because
they compare exact orderings rather than distributions.

A balance test is slow. One million keys against a fifty node rendezvous topology at one virtual
node per weight unit performs fifty million hashes.

## Alternatives

A single band for every strategy. Rejected because a band loose enough for a four token ring is
loose enough to hide a real defect under rendezvous, and a band tight enough for rendezvous fails a
correct ring implementation.

Stating the bound as a multiple of the binomial standard deviation rather than as a fixed
percentage. More precise and rejected because it puts a square root in the conformance harness of
every port, and the fixed band at a stated minimum expected count expresses the same thing with
integer arithmetic.

A chi-squared or Kolmogorov test over the whole distribution. Rejected as more statistical machinery
than five ports should each carry, and because a per-node band names the offending node when it
fails.

Raising the ring default token count so that every ring topology carries a bound. Rejected because
the token count is the cost of every routing call's ring search and of the snapshot's memory, and
because an operator who wants balance can raise the weight.
