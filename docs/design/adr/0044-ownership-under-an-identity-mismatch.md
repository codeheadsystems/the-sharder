# 0044. Ownership under an identity mismatch

Status: accepted. Date: 2026-09-17.

## Context

`FENCE-061` declares a recipient verdict whose `ownership` is one of `owner`, `notOwner`, and
`unknown`. `FENCE-081` required `ownership` to be computed by evaluating the preference list for
`routingKey` against the snapshot in force, and required `currentOwner` to be its first entry, with
no exception. No requirement anywhere said when `unknown` arises, so the enumeration carried a
member with no producer.

The reference implementation produced one. Where `relation` is `identityMismatch` it returned
`ownership` of `unknown` and `currentOwner` of none, and where no snapshot is in force it returned
the same. `conformance/scenarios/caller-three-epochs-stale.json` asserted the first of those, so a
port written from the specification computed `owner` with `currentOwner` of `n1` at that step and
failed a scenario the specification had not warned it about.

The question the case raises is what a recipient can honestly answer when the caller's topology
identity does not match its own.

## Decision

The suppression is correct behaviour that the specification failed to state.

`FENCE-081` is now scoped to the three relations under which a preference list is evaluable: `same`,
`senderBehind`, and `senderAhead`. `FENCE-082` says that where `relation` is `identityMismatch`,
`ownership` is `unknown`, `currentOwner` is absent, and no preference list is evaluated.
`FENCE-083` says that where `relation` is `unknownEpoch`, `ownership` is `unknown` and
`currentOwner` and `localToken` are absent. `FENCE-084` closes the enumeration: `unknown` arises
under those two requirements and nowhere else.

`Verdict.localToken` is typed `FencingToken | none` to match `FENCE-083`, which is what the
reference already produced where no snapshot is in force.

The reference moves the identity-mismatch test ahead of the preference list evaluation, so it no
longer computes a list it discards. The verdict it produces is unchanged, so no expected value in
the suite moved. `caller-three-epochs-stale` gains a step before its first installation, asserting
the `unknownEpoch` verdict, and the Python driver runs a recipient check against no snapshot where
the step declares `noSnapshot`.

## Consequences

A recipient under `identityMismatch` holds no evidence about the sender's shard. The routing key it
receives was derived under the sender's topology, whose `keyTransform` decides which octets of the
key the routing key holds and whose `hash.seed` decides where those octets land. Evaluating it
against the recipient's snapshot answers a question about a different key space, and the answer
would be arbitrary rather than merely unhelpful. Reporting `unknown` says what is true.

Suppressing `currentOwner` matters more than suppressing `ownership`. `FENCE-171` lets a caller
retry against the node a refusal names, resolving its address from its own `nodes` list. A
`currentOwner` drawn from a foreign topology names a node the caller may not hold, or worse, a node
it does hold under a different identity, and the redirect walk of `FENCE-181` would spend its bound
chasing it. `FENCE-111` already required the request to be refused with no recovery available at the
recipient, and `ERR-045` already put `identityMismatch` first; a named owner was the one part of the
refusal that invited a retry.

`unknown` is now a closed member of the enumeration with two producers, so a port can assert that
its verdict carries `owner` or `notOwner` in every other case. A port that computed ownership
unconditionally is non-conforming, and the scenario is what tells it so.

`FENCE-091` is unchanged: it already made `ownershipStable` false in every case other than a
retained `senderBehind`, which covers both relations this record names.

## Alternatives

Repairing the reference to compute ownership unconditionally, as `FENCE-081` read. Rejected because
the value computed is not an answer to the question asked. It would also make the refusal name a
`currentOwner` that `FENCE-111` gives a caller no way to use, and `ERR-040` carries `currentOwner`
on the refusal precisely so that a caller can act on it.

Reporting `notOwner` under `identityMismatch`, on the ground that the recipient certainly does not
own the sender's shard. Rejected because it is a claim about a shard the recipient cannot identify,
and because `ERR-045` would then report `notOwner` ahead of `identityMismatch` and hide the
condition an operator has to act on. The two identifiers are an authority defect, and the refusal
should say so.

Evaluating ownership under `identityMismatch` and reporting it alongside `unknown`, as diagnostic
data. Rejected because `FENCE-101` makes the check a pure function with a fixed shape, and because a
field carrying a value derived from a foreign key space would be read as an answer whatever the
surrounding text said.

Leaving `unknown` in the enumeration with no producer and removing it from the reference. Rejected
because the `unknownEpoch` case needs it: a recipient with no snapshot in force has no preference
list at all, and `FENCE-151` already requires it to refuse and report the unready condition.
