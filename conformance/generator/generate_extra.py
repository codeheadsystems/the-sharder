#!/usr/bin/env python3
"""Vectors for rules that the strategy vectors reach only indirectly.

The error taxonomy, the document defaults, the node identity comparator, and the ownership delta
each have an output a data file can carry, and each is a rule a port can get wrong while passing
every placement vector.

    python3 generate_extra.py [--out <conformance root>]
"""

import argparse
import copy
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import topologies as T                                          # noqa: E402
from generate import key_spec, write_json                       # noqa: E402
from sharder_ref import handoff, lineage, placement, routing    # noqa: E402
from sharder_ref.topology import Snapshot                       # noqa: E402

ENTRIES = []


def emit(root, path, vector_set, kind, description, requirements, cases, topology=None,
         level="core"):
    payload = {"vectorSet": vector_set, "kind": kind, "level": level,
               "description": description,
               "requirements": sorted(set(requirements)), "cases": cases}
    if topology:
        import json as _json
        payload["topology"] = topology
        payload["topologyDigest"] = Snapshot(
            _json.loads((root / topology).read_text())).digest
    write_json(root / path, payload)
    ENTRIES.append(path)


# ------------------------------------------------------------------ error taxonomy

CONDITIONS = [
    (101, "noCandidate", "no", "the candidate ordering is empty",
     "inspect the topology; the same key answers the same way",
     ["emptyPlacementSet", "constraintExcludedAll", "noDirectoryEntry", "pinExcludedAll",
      "noSlotEntry", "zeroVirtualNodes", "authoredListExcludedAll",
      "noEligibleTokenOwner"]),
    (102, "exhausted", "yes", "the attempt sequence ran out after at least one attempt",
     "back off, then retry; a retryBudget cause means the cluster is shedding",
     ["preferenceList", "attemptLimit", "retryBudget"]),
    (103, "unready", "yes", "no snapshot is in force",
     "wait for a first document, bounded by initialTimeoutMillis", []),
    (104, "staleSnapshot", "yes", "the snapshot in force is stale and the policy is refuse",
     "wait for the provider, or serve the request from another region", []),
    (105, "invalidArgument", "no", "a caller-supplied argument is outside the contract",
     "correct the call", []),
    (201, "invalidTopology", "no", "a document failed schema or semantic validation",
     "fix the document at the authority; the snapshot in force is unchanged", []),
    (202, "topologyConflict", "no", "a differing identifier, or an equal epoch with a new digest",
     "an authority defect; two writers are publishing one identifier", []),
    (203, "staleDocument", "no", "an arriving epoch is below the epoch in force or below minEpoch",
     "none at the caller; the provider is serving a lagging replica", []),
    (204, "providerError", "yes", "the provider failed to deliver a document",
     "none; the snapshot in force stays in force and the backoff applies", []),
    (301, "notOwner", "at another node", "the recipient does not hold the shard for the key",
     "retry at currentOwner, bounded by maxRedirects", []),
    (302, "epochMismatch", "after a refresh", "the sender is behind or ahead of the recipient",
     "refresh the topology, then retry", ["senderBehind", "senderAhead", "unfenced"]),
    (303, "identityMismatch", "no", "the two topologyId values differ",
     "operator action; epochs under two identifiers are incomparable", []),
    (304, "redirectExhausted", "no", "a redirect walk reached its bound or revisited a node",
     "surface the failure; a retryBudget cause means the cluster is shedding",
     ["boundReached", "revisitedNode", "unknownNode", "retryBudget"]),
    (401, "planRefused", "no", "a plan cannot be built from the two snapshots and the policy",
     "correct the snapshots or the policy member named in cause",
     ["incomparableShards", "epochNotAdvancing", "strategyUnsupported",
      "destinationOutsidePlacementSet", "policyInvalid", "topologyMismatch", "unalignedLineage",
      "lineageUnsupported"]),
    (402, "quiesced", "yes", "the shard is inside the cutover window",
     "retry after the window, which commitDeadlineMillis bounds", []),
    (403, "handoffFailed", "no", "a handoff reached failed",
     "operator action, directed by the failure kind in cause; undetermined takes a"
     " re-observation",
     ["unverified", "residue", "undetermined", "rollbackFailed", "undivided"]),
]


def build_error_taxonomy(root):
    cases = [{
        "name": "closed-set",
        "requirements": ["ERR-001", "ERR-002", "ERR-003", "ERR-010", "ERR-011"],
        "note": "the closed set is exactly these sixteen.  A binding renders each as the idiom "
                "of its language and changes neither the code nor the name.",
        "expect": {
            "conditionCount": len(CONDITIONS),
            "conditions": [
                {"code": code, "name": name, "retryable": retryable, "condition": condition,
                 "callerResponse": response, "causes": causes}
                for code, name, retryable, condition, response, causes in CONDITIONS
            ],
            "codes": sorted(c[0] for c in CONDITIONS),
            "names": sorted(c[1] for c in CONDITIONS),
        },
    }, {
        "name": "report-order",
        "requirements": ["ERR-008"],
        "note": "`ERR-008`: where more than one condition holds for one call, the first that "
                "holds in this order is reported.",
        "expect": {"order": ["invalidArgument", "unready", "staleSnapshot", "noCandidate",
                             "exhausted"]},
    }, {
        "name": "shortfall-is-not-a-condition",
        "requirements": ["ERR-009", "REPL-025"],
        "note": "a preference list shorter than the effective replication factor is a successful "
                "routing decision, reported through `shortfall` on the decision.",
        "expect": {"raisesCondition": False, "reportedThrough": "shortfall"},
    }, {
        "name": "error-members",
        "requirements": ["ERR-004", "ERR-005", "ERR-006", "ERR-007"],
        "note": "every condition carries these members, and none carries the key unless "
                "`includeKeysInDiagnostics` is enabled.",
        "expect": {
            "members": ["code", "name", "retryable", "detail", "token", "shard", "cause",
                        "currentOwner"],
            "carriesKeyByDefault": False,
            "mergeablePairs": [],
            "causeIsAClosedSubReason": True,
        },
    }]
    emit(root, "vectors/errors/taxonomy.json", "error-taxonomy", "errorTaxonomy",
         "The closed set of failure conditions, their codes, their retryability, their closed "
         "cause sets, and the order in which one call reports the first that holds.",
         ["ERR-001", "ERR-002", "ERR-003", "ERR-004", "ERR-005", "ERR-006", "ERR-007",
          "ERR-008", "ERR-009", "ERR-010", "ERR-011", "ERR-021"], cases)


