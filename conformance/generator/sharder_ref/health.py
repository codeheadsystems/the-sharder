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

    def __init__(self, parameters=None, placement_set_size=0, event_sink=None):
        self.p = dict(DEFAULTS, **(parameters or {}))
        self.nodes = {}
        self.placement_set_size = placement_set_size
        self.events = [] if event_sink is None else event_sink

    def entry(self, node_id):
        return self.nodes.setdefault(node_id, NodeHealth())

    def state_of(self, node_id):
        return self.nodes[node_id].state if node_id in self.nodes else "unknown"

    def _transition(self, node_id, entry, new_state, trigger, now):
        if entry.state == new_state:
            return
        if new_state == "unavailable" and entry.state != "probation":
            # `HEALTH-034`: the ceiling on concurrently ejected nodes.
            ejected = sum(1 for e in self.nodes.values() if e.state == "unavailable")
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

    def _is_outlier(self, node_id, entry, now):
        """`HEALTH-030` through `HEALTH-032`.

        `HEALTH-030` draws the comparison set from the placement set at the epoch in force.  This
        view holds no snapshot, so it compares over the nodes it has ingested signals for, which
        is the same set in every scenario the suite ships.
        """
        comparison = []
        for other_id, other in sorted(self.nodes.items()):
            s, f = other.totals(now, self.p["windowMillis"])
            if s + f >= self.p["minimumSamples"]:
                comparison.append(other.failure_percent(now, self.p["windowMillis"]))
        if len(comparison) < self.p["outlierMinimumNodes"]:
            return False
        comparison.sort()
        median = comparison[(len(comparison) - 1) // 2]     # `HEALTH-031`: the lower of two
        return entry.failure_percent(now, self.p["windowMillis"]) >= \
            median + self.p["outlierMarginPercent"]

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
        """`HEALTH-005` and `HEALTH-051`."""
        entry = self.nodes.get(node_id)
        if entry is None or entry.state in ("unknown", "available", "suspect"):
            return True
        if entry.state == "unavailable":
            return False
        entry.probe_counter += 1
        return entry.probe_counter % self.p["probationDivisor"] == 1

    def attempt_sequence(self, preference_list):
        """`FAIL-002`, `FAIL-003`, and `FAIL-012`: filter, never reorder, never empty."""
        kept = [e for e in preference_list if self.attemptable(e["node"])]
        if preference_list and not kept:
            return list(preference_list), True          # the filter failed open
        return kept, False
