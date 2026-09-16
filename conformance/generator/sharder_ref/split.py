"""Range split and merge lineage, per `SPLIT-061` through `SPLIT-161`.

Lineage is derived from bounds and never from `shardId`, under `SPLIT-061`.  A null `start`
compares below every routing key and a null `end` above every routing key, which the sentinels
below stand for.
"""

LOW = object()      # below every octet sequence
HIGH = object()     # above every octet sequence


def bound(value, sentinel):
    return sentinel if value is None else bytes.fromhex(value)


def less_or_equal(a, b) -> bool:
    if a is LOW or b is HIGH:
        return True
    if a is HIGH:
        return b is HIGH
    if b is LOW:
        return a is LOW
    return a <= b


def ranges_of(snapshot):
    return [{"shardId": entry["shardId"],
             "start": bound(entry["start"], LOW),
             "end": bound(entry["end"], HIGH),
             "raw": entry}
            for entry in snapshot.strategy["ranges"]]


def is_child(parent, child) -> bool:
    """`SPLIT-071`: `a.start <= b.start` and `b.end <= a.end`."""
    return less_or_equal(parent["start"], child["start"]) and \
        less_or_equal(child["end"], parent["end"])


def same_bounds(a, b) -> bool:
    return _eq(a["start"], b["start"]) and _eq(a["end"], b["end"])


def _eq(a, b) -> bool:
    if a is LOW or a is HIGH or b is LOW or b is HIGH:
        return a is b
    return a == b


def covers_exactly(parent, children) -> bool:
    """The children, in ascending order, meet each other and span the parent exactly."""
    if not children:
        return False
    ordered = sorted(children, key=lambda r: (_sort_key(r["start"])))
    if not _eq(ordered[0]["start"], parent["start"]):
        return False
    if not _eq(ordered[-1]["end"], parent["end"]):
        return False
    for left, right in zip(ordered, ordered[1:]):
        if not _eq(left["end"], right["start"]):
            return False
    return True


def _sort_key(value):
    if value is LOW:
        return (0, b"")
    if value is HIGH:
        return (2, b"")
    return (1, value)


def classify(before_snapshot, after_snapshot):
    """`SPLIT-081`: classify each source shard as unchanged, split, merged, or unaligned."""
    sources = ranges_of(before_snapshot)
    targets = ranges_of(after_snapshot)
    rows = []
    for source in sources:
        children = [t for t in targets if is_child(source, t)]
        if len(children) == 1 and same_bounds(source, children[0]):
            classification = "unchanged"
        elif len(children) >= 2 and covers_exactly(source, children):
            classification = "split"
        else:
            containers = [t for t in targets if is_child(t, source)]
            siblings = []
            if len(containers) == 1:
                siblings = [s for s in sources if is_child(containers[0], s)]
            if containers and len(siblings) >= 2 and covers_exactly(containers[0], siblings):
                classification = "merged"
            else:
                classification = "unaligned"
        row = {
            "shardId": source["shardId"],
            "classification": classification,
            "children": [c["shardId"] for c in children],
            "start": source["raw"]["start"],
            "end": source["raw"]["end"],
        }
        if classification == "merged":
            row["mergedInto"] = containers[0]["shardId"]
        rows.append(row)
    return rows


def plannable(rows):
    """`SPLIT-091`: a plan is refused across an `unaligned` classification."""
    offending = [r for r in rows if r["classification"] == "unaligned"]
    if offending:
        return {"plannable": False,
                "condition": {"code": 401, "name": "planRefused", "cause": "unalignedRanges"},
                "offendingShards": [r["shardId"] for r in offending],
                "offendingBounds": [{"shardId": r["shardId"], "start": r["start"],
                                     "end": r["end"]} for r in offending]}
    return {"plannable": True, "condition": None, "offendingShards": [],
            "offendingBounds": []}


def decompose(rows, replica_sets_before, replica_sets_after):
    """`SPLIT-121`, `SPLIT-131`, and `SPLIT-161`: the step sequence a classification implies."""
    steps = []
    for row in rows:
        if row["classification"] == "split":
            moved = [child for child in row["children"]
                     if replica_sets_after.get(child) != replica_sets_before.get(row["shardId"])]
            steps.append({
                "shardId": row["shardId"],
                "classification": "split",
                "localStep": "splitLocal",
                "localStepFirst": True,
                "handoffs": moved,
                "localStepAlone": not moved,
            })
        elif row["classification"] == "merged":
            steps.append({
                "shardId": row["shardId"],
                "classification": "merged",
                "localStep": "mergeLocal",
                "localStepFirst": False,
                "mergedInto": row["mergedInto"],
                "handoffs": [row["shardId"]]
                if replica_sets_after.get(row["mergedInto"])
                != replica_sets_before.get(row["shardId"]) else [],
                "localStepAlone": False,
            })
        elif row["classification"] == "unchanged":
            steps.append({
                "shardId": row["shardId"],
                "classification": "unchanged",
                "localStep": None,
                "localStepFirst": False,
                "handoffs": [row["shardId"]]
                if replica_sets_after.get(row["shardId"])
                != replica_sets_before.get(row["shardId"]) else [],
                "localStepAlone": False,
            })
    return steps
