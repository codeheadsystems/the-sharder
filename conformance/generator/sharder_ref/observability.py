"""The events a snapshot publication emits, per `PLACE-073`, `SPREAD-024`, and `SEC-011`.

Every event here is decided before stage 6 of `TOPO-001` runs.  `PLACE-077` requires the totals of
`PLACE-073` to be computed from the node weights and the authored token arrays, which the document
carries, so an operator reads a preparation cost from the event rather than from a pipeline that
has already paid it.  The domain count of `SPREAD-022` and the seed evidence of `SEC-011` are the
same shape: each is a scan of the document.

Payload member names are the names the `OBS-020` table gives.  Every event carries the common
members of `OBS-021` and the severity the same table gives, which `publication_events` attaches.
"""

from .topology import PLACEMENT_STATES, STRATEGY_DEFAULTS

# The severity of each event, as the `Severity` column of `OBS-020` gives it.  A publication event
# reports a shape an operator chose, so none of them is an `error`.
SEVERITY = {
    "sharder.topology.weight_clamped": "warning",
    "sharder.topology.directory_large": "warning",
    "sharder.topology.rendezvous_large": "warning",
    "sharder.topology.ring_large": "warning",
    "sharder.topology.spread_infeasible": "warning",
    "sharder.topology.default_seed": "warning",
}

# The thresholds of `CFG-010`, at their defaults.  `CFG-014` makes 0 disable the event a threshold
# governs, and the suite ships no document that sets one.
DEFAULT_THRESHOLDS = {
    "directoryWarnEntries": 10000,
    "rendezvousWarnVirtualNodes": 4096,
    "ringWarnTokens": 1000000,
}


def _strategy(document):
    kind = document["strategy"]["kind"]
    return kind, dict(STRATEGY_DEFAULTS[kind], **document["strategy"])


def _placement_set(document):
    return [n for n in document["nodes"] if n.get("state", "active") in PLACEMENT_STATES]


def _granted(weight, per_unit, cap):
    """`PLACE-050`: the count a node is granted, which is the requested count under the cap."""
    return min(weight * per_unit, cap)


def weight_clamp_events(document):
    """`PLACE-052`, reported per node whose requested count exceeds the cap of `PLACE-050`.

    A node outside the placement set contributes no virtual node and no token, so it is clamped by
    nothing and reports nothing.
    """
    kind, strategy = _strategy(document)
    if kind == "ring":
        if strategy["tokenAssignment"] != "derived":
            return []
        per_unit, cap = strategy["tokensPerWeightUnit"], strategy["maxTokensPerNode"]
    elif kind == "rendezvous":
        per_unit = strategy["virtualNodesPerWeightUnit"]
        cap = strategy["maxVirtualNodesPerNode"]
    else:
        return []
    events = []
    for node in _placement_set(document):
        requested = node.get("weight", 1) * per_unit
        granted = _granted(node.get("weight", 1), per_unit, cap)
        if requested > granted:
            events.append({"event": "sharder.topology.weight_clamped", "node": node["id"],
                           "requestedCount": requested, "grantedCount": granted})
    return events


def placement_total(document):
    """The total of `PLACE-073` for the configuration in force, or `None` where it states none.

    The total is a sum over the placement set, in Python integers, which `PLACE-074` requires to be
    at least 64 bits wide or saturating.  The `directory` total is the entry count the `CFG-010`
    row of `directoryWarnEntries` names.
    """
    kind, strategy = _strategy(document)
    nodes = _placement_set(document)
    if kind == "ring":
        if strategy["tokenAssignment"] == "derived":
            total = sum(_granted(n.get("weight", 1), strategy["tokensPerWeightUnit"],
                                 strategy["maxTokensPerNode"]) for n in nodes)
        else:
            total = sum(len(n.get("tokens", [])) for n in nodes)
        return "ringWarnTokens", "sharder.topology.ring_large", total
    if kind == "rendezvous":
        total = sum(_granted(n.get("weight", 1), strategy["virtualNodesPerWeightUnit"],
                             strategy["maxVirtualNodesPerNode"]) for n in nodes)
        return "rendezvousWarnVirtualNodes", "sharder.topology.rendezvous_large", total
    if kind == "directory":
        return ("directoryWarnEntries", "sharder.topology.directory_large",
                len(strategy.get("entries", [])))
    return None


