# 0065. Level coverage inside surface boundaries

Status: accepted. Date: 2026-09-17.

## Context

Record [`0058`](0058-conformance-surfaces.md) gave every requirement a conformance surface, and
record [`0063`](0063-decision-api-surface-boundaries.md) put the boundary of the `routing` surface
where the routing decision ends. Both are statements about the specification. The Conformance
levels section of [`../30-conformance.md`](../30-conformance.md#conformance-levels) makes a
matching statement about the suite, that each level tests one conformance surface, and the suite
did not hold it in either direction.

The levels `hash`, `place`, and `core` are mandatory, so an artefact at one of them binds a port
that has declined every optional surface. Six requirements of optional surfaces were named there.
A `place` case that asserts shard enumeration, and asserts nothing about orchestrated migration,
named `MOVE-241`. A case of the error taxonomy at `core` named `ERR-045`, which orders the
conditions only a recipient check raises. Two `core` cases named `ERR-050` for an expectation that
is a condition of the closed set of `ERR-010`, which every implementation exposes whole. One `core`
case that asserts a refused plan named `MOVE-241`, `MOVE-251`, and `MOVE-271`. The `core` rollback
scenario named `MOVE-471`, while what it demonstrates is the epoch monotonicity of `TOPO-081`, and
it holds no coordinator.

The other direction was narrower and mattered more. Record `0063` made `CORE-048` a `routing`
requirement, so it binds a port at `core`, and its only executable coverage was the twelve
`defaultAttemptLimit` and `resolvedAttemptLimit` cases of `vectors/formulas/failover.json`. Those
cases compute the value that `FAIL-022` clamps, which is a property of the attempt walk. A port
declaring `core` therefore met the default resolution through the `materialisedEntries` that
`CORE-046` bounds, and never reached the `RouteOptions` or `CFG-020` branches of a rule that binds
it.

Two requirement paragraphs stated a surface's rule outside its own boundary. The retry budget is a
`failover` construct, and `SEC-032` stated a positive obligation about it under the `SEC` prefix,
which is `routing`. Setting `tokenDigest` belongs to `fencing` under `CFG-040`, and nothing stated
what `FencingToken.digest` holds for a port that refuses that setting under `CORE-111`.

## Decision

Requirement `CORE-048` gains coverage at `place` rather than losing it at `failover`. The reference
gains `resolved_attempt_limit_before_clamp`, which is the resolution `CORE-048` states with no
clamp applied, and `resolved_attempt_limit` becomes that function clamped, so no value moves. The
file `vectors/formulas/attempt-limit.json` is generated at `place` with seven cases reaching all
three branches: the limit `RouteOptions` supplies, the configured `attemptLimit` of `CFG-020`, and
the effective replication factor plus 2. The twelve cases of `vectors/formulas/failover.json` stay
where they are, because the value they compute is the clamped one and the clamp belongs to the
walk.

The six off-surface names are resolved case by case, by the rule that a case names the requirements
it asserts and sits at a level the surfaces of those requirements admit.

- The shard enumeration case at `place` drops `MOVE-241`, which it never asserted.
- The two delta refusal cases name `ERR-010` rather than `ERR-050`, because the condition they
  assert is a member of the closed set every implementation exposes, and not `plan` raising it.
- The recipient report order moves out of `vectors/errors/taxonomy.json` into a file of its own,
  `vectors/errors/recipient-taxonomy.json`, at `fencing`, with the case body unchanged.
- The refused plan moves out of `vectors/topology/ownership-delta.json` into a file of its own,
  `vectors/topology/ownership-delta-unsupported.json`, at `migration`, with the body unchanged.
- The rollback scenario drops `MOVE-471`, and cites `TOPO-081` for the step that republishes the
  former assignment at a higher epoch.

Requirement `SEC-032` is scoped to an implementation that exposes `failover`, and states that one
which does not holds no retry budget. What it requires of a port with a budget is unchanged.
Requirement `CORE-040` states that the `digest` component of the token is governed by
`tokenDigest`, and that a port which does not expose `fencing` refuses that setting and carries
`none` there.

## Consequences

Requirement `MOVE-471` becomes uncovered. No artefact at `migration` returns ownership to a former
source, and the `core` scenario that republishes an assignment demonstrates `TOPO-081` rather than
the migration rule. Recording it as uncovered is what the Requirement coverage section of
[`../30-conformance.md`](../30-conformance.md#requirement-coverage) is for.

The suite revision moves. Three vector files are added and three change, so the file digests that
record [`0061`](0061-suite-revision-identifier.md) makes the revision basis move with them. No
expected value changes anywhere: the two cases that move are byte-identical in their new files, the
rollback scenario differs only in its requirement list and one step note, and every other case body
is unchanged.

A port declaring `routing`, `rendezvous`, and `directory` now walks the specification and the suite
alike without meeting a rule of a surface it declined. The level `place` gains two requirement
identifiers and `core` loses seven, and `conformance/coverage.json` holds both figures under
`byLevel`.

The two new files carry one case each, which is small for a file. A level is the unit a port
declares and a file carries one level, so a case whose level differs from its neighbours' needs a
file of its own.

## Alternatives

Leaving `CORE-048` covered at `failover` alone. Rejected because the rule binds a port at `core`,
and a level a port does not run proves nothing about it. The `materialisedEntries` assertions at
`place` cover the default branch and no other, so a port that ignored `RouteOptions` and `CFG-020`
entirely would pass every level it declared.

Moving the twelve existing cases from `failover` to `place`. Rejected because they compute the
clamped limit, and the clamp is `FAIL-022`, which a port with no attempt walk does not apply.
Moving them would put a `failover` rule at a mandatory level, which is the defect in the other
direction.

Dropping the six off-surface names without moving any case. Rejected for two of them: the recipient
report order and the refused plan are asserted by their expectations, not only by their tags, so a
port at `core` would still have been asked to produce them.

Asserting the level-to-surface property in `build_manifest.py`. Deferred. The surface of a `CFG-*`
identifier is stated one table row at a time, the surface of an `OBS-*` metric comes from the first
segment of its name, and a check reading those from prose would be a second, weaker copy of the
Conformance surfaces section. The property is stated in `30-conformance.md` and holds at review.
