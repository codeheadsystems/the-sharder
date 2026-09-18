"""The built-in health state machine, per `HEALTH-001` through `HEALTH-055`.

Every computation is unsigned integer arithmetic.  The state machine is caller-local: it filters
a preference list into an attempt sequence under `HEALTH-005` and never reorders one.
"""

DEFAULTS = {
    "windowMillis": 30000,
    "bucketCount": 10,
    "minimumSamples": 20,
    "failureRatePercent": 50,
    "consecutiveFailureThreshold": 5,
    "baseEjectionMillis": 30000,
    "maxEjectionMillis": 300000,
    "probationMillis": 30000,
    "probationDivisor": 16,
    "outlierMarginPercent": 30,
    "outlierMinimumNodes": 5,
    "maxEjectionPercent": 50,
    "ejectionResetMillis": 600000,
    "resetOnPlacementReentry": False,
}

FAILURE_OUTCOMES = ("failure", "timeout", "refused")


class NodeHealth:
    def __init__(self):
        self.state = "unknown"
        self.buckets = []          # (bucketEnd, successes, failures)
        self.consecutive = 0
        self.ejections = 0
        self.ejected_at = None
        self.probation_since = None
        self.probe_counter = 0
        self.available_since = None
        self.newest_signal = None
        self.last_failure_at = None

    def totals(self, now, window):
        successes = failures = 0
        for end, s, f in self.buckets:
            if end >= now - window:
                successes += s
                failures += f
        return successes, failures

    def failure_percent(self, now, window):
        """`HEALTH-023`: `failures * 100 / total`, truncating, and 0 where the total is 0."""
        successes, failures = self.totals(now, window)
        total = successes + failures
        return 0 if total == 0 else failures * 100 // total


