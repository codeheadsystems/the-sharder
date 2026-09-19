#!/usr/bin/env python3
"""Vectors for rules that the strategy vectors reach only indirectly.

The error taxonomy, the document defaults, the node identity comparator, and the ownership delta
each have an output a data file can carry, and each is a rule a port can get wrong while passing
every placement vector.

    python3 generate_extra.py [--out <conformance root>]
"""

import argparse
import copy
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import topologies as T                                          # noqa: E402
from generate import key_spec, write_json                       # noqa: E402
from sharder_ref import handoff, placement, routing             # noqa: E402
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
      "destinationOutsidePlacementSet", "policyInvalid", "topologyMismatch"]),
    (402, "quiesced", "yes", "the shard is inside the cutover window",
     "retry after the window, which commitDeadlineMillis bounds", []),
    (403, "handoffFailed", "no", "a handoff reached failed",
     "operator action, directed by the failure kind in cause; undetermined takes a"
     " re-observation",
     ["unverified", "residue", "undetermined", "rollbackFailed"]),
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


def build_ownership_delta(root):
    documents = {"delta-before": DELTA_BEFORE, "delta-moved": DELTA_MOVED,
                 "delta-reordered": DELTA_REORDERED, "delta-other-slot-count": DELTA_OTHER_COUNT,
                 "delta-other-seed": DELTA_OTHER_SEED,
                 "delta-wide-before": DELTA_WIDE_BEFORE, "delta-wide-after": DELTA_WIDE_AFTER,
                 "delta-short-before": DELTA_SHORT_BEFORE, "delta-short-after": DELTA_SHORT_AFTER}
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
         "nothing, the entry order over sixteen slots, the replica set under a shortfall, and the "
         "changes that make shard identity incomparable.",
         ["TOPO-211", "TOPO-213", "TOPO-221", "TOPO-231", "TOPO-241", "PLACE-031", "SLOT-031",
          "REPL-017", "REPL-020", "SPREAD-014", "SEC-013", "ERR-010"], cases)

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
    build_ring_and_pin_cases(root)

    print("wrote %d further vector files" % len(ENTRIES))
    return 0


if __name__ == "__main__":
    sys.exit(main())