def build_recipient_taxonomy(root):
    """`ERR-045`, which orders the conditions only a recipient check raises.

    The order is a `fencing` rule, so it sits at the `fencing` level rather than beside the closed
    routing set of `ERR-010`, which every implementation exposes whole.
    """
    cases = [{
        "name": "recipient-report-order",
        "requirements": ["ERR-045"],
        "note": "`ERR-045`: where more than one recipient condition holds, the first that holds "
                "in this order is reported.  `ERR-008` does not cover these.",
        "expect": {"order": ["identityMismatch", "unready", "notOwner", "epochMismatch"]},
    }]
    emit(root, "vectors/errors/recipient-taxonomy.json", "error-recipient-taxonomy",
         "errorTaxonomy",
         "The order in which one request reports the first recipient condition that holds.",
         ["ERR-045"], cases, level="fencing")


# --------------------------------------------------------------- document defaults

MINIMAL = {
    "formatVersion": "1.0",
    "topologyId": "defaults-omitted",
    "epoch": 1,
    "strategy": {"kind": "ring"},
    "nodes": [{"id": "d1"}, {"id": "d2"}, {"id": "d3"}],
}

EXPLICIT = {
    "formatVersion": "1.0",
    "topologyId": "defaults-explicit",
    "epoch": 1,
    "hash": {"algorithm": "siphash-2-4", "seed": "00000000000000000000000000000000"},
    "keyTransform": {"kind": "none"},
    "domainLevels": [],
    "replication": {"factor": 1, "spread": [], "spreadPolicy": "relaxed"},
    "strategy": {"kind": "ring", "tokenAssignment": "derived", "tokensPerWeightUnit": 4,
                 "maxTokensPerNode": 4096},
    "nodes": [{"id": "d1", "state": "active", "weight": 1, "domains": {}, "tags": {}},
              {"id": "d2", "state": "active", "weight": 1, "domains": {}, "tags": {}},
              {"id": "d3", "state": "active", "weight": 1, "domains": {}, "tags": {}}],
    "overrides": [],
    "metadata": {},
}

# `SLOT-024` gives the assignment mode a document that omits the member.  The pair carries one
# document that omits `assignment` and one that names the mode the default supplies, and the suite
# asserts that the two route identically.
SLOT_ASSIGNMENT_OMITTED = copy.deepcopy(T.SLOT_EXPLICIT)
SLOT_ASSIGNMENT_OMITTED["topologyId"] = "slot-assignment-omitted"
del SLOT_ASSIGNMENT_OMITTED["strategy"]["assignment"]

SLOT_ASSIGNMENT_NAMED = copy.deepcopy(T.SLOT_EXPLICIT)
SLOT_ASSIGNMENT_NAMED["topologyId"] = "slot-assignment-named"

SLOT_WEIGHTED = copy.deepcopy(T.SLOT_EXPLICIT)
SLOT_WEIGHTED["topologyId"] = "slot-explicit-weighted"
for node in SLOT_WEIGHTED["nodes"]:
    node["weight"] = 7 if node["id"] == "shard-a" else 1


