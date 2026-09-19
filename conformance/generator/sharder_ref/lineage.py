"""Shard lineage: the correspondence between the extents of two snapshots.

`LIN-001` through `LIN-045`.  A lineage relates extents rather than shard identifiers, so it
answers where a shard's contents come from when an epoch changes which shards exist.  An ownership
delta joins two snapshots on the identifier and has no answer there.

The extent representation is per strategy, under `LIN-011` through `LIN-015`.  Under `ring` an
extent is a set of half-open segments of the 64-bit hash space, which wraps at zero, so containment
and union are interval arithmetic.  Under `slot` `TOPO-231` holds `slotCount` equal, so every extent
equals its counterpart and the lineage is the identity of `LIN-007`.  Under `directory` this module
refuses a pair whose shard sets differ, which is the refusal `LIN-013` states.
"""

from . import placement

SPACE = 1 << 64


class Refused(Exception):
    """`ERR-050`: a plan refused with the cause the lineage names."""

    def __init__(self, cause, detail=None):
        super().__init__(cause if detail is None else "%s: %s" % (cause, detail))
        self.cause = cause
        self.detail = detail


# ------------------------------------------------------------------ interval extents under `ring`

def _segments(low_exclusive, high_inclusive):
    """The keys `h` with `low_exclusive < h <= high_inclusive`, as half-open `[start, end)` pairs.

    `RING-020` gives the owning entry that interval, and it wraps at zero, so a wrapping interval is
    two segments rather than one.
    """
    start, end = (low_exclusive + 1) % SPACE, (high_inclusive + 1) % SPACE
    if start == end:
        return ((0, SPACE),)
    if start < end:
        return ((start, end),)
    return ((0, end), (start, SPACE))


def ring_extents(snapshot):
    """`LIN-011`: each distinct token's interval, in the ascending ring order of `RING-031`."""
    tokens = [int(s, 16) for s in placement.ring_shards(snapshot)]
    out = {}
    for position, token in enumerate(tokens):
        predecessor = tokens[position - 1] if len(tokens) > 1 else token
        out[placement.hashing.hex_u64(token)] = _segments(predecessor, token)
    return out


def _measure(segments):
    return sum(end - start for start, end in segments)


def _intersection(first, second):
    out = []
    for a_start, a_end in first:
        for b_start, b_end in second:
            start, end = max(a_start, b_start), min(a_end, b_end)
            if start < end:
                out.append((start, end))
    return tuple(sorted(out))


def meets(first, second):
    return bool(_intersection(first, second))


def contains(outer, inner):
    return _measure(_intersection(outer, inner)) == _measure(inner)


def equal(first, second):
    return _measure(first) == _measure(second) == _measure(_intersection(first, second))


# ------------------------------------------------------------------------------- the lineage

def extents(snapshot):
    """The extent of each shard the snapshot enumerates, under `LIN-011` through `LIN-014`."""
    kind = snapshot.strategy["kind"]
    if kind == "ring":
        return ring_extents(snapshot)
    if kind == "rendezvous":
        raise Refused("strategyUnsupported", "rendezvous enumerates no shard")
    # `LIN-012` and `LIN-013`: an identity extent, keyed by the shard identifier itself.  Under
    # `slot` `TOPO-231` holds `slotCount` equal; under `directory` a differing shard set is refused
    # by `classify` before an extent is compared.
    return {shard: shard for shard in placement.shards(snapshot)}


def _identity_lineage(before, after, replicas_of):
    rows = []
    for shard in placement.shards(after):
        was, now = replicas_of(before, shard), replicas_of(after, shard)
        rows.append({"shard": shard, "class": "unchanged" if was == now else "moved",
                     "parents": [shard]})
    return rows


def comparable(before, after):
    """`TOPO-231`: the member the two snapshots differ in, or `None` where they are comparable.

    Shard identity is the join both an ownership delta and a lineage rest on, so the two joins
    share one comparability rule and `LIN-006` refuses exactly where `TOPO-231` does.
    """
    if before.strategy["kind"] != after.strategy["kind"]:
        return "strategy.kind"
    if before.key_transform != after.key_transform:
        return "keyTransform"
    if before.seed != after.seed:
        return "hash.seed"
    if before.hash_config["algorithm"] != after.hash_config["algorithm"]:
        return "hash.algorithm"
    if before.strategy["kind"] == "slot" \
            and before.strategy["slotCount"] != after.strategy["slotCount"]:
        return "slotCount"
    return None