class HealthView:
    """The built-in `HealthView` of `HEALTH-014`."""

    def __init__(self, parameters=None, placement_set=None, event_sink=None):
        self.p = dict(DEFAULTS, **(parameters or {}))
        self.nodes = {}
        self.placement_set = None if placement_set is None else set(placement_set)
        self.events = [] if event_sink is None else event_sink

    @property
    def placement_set_size(self):
        return 0 if self.placement_set is None else len(self.placement_set)

    def on_snapshot_installed(self, placement_set, at=None):
        """`HEALTH-016`, and the re-entry rule of `HEALTH-007`.

        The argument is the placement set of the snapshot being installed, as node identities.  A
        view that is never told a placement set runs no outlier ejection and refuses no transition,
        which is what `HEALTH-016` states for a view that ignores the call.
        """
        arriving = set(placement_set)
        if self.p["resetOnPlacementReentry"] and self.placement_set is not None:
            for node_id in sorted(arriving - self.placement_set):
                entry = self.nodes.pop(node_id, None)
                if entry is not None and entry.state != "unknown":
                    self.events.append({"event": "sharder.health.transition", "node": node_id,
                                        "from": entry.state, "to": "unknown",
                                        "trigger": "placementReentry", "at": at})
        self.placement_set = arriving

    def entry(self, node_id):
        return self.nodes.setdefault(node_id, NodeHealth())

    def state_of(self, node_id):
        return self.nodes[node_id].state if node_id in self.nodes else "unknown"

    def _transition(self, node_id, entry, new_state, trigger, now):
        if entry.state == new_state:
            return
        if (new_state == "unavailable" and entry.state != "probation"
                and self.placement_set is not None):
            # `HEALTH-034`: the ceiling on concurrently ejected nodes.  The count and the set size
            # are both over the placement set, so an entry held for an identity outside it neither
            # refuses an ejection nor relaxes the ceiling.  A view that has been told no placement
            # set refuses no transition, which is what `HEALTH-016` states.
            ejected = sum(1 for other_id, e in self.nodes.items()
                          if e.state == "unavailable" and self._in_placement_set(other_id))
            if (ejected + 1) * 100 > self.p["maxEjectionPercent"] * self.placement_set_size:
                self.events.append({"event": "sharder.health.ejection_refused", "node": node_id,
                                    "ejected": ejected, "setSize": self.placement_set_size,
                                    "at": now})
                if entry.state != "suspect":
                    self._transition(node_id, entry, "suspect", "ejectionRefused", now)
                return
        prior = entry.state
        entry.state = new_state
        if new_state == "unavailable":
            entry.ejections += 1
            entry.ejected_at = now
            entry.probation_since = None
            entry.available_since = None
        elif new_state == "probation":
            entry.probation_since = now
            entry.probe_counter = 0
        elif new_state == "available":
            entry.available_since = now
        self.events.append({"event": "sharder.health.transition", "node": node_id,
                            "from": prior, "to": new_state, "trigger": trigger, "at": now})

    def ejection_interval(self, entry):
        """`HEALTH-050`."""
        shift = min(entry.ejections - 1, 10)
        return min(self.p["baseEjectionMillis"] << shift, self.p["maxEjectionMillis"])

    def report(self, node_id, outcome, at):
        """`HEALTH-010` ingestion.  `HEALTH-047`: an ingested signal evaluates timers first."""
        self.advance(at)
        entry = self.entry(node_id)
        if entry.newest_signal is not None and at < entry.newest_signal:
            return                                     # `HEALTH-012`
        entry.newest_signal = at
        if outcome == "cancelled":
            return                                     # `HEALTH-022`
        bucket_length = max(1, self.p["windowMillis"] // self.p["bucketCount"])
        bucket_end = ((at // bucket_length) + 1) * bucket_length
        for index, (end, s, f) in enumerate(entry.buckets):
            if end == bucket_end:
                entry.buckets[index] = (end, s + (outcome == "success"),
                                        f + (outcome in FAILURE_OUTCOMES))
                break
        else:
            entry.buckets.append((bucket_end, int(outcome == "success"),
                                  int(outcome in FAILURE_OUTCOMES)))
        entry.buckets = [b for b in entry.buckets if b[0] >= at - self.p["windowMillis"]]

        if outcome == "success":
            entry.consecutive = 0
        else:
            entry.consecutive += 1
            entry.last_failure_at = at

        self._evaluate(node_id, entry, at, ingested=outcome)

    def _in_placement_set(self, node_id):
        return self.placement_set is not None and node_id in self.placement_set

    def comparison_set(self, now):
        """`HEALTH-030`: the placement set members whose window total reaches `minimumSamples`.

        A health entry for an identity the placement set does not hold is ingested under
        `HEALTH-011` and is not a peer, so it moves no median.  A view that has been told no
        placement set has no comparison set at all and runs no outlier ejection.
        """
        members = []
        for other_id, other in sorted(self.nodes.items()):
            if not self._in_placement_set(other_id):
                continue
            s, f = other.totals(now, self.p["windowMillis"])
            if s + f >= self.p["minimumSamples"]:
                members.append(other_id)
        return members

    def peer_median(self, now):
        """`HEALTH-031`: the lower of the two central values where the set is even."""
        values = sorted(self.nodes[node_id].failure_percent(now, self.p["windowMillis"])
                        for node_id in self.comparison_set(now))
        return None if not values else values[(len(values) - 1) // 2]

    def _is_outlier(self, node_id, entry, now):
        """`HEALTH-030` through `HEALTH-032`."""
        if not self._in_placement_set(node_id):
            return False
        if len(self.comparison_set(now)) < self.p["outlierMinimumNodes"]:
            return False
        return entry.failure_percent(now, self.p["windowMillis"]) >= \
            self.peer_median(now) + self.p["outlierMarginPercent"]

    def _evaluate(self, node_id, entry, now, ingested=None):
        window = self.p["windowMillis"]
        successes, failures = entry.totals(now, window)
        total = successes + failures

        if entry.state == "unknown" and ingested == "success":
            self._transition(node_id, entry, "available", "success", now)
        elif entry.state in ("unknown", "available") and ingested in FAILURE_OUTCOMES:
            self._transition(node_id, entry, "suspect", "failure", now)
        elif entry.state == "probation" and ingested in FAILURE_OUTCOMES:
            # `HEALTH-046`: the ceiling does not refuse this transition.
            prior = entry.state
            entry.state = "unavailable"
            entry.ejections += 1
            entry.ejected_at = now
            entry.probation_since = None
            self.events.append({"event": "sharder.health.transition", "node": node_id,
                                "from": prior, "to": "unavailable",
                                "trigger": "probationFailure", "at": now})
            return

        if entry.state == "suspect":
            if failures == 0 and total >= self.p["minimumSamples"]:
                self._transition(node_id, entry, "available", "failureFreeWindow", now)
            else:
                reasons = []
                if entry.consecutive >= self.p["consecutiveFailureThreshold"]:
                    reasons.append("consecutiveFailures")
                if total >= self.p["minimumSamples"] and \
                        entry.failure_percent(now, window) >= self.p["failureRatePercent"]:
                    reasons.append("failureRate")
                if self._is_outlier(node_id, entry, now):
                    reasons.append("outlier")
                if reasons:
                    self._transition(node_id, entry, "unavailable", reasons[0], now)

    def advance(self, now):
        """`HEALTH-015`: timers evaluate in ascending node identity order."""
        for node_id in sorted(self.nodes):
            entry = self.nodes[node_id]
            if entry.state == "unavailable" and entry.ejected_at is not None:
                if now - entry.ejected_at >= self.ejection_interval(entry):
                    self._transition(node_id, entry, "probation", "ejectionElapsed", now)
            if entry.state == "probation" and entry.probation_since is not None:
                # `HEALTH-045`: the interval runs from entry into probation, or from the last
                # failure ingested since, whichever is later.
                clean_since = entry.probation_since
                if entry.last_failure_at is not None:
                    clean_since = max(clean_since, entry.last_failure_at)
                if now - clean_since >= self.p["probationMillis"]:
                    self._transition(node_id, entry, "available", "probationElapsed", now)
            if entry.state == "available" and entry.available_since is not None:
                if now - entry.available_since >= self.p["ejectionResetMillis"]:
                    entry.ejections = 0

    def attemptable(self, node_id):
        """`HEALTH-005`: the health state alone, with no probe consumed."""
        entry = self.nodes.get(node_id)
        return entry is None or entry.state != "unavailable"

    def admit_probe(self, node_id):
        """`HEALTH-051`, called once for each entry in `probation` the walk reaches, `HEALTH-017`."""
        entry = self.nodes.get(node_id)
        if entry is None or entry.state != "probation":
            return True
        entry.probe_counter += 1
        return entry.probe_counter % self.p["probationDivisor"] == 1

    def attempt_sequence(self, preference_list):
        """`FAIL-002`, `FAIL-003`, and `FAIL-012`: filter, never reorder, never empty.

        The filter reads the health state alone, so a declined probe does not fail it open.  An
        entry in `probation` is attemptable under `HEALTH-005` and the walk decides it.
        """
        kept = [e for e in preference_list if self.attemptable(e["node"])]
        if preference_list and not kept:
            return list(preference_list), True          # the filter failed open
        return kept, False

    def walk(self, attempt_sequence):
        """`FAIL-023` `next` over an attempt sequence, under `HEALTH-017` and `FAIL-015`.

        Answers the identities the walk attempts, in order.  A probe is consumed for each entry in
        `probation` the walk reaches.  Where every probe is declined the walk answers the first
        entry of the sequence rather than nothing, and consumes no further probe for it.
        """
        answered = []
        for entry in attempt_sequence:
            node_id = entry["node"] if isinstance(entry, dict) else entry
            if self.state_of(node_id) == "probation" and not self.admit_probe(node_id):
                continue
            answered.append(node_id)
        if not answered and attempt_sequence:
            first = attempt_sequence[0]
            answered.append(first["node"] if isinstance(first, dict) else first)
        return answered
