"""The handoff state machine, per `MOVE-001` through `MOVE-491`.

The transition table below is `MOVE-021` transcribed as data, and the recovery mapping is
`MOVE-211` transcribed as data.  A scenario programs the hook results it wants and the
coordinator computes the state sequence, so no expected sequence in a scenario file is written by
hand.

The coordinator is passive: it advances only from a `step`, a `recover`, an `abort`, or an
`onSnapshotInstalled` that a scenario performs.
"""

STATES = ["planned", "preparing", "transferring", "catchingUp", "cutover", "verifying",
          "cleanup", "complete", "aborting", "aborted", "failed"]

TERMINAL = ("complete", "aborted", "failed")

FAILURE_KINDS = ("unverified", "residue", "undetermined", "rollbackFailed")

# `MOVE-021`, exactly.  (from, trigger) -> to
TRANSITIONS = {
    ("planned", "admittedByRatePolicy"): "preparing",
    ("planned", "abort"): "aborted",
    ("preparing", "prepareSuccess"): "transferring",
    ("preparing", "abort"): "aborting",
    ("preparing", "attemptsExhausted"): "aborting",
    ("transferring", "noBulkRemaining"): "catchingUp",
    ("transferring", "abort"): "aborting",
    ("transferring", "attemptsExhausted"): "aborting",
    ("catchingUp", "residueAtOrBelowThreshold"): "cutover",
    ("catchingUp", "residueAboveRetransferThreshold"): "transferring",
    ("catchingUp", "abort"): "aborting",
    ("catchingUp", "attemptsExhausted"): "aborting",
    ("cutover", "cutoverCommitted"): "verifying",
    ("cutover", "quiesceFailedNoRecord"): "aborting",
    ("cutover", "commitUndetermined"): "failed",
    ("verifying", "verifySuccess"): "cleanup",
    ("verifying", "verifyMismatch"): "failed",
    ("verifying", "attemptsExhausted"): "failed",
    ("cleanup", "cleanupSuccess"): "complete",
    ("cleanup", "cleanupWaived"): "complete",
    ("cleanup", "attemptsExhausted"): "failed",
    ("aborting", "rollbackSuccess"): "aborted",
    ("aborting", "attemptsExhausted"): "failed",
}

# `MOVE-211`, exactly.  observation -> resumed state
RECOVERY = [
    ("recordOwnedByDestinationAtTargetEpoch", "verifying"),
    ("recordNamesAnotherOwnerOrEpoch", "aborting"),
    ("noRecordSourceQuiesced", "cutover"),
    ("noRecordDestinationPrepared", "transferring"),
    ("noRecordDestinationNotPrepared", "preparing"),
]

FAILURE_KIND_FOR_TRIGGER = {
    ("verifying", "verifyMismatch"): "unverified",
    ("verifying", "attemptsExhausted"): "unverified",
    ("cutover", "commitUndetermined"): "undetermined",
    ("cleanup", "attemptsExhausted"): "residue",
    ("aborting", "attemptsExhausted"): "rollbackFailed",
}


class HandoffError(Exception):
    pass


class Handoff:
    def __init__(self, handoff_id, shard, source, destination):
        self.id = handoff_id
        self.shard = shard
        self.source = source
        self.destination = destination
        self.state = "planned"
        self.failure_kind = None
        self.cutover_record = None
        self.history = []

    def apply(self, trigger, at=None):
        key = (self.state, trigger)
        if key not in TRANSITIONS:
            raise HandoffError("no transition from %s on %s (MOVE-021)" % (self.state, trigger))
        target = TRANSITIONS[key]
        self.history.append({"from": self.state, "to": target, "trigger": trigger, "at": at})
        if target == "failed":
            self.failure_kind = FAILURE_KIND_FOR_TRIGGER.get(key)
        self.state = target
        return target


