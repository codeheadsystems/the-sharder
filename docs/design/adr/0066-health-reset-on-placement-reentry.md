# 0066. Health reset on placement set re-entry

Status: accepted. Date: 2026-09-17.

## Context

[`0016`](0016-node-health-state-machine.md) fixes the health state machine around a fleet whose
nodes are long lived. `HEALTH-006` keys a health entry by node identity, keeps it across an epoch
change, and permits eviction only once the identity has been absent for longer than
`ejectionResetMillis`, which defaults to 600,000 milliseconds. `HEALTH-054` forbids leaving
`unavailable` on a signal, so only the elapse of `ejectionMillis` reaches `probation`, and
`HEALTH-050` doubles that interval on each ejection to a ceiling of 300,000 milliseconds.

For a storage fleet that is the behaviour the machine exists to give. A node that flaps is attempted
less often on each cycle, and a stale success does not cancel an ejection.

An edge fleet churns differently. A node is re-imaged and returns under the identity it left with,
because the identity is a DNS name or a rack slot rather than a machine. The sequence is: the node
degrades, callers eject it and raise its ejection count, an operator pulls it from the topology, it
is re-imaged, and it returns within minutes as a machine that has never served a request. It arrives
carrying its predecessor's entry: still `unavailable`, with the ejection count and the lengthened
ejection interval intact. A re-image is faster than `ejectionResetMillis`, so the eviction the
health entry rule permits never fires, and every caller in the fleet skips a healthy machine for up
to five minutes for failures it did not cause.

The library has the signal it needs. `HEALTH-016` delivers `onSnapshotInstalled` with every snapshot
the library installs, and the placement set it carries is the only thing a `HealthView` learns about
a topology. An identity present in the arriving placement set and absent from the one before it has
re-entered, and that comparison costs one set difference per installation.

Health state is caller-local and is never serialised, so the judgement is made independently by each
caller. Two callers that observe different installation sequences reach different entries for one
identity, which is the variation `FAIL-010` already permits and which changes no preference list.

## Decision

`HEALTH-007` states the rule, and `HEALTH-055` carries the parameter `resetOnPlacementReentry` with
a default of false.

An identity re-enters the placement set where the snapshot `onSnapshotInstalled` delivers holds it
and the snapshot delivered before it did not. Where the parameter is true, the view discards the
health entry of an identity that re-enters, so the identity holds `unknown` under `HEALTH-004` with
no window, no consecutive failure counter, no ejection count, and no probe counter. Where the
parameter is false, the entry survives the absence and `HEALTH-006` alone governs its eviction. A
view that ignores `onSnapshotInstalled`, which `HEALTH-016` permits, applies neither.

The default preserves the behaviour the storage deployment has, and every vector generated before
this record. An edge integrator sets the parameter, and the choice is stated in its configuration
rather than implied by its churn rate.

## Consequences

A re-imaged edge node is attemptable on arrival at every caller that watched it leave. A caller that
polls less often than the operator's drain and return cycle never observes the absence and keeps the
entry, so the fleet converges over a poll interval rather than at an instant. Nothing about a
routing decision depends on the difference, because health state filters an attempt sequence and
never reorders a preference list.

An authority that cycles a node in and out of the placement set can clear an ejection under the
parameter. That is a control plane with write access to the topology deciding what a caller's health
view holds, which is a narrower power than the one it already has over placement, and it is why the
default is false.

The parameter is the fourteenth of the built-in machine, against the thirteen
[`0016`](0016-node-health-state-machine.md) already records as more configuration surface than a
small core would like. It is a boolean rather than a duration, and it is the only one whose value
depends on how an operator replaces hardware rather than on how a node fails.

`HEALTH-034` counts the ejected over the placement set, so discarding an entry that held
`unavailable` lowers that count and admits an ejection the ceiling would otherwise have refused.
That is the same arithmetic a node leaving the placement set already performs.

## Alternatives

Evicting an entry on absence alone, with no re-entry test. Rejected because `HEALTH-006` already
permits it after `ejectionResetMillis` and the edge case is faster than that interval, and because
shortening the interval would also shorten the flap containment that the interval exists for.

Resetting unconditionally, with no parameter. Rejected because it changes the behaviour of every
existing deployment and every vector, and because a storage fleet that drains a node for an hour of
maintenance and returns it wants the ejection history it had.

Resetting only the ejection count, leaving the state. Rejected because the state is what skips the
node: an entry left in `unavailable` with a reset count is still skipped for a full
`baseEjectionMillis`, which is the outcome the record is written to avoid.

Keying a health entry by identity and epoch of first sight, so that a returning node is a different
key. Rejected because `HEALTH-006` requires an entry to survive an epoch change, which is the
property that makes health state useful across a rebalance, and because the key would then differ
between callers that first saw the node at different epochs.

A signal that the integrator sends, such as a `report` outcome meaning "this node is new". Rejected
because the library already learns it from the topology, and because an integrator that knows a node
is new is the same integrator that published the document saying so.
