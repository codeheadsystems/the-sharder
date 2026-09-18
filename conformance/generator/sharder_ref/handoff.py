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
    # `MOVE-021`, the five rows leaving `failed`.  Admitted only from `reobserve`, under
    # `MOVE-233`, and only for the failure kind `undetermined`.
    ("failed", "reobserveRecordForHandoff"): "verifying",
    ("failed", "reobserveRecordAnotherOwnerOrEpoch"): "aborting",
    ("failed", "reobserveNoRecordSourceQuiesced"): "cutover",
    ("failed", "reobserveNoRecordDestinationPrepared"): "transferring",
    ("failed", "reobserveNoRecordDestinationNotPrepared"): "preparing",
}

# `MOVE-233`: the one failure kind a re-observation admits.  `MOVE-237` states why the other
# three do not: each names a duty outside the library.
REOBSERVABLE_KIND = "undetermined"

# `MOVE-231` and `RATE-011`: `recover` retries an `unavailable` observation this many times
# before a handoff in `cutover` reaches `failed`.
MAX_ATTEMPTS_PER_STEP = 5
RETRY_BACKOFF_BASE_MILLIS = 1000
RETRY_BACKOFF_CAP_MILLIS = 60000

# `CFG-050`: the two settings the commit horizon of `MOVE-332` is computed from.
COMMIT_DEADLINE_MILLIS = 30000
QUIESCE_LEASE_MARGIN_MILLIS = 1000

# The triggers that follow a call to `commitCutover`, which `MOVE-333` admits only inside the
# commit horizon.  An undetermined outcome is one the call reached the deadline without.
COMMIT_TRIGGERS = ("cutoverCommitted", "commitUndetermined")


def retry_backoff(attempt):
    """`RATE-051`, by integer arithmetic."""
    return min(RETRY_BACKOFF_BASE_MILLIS * (2 ** (attempt - 1)), RETRY_BACKOFF_CAP_MILLIS)

# `MOVE-211`, exactly.  observation -> resumed state.  The first two rows read the rebase
# interval of `MOVE-102`: a record belongs to a handoff when its owner is the destination and its
# epoch lies above the plan's source epoch and at or below the plan's target epoch.
RECOVERY = [
    ("recordBelongingToHandoff", "verifying"),
    ("recordNotBelongingToHandoff", "aborting"),
    ("noRecordSourceQuiesced", "cutover"),
    ("noRecordDestinationPrepared", "transferring"),
    ("noRecordDestinationNotPrepared", "preparing"),
]

# The trigger each recovery row corresponds to when `reobserve` applies it, under `MOVE-234`.
REOBSERVE_TRIGGER = {
    "verifying": "reobserveRecordForHandoff",
    "aborting": "reobserveRecordAnotherOwnerOrEpoch",
    "cutover": "reobserveNoRecordSourceQuiesced",
    "transferring": "reobserveNoRecordDestinationPrepared",
    "preparing": "reobserveNoRecordDestinationNotPrepared",
}

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
    def __init__(self, handoff_id, shard, source, destination, target_epoch=None):
        self.id = handoff_id
        self.shard = shard
        self.source = source
        self.destination = destination
        self.state = "planned"
        self.failure_kind = None
        self.cutover_record = None
        self.history = []
        # `MOVE-099`: a handoff carries its own target epoch, which a rebase advances while the
        # handoff is short of `cutover` and never afterwards.  It is the `toEpoch` of the handoff
        # context in `MOVE-111`.
        self.target_epoch = target_epoch
        # `MOVE-231`: consecutive `unavailable` answers from `observe` during `recover`.
        self.observe_attempts = 0
        # `MOVE-332`: the reading taken immediately before the last successful `quiesce`, and the
        # `leaseMillis` that call answered with.  Both are discarded when the handoff leaves
        # `cutover`.
        self.quiesce_instant = None
        self.lease_millis = None

    def commit_horizon(self, margin):
        """`MOVE-332`: the quiesce instant plus the lease, less the margin."""
        if self.quiesce_instant is None:
            return None
        return self.quiesce_instant + self.lease_millis - margin

    def apply(self, trigger, at=None):
        key = (self.state, trigger)
        if key not in TRANSITIONS:
            raise HandoffError("no transition from %s on %s (MOVE-021)" % (self.state, trigger))
        target = TRANSITIONS[key]
        self.history.append({"from": self.state, "to": target, "trigger": trigger, "at": at})
        if target == "failed":
            self.failure_kind = FAILURE_KIND_FOR_TRIGGER.get(key)
        if self.state == "cutover" and target != "cutover":
            # `MOVE-332`: the quiesce instant and the lease are discarded when the handoff leaves
            # `cutover`, so a resumption there takes a fresh `quiesce` under `MOVE-331`.
            self.quiesce_instant = None
            self.lease_millis = None
        self.state = target
        return target


