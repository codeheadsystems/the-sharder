# 0068. Ownership of an unfenced request

Status: accepted. Date: 2026-09-17.

## Context

[`0020`](0020-recipient-side-fencing-verdicts.md) gives a recipient a verdict with two independent
axes. `relation` compares the sender's epoch against the recipient's, and `ownership` answers
whether the recipient holds the key, computed by evaluating the preference list for the routing key
against the snapshot in force. The record fixes two refusals as not negotiable, namely
`identityMismatch` and `notOwner`, and leaves one case to policy: a sender behind the recipient at a
node that does own the key, where `strict` refuses and `stable` serves.

`FENCE-041` treats a request carrying no token as unfenced and sends it to the same policy.
`FENCE-131` said the policy governs an unfenced request, which `strict` refuses and `stable` serves,
and said nothing about ownership. Read on its own that sentence disposes of the whole unfenced case,
and the reference implementation read it that way: `policy_outcome` answered `serve` under `stable`
without consulting the verdict's `ownership` at all.

That is a node serving a shard it does not own. `FENCE-121` refuses a `notOwner` verdict
unconditionally and `ERR-045` puts `notOwner` ahead of `epochMismatch`, so the reading contradicts
the rest of the section, and `MOVE-281` makes the cutover record the only thing that decides
authority for a shard under handoff. No scenario covered an unfenced request at a node that does not
own the key, so no vector pinned the behaviour.

The gap is real in the specification and not only in the reference. A verdict is computed from a
token, and an unfenced request carries none, so `FENCE-071` computes no `relation` and `FENCE-081`,
which computes `ownership` for three values of `relation`, states nothing about a request that has
none.

## Decision

An unfenced request has an ownership axis and no epoch axis.

`FENCE-042` states it. A recipient computes no `relation` for an unfenced request, computes
`ownership` as `FENCE-081` states against the snapshot in force, and refuses the request under
`FENCE-121` where `ownership` is `notOwner`, whatever value `recipientPolicy` holds. Where no
snapshot is in force it refuses and reports the unready condition, as `FENCE-151` states for a
token. The policy decides the one case that remains, in which the recipient owns the key, and
`FENCE-131` keeps that sentence. `ERR-045` orders the conditions where more than one holds, so an
unfenced request at a non-owner is reported as `notOwner` carrying `currentOwner` rather than as the
unfenced `epochMismatch` of `ERR-044`.

The reference is corrected to match, and the recipient scenario gains an unfenced request at an
owner and at a non-owner under both policies. The Python driver now compares the condition a
recipient reports as well as the verdict it computes, so the suite enforces the order rather than
carrying it as data a port may read.

## Consequences

`stable` is a choice about staleness and not about ownership. An integrator that sets it accepts
serving a caller whose view of the topology is old, and does not thereby accept serving a key the
node does not hold, which is the distinction that makes the policy safe to offer at all.

A caller that omits the token is redirected rather than served by whichever node it reached. The
refusal names `currentOwner`, so the redirect walk of `FENCE-221` carries it to the owner, and a
deployment that has not yet attached tokens degrades to one extra hop rather than to a split
ownership.

The reference changes and one scenario gains steps. No vector moves, because no case covered the
combination: the defect was invisible to the suite, which is why the record also moves the driver to
compare the condition.

An unfenced request now costs a preference list evaluation at the recipient even under `stable`,
where the reference previously answered without one. That is the same evaluation every fenced
request already performs, and `FENCE-101` keeps it a pure function of the token, the key, the
identity, and the retained snapshots.

## Alternatives

Serving an unfenced request under `stable` whatever the ownership, which is what the reference did.
Rejected because it makes a recipient's ownership check optional at the caller's discretion: a
caller that omits the token gets a weaker check than one that attaches a stale one, which inverts
the incentive `FENCE-041` exists to create.

Refusing every unfenced request, whatever the policy. Rejected because `FENCE-041` and
[`0020`](0020-recipient-side-fencing-verdicts.md) already give an integrator the choice, and a cache
deployment that has not attached tokens is the deployment `stable` was added for.

Giving an unfenced request a `relation` of its own, such as `unfenced`. Rejected because `relation`
is defined as a comparison of two epochs and a request with no token has no epoch to compare, and
because a sixth value would reach every table that enumerates the five.

Reporting the unfenced non-owner case as `epochMismatch` with a `cause` of `unfenced`. Rejected
because the caller's response differs: `epochMismatch` asks for a refresh and a retry at the same
node, and `notOwner` carries the owner to retry at, which is the response that resolves it.
