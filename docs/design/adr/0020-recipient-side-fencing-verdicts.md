# 0020. Recipient-side fencing verdicts

Status: accepted, with the unfenced case stated by
[`0068`](0068-ownership-of-an-unfenced-request.md) and the relation rows ordered by
[`0076`](0076-ordered-rows-in-a-precedence-table.md). Date: 2026-09-16.

The two axes and the policy below are unchanged. Where the text says policy governs a request
carrying no token, `FENCE-042` states that such a request still has an ownership axis, so `stable`
serves one only at a node that owns the key and `FENCE-121` refuses it everywhere else.

## Context

ADR 0005 fixes the fencing token as the pair of `topologyId` and `epoch` and leaves the comparison
rules at a recipient to the specification. A recipient holding its own snapshot receives a request
carrying a sender's token, and has to decide whether to serve it.

Comparing two epochs gives three answers and none of them is a decision. A sender behind the
recipient is usually wrong and is sometimes harmless, because a topology change that does not touch
this key changes nothing for this request. A sender ahead of the recipient means the recipient is
the stale one, and refusing on the strength of a view it already knows to be old is the wrong
instinct. Two different identifiers are not comparable at all. Meanwhile the interesting question,
whether this node owns this key, is not an epoch question.

A recipient also has to work during a handoff, when the epoch is deliberately not the thing that
decides authority.

## Decision

The recipient-side check takes the token, the routing key, and the recipient's own node identity,
and returns a verdict with two independent axes rather than a single answer.

The epoch axis, `relation`, is `same`, `senderBehind`, `senderAhead`, `unknownEpoch`, or
`identityMismatch`. The ownership axis, `ownership`, is `owner`, `notOwner`, or `unknown`, computed
by evaluating the preference list for the routing key against the snapshot in force. The verdict
also carries `currentOwner`, so a refusal can redirect, and `ownershipStable`.

`ownershipStable` is the reason the check takes a key rather than a shard identifier. Where the
sender is behind and the recipient still retains the snapshot at the sender's epoch, the recipient
can ask whether it was a replica for that key at both epochs. Where it was, the topology change did
not touch this key, and serving the request is safe. That is the common case during a rebalance,
where a delta touches a small fraction of the keyspace and the great majority of requests from stale
callers are unaffected by it. Retaining a bounded number of previous snapshots, three by default,
buys that at a cost of a few validated documents in memory.

A key rather than a shard identifier also makes a range split transparent. A request routed against
a parent range arrives at a node that has moved on to the children; if the node owns the child
covering the key, the ownership axis says so without the shard names having to match.

Policy is separated from the verdict. The library computes the verdict and the recipient chooses.
The specification fixes the choices that are not negotiable, namely refusing on `identityMismatch`
and refusing on `notOwner`, and offers two values, `strict` and `stable`, for the case that is.
`strict` is the default, so a storage deployment that never configured anything refuses a stale
sender, and a cache deployment that is content with a stale view sets `stable`.

A recipient that is itself behind does not serve on the strength of its own snapshot. It asks for a
refresh, waits up to a deadline the integrator sets, and refuses if it has not caught up. It never
synthesises the sender's topology from the token, because a token carries an ordering and not a
content.

## Consequences

The verdict is a pure function of the token, the key, the identity, and the retained snapshots, so
it is a conformance vector rather than a scenario. Every axis and every combination is directly
testable.

Snapshot retention is new memory that a routing-only integrator does not need. The depth is an
integer the integrator sets, and setting it to zero gives the plain behaviour in which every stale
sender is refused.

Redirects become the normal mechanism for a caller that arrived at the wrong node, which means the
error taxonomy carries a refusal that names a node and the caller walks a bounded number of them.
The bound and the rule against revisiting an attempted node keep a disagreeing pair of nodes from
bouncing a request between them.

A recipient behind the sender adds latency to a request that would otherwise fail, bounded by the
deadline. An integrator that prefers to fail fast sets the deadline to zero.

## Alternatives

A single boolean answer, accept or reject, from an epoch comparison alone. Rejected because it
refuses every stale request including the overwhelming majority that a rebalance did not touch, and
because it cannot express a redirect.

Carrying the topology digest in the ordering and rejecting on a digest mismatch. Rejected because
the digest gives equality without order, so a mismatch cannot tell a recipient which side is old.
The digest is retained as a diagnostic component and is excluded from every ordering decision.

Having the recipient adopt the sender's epoch when the sender is ahead. Rejected because a token is
not a document, adopting it would route against a topology nobody validated, and a malicious or
buggy sender could push a recipient to an arbitrary epoch.

Making the check take a shard identifier. Rejected because shard identifiers do not survive a range
split, so the check would refuse every request across a split even where ownership is unchanged.

Fixing the stale-sender policy at `stable` for everyone. Rejected because the storage use case
treats a divergent owner as data loss, and a default that trades that for a lower redirect rate is
the wrong default for the case with the worst failure.
