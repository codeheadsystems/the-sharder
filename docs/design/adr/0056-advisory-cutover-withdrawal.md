# 0056. Advisory cutover mode withdrawal

Status: accepted. Date: 2026-09-17.

## Context

[`0018`](0018-concurrent-ownership-during-handoff.md) decided that the cutover record, not the
topology epoch, decides which of two nodes holding a shard is authoritative, and that the record is
a durable single-winner write the integrator's hooks perform. `MOVE-321` states that requirement.

The same record then admitted a second declaration. Hooks declared `cutoverGuarantee` as
`linearisable` or `advisory`, and under `advisory` the coordinator waited `cutoverGraceMillis` after
a successful `quiesce` before committing, which converted an exclusivity claim into a time bound.
`MOVE-401` said what that bought: under an `advisory` declaration "mutual exclusion is not
provided". That is a direct negation of `MOVE-321`, which exists to provide it.

The mode was off by default. `MOVE-371` made `plan` refuse `advisory` hooks unless
`requireLinearisableCutover` was set to false, and `CFG-053` recorded the default as true. A second
mode that is refused unless an integrator turns it on, and that negates the central safety property
when they do, cost `MOVE-235`, `MOVE-341`, `MOVE-361`, `MOVE-371`, `MOVE-401`, `CFG-053`, two
migration policy members, a member of `HookDeclaration`, and one permanent event name.

For the storage use case a wrong routing decision means data loss. A mode that admits two
authoritative owners of one shard is not a weaker configuration of that use case; it is a different
one. `HookDeclaration` and an event name are both published surfaces, so the decision belongs before
v0.1 rather than after.

## Decision

The `advisory` cutover mode leaves the design. A linearisable single-winner cutover is the only mode
the coordinator supports.

Withdrawn under the convention of [`0053`](0053-requirement-withdrawal-convention.md): `MOVE-235`,
`MOVE-341`, `MOVE-361`, `MOVE-371`, `MOVE-401`, and `CFG-053`.

`cutoverGuarantee` leaves `HookDeclaration`. `cutoverGraceMillis` and
`requireLinearisableCutover` leave the migration policy of `RATE-011` and `CFG-050`, and
`guaranteeTooWeak` leaves the closed cause set of `ERR-050`. The permanent event name
`sharder.migration.advisory` leaves `OBS-020`, and the `migration.planned` payload names the handoff
count and the policy rather than a guarantee level.

Two requirements are amended rather than withdrawn. `MOVE-311` bounded the quiesce window by
`cutoverGraceMillis` plus the commit deadline and now bounds it by `commitDeadlineMillis` alone.
`MOVE-381` stated its guarantees under a `linearisable` declaration and now states them
unconditionally, and `MOVE-391` states its best-effort properties without naming a declaration.

The consequence is stated rather than implied. The Provided guarantees section of
[`../10-specification.md`](../10-specification.md) says that an integrator whose store offers no
single-winner write over a record both the source and the destination read cannot use orchestrated
migration and moves a shard outside the library, and
[`../99-roadmap.md`](../99-roadmap.md) repeats it where v0.2 is described.

The library does not check the claim. `MOVE-321` is an obligation on a hook the integrator writes,
and no declaration the hook makes about its own store is verifiable from inside the library, so
there is nothing for `plan` to refuse and no cause for it to carry.

## Consequences

An integrator whose store has no compare-and-set primitive, no conditional write, and no lease
cannot use the handoff coordinator. `MOVE-321` told them that already, and the `advisory` mode gave
them a way to proceed while telling them in every event that the guarantee they needed was absent.

`MOVE-233` and `MOVE-234` simplify. A re-observation at `cutover` follows an observation that
reports no record, and `MOVE-331` requires a fresh successful `quiesce` before the next
`commitCutover`, which is now the whole rule; the refusal `MOVE-235` imposed under `advisory` has no
condition left to test. That statement moves into `MOVE-236`, where the other resumption rules are.

The quiesce window shortens. `MOVE-311` bounded it by a grace interval plus a deadline and now
bounds it by the deadline, so the interval in which neither node accepts a write is the shortest the
design admits.

One permanent event name is spent rather than three. `OBS-020` fixes names for the life of the
major version, and `sharder.migration.advisory` is withdrawn before anything reads it.

The handoff scenarios lose nothing. No shipped scenario declared `advisory` hooks except
`undetermined-resolves-both-ways`, which exercised the `MOVE-235` refusal, and that scenario keeps
its other resolutions.

## Alternatives

Keeping the mode and making `requireLinearisableCutover` default to false. Rejected outright: it
inverts the safe default and offers a mode that negates `MOVE-321` to an integrator who read no
further than the policy record.

Keeping `cutoverGuarantee` with `linearisable` as its only value, so that a future mode is additive.
Rejected because an enumeration of one value is a member every hook author has to write and no
reader can act on, and because adding a value to a declaration record is itself additive when the
mode that needs it arrives.

Keeping the declaration and having `plan` refuse anything but `linearisable`. Rejected because the
refusal is unverifiable. A hook that declares `linearisable` over a store that cannot serialise the
write passes the check and provides nothing, so the check buys a false assurance rather than a
guarantee.

Keeping `cutoverGraceMillis` as a wait the integrator may configure under the linearisable mode.
Rejected because `MOVE-331` already requires a fresh `quiesce` where the lease has expired, and a
grace interval under a single-winner write holds the shard in the window where neither node accepts
a write for no gain.