class Plan:
    """A migration plan over one ownership delta."""

    def __init__(self, source_epoch, target_epoch, topology_id, handoffs, policy=None):
        self.source_epoch = source_epoch
        self.target_epoch = target_epoch
        self.topology_id = topology_id
        # `MOVE-092`: the snapshot a later install recorded, and nothing else.
        self.rebase_pending = None
        self.handoffs = {h.id: h for h in handoffs}
        for handoff in self.handoffs.values():
            if handoff.target_epoch is None:
                handoff.target_epoch = target_epoch
        self.policy = dict({"maxConcurrentHandoffs": 4,
                            "maxConcurrentPerSourceNode": 1,
                            "maxConcurrentPerDestinationNode": 1,
                            "initialStepBudget": 1,
                            "commitDeadlineMillis": COMMIT_DEADLINE_MILLIS,
                            "quiesceLeaseMarginMillis": QUIESCE_LEASE_MARGIN_MILLIS},
                           **(policy or {}))
        self.events = []

    def state(self, handoff_id):
        return self.handoffs[handoff_id].state

    def rebase_interval(self):
        """`MOVE-102`: the epochs above the source epoch and at or below the target epoch."""
        return {"above": self.source_epoch, "throughInclusive": self.target_epoch}

    def record_belongs(self, handoff, record):
        """`MOVE-102`: whether a cutover record belongs to a handoff of this plan."""
        if record is None:
            return False
        return (record.get("shardId") == handoff.shard
                and record.get("owner") == handoff.destination
                and self.source_epoch < record.get("epoch", 0) <= self.target_epoch)

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
        if self.rebase_pending is not None and trigger in (
                "admittedByRatePolicy", "residueAtOrBelowThreshold"):
            # `MOVE-093`: while a rebase is pending no handoff leaves `planned` and none reaches
            # `cutover`.  Every other transition stays available so that work already begun
            # finishes.
            return {"outcome": "idle", "reason": "rebasePending"}
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
        if trigger in COMMIT_TRIGGERS and handoff.state == "cutover":
            admissible, horizon = self.commit_admissible(handoff_id, at)
            if not admissible:
                # `MOVE-331` and `MOVE-333`: the commit window no longer fits inside the lease,
                # so `commitCutover` is not called and the coordinator quiesces again.
                self.events.append({"event": "sharder.migration.quiesce_expired",
                                    "handoff": handoff.id, "shard": handoff.shard,
                                    "leaseMillis": handoff.lease_millis,
                                    "marginMillis": self.policy["quiesceLeaseMarginMillis"],
                                    "commitHorizon": horizon, "at": at})
                return {"outcome": "idle", "reason": "quiesceExpired", "commitHorizon": horizon}
        before = handoff.state
        after = handoff.apply(trigger, at)
        self.events.append({"event": "sharder.migration.state_changed", "handoff": handoff.id,
                            "shard": handoff.shard, "from": before, "to": after,
                            "trigger": trigger, "at": at})
        if after in TERMINAL:
            return {"outcome": "settled", "id": handoff.id, "terminalState": after,
                    "failureKind": handoff.failure_kind}
        return {"outcome": "advanced", "id": handoff.id, "fromState": before, "toState": after}

    def quiesce(self, handoff_id, lease_millis, at):
        """`MOVE-331` through `MOVE-336`: a successful `quiesce` and the horizon it fixes.

        `at` is the quiesce instant of `MOVE-332`, which the coordinator reads immediately before
        it calls the hook rather than after the hook returns, so the request's transit and the
        source's own processing fall inside the interval the coordinator measures.
        """
        handoff = self.handoffs[handoff_id]
        if handoff.state != "cutover":
            raise HandoffError("quiesce is called in `cutover`, not %s (MOVE-021)" % handoff.state)
        margin = self.policy["quiesceLeaseMarginMillis"]
        deadline = self.policy["commitDeadlineMillis"]
        if lease_millis <= margin + deadline:
            # `MOVE-336`: no reading admits a commit, so this is a failed quiesce rather than a
            # call to repeat.  The handoff aborts and compensates.
            before = handoff.state
            after = handoff.apply("quiesceFailedNoRecord", at)
            self.events.append({"event": "sharder.migration.state_changed",
                                "handoff": handoff.id, "shard": handoff.shard,
                                "from": before, "to": after,
                                "trigger": "quiesceFailedNoRecord", "at": at})
            return {"outcome": "advanced", "reason": "leaseBelowCommitWindow",
                    "leaseMillis": lease_millis, "state": after}
        handoff.quiesce_instant = at
        handoff.lease_millis = lease_millis
        return {"outcome": "quiesced", "quiesceInstant": at, "leaseMillis": lease_millis,
                "commitHorizon": handoff.commit_horizon(margin), "state": handoff.state}

    def commit_admissible(self, handoff_id, at):
        """`MOVE-333`: the reading at the call plus the commit deadline, against the horizon."""
        handoff = self.handoffs[handoff_id]
        margin = self.policy["quiesceLeaseMarginMillis"]
        horizon = handoff.commit_horizon(margin)
        if horizon is None:
            return False, None
        return at + self.policy["commitDeadlineMillis"] <= horizon, horizon

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

    def resolve_observation(self, handoff, observation):
        """Reduce an observation to a row of `MOVE-211`.

        A symbolic observation names its row directly.  An observation given as a record and
        three booleans is classified against the rebase interval of `MOVE-102`, which is the
        amendment a rebase makes to the mapping.
        """
        mapping = dict(RECOVERY)
        if isinstance(observation, str):
            if observation not in mapping:
                raise HandoffError("unknown observation %r (MOVE-211)" % (observation,))
            return observation, mapping[observation]
        record = observation.get("cutoverRecord")
        if record is not None:
            if self.record_belongs(handoff, record):
                return "recordBelongingToHandoff", "verifying"
            return "recordNotBelongingToHandoff", "aborting"
        if observation.get("sourceQuiesced"):
            return "noRecordSourceQuiesced", "cutover"
        if observation.get("destinationPrepared"):
            return "noRecordDestinationPrepared", "transferring"
        return "noRecordDestinationNotPrepared", "preparing"

    def recover(self, observations, at=None):
        """`MOVE-211`, `MOVE-212`, `MOVE-231`, and `MOVE-232`.

        An answer of `unavailable` is retried across successive calls rather than failing the
        handoff on the first one, and only a handoff in `cutover` reaches `failed` once the
        attempts are spent.  `recover` never sleeps; it reports the backoff and returns.
        """
        report = {"resolved": [], "unresolved": [], "failed": [], "retryAfterMillis": 0}
        backoffs = []
        for handoff_id in sorted(observations):
            observation = observations[handoff_id]
            handoff = self.handoffs[handoff_id]
            if handoff.state in TERMINAL:
                continue
            if observation == "unavailable":
                handoff.observe_attempts += 1
                if handoff.observe_attempts < MAX_ATTEMPTS_PER_STEP:
                    report["unresolved"].append(handoff_id)
                    backoffs.append(retry_backoff(handoff.observe_attempts))
                    continue
                observation = "undetermined"                          # attempts are spent
            if observation == "undetermined":
                if handoff.state == "cutover":
                    handoff.state = "failed"                          # `MOVE-231`
                    handoff.failure_kind = "undetermined"
                    handoff.history.append({"from": "cutover", "to": "failed",
                                            "trigger": "observeUndetermined", "at": at})
                    report["failed"].append(handoff_id)
                else:
                    report["unresolved"].append(handoff_id)
                continue
            handoff.observe_attempts = 0
            trigger, resumed_state = self.resolve_observation(handoff, observation)
            handoff.state = resumed_state
            if resumed_state == "verifying" and not isinstance(observation, str):
                # `MOVE-212`: a handoff resumed at `verifying` takes the record's epoch.
                handoff.target_epoch = observation["cutoverRecord"]["epoch"]
            handoff.history.append({"from": "recovered", "to": handoff.state,
                                    "trigger": trigger, "at": at})
            report["resolved"].append(handoff_id)
        report["retryAfterMillis"] = min(backoffs) if backoffs else 0
        return report

    def reobserve(self, handoff_id, observation, at=None):
        """`MOVE-233` through `MOVE-238`: one further `observe` for one named handoff."""
        handoff = self.handoffs[handoff_id]
        if handoff.state != "failed":
            return {"outcome": "refused", "reason": "notFailed", "state": handoff.state}
        if handoff.failure_kind != REOBSERVABLE_KIND:
            # `MOVE-237`: the other three kinds name a duty outside the library.
            return {"outcome": "refused", "reason": "failureKindNotRecoverable",
                    "state": handoff.state, "failureKind": handoff.failure_kind}
        if observation in ("unavailable", "undetermined"):
            # `MOVE-234`: the handoff stays where it is and no state changes.
            return {"outcome": "unresolved", "state": handoff.state,
                    "failureKind": handoff.failure_kind}
        trigger, resumed_state = self.resolve_observation(handoff, observation)
        handoff.failure_kind = None
        handoff.apply(REOBSERVE_TRIGGER[resumed_state], at)
        if resumed_state == "verifying" and not isinstance(observation, str):
            handoff.target_epoch = observation["cutoverRecord"]["epoch"]   # `MOVE-212`
        return {"outcome": "resumed", "state": handoff.state,
                "observationRow": trigger}

    def supersede(self, epoch, at=None):
        """`MOVE-091`: abort everything short of `cutover` and let the rest finish."""
        aborted, finishing = [], []
        for handoff in self.handoffs.values():
            if handoff.state in TERMINAL:
                continue
            if handoff.state in ("cutover", "verifying", "cleanup"):
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

    def on_snapshot_installed(self, topology_id, epoch, at=None, comparable=True):
        """`MOVE-091` and `MOVE-092`.

        A differing `topologyId`, or shard identity that `TOPO-231` refuses, supersedes the plan.
        Every other epoch above the plan's target marks the plan rebase pending and does nothing
        else: no abort, no preference list, no hook.  An epoch at or below the target leaves the
        plan alone.
        """
        if topology_id != self.topology_id or not comparable:
            return dict(self.supersede(epoch, at), rebasePending=None)
        if epoch <= self.target_epoch:
            return {"superseded": False, "aborted": [], "finishing": [],
                    "rebasePending": self.rebase_pending}
        if self.rebase_pending is None or epoch > self.rebase_pending:
            self.rebase_pending = epoch
        self.events.append({"event": "sharder.migration.rebase_pending", "epoch": epoch,
                            "targetEpoch": self.target_epoch, "at": at})
        return {"superseded": False, "aborted": [], "finishing": [],
                "rebasePending": self.rebase_pending}

    def rebase(self, topology_id, epoch, replica_sets, at=None, comparable=True):
        """`MOVE-094` through `MOVE-099`: move the plan onto a newer snapshot, per handoff.

        `replica_sets` maps each shard the target snapshot enumerates to its replica set under
        that snapshot, which is what `MOVE-096` classifies each handoff against.
        """
        if topology_id != self.topology_id:
            return {"outcome": "refused", "condition": {"code": 401, "name": "planRefused",
                                                        "cause": "topologyMismatch"}}
        if not comparable:
            return {"outcome": "refused", "condition": {"code": 401, "name": "planRefused",
                                                        "cause": "incomparableShards"}}
        if epoch <= self.source_epoch:
            return {"outcome": "refused", "condition": {"code": 401, "name": "planRefused",
                                                        "cause": "epochNotAdvancing"}}
        rebased, aborted, unchanged = [], [], []
        for handoff in sorted(self.handoffs.values(), key=lambda h: h.id):
            if handoff.state in TERMINAL or handoff.state in ("cutover", "verifying", "cleanup",
                                                              "aborting"):
                unchanged.append(handoff.id)                          # `MOVE-099`
                continue
            replicas = replica_sets.get(handoff.shard)
            holds = (replicas is not None
                     and handoff.destination in replicas
                     and handoff.source not in replicas)
            if holds:
                handoff.target_epoch = epoch                          # `MOVE-097`
                handoff.history.append({"from": handoff.state, "to": handoff.state,
                                        "trigger": "rebased", "at": at})
                rebased.append(handoff.id)
            else:
                handoff.apply("abort", at)                            # `MOVE-098`
                aborted.append(handoff.id)
        report = {"fromEpoch": self.target_epoch, "toEpoch": epoch, "rebased": rebased,
                  "aborted": aborted, "unchanged": unchanged}
        self.target_epoch = epoch
        if self.rebase_pending is not None and self.rebase_pending <= epoch:
            self.rebase_pending = None
        self.events.append(dict({"event": "sharder.migration.rebased", "at": at}, **report))
        return {"outcome": "rebased", "report": report,
                "rebaseInterval": self.rebase_interval()}


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