def build_defaults(root):
    write_json(root / "topologies/defaults-omitted.topology.json", MINIMAL)
    write_json(root / "topologies/defaults-explicit.topology.json", EXPLICIT)
    write_json(root / "topologies/slot-explicit-weighted.topology.json", SLOT_WEIGHTED)

    minimal, explicit = Snapshot(MINIMAL), Snapshot(EXPLICIT)
    rows = []
    for i in range(8):
        key = ("defaults-%d" % i).encode()
        left = routing.route(minimal, key)
        right = routing.route(explicit, key)
        assert left["candidates"] == right["candidates"]
        assert left["preferenceList"] == right["preferenceList"]
        rows.append({"key": key_spec(key), "candidates": left["candidates"],
                     "replicaCount": left["replicaCount"], "factor": left["factor"]})

    cases = [{
        "name": "omitted-equals-explicit",
        "requirements": ["PLACE-040", "KEY-010", "REPL-001", "SPREAD-003", "RING-001",
                         "RING-006", "PLACE-050"],
        "omittedDocument": "topologies/defaults-omitted.topology.json",
        "explicitDocument": "topologies/defaults-explicit.topology.json",
        "note": "a document omitting every optional member routes identically to one writing "
                "every default out.  `weight` defaults to 1, `replication.factor` to 1, "
                "`keyTransform` to `none`, `tokenAssignment` to `derived` under `RING-006`, and "
                "the ring sizing fields to 4 and 4096.",
        "expect": {"identical": True, "rows": rows},
    }]

    base, weighted = Snapshot(T.SLOT_EXPLICIT), Snapshot(SLOT_WEIGHTED)
    weighted_rows = []
    for key in [b"slot-1", b"slot-4", b"slot-9"]:
        left = routing.route(base, key)
        right = routing.route(weighted, key)
        assert left["candidates"] == right["candidates"]
        weighted_rows.append({"key": key_spec(key), "candidates": left["candidates"]})
    cases.append({
        "name": "weight-advisory-under-explicit-assignment",
        "requirements": ["PLACE-044", "SLOT-014", "DIR-004"],
        "omittedDocument": "topologies/slot-explicit.topology.json",
        "explicitDocument": "topologies/slot-explicit-weighted.topology.json",
        "note": "raising one node's weight sevenfold changes no ordering under an authored "
                "assignment; weight is advisory there.",
        "expect": {"identical": True, "rows": weighted_rows},
    })

    for label, omitted, named, keys, requirement in [
        ("slot", SLOT_ASSIGNMENT_OMITTED, SLOT_ASSIGNMENT_NAMED,
         [b"slot-a", b"slot-b", b"slot-c", b"slot-d"], "SLOT-024"),
    ]:
        write_json(root / ("topologies/%s-assignment-omitted.topology.json" % label), omitted)
        write_json(root / ("topologies/%s-assignment-named.topology.json" % label), named)
        left_snapshot, right_snapshot = Snapshot(omitted), Snapshot(named)
        mode = right_snapshot.strategy["assignment"]
        assignment_rows = []
        for key in keys:
            left = routing.route(left_snapshot, key)
            right = routing.route(right_snapshot, key)
            assert left["candidates"] == right["candidates"]
            assignment_rows.append({"key": key_spec(key), "candidates": left["candidates"]})
        cases.append({
            "name": "%s-assignment-default" % label,
            "requirements": [requirement, "PLACE-040"],
            "omittedDocument": "topologies/%s-assignment-omitted.topology.json" % label,
            "explicitDocument": "topologies/%s-assignment-named.topology.json" % label,
            "note": "a `%s` document that omits `assignment` routes as one naming `%s`."
                    % (label, mode),
            "expect": {"identical": True, "rows": assignment_rows},
        })

    emit(root, "vectors/placement/document-defaults.json", "placement-document-defaults",
         "defaults",
         "A document that omits every optional member and one that writes every default out "
         "produce identical orderings, weight changes nothing under an authored assignment, and "
         "an omitted `assignment` takes the default its strategy gives.",
         ["PLACE-040", "PLACE-044", "KEY-010", "REPL-001", "SPREAD-003", "SLOT-014",
          "SLOT-024", "RING-006", "DIR-004"], cases, level="place")


# ------------------------------------------------------- node identity comparison

IDENTITY_PAIRS = [
    ("a", "b", "an ordinary ordering"),
    ("a", "a", "equality"),
    ("a", "ab", "a proper prefix compares less"),
    ("Node", "node", "case sensitive: 0x4e is below 0x6e"),
    ("Z", "a", "0x5a is below 0x61, which a case-insensitive comparator reverses"),
    ("a", "B", "0x61 is above 0x42, which a locale collation commonly reverses"),
    ("n-1", "n1", "0x2d is below 0x31"),
    ("n2", "n10", "octet order, not numeric order"),
    ("café", "cafz", "the UTF-8 octets of é begin 0xc3, which is above 0x7a"),
    ("", "a", "the empty identity compares below every non-empty one"),
    ("ÿ", "Ā", "two code points whose UTF-8 encodings order the same way"),
]


def build_identity_comparison(root):
    cases = []
    for left, right, note in IDENTITY_PAIRS:
        a, b = left.encode("utf-8"), right.encode("utf-8")
        result = "equal" if a == b else ("less" if a < b else "greater")
        cases.append({
            "name": "%s|%s" % (left or "<empty>", right),
            "requirements": ["PLACE-020", "PLACE-021", "PLACE-022", "CORE-003"],
            "formula": "compareNodeIdentity",
            "inputs": {"left": left, "right": right,
                       "leftOctets": a.hex(), "rightOctets": b.hex()},
            "note": note,
            "expect": result,
        })
    emit(root, "vectors/placement/identity-comparison.json", "placement-identity-comparison",
         "formula",
         "The node identity comparator: unsigned octets of the UTF-8 encoding, lexicographic, "
         "with a proper prefix comparing less.  It is the final tie-break of every ordering the "
         "specification defines, so a port that compares by code point, by collation, or case "
         "insensitively passes every ordering vector and fails on the first tie.",
         ["PLACE-020", "PLACE-021", "PLACE-022", "PLACE-023", "CORE-003"], cases, level="place")


# ------------------------------------------------------------------ ownership delta

DELTA_BEFORE = {
    "formatVersion": "1.0", "topologyId": "delta", "epoch": 1,
    "replication": {"factor": 2},
    "strategy": {"kind": "slot", "slotCount": 6, "assignment": "explicit",
                 "assignments": [{"slots": ["0-2"], "nodes": ["a", "b"]},
                                 {"slots": ["3-5"], "nodes": ["b", "c"]}]},
    "nodes": [{"id": "a"}, {"id": "b"}, {"id": "c"}, {"id": "d"}],
}