class Plan:
    """A migration plan over one ownership delta."""

    def __init__(self, source_epoch, target_epoch, topology_id, handoffs, policy=None):
        self.source_epoch = source_epoch
        self.target_epoch = target_epoch
        self.topology_id = topology_id
        self.handoffs = {h.id: h for h in handoffs}
        self.policy = dict({"maxConcurrentHandoffs": 4,
                            "maxConcurrentPerSourceNode": 1,
                            "maxConcurrentPerDestinationNode": 1,
                            "requireLinearisableCutover": True}, **(policy or {}))
        self.events = []

    def state(self, handoff_id):
        return self.handoffs[handoff_id].state

    def summary(self):
        counts = {}
        for handoff in self.handoffs.values():
            counts[handoff.state] = counts.get(handoff.state, 0) + 1
        return counts

    def active_count(self):
        return sum(1 for h in self.handoffs.values()
                   if h.state not in TERMINAL and h.state != "planned")

    def admissible(self, handoff):
        """`MOVE-021` plus the concurrency bounds of `RATE-011`."""
        if handoff.state != "planned":
            return True
        if self.active_count() >= self.policy["maxConcurrentHandoffs"]:
            return False
        per_source = sum(1 for h in self.handoffs.values()
                         if h.source == handoff.source and h.state not in TERMINAL
                         and h.state != "planned")
        if per_source >= self.policy["maxConcurrentPerSourceNode"]:
            return False
        per_destination = sum(1 for h in self.handoffs.values()
                              if h.destination == handoff.destination
                              and h.state not in TERMINAL and h.state != "planned")
        return per_destination < self.policy["maxConcurrentPerDestinationNode"]

    def step(self, handoff_id, trigger, at=None, pressure="none"):
        handoff = self.handoffs[handoff_id]
        if handoff.state in TERMINAL:
            raise HandoffError("%s is terminal (MOVE-031)" % handoff.state)
        if trigger == "admittedByRatePolicy":
            if pressure != "none":
                # `RATE-081` and `RATE-091`: neither level moves a handoff out of `planned`.
                return {"outcome": "idle", "reason": "pressure:%s" % pressure}
            if not self.admissible(handoff):
                return {"outcome": "idle", "reason": "concurrencyBound"}
        if pressure == "hard" and trigger in ("noBulkRemaining",
                                              "residueAboveRetransferThreshold"):
            # `RATE-091`: `transfer` and `catchUp` are withheld; later hooks still run.
            return {"outcome": "idle", "reason": "pressure:hard"}
        before = handoff.state
        after = handoff.apply(trigger, at)
        self.events.append({"event": "sharder.migration.state_changed", "handoff": handoff.id,
                            "shard": handoff.shard, "from": before, "to": after,
                            "trigger": trigger, "at": at})
        if after in TERMINAL:
            return {"outcome": "settled", "id": handoff.id, "terminalState": after,
                    "failureKind": handoff.failure_kind}
        return {"outcome": "advanced", "id": handoff.id, "fromState": before, "toState": after}

    def abort(self, handoff_id, reason, at=None, record_exists=False):
        """`MOVE-411` through `MOVE-491`."""
        handoff = self.handoffs[handoff_id]
        if handoff.state in ("aborting", "aborted", "failed"):
            return {"outcome": "noop", "state": handoff.state}      # `MOVE-491`
        if handoff.state in ("verifying", "cleanup", "complete"):
            return {"outcome": "refused", "reason": "abortNotAdmitted", "state": handoff.state}
        if handoff.state == "cutover":
            # `MOVE-431`: admitted only while no cutover record exists, checked through `observe`.
            if record_exists:
                return {"outcome": "refused", "reason": "cutoverRecordExists",
                        "state": handoff.state}
            return {"outcome": "advanced", "state": handoff.apply("quiesceFailedNoRecord", at)}
        return {"outcome": "advanced", "state": handoff.apply("abort", at)}

    def recover(self, observations, at=None):
        """`MOVE-211`: assign a state from each non-terminal handoff's observation."""
        resumed = {}
        mapping = dict(RECOVERY)
        for handoff_id, observation in observations.items():
            handoff = self.handoffs[handoff_id]
            if handoff.state in TERMINAL:
                continue
            if observation == "unavailable" and handoff.state == "cutover":
                handoff.state = "failed"                             # `MOVE-231`
                handoff.failure_kind = "undetermined"
                resumed[handoff_id] = "failed"
                continue
            if observation not in mapping:
                raise HandoffError("unknown observation %r (MOVE-211)" % (observation,))
            handoff.state = mapping[observation]
            handoff.history.append({"from": "recovered", "to": handoff.state,
                                    "trigger": observation, "at": at})
            resumed[handoff_id] = handoff.state
        return resumed

    def on_snapshot_installed(self, topology_id, epoch, at=None):
        """`MOVE-091`: supersede unless the epoch is the plan's source or target."""
        if topology_id == self.topology_id and epoch in (self.source_epoch, self.target_epoch):
            return {"superseded": False, "aborted": [], "finishing": []}
        aborted, finishing = [], []
        for handoff in self.handoffs.values():
            if handoff.state in TERMINAL:
                continue
            if handoff.state in ("cutover", "verifying", "cleanup"):
                # `MOVE-091`: a handoff at cutover or beyond runs to a terminal state.
                finishing.append(handoff.id)
            elif handoff.state == "aborting":
                # Compensation is already running; `MOVE-491` makes a further abort a no-op.
                finishing.append(handoff.id)
            else:
                handoff.apply("abort", at)
                aborted.append(handoff.id)
        self.events.append({"event": "sharder.migration.superseded", "epoch": epoch,
                            "abortedCount": len(aborted), "finishingCount": len(finishing),
                            "at": at})
        return {"superseded": True, "aborted": sorted(aborted), "finishing": sorted(finishing)}


def ownership_delta(before, after, factor_of):
    """`TOPO-211`: the shards whose ordered replica set differs, with nodes gained and lost.

    The replica set is the entries whose role is `replica` under `REPL-017`, which is the achieved
    replica count `r` of `REPL-020` and not the configured factor.

    `TOPO-213` fixes the order: the shards the second snapshot enumerates, in the order `shards`
    gives for it under `PLACE-031`, then the shards only the first snapshot enumerates, in the order
    `shards` gives for that one.  Sorting the identifiers as octets would read slot 10 before slot 2.
    """
    from . import placement, routing as route_module

    order_after = placement.shards(after)
    order_before = placement.shards(before)
    shards_before = set(order_before)
    shards_after = set(order_after)
    ordered = order_after + [s for s in order_before if s not in shards_after]
    rows = []
    for shard in ordered:
        def replicas(snapshot, present):
            if not present:
                return []
            ordering = placement.candidates_for_shard(snapshot, shard, snapshot.placement_set)
            entries, r, _, _ = route_module.build_preference_list(
                snapshot, ordering, factor_of(snapshot))
            return [e["node"] for e in entries[:r]]

        was = replicas(before, shard in shards_before)
        now = replicas(after, shard in shards_after)
        if was != now:
            rows.append({
                "shard": shard,
                "before": was,
                "after": now,
                "gained": sorted(set(now) - set(was)),
                "lost": sorted(set(was) - set(now)),
            })
    return rows
