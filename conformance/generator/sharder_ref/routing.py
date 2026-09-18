"""Override evaluation, preference list construction, spread degradation, and the decision.

Covers `OVR-*`, `REPL-*`, `SPREAD-*`, and the `READ-*` reordering.  `preferenceList` is the whole
list `REPL-014` defines and `CORE-047` answers on demand; `materialisedEntries` is the count
`CORE-046` bounds the decision's own `entries` at.  Health filtering is not
modelled: a golden vector asserts the preference list, which `REPL-016` makes a pure function of
the snapshot and the routing key, and the attempt sequence belongs to the property and scenario
layers.
"""

from . import placement
from .placement import NO_SHARD
from .topology import decode_matcher_value
from .transforms import apply_transform


class NoCandidate(Exception):
    def __init__(self, cause):
        self.cause = cause
        self.code = 101
        self.name = "noCandidate"
        super().__init__("noCandidate(%s)" % cause)


def routing_key_of(snapshot, key: bytes) -> bytes:
    return apply_transform(snapshot.key_transform, key)


def match_override(snapshot, routing_key):
    """`OVR-001` through `OVR-003`: at most one entry applies, selected by `PLACE-065`."""
    index, entry = placement.select_matched_entry(snapshot.overrides, routing_key)
    if entry is None:
        return None, None
    return index, entry


def override_mode(entry):
    has_pin = bool(entry.get("pin"))
    has_constrain = bool(entry.get("constrain"))
    if has_pin and has_constrain:
        return "both"
    return "pin" if has_pin else "constrain"


def node_satisfies_constraint(node, constrain):
    """`OVR-021` through `OVR-025`: the three members intersect."""
    domains = constrain.get("domains")
    if domains is not None:
        for level, values in domains.items():
            identifier = node.domains.get(level)
            if identifier is None or identifier not in values:
                return False
    tags = constrain.get("tags")
    if tags is not None:
        for tag, values in tags.items():
            value = node.tags.get(tag)
            if value is None or value not in values:
                return False
    names = constrain.get("nodes")
    if names is not None and node.id not in names:
        return False
    return True


def eligible_set(snapshot, entry):
    """`PLACE-003`: the placement set filtered by the matched entry's constraint."""
    if entry is None or not entry.get("constrain"):
        return list(snapshot.placement_set)
    return [n for n in snapshot.placement_set
            if node_satisfies_constraint(n, entry["constrain"])]


def candidate_ordering(snapshot, routing_key, entry, eligible):
    """The candidate ordering, with a pin replacing the strategy under `OVR-010`."""
    if entry is not None and entry.get("pin"):
        eligible_ids = {n.id for n in eligible}
        return placement.dedupe_and_filter(entry["pin"], eligible_ids)
    return placement.candidates(snapshot, routing_key, eligible)


def _shares_domain(node, prefix, levels):
    for level in levels:
        for other in prefix:
            if node.domain_path(level) == other.domain_path(level):
                return True
    return False


def select_stage(snapshot, ordering_nodes, n, levels):
    """`SPREAD-011`: the replica prefix `REPL-012` produces with `levels` in force.

    Stage `k` of `SPREAD-010` passes `spread[k:]`, the finest `m-k` levels.  `SPREAD-018` makes
    that equivalent to enforcing `spread[k]` alone, so the ladder has `m+1` distinct rungs.
    """
    prefix = []
    for node in ordering_nodes:
        if any(p.id == node.id for p in prefix):
            continue
        if _shares_domain(node, prefix, levels):
            continue
        prefix.append(node)
        if len(prefix) == n:
            break
    return prefix