DELTA_MOVED = copy.deepcopy(DELTA_BEFORE)
DELTA_MOVED["epoch"] = 2
DELTA_MOVED["strategy"]["assignments"] = [{"slots": ["0-2"], "nodes": ["a", "b"]},
                                          {"slots": ["3-5"], "nodes": ["d", "c"]}]

DELTA_REORDERED = copy.deepcopy(DELTA_BEFORE)
DELTA_REORDERED["epoch"] = 3
DELTA_REORDERED["strategy"]["assignments"] = [{"slots": ["0-2"], "nodes": ["b", "a"]},
                                              {"slots": ["3-5"], "nodes": ["b", "c"]}]

DELTA_OTHER_COUNT = copy.deepcopy(DELTA_BEFORE)
DELTA_OTHER_COUNT["epoch"] = 4
DELTA_OTHER_COUNT["strategy"]["slotCount"] = 12
DELTA_OTHER_COUNT["strategy"]["assignments"] = [{"slots": ["0-11"], "nodes": ["a", "b"]}]

DELTA_OTHER_SEED = copy.deepcopy(DELTA_BEFORE)
DELTA_OTHER_SEED["epoch"] = 5
DELTA_OTHER_SEED["hash"] = {"algorithm": "siphash-2-4", "seed": T.STORAGE_SEED}

# `TOPO-213`: sixteen slots, so the ascending slot index of `SLOT-031` and the octet order of the
# shard identifiers disagree.  Slots 8 through 15 move, which the index order lists as 8, 9, 10 and
# the octet order as 10, 11, ..., 8, 9.
DELTA_WIDE_BEFORE = {
    "formatVersion": "1.0", "topologyId": "delta-wide", "epoch": 1,
    "replication": {"factor": 2},
    "strategy": {"kind": "slot", "slotCount": 16, "assignment": "explicit",
                 "assignments": [{"slots": ["0-15"], "nodes": ["a", "b"]}]},
    "nodes": [{"id": "a"}, {"id": "b"}, {"id": "c"}],
}

DELTA_WIDE_AFTER = copy.deepcopy(DELTA_WIDE_BEFORE)
DELTA_WIDE_AFTER["epoch"] = 2
DELTA_WIDE_AFTER["strategy"]["assignments"] = [{"slots": ["0-7"], "nodes": ["a", "b"]},
                                               {"slots": ["8-15"], "nodes": ["c", "b"]}]

# `TOPO-211`: `spreadPolicy` of `strict` over one zone level admits two of the three replicas the
# factor asks for, so the replica set is the two entries whose role is `replica` and the third
# preference list entry is a fallback the delta must not report as an owner.
DELTA_SHORT_BEFORE = {
    "formatVersion": "1.0", "topologyId": "delta-short", "epoch": 1,
    "domainLevels": ["zone"],
    "replication": {"factor": 3, "spread": ["zone"], "spreadPolicy": "strict"},
    "strategy": {"kind": "slot", "slotCount": 4, "assignment": "explicit",
                 "assignments": [{"slots": ["0-3"], "nodes": ["a", "b", "c", "d"]}]},
    "nodes": [{"id": "a", "domains": {"zone": "z1"}}, {"id": "b", "domains": {"zone": "z1"}},
              {"id": "c", "domains": {"zone": "z2"}}, {"id": "d", "domains": {"zone": "z2"}}],
}

DELTA_SHORT_AFTER = copy.deepcopy(DELTA_SHORT_BEFORE)
DELTA_SHORT_AFTER["epoch"] = 2
DELTA_SHORT_AFTER["strategy"]["assignments"] = [{"slots": ["0-3"],
                                                 "nodes": ["d", "b", "c", "a"]}]


# `TOPO-213`: a `ring` pair whose two snapshots enumerate different shard sets.  Adding the token
# `0000000000002000` divides the extent the token `0000000000003000` bounded, so the later snapshot
# enumerates a shard the earlier one does not; removing it folds that extent back, so the earlier
# snapshot enumerates one the later one does not.  Every other pair in this file is `slot` at a
# fixed `slotCount`, where the two shard sets are always equal and the second clause of `TOPO-213`
# is unreachable.
DELTA_RING_BEFORE = {
    "formatVersion": "1.0", "topologyId": "delta-ring", "epoch": 1,
    "replication": {"factor": 2},
    "strategy": {"kind": "ring", "tokenAssignment": "explicit"},
    "nodes": [{"id": "a", "tokens": ["0000000000001000", "0000000000005000"]},
              {"id": "b", "tokens": ["0000000000003000", "0000000000007000"]}],
}

DELTA_RING_ADDED = copy.deepcopy(DELTA_RING_BEFORE)
DELTA_RING_ADDED["epoch"] = 2
DELTA_RING_ADDED["nodes"].append({"id": "c", "tokens": ["0000000000002000"]})

DELTA_RING_REMOVED = copy.deepcopy(DELTA_RING_BEFORE)
DELTA_RING_REMOVED["epoch"] = 3


# `LIN-022`: removing the token `0000000000003000` and adding `0000000000002000` in the same epoch
# moves a boundary without dividing or folding an extent whole.  The extent `(2000, 5000]` of the
# later snapshot overlaps `(1000, 3000]` of the earlier one and neither contains the other, so the
# lineage is unaligned and the plan is refused.
DELTA_RING_UNALIGNED = copy.deepcopy(DELTA_RING_BEFORE)
DELTA_RING_UNALIGNED["epoch"] = 4
DELTA_RING_UNALIGNED["nodes"] = [{"id": "a", "tokens": ["0000000000001000", "0000000000005000"]},
                                 {"id": "b", "tokens": ["0000000000007000"]},
                                 {"id": "c", "tokens": ["0000000000002000"]}]