def classify(before, after, replicas_of):
    """`LIN-021`: each shard of the later snapshot, then each shard only the earlier one holds.

    `LIN-033` fixes that order, which is the order `TOPO-213` fixes for an ownership delta.
    """
    differing = comparable(before, after)
    if differing is not None:
        raise Refused("incomparableShards", differing)
    kind = before.strategy["kind"]
    if kind == "rendezvous":
        raise Refused("strategyUnsupported", "rendezvous enumerates no shard")

    shards_before, shards_after = placement.shards(before), placement.shards(after)
    if kind in ("slot", "directory"):
        if shards_before != shards_after:
            # `LIN-013`: a directory extent is decidable and not yet defined, so the pair is
            # refused rather than guessed.  Under `slot` `TOPO-231` has already refused a differing
            # `slotCount`, so an unequal shard set cannot arise.
            raise Refused("unalignedLineage", "the two snapshots enumerate different shards")
        return _identity_lineage(before, after, replicas_of)

    # `LIN-007`: an equal shard set is an equal extent set, so the lineage is the identity.
    if shards_before == shards_after:
        return _identity_lineage(before, after, replicas_of)

    earlier, later = extents(before), extents(after)
    rows = []
    for shard in shards_after:
        mine = later[shard]
        parents = [s for s in shards_before if meets(earlier[s], mine)]
        for parent in parents:
            if not contains(earlier[parent], mine) and not contains(mine, earlier[parent]):
                raise Refused("unalignedLineage", shard)
        if not parents:
            rows.append({"shard": shard, "class": "fresh", "parents": []})
        elif len(parents) > 1:
            rows.append({"shard": shard, "class": "merged", "parents": parents})
        elif equal(earlier[parents[0]], mine):
            was, now = replicas_of(before, shard), replicas_of(after, shard)
            rows.append({"shard": shard, "class": "unchanged" if was == now else "moved",
                         "parents": parents})
        else:
            rows.append({"shard": shard, "class": "divided", "parents": parents})

    for shard in shards_before:
        if shard in later:
            continue
        mine = earlier[shard]
        children = [s for s in shards_after if meets(later[s], mine)]
        if not children:
            rows.append({"shard": shard, "class": "vacated", "parents": []})
        elif len(children) > 1:
            rows.append({"shard": shard, "class": "split", "parents": children})
        else:
            rows.append({"shard": shard, "class": "folded", "parents": children})
    return rows


def parents_of(before, after):
    """For each shard of the later snapshot, the shards of the earlier one its extent draws from."""
    kind = before.strategy["kind"]
    shards_before, shards_after = placement.shards(before), placement.shards(after)
    if kind in ("slot", "directory") or shards_before == shards_after:
        return {shard: [shard] for shard in shards_after}
    earlier, later = extents(before), extents(after)
    return {shard: [s for s in shards_before if meets(earlier[s], later[shard])]
            for shard in shards_after}


def plan_handoffs(before, after, replicas_of):
    """`LIN-041` through `LIN-058`: the handoffs of a plan and the local steps beside them.

    `LIN-057` fixes the order within one shard: a division is sequenced before every handoff that
    draws from the divided parent, and a fold after every handoff that draws into the folded shard.
    """
    parents = parents_of(before, after)
    kind = before.strategy["kind"]
    identity = kind != "ring" or placement.shards(before) == placement.shards(after)
    earlier = {} if identity else extents(before)
    later = {} if identity else extents(after)

    out = []
    for shard in placement.shards(after):
        destinations = replicas_of(after, shard)
        mine = parents[shard]
        divisions, folds, moves = [], [], []
        if not identity and len(mine) == 1 and not equal(earlier[mine[0]], later[shard]):
            # `LIN-052`: the extent narrowed, so a node holding both divides its own copy.
            for node in destinations:
                if node in replicas_of(before, mine[0]):
                    divisions.append({"shard": shard, "sourceShard": mine[0], "kind": "divide",
                                      "source": node, "destination": node})
        if not identity and len(mine) > 1:
            # `LIN-052`: the extent widened, so a node holding a parent folds what it holds.
            for node in destinations:
                if any(node in replicas_of(before, parent) for parent in mine):
                    folds.append({"shard": shard, "sourceShard": shard, "kind": "combine",
                                  "source": node, "destination": node})
        for parent in mine:
            held = replicas_of(before, parent)
            if not held:
                continue
            needing = [d for d in destinations if d not in held]
            departing = [n for n in held if n not in destinations]
            for position, destination in enumerate(needing):
                # `LIN-045`: a replica giving the shard up is drained before one keeping it.
                source = departing[position] if position < len(departing) else held[0]
                moves.append({"shard": shard, "sourceShard": parent, "kind": "handoff",
                              "source": source, "destination": destination})
        out.extend(divisions + moves + folds)

    for position, entry in enumerate(out):
        same = [e for e in out[:position] if e["shard"] == entry["shard"]]
        prefix = "l-" if entry["kind"] != "handoff" else "h-"
        entry["id"] = "%s%s" % (prefix, entry["shard"]) if not same \
            else "%s%s-%d" % (prefix, entry["shard"], len(same))
    return out