def large_placement_events(document, thresholds=None):
    """`PLACE-073` and `PLACE-077`: the event of a total above the threshold in force."""
    thresholds = dict(DEFAULT_THRESHOLDS, **(thresholds or {}))
    measured = placement_total(document)
    if measured is None:
        return []
    setting, name, total = measured
    threshold = thresholds[setting]
    if threshold == 0 or total <= threshold:
        return []
    if name == "sharder.topology.directory_large":
        return [{"event": name, "entryCount": total, "threshold": threshold}]
    return [{"event": name, "nodeCount": len(_placement_set(document)), "total": total,
             "threshold": threshold}]


def domain_count(document, level):
    """`SPREAD-022`: distinct values of `domain_path` at `level` over the placement set."""
    levels = document.get("domainLevels", [])
    cut = levels.index(level) + 1
    paths = {tuple(n.get("domains", {}).get(name, "") for name in levels[:cut])
             for n in _placement_set(document)}
    return len(paths)


def occupancy_cap(level):
    """`SPREAD-007`: the occupancy cap of a level, which no document member sets."""
    return 1


def lowest_admissible_stage(document):
    """The lowest stage `SPREAD-023` does not rule out, which stage `m` always is."""
    replication = document.get("replication", {})
    spread = replication.get("spread", [])
    factor = replication.get("factor", 1)
    for index, level in enumerate(spread):
        if occupancy_cap(level) * domain_count(document, level) >= factor:
            return index
    return len(spread)


def spread_infeasible_events(document):
    """`SPREAD-024`: one event for the coarsest level whose product is below the factor."""
    replication = document.get("replication", {})
    spread = replication.get("spread", [])
    factor = replication.get("factor", 1)
    for level in spread:
        count = domain_count(document, level)
        if occupancy_cap(level) * count < factor:
            return [{"event": "sharder.topology.spread_infeasible", "level": level,
                     "domainCount": count, "factor": factor,
                     "stage": lowest_admissible_stage(document)}]
    return []


def default_seed_events(document):
    """`SEC-011`: a zero seed on a document that separates tenants."""
    seed = document.get("hash", {}).get("seed", "0" * 32)
    if bytes.fromhex(seed) != bytes(16):
        return []
    evidence = []
    if document.get("overrides"):
        evidence.append("overrides")
    if document["strategy"]["kind"] == "directory":
        evidence.append("directory")
    if not evidence:
        return []
    return [{"event": "sharder.topology.default_seed", "evidence": evidence}]


# The order in which one publication reports its events.  Each group is decided at a different
# point of `TOPO-001`: the totals of `PLACE-073` are computed before stage 6 under `PLACE-077`, the
# clamp of `PLACE-052` is read from the same arithmetic, and the domain count of `SPREAD-022` and
# the seed evidence of `SEC-011` are scans of the accepted document.
GROUPS = [large_placement_events, weight_clamp_events, spread_infeasible_events,
          default_seed_events]


def publication_events(document):
    """Every event one publication of this document emits, in the order `GROUPS` states.

    The common members of `OBS-021` other than the severity are the emitting caller's own: the
    `Instant` is a clock reading and the `topologyId` and `epoch` are the document's.  The identity
    and the epoch are attached here and the instant is not, because a data file carries no clock.
    """
    events = []
    for group in GROUPS:
        for event in group(document):
            events.append(dict({"event": event["event"],
                                "severity": SEVERITY[event["event"]],
                                "topologyId": document["topologyId"],
                                "epoch": document["epoch"]},
                               **event))
    return events