# `LIN-013`: a `directory` pair whose entry sets differ.  A directory extent is a matcher narrowed
# by the precedence of `DIR-002`, which is decidable and not yet defined, so the pair is refused.
LINEAGE_DIR_BEFORE = {
    "formatVersion": "1.0", "topologyId": "lineage-directory", "epoch": 1,
    "replication": {"factor": 1},
    "strategy": {"kind": "directory",
                 "entries": [{"match": {"kind": "prefix", "value": "ab"}, "nodes": ["d1"]},
                             {"match": {"kind": "prefix", "value": "cd"}, "nodes": ["d2"]}]},
    "nodes": [{"id": "d1"}, {"id": "d2"}],
}

# `LIN-043`: `ef` matched no entry of the earlier table, so the shard it names has no parent and
# no contents to move.  A `directory` table is the only place a fresh extent arises, because it is
# the only kind whose `shardOf` answers with no shard under `DIR-010`.
LINEAGE_DIR_FRESH = copy.deepcopy(LINEAGE_DIR_BEFORE)
LINEAGE_DIR_FRESH["epoch"] = 3
LINEAGE_DIR_FRESH["strategy"]["entries"] = [
    {"match": {"kind": "prefix", "value": "ab"}, "nodes": ["d1"]},
    {"match": {"kind": "prefix", "value": "cd"}, "nodes": ["d2"]},
    {"match": {"kind": "prefix", "value": "ef"}, "nodes": ["d1"]},
]

LINEAGE_DIR_REFINED = copy.deepcopy(LINEAGE_DIR_BEFORE)
LINEAGE_DIR_REFINED["epoch"] = 2
LINEAGE_DIR_REFINED["strategy"]["entries"] = [
    {"match": {"kind": "prefix", "value": "ab0"}, "nodes": ["d1"]},
    {"match": {"kind": "prefix", "value": "ab1"}, "nodes": ["d2"]},
    {"match": {"kind": "prefix", "value": "ab"}, "nodes": ["d1"]},
    {"match": {"kind": "prefix", "value": "cd"}, "nodes": ["d2"]},
]


