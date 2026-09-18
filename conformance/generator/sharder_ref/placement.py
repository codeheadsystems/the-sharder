"""The four core placement strategies, per `RING-*`, `RV-*`, `SLOT-*`, and `DIR-*`.

Every ordering here is computed with integer arithmetic only, and every comparator ends in
ascending node identity comparison over unsigned UTF-8 octets, per `PLACE-020` and `PLACE-023`.
"""

from . import hashing
from .topology import decode_matcher_value, parse_slot_range


class NoShard:
    """The absence of a shard, distinct from a shard whose identifier happens to be empty."""

    def __repr__(self):
        return "NoShard"


NO_SHARD = NoShard()


def dedupe_and_filter(names, eligible_ids):
    """Apply `PLACE-013` and `PLACE-014` to an authored node list."""
    out = []
    seen = set()
    for name in names:
        if name not in eligible_ids or name in seen:
            continue
        seen.add(name)
        out.append(name)
    return out


# --------------------------------------------------------------------------- ring

_RING_CACHE = {}


def ring_entries(snapshot, nodes):
    """`RING-010`: entries sorted by token, then owner identity, then token index.

    The result is memoised per snapshot and per node set.  `CORE-011` makes the ring order a pure
    function of the snapshot, so a cache cannot change any answer; it only keeps the property
    runs, which evaluate one topology over a hundred thousand keys, from rebuilding the ring each
    time.

    The key is the snapshot's serial number rather than the object's identity.  A Python object
    identity is reused once the object it named is collected, so an identity key hands one snapshot
    the ring of a different document that happened to occupy the same address, which is a wrong
    answer rather than a slow one.  A serial is issued once per snapshot and never reissued.  The
    digest would name the document too, and would be the sharper key, but reading it costs the
    canonical form and SHA-256, which a caller at the `place` level of `30-conformance.md` has
    neither.
    """
    cache_key = (snapshot.cache_key, tuple(n.id for n in nodes))
    cached = _RING_CACHE.get(cache_key)
    if cached is not None:
        return cached
    strategy = snapshot.strategy
    entries = []
    if strategy.get("tokenAssignment", "derived") == "derived":
        per_unit = strategy["tokensPerWeightUnit"]
        cap = strategy["maxTokensPerNode"]
        for node in nodes:
            for i in range(node.virtual_node_count(per_unit, cap)):
                entries.append((hashing.ring_token(snapshot.seed, node.id_bytes, i),
                                node.id_bytes, i, node.id))
    else:
        for node in nodes:
            for i, token in enumerate(node.tokens):
                entries.append((token, node.id_bytes, i, node.id))
    entries.sort(key=lambda e: (e[0], e[1], e[2]))
    _RING_CACHE[cache_key] = entries
    return entries


def ring_position_at_or_above(entries, value):
    """`RING-020`, by binary search.

    `RING-025` requires the result to be identical whether an implementation scans linearly,
    searches binarily, or precomputes a lookup table, so the faster form is used here.
    """
    lo, hi = 0, len(entries)
    while lo < hi:
        mid = (lo + hi) // 2
        if entries[mid][0] >= value:
            hi = mid
        else:
            lo = mid + 1
    return lo if lo < len(entries) else 0


def ring_walk(entries, start):
    out = []
    seen = set()
    for k in range(len(entries)):
        owner = entries[(start + k) % len(entries)][3]
        if owner not in seen:
            seen.add(owner)
            out.append(owner)
    return out


def ring_candidates(snapshot, routing_key, eligible):
    entries = ring_entries(snapshot, eligible)
    if not entries:
        return []
    return ring_walk(entries, ring_position_at_or_above(
        entries, hashing.key_hash(snapshot.seed, routing_key)))


def ring_shard_of(snapshot, routing_key):
    entries = ring_entries(snapshot, snapshot.placement_set)
    if not entries:
        return NO_SHARD
    position = ring_position_at_or_above(entries,
                                         hashing.key_hash(snapshot.seed, routing_key))
    return hashing.hex_u64(entries[position][0])


def ring_shards(snapshot):
    """`RING-031`: distinct token values in ring order, each enumerated once."""
    out, seen = [], set()
    for entry in ring_entries(snapshot, snapshot.placement_set):
        shard = hashing.hex_u64(entry[0])
        if shard not in seen:
            seen.add(shard)
            out.append(shard)
    return out


def ring_candidates_for_shard(snapshot, shard, eligible):
    entries = ring_entries(snapshot, eligible)
    if not entries:
        return []
    return ring_walk(entries, ring_position_at_or_above(entries, int(shard, 16)))


# --------------------------------------------------------------------- rendezvous

def rendezvous_count(snapshot, node):
    return node.virtual_node_count(snapshot.strategy["virtualNodesPerWeightUnit"],
                                   snapshot.strategy["maxVirtualNodesPerNode"])


def rendezvous_scored(snapshot, routing_key, eligible):
    scored = []
    for node in eligible:
        count = rendezvous_count(snapshot, node)
        if count == 0:
            continue
        best = max(hashing.rv_score(snapshot.seed, routing_key, node.id_bytes, i)
                   for i in range(count))
        scored.append((best, node.id_bytes, node.id))
    scored.sort(key=lambda s: (-s[0], s[1]))
    return scored


def rendezvous_candidates(snapshot, routing_key, eligible):
    return [s[2] for s in rendezvous_scored(snapshot, routing_key, eligible)]


# --------------------------------------------------------------------------- slot

def slot_index(snapshot, routing_key):
    return hashing.key_hash(snapshot.seed, routing_key) % snapshot.strategy["slotCount"]


def slot_covering_entry(snapshot, index):
    for entry in snapshot.strategy.get("assignments", []):
        for text in entry["slots"]:
            low, high = parse_slot_range(text)
            if low <= index <= high:
                return entry
    return None