def build_preference_list(snapshot, ordering, n):
    """Return (preference list entries, r, relaxed levels, chosen stage)."""
    spread = [level for level in snapshot.spread]
    m = len(spread)
    ordering_nodes = [snapshot.by_id[name] for name in ordering]

    if snapshot.spread_policy == "strict":
        chosen = 0
        prefix = select_stage(snapshot, ordering_nodes, n, spread)
    else:
        chosen = m
        prefix = None
        for k in range(m + 1):
            levels = spread[k:]
            attempt = select_stage(snapshot, ordering_nodes, n, levels)
            if len(attempt) == n:
                chosen = k
                prefix = attempt
                break
        if prefix is None:
            chosen = m
            prefix = select_stage(snapshot, ordering_nodes, n, [])

    replica_ids = [node.id for node in prefix]
    tail = [name for name in ordering if name not in set(replica_ids)]
    r = len(replica_ids)
    entries = []
    for position, name in enumerate(replica_ids + tail):
        entries.append({"node": name,
                        "position": position,
                        "role": "replica" if position < r else "fallback"})
    relaxed = spread[:chosen] if chosen else []
    return entries, r, relaxed, chosen


def read_affinity_reorder(snapshot, entries, r, level, path, window):
    """`READ-013`: a stable partition of the first `window` entries."""
    if level not in snapshot.domain_levels:
        raise ValueError("affinity level not declared: %r" % (level,))
    window = min(window if window is not None else r, r)
    head, tail = entries[:window], entries[window:]
    cut = snapshot.domain_levels.index(level) + 1
    wanted = tuple(item.encode("utf-8") if isinstance(item, str) else item
                   for item in path[:cut])
    local = [e for e in head if snapshot.by_id[e["node"]].domain_path(level) == wanted]
    remote = [e for e in head if snapshot.by_id[e["node"]].domain_path(level) != wanted]
    merged = local + remote + tail
    return [{"node": e["node"], "position": i, "role": e["role"]}
            for i, e in enumerate(merged)]


def materialised_entries(entries, r, n, attempt_limit=None):
    """`CORE-046`: the length of the prefix a routing decision carries.

    The bound is the lesser of the preference list length and the greater of the achieved replica
    count and the resolved attempt limit of `CORE-048`, which is the value before the clamp
    `FAIL-022` applies to the attempt walk.  No health view is modelled here, and `CORE-046` makes
    the bound independent of one.
    """
    limit = n + 2 if attempt_limit is None else attempt_limit
    return min(len(entries), max(r, limit))


def route(snapshot, key: bytes, affinity=None, attempt_limit=None):
    """Compute the routing decision for `key` against `snapshot`."""
    routing_key = routing_key_of(snapshot, key)
    index, entry = match_override(snapshot, routing_key)
    eligible = eligible_set(snapshot, entry)
    ordering = candidate_ordering(snapshot, routing_key, entry, eligible)

    if not ordering:
        raise NoCandidate(placement.no_candidate_cause(snapshot, routing_key, eligible, entry))

    n = entry["factor"] if entry is not None and "factor" in entry else snapshot.factor
    entries, r, relaxed, stage = build_preference_list(snapshot, ordering, n)

    if r == n:
        shortfall = "none"
    elif len(ordering) < n:
        shortfall = "nodes"
    else:
        shortfall = "domains"

    shard = placement.shard_of(snapshot, routing_key)
    decision = {
        "routingKey": routing_key.hex(),
        "shard": None if shard is NO_SHARD else shard,
        "token": snapshot.token,
        "factor": n,
        "replicaCount": r,
        "candidates": ordering,
        "preferenceList": entries,
        "materialisedEntries": materialised_entries(entries, r, n, attempt_limit),
        "relaxedLevels": relaxed,
        "spreadStage": stage,
        "shortfall": shortfall,
        "matchedOverride": None if entry is None else {"index": index, "mode": override_mode(entry)},
    }
    if affinity is not None:
        decision["ordered"] = read_affinity_reorder(
            snapshot, entries, r, affinity["level"], affinity["path"], affinity.get("window"))
    return decision


def first_candidate(snapshot, key: bytes):
    """The head of the candidate ordering, or `None`.  Used by the movement and balance checks."""
    routing_key = routing_key_of(snapshot, key)
    _, entry = match_override(snapshot, routing_key)
    eligible = eligible_set(snapshot, entry)
    ordering = candidate_ordering(snapshot, routing_key, entry, eligible)
    return ordering[0] if ordering else None


def matcher_identity(matcher):
    return matcher["kind"], decode_matcher_value(matcher)