def build_lineage(root):
    """`LIN-*`: the classification over pairs of documents, and the plan built over it."""
    documents = {"lineage-directory-before": LINEAGE_DIR_BEFORE,
                 "lineage-directory-refined": LINEAGE_DIR_REFINED,
                 "lineage-directory-fresh": LINEAGE_DIR_FRESH,
                 "delta-ring-unaligned": DELTA_RING_UNALIGNED}
    for name, document in documents.items():
        write_json(root / ("topologies/%s.topology.json" % name), document)

    snap = {name: Snapshot(d) for name, d in documents.items()}
    snap.update({name: Snapshot(d) for name, d in
                 {"delta-ring-before": DELTA_RING_BEFORE, "delta-ring-added": DELTA_RING_ADDED,
                  "delta-ring-removed": DELTA_RING_REMOVED, "delta-before": DELTA_BEFORE,
                  "delta-moved": DELTA_MOVED,
                  "delta-other-seed": DELTA_OTHER_SEED}.items()})
    snap["rendezvous-plain"] = Snapshot(
        json.loads((root / "topologies/rendezvous-plain.topology.json").read_text()))

    def replicas(snapshot, shard):
        return handoff.replica_set(snapshot, shard, lambda s: s.factor)

    def path(name):
        return "topologies/%s.topology.json" % name

    cases = []
    for label, before, after, requirements, note in [
        ("ring-extent-divided", "delta-ring-before", "delta-ring-added",
         ["LIN-004", "LIN-011", "LIN-021", "LIN-031", "LIN-033"],
         "`LIN-011`: the added token divides `(1000, 3000]` into `(1000, 2000]` and "
         "`(2000, 3000]`, so both shards of the later snapshot are `divided` from one parent and "
         "the shard whose extent did not move is `moved` because its replica set did."),
        ("ring-extent-folded", "delta-ring-added", "delta-ring-removed",
         ["LIN-004", "LIN-011", "LIN-021", "LIN-033"],
         "`LIN-021`: removing the token folds `(1000, 2000]` into the extent that follows it, so "
         "the later shard is `merged` from two parents and the shard only the earlier snapshot "
         "enumerates is `folded` and follows every later entry under `LIN-033`."),
        ("directory-prefix-refined", "lineage-directory-before", "lineage-directory-refined",
         ["LIN-013", "LIN-016", "LIN-021", "DIR-002", "PLACE-065"],
         "`LIN-016`: refining `prefix:ab` into `ab0` and `ab1` leaves `ab` winning the keys "
         "neither longer prefix claims, so all three shards of the later table are `divided` from "
         "the one entry and the untouched `cd` is `unchanged`."),
        ("directory-fresh-extent", "lineage-directory-before", "lineage-directory-fresh",
         ["LIN-013", "LIN-016", "LIN-021", "DIR-010"],
         "`LIN-021`: the added entry wins keys the earlier table matched to no shard under "
         "`DIR-010`, so the shard it names is `fresh` and has no parent to draw from."),
        ("slot-identity", "delta-before", "delta-moved",
         ["LIN-007", "LIN-012", "LIN-021"],
         "`LIN-012`: `TOPO-231` holds `slotCount` equal, so the two snapshots enumerate the same "
         "shards, the lineage is the identity of `LIN-007`, and a shard is `moved` or `unchanged` "
         "and never divided, merged, fresh, or vacated."),
    ]:
        cases.append({
            "name": label, "requirements": requirements,
            "before": path(before), "after": path(after), "note": note,
            "expect": {"lineage": lineage.classify(snap[before], snap[after], replicas)},
        })

    for label, before, after, requirements, cause, note in [
        ("ring-unaligned-boundary", "delta-ring-before", "delta-ring-unaligned",
         ["LIN-021", "LIN-022", "ERR-050"], "unalignedLineage",
         "`LIN-022`: a boundary that moves without dividing or folding an extent whole has no "
         "correspondence to name, so the plan is refused and the authority publishes the change "
         "as a division epoch followed by a fold epoch."),
        ("incomparable-shard-identity", "delta-before", "delta-other-seed",
         ["LIN-006", "TOPO-231", "ERR-050"], "incomparableShards",
         "`LIN-006`: a lineage joins two snapshots on their extents and an ownership delta joins "
         "them on their identifiers, and a change that renames every shard leaves neither one an "
         "answer, so both refuse on the condition `TOPO-231` states."),
        ("rendezvous-has-no-extent", "rendezvous-plain", "rendezvous-plain",
         ["LIN-014", "MOVE-241", "ERR-050"], "strategyUnsupported",
         "`LIN-014`: `PLACE-032` makes `shards` empty under `rendezvous`, so the kind has no "
         "extent and no lineage, and `MOVE-251` refuses the plan before one is reached."),
    ]:
        cases.append({
            "name": label, "requirements": requirements,
            "before": path(before), "after": path(after), "note": note,
            "expect": {"lineageComputed": False,
                       "condition": {"code": 401, "name": "planRefused", "cause": cause}},
        })

    emit(root, "vectors/migration/lineage.json", "migration-lineage", "lineage",
         "The lineage classification over pairs of documents: a ring extent divided, a ring extent "
         "folded, the identity lineage under `slot`, the unaligned boundary that is refused, the "
         "`directory` prefix refined and the fresh extent beside it, the incomparable pair, and "
         "the kind that enumerates no shard.",
         ["LIN-004", "LIN-006", "LIN-007", "LIN-011", "LIN-012", "LIN-013", "LIN-014", "LIN-016",
          "LIN-021", "LIN-022", "LIN-031", "LIN-033", "DIR-002", "DIR-010", "PLACE-065",
          "TOPO-231", "MOVE-241", "ERR-050"], cases, level="migration")

    plans = []
    for label, before, after, requirements, note in [
        ("divided-source-from-the-parent", "delta-ring-before", "delta-ring-added",
         ["LIN-041", "LIN-042", "LIN-045", "LIN-051", "LIN-052", "LIN-057"],
         "`LIN-041`: the contents of the divided shard `0000000000002000` are held by the "
         "replicas of its parent `0000000000003000`, so the handoff names one of them as its "
         "source. A plan built over the ownership delta alone has no entry for the parent, whose "
         "replica set did not change, and names a source equal to the destination."),
        ("folded-destination-outside-the-delta", "delta-ring-added", "delta-ring-removed",
         ["LIN-041", "LIN-044", "LIN-045", "LIN-051", "LIN-052", "LIN-057"],
         "`LIN-044`: the shard that absorbs the folded extent keeps its replica set, so the "
         "ownership delta reports no entry for it, and a plan built over the delta alone moves "
         "nothing to the replica that does not hold the folded parent."),
        ("slot-plan-over-the-identity-lineage", "delta-before", "delta-moved",
         ["LIN-041", "LIN-045"],
         "`LIN-007`: under the identity lineage every shard is its own parent, so the plan is the "
         "one the ownership delta already produced and this case is the regression guard for it."),
    ]:
        plans.append({
            "name": label, "requirements": requirements,
            "before": path(before), "after": path(after), "note": note,
            "expect": {"handoffs": lineage.plan_handoffs(snap[before], snap[after], replicas)},
        })

    emit(root, "vectors/migration/plan-construction.json", "migration-plan-construction",
         "planConstruction",
         "How a plan derives a handoff from a lineage: the source of a divided shard, the "
         "destination of a fold that the ownership delta does not report, the local steps beside "
         "them and their order, and the plan under the identity lineage.",
         ["LIN-041", "LIN-042", "LIN-044", "LIN-045", "LIN-051", "LIN-052", "LIN-057"], plans,
         level="migration")