def slot_candidates_for_index(snapshot, index, eligible):
    """`SLOT-012`: the covering entry's authored list, filtered to the eligible set."""
    entry = slot_covering_entry(snapshot, index)
    if entry is None:
        return []
    return dedupe_and_filter(entry["nodes"], {n.id for n in eligible})


# ---------------------------------------------------------------------- directory

def matcher_matches(matcher, routing_key):
    value = decode_matcher_value(matcher)
    if matcher["kind"] == "exact":
        return value == routing_key
    return len(value) <= len(routing_key) and routing_key[:len(value)] == value


def select_matched_entry(entries, routing_key):
    """`PLACE-065`: exact beats prefix, longest prefix wins, lower index breaks the remaining tie."""
    best = None
    for index, entry in enumerate(entries):
        matcher = entry["match"]
        if not matcher_matches(matcher, routing_key):
            continue
        rank = (0 if matcher["kind"] == "exact" else 1,
                -len(decode_matcher_value(matcher)),
                index)
        if best is None or rank < best[0]:
            best = (rank, index, entry)
    if best is None:
        return None, None
    return best[1], best[2]


def directory_shard_id(matcher):
    return "%s:%s" % (matcher["kind"], decode_matcher_value(matcher).hex())


# ------------------------------------------------------------------- dispatch

def shard_of(snapshot, routing_key):
    """`PLACE-030`: computed over the placement set, never over the eligible set."""
    kind = snapshot.strategy["kind"]
    if kind == "ring":
        return ring_shard_of(snapshot, routing_key)
    if kind == "rendezvous":
        return routing_key.hex()
    if kind == "slot":
        return str(slot_index(snapshot, routing_key))
    if kind == "directory":
        _, entry = select_matched_entry(snapshot.strategy["entries"], routing_key)
        return NO_SHARD if entry is None else directory_shard_id(entry["match"])
    raise ValueError("unknown strategy kind: %r" % (kind,))


def shards(snapshot):
    kind = snapshot.strategy["kind"]
    if kind == "ring":
        return ring_shards(snapshot)
    if kind == "rendezvous":
        return []
    if kind == "slot":
        return [str(i) for i in range(snapshot.strategy["slotCount"])]
    if kind == "directory":
        return [directory_shard_id(e["match"]) for e in snapshot.strategy["entries"]]
    raise ValueError("unknown strategy kind: %r" % (kind,))


def candidates(snapshot, routing_key, eligible):
    """The candidate ordering, before any replication rule runs."""
    kind = snapshot.strategy["kind"]
    if kind == "ring":
        return ring_candidates(snapshot, routing_key, eligible)
    if kind == "rendezvous":
        return rendezvous_candidates(snapshot, routing_key, eligible)
    if kind == "slot":
        return slot_candidates_for_index(snapshot, slot_index(snapshot, routing_key), eligible)
    if kind == "directory":
        _, entry = select_matched_entry(snapshot.strategy["entries"], routing_key)
        if entry is None:
            return []
        return dedupe_and_filter(entry["nodes"], {n.id for n in eligible})
    raise ValueError("unknown strategy kind: %r" % (kind,))


def candidates_for_shard(snapshot, shard, eligible):
    kind = snapshot.strategy["kind"]
    if kind == "ring":
        return ring_candidates_for_shard(snapshot, shard, eligible)
    if kind == "rendezvous":
        return rendezvous_candidates(snapshot, bytes.fromhex(shard), eligible)
    if kind == "slot":
        return slot_candidates_for_index(snapshot, int(shard), eligible)
    if kind == "directory":
        for entry in snapshot.strategy["entries"]:
            if directory_shard_id(entry["match"]) == shard:
                return dedupe_and_filter(entry["nodes"], {n.id for n in eligible})
        return []
    raise ValueError("unknown strategy kind: %r" % (kind,))


def no_candidate_cause(snapshot, routing_key, eligible, matched_override):
    """The `ERR-021` cause for an empty candidate ordering.

    The set is closed and total: `ERR-021` names a cause for every empty ordering this
    specification can produce, including an authored list whose every node is ineligible and a
    `ring` under `explicit` assignment in which no eligible node carries a token.
    """
    kind = snapshot.strategy["kind"]
    eligible_ids = {n.id for n in eligible}
    if not snapshot.placement_set:
        return "emptyPlacementSet"
    if matched_override is not None and matched_override.get("constrain") and not eligible:
        return "constraintExcludedAll"
    if matched_override is not None and matched_override.get("pin"):
        return "pinExcludedAll"
    if kind == "directory":
        _, entry = select_matched_entry(snapshot.strategy["entries"], routing_key)
        if entry is None:
            return "noDirectoryEntry"
        if not dedupe_and_filter(entry["nodes"], eligible_ids):
            return "authoredListExcludedAll"
    if kind == "slot":
        entry = slot_covering_entry(snapshot, slot_index(snapshot, routing_key))
        if entry is None:
            return "noSlotEntry"
        if not dedupe_and_filter(entry["nodes"], eligible_ids):
            return "authoredListExcludedAll"
    if kind == "rendezvous" and all(rendezvous_count(snapshot, n) == 0 for n in eligible):
        return "zeroVirtualNodes"
    if kind == "ring":
        if snapshot.strategy.get("tokenAssignment", "derived") == "derived":
            per_unit = snapshot.strategy["tokensPerWeightUnit"]
            cap = snapshot.strategy["maxTokensPerNode"]
            if all(n.virtual_node_count(per_unit, cap) == 0 for n in eligible):
                return "zeroVirtualNodes"
        elif not ring_entries(snapshot, eligible):
            return "noEligibleTokenOwner"
    if not eligible:
        return "emptyPlacementSet"
    return None