def build_ownership_delta(root):
    documents = {"delta-before": DELTA_BEFORE, "delta-moved": DELTA_MOVED,
                 "delta-reordered": DELTA_REORDERED, "delta-other-slot-count": DELTA_OTHER_COUNT,
                 "delta-other-seed": DELTA_OTHER_SEED,
                 "delta-wide-before": DELTA_WIDE_BEFORE, "delta-wide-after": DELTA_WIDE_AFTER,
                 "delta-short-before": DELTA_SHORT_BEFORE, "delta-short-after": DELTA_SHORT_AFTER,
                 "delta-ring-before": DELTA_RING_BEFORE, "delta-ring-added": DELTA_RING_ADDED,
                 "delta-ring-removed": DELTA_RING_REMOVED}
    for name, document in documents.items():
        write_json(root / ("topologies/%s.topology.json" % name), document)
    snapshots = {name: Snapshot(d) for name, d in documents.items()}

    cases = []
    for label, target, note in [
        ("one-shard-moved", "delta-moved",
         "slots 3 to 5 lose b and gain d; slots 0 to 2 are unchanged"),
        ("reorder-only", "delta-reordered",
         "`TOPO-241`: a shard whose replica set is the same set in a different order is a delta "
         "entry with no node gained and no node lost"),
    ]:
        rows = handoff.ownership_delta(snapshots["delta-before"], snapshots[target],
                                       lambda s: s.factor)
        cases.append({
            "name": label,
            "requirements": ["TOPO-211", "TOPO-221", "TOPO-241"],
            "before": "topologies/delta-before.topology.json",
            "after": "topologies/%s.topology.json" % target,
            "note": note,
            "expect": {"shardsChanged": len(rows), "delta": rows},
        })

    for label, target, field in [("differing-slot-count", "delta-other-slot-count", "slotCount"),
                                 ("differing-seed", "delta-other-seed", "hash.seed")]:
        cases.append({
            "name": label,
            "requirements": ["TOPO-231", "SEC-013", "ERR-010"],
            "before": "topologies/delta-before.topology.json",
            "after": "topologies/%s.topology.json" % target,
            "note": "`TOPO-231`: shard identity is not comparable across a change to %s, so the "
                    "delta is refused rather than computed." % field,
            "expect": {"comparable": False, "differingField": field,
                       "condition": {"code": 401, "name": "planRefused",
                                     "cause": "incomparableShards"}},
        })

    for label, before, after, requirements, note in [
        ("slot-index-order", "delta-wide-before", "delta-wide-after",
         ["TOPO-211", "TOPO-213", "PLACE-031", "SLOT-031"],
         "`TOPO-213`: the entries follow the ascending slot index `SLOT-031` enumerates, so slots "
         "8 and 9 precede slot 10.  Ordering the shard identifiers as octets would put 10 first "
         "and is the divergence a `slotCount` at or below 10 cannot show."),
        ("ring-token-added", "delta-ring-before", "delta-ring-added",
         ["TOPO-211", "TOPO-213", "PLACE-031", "RING-031"],
         "`TOPO-213`: the added token divides the extent `0000000000003000` bounded, so the "
         "second snapshot enumerates `0000000000002000` and the first does not.  That shard's "
         "entry carries an empty before set and reports every node it gained."),
        ("ring-token-removed", "delta-ring-added", "delta-ring-removed",
         ["TOPO-211", "TOPO-213", "PLACE-031", "RING-031"],
         "`TOPO-213`: removing the token folds `0000000000002000` into the extent that follows "
         "it, so only the first snapshot enumerates that shard.  Its entry carries an empty "
         "after set and follows every entry for a shard the second snapshot enumerates."),
        ("replica-prefix-short-of-factor", "delta-short-before", "delta-short-after",
         ["TOPO-211", "REPL-017", "REPL-020", "SPREAD-014"],
         "`TOPO-211`: `strict` over one zone level admits two replicas of the three the factor "
         "asks for, so the replica set is the two entries whose role is `replica` and the third "
         "preference list entry is a fallback under `REPL-013` rather than an owner."),
    ]:
        rows = handoff.ownership_delta(snapshots[before], snapshots[after], lambda s: s.factor)
        cases.append({
            "name": label,
            "requirements": requirements,
            "before": "topologies/%s.topology.json" % before,
            "after": "topologies/%s.topology.json" % after,
            "note": note,
            "expect": {"shardsChanged": len(rows), "delta": rows},
        })

    emit(root, "vectors/topology/ownership-delta.json", "topology-ownership-delta",
         "ownershipDelta",
         "The ownership delta between two snapshots, the reorder-only case that gains and loses "
         "nothing, the entry order over sixteen slots, the replica set under a shortfall, the "
         "`ring` pair whose two snapshots enumerate different shard sets, and the changes that "
         "make shard identity incomparable.",
         ["TOPO-211", "TOPO-213", "TOPO-221", "TOPO-231", "TOPO-241", "PLACE-031", "SLOT-031",
          "RING-031", "REPL-017", "REPL-020", "SPREAD-014", "SEC-013", "ERR-010"], cases)

    unsupported = [{
        "name": "rendezvous-enumerates-no-shard",
        "requirements": ["TOPO-211", "MOVE-241", "MOVE-251", "MOVE-271", "RV-021"],
        "before": "topologies/rendezvous-plain.topology.json",
        "after": "topologies/rendezvous-plain.topology.json",
        "note": "`MOVE-271`: under a strategy that enumerates no shard the delta is empty and a "
                "plan is refused naming the strategy kind.",
        "expect": {"shardsChanged": 0, "delta": [], "plannable": False,
                   "condition": {"code": 401, "name": "planRefused",
                                 "cause": "strategyUnsupported"}},
    }]
    emit(root, "vectors/topology/ownership-delta-unsupported.json",
         "topology-ownership-delta-unsupported", "ownershipDelta",
         "The empty delta and the refused plan under a strategy that enumerates no shard.",
         ["TOPO-211", "MOVE-241", "MOVE-251", "MOVE-271", "RV-021"], unsupported,
         level="migration")


# ------------------------------------------------- ring weight zero, pins, and spread

RING_WEIGHT_ZERO = {
    "formatVersion": "1.0", "topologyId": "ring-weight-zero", "epoch": 1,
    "replication": {"factor": 2},
    "strategy": {"kind": "ring", "tokenAssignment": "derived", "tokensPerWeightUnit": 4},
    "nodes": [{"id": "rz-live-1", "weight": 1}, {"id": "rz-live-2", "weight": 1},
              {"id": "rz-zero", "weight": 0}],
}

RING_PINNED = {
    "formatVersion": "1.0", "topologyId": "ring-pinned", "epoch": 1,
    "replication": {"factor": 2},
    "strategy": {"kind": "ring", "tokenAssignment": "derived", "tokensPerWeightUnit": 2},
    "nodes": [{"id": "rp1"}, {"id": "rp2"}, {"id": "rp3"}],
    "overrides": [{"match": {"kind": "exact", "value": "pinned-key"}, "pin": ["rp3", "rp1"]}],
}

SPREAD_PINNED = {
    "formatVersion": "1.0", "topologyId": "spread-pinned", "epoch": 1,
    "domainLevels": ["zone"],
    "replication": {"factor": 3, "spread": ["zone"], "spreadPolicy": "strict"},
    "strategy": {"kind": "rendezvous"},
    "nodes": [{"id": "sp-a1", "domains": {"zone": "za"}},
              {"id": "sp-a2", "domains": {"zone": "za"}},
              {"id": "sp-b1", "domains": {"zone": "zb"}},
              {"id": "sp-c1", "domains": {"zone": "zc"}}],
    "overrides": [{"match": {"kind": "exact", "value": "pinned-key"},
                   "pin": ["sp-a1", "sp-a2", "sp-b1", "sp-c1"]}],
}


def build_ring_and_pin_cases(root):
    for name, document in [("ring-weight-zero", RING_WEIGHT_ZERO),
                           ("ring-pinned", RING_PINNED),
                           ("spread-pinned", SPREAD_PINNED)]:
        write_json(root / ("topologies/%s.topology.json" % name), document)

    snapshot = Snapshot(RING_WEIGHT_ZERO)
    cases = []
    for i in range(6):
        key = ("rz-%d" % i).encode()
        decision = routing.route(snapshot, key)
        assert "rz-zero" not in decision["candidates"]
        cases.append({"name": "weight-zero/%d" % i,
                      "requirements": ["RING-002", "PLACE-042"],
                      "key": key_spec(key), "expect": decision})
    emit(root, "vectors/ring/weight-zero.json", "ring-weight-zero", "routing",
         "A node of weight 0 under derived token assignment owns no token and never appears.",
         ["RING-001", "RING-002", "PLACE-042", "PLACE-043", "CORE-046", "CORE-047"], cases,
         topology="topologies/ring-weight-zero.topology.json", level="place")

    snapshot = Snapshot(RING_PINNED)
    cases = []
    for label, key in [("pinned", b"pinned-key"), ("unpinned", b"other-key")]:
        decision = routing.route(snapshot, key)
        routing_key = routing.routing_key_of(snapshot, key)
        shard = placement.shard_of(snapshot, routing_key)
        for_shard = placement.candidates_for_shard(snapshot, shard, snapshot.placement_set)
        cases.append({
            "name": label,
            "requirements": ["OVR-016", "PLACE-030", "PLACE-033"],
            "key": key_spec(key),
            "note": "`OVR-016`: `shardOf` keeps answering for a pinned key, and the pinned "
                    "ordering differs from the shard's ordering, so `PLACE-033` does not hold "
                    "for a pinned routing key.",
            "expect": {"shard": shard, "candidates": decision["candidates"],
                       "candidatesForShard": for_shard,
                       "agrees": decision["candidates"] == for_shard,
                       "matchedOverride": decision["matchedOverride"]},
        })
    emit(root, "vectors/overrides/pin-keeps-shard.json", "overrides-pin-keeps-shard", "pinShard",
         "A pinned routing key keeps its shard identifier, and its candidate ordering differs "
         "from the ordering the shard itself produces.",
         ["OVR-010", "OVR-016", "PLACE-030", "PLACE-033"], cases,
         topology="topologies/ring-pinned.topology.json", level="place")

    snapshot = Snapshot(SPREAD_PINNED)
    cases = []
    for label, key in [("pinned", b"pinned-key"), ("unpinned", b"other-key")]:
        decision = routing.route(snapshot, key)
        cases.append({
            "name": label,
            "requirements": ["SPREAD-004", "OVR-013", "PROP-050"],
            "key": key_spec(key),
            "note": "`SPREAD-004`: spread applies to a pinned ordering by filtering it, never by "
                    "reordering it.  Under `strict` the pinned key admits one node per zone, so "
                    "`sp-a2` is skipped and reappears in the tail at its original position.",
            "expect": decision,
        })
    emit(root, "vectors/spread/applies-to-pinned-ordering.json",
         "spread-applies-to-pinned-ordering", "routing",
         "A pin is exempt from the balance and movement bounds and is not exempt from spread.",
         ["SPREAD-004", "OVR-013", "PROP-050", "REPL-013", "CORE-046", "CORE-047"], cases,
         topology="topologies/spread-pinned.topology.json", level="place")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(HERE.parent))
    args = parser.parse_args()
    root = Path(args.out)

    build_error_taxonomy(root)
    build_recipient_taxonomy(root)
    build_defaults(root)
    build_identity_comparison(root)
    build_ownership_delta(root)
    build_lineage(root)
    build_ring_and_pin_cases(root)

    print("wrote %d further vector files" % len(ENTRIES))
    return 0


if __name__ == "__main__":
    sys.exit(main())
