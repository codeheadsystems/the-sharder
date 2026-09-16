#!/usr/bin/env python3
"""Generate the property definitions and their computed witnesses.

A property is a claim over a sample rather than over one key, so it is stated here as data: the
requirement identifiers it proves, the topologies it runs against, the key sample, the sample
size at which the specification's precondition holds, and the exact integer inequality to
evaluate.  Every bound and every sample-size precondition is the specification's own, taken from
`PROP-*`; none is loosened.

Where the reference can evaluate a property within a reasonable running time, this script also
writes a witness: the observed counts and the evaluated inequality.  A witness turns a
statistical property into a golden vector, because the sample is deterministic, so a port that
disagrees with a witness has a defect rather than an unlucky draw.

    python3 generate_properties.py [--out <conformance root>] [--quick]
"""

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from generate import write_json                                  # noqa: E402
from sharder_ref import placement, routing                       # noqa: E402
from sharder_ref.sample import SplitMix64, sample_keys           # noqa: E402
from sharder_ref.topology import Snapshot                        # noqa: E402

SAMPLE_SEED = 0x5348415244455201  # "SHARDER" with a version octet

BALANCE_RV = {
    "formatVersion": "1.0", "topologyId": "balance-rendezvous", "epoch": 1,
    "replication": {"factor": 1},
    "strategy": {"kind": "rendezvous", "virtualNodesPerWeightUnit": 1,
                 "maxVirtualNodesPerNode": 1024},
    "nodes": [{"id": "b%d" % i, "weight": 1} for i in range(8)],
}

BALANCE_RV_WEIGHTED = {
    "formatVersion": "1.0", "topologyId": "balance-rendezvous-weighted", "epoch": 1,
    "replication": {"factor": 1},
    "strategy": {"kind": "rendezvous", "virtualNodesPerWeightUnit": 1,
                 "maxVirtualNodesPerNode": 1024},
    "nodes": [{"id": "w1", "weight": 1}, {"id": "w2", "weight": 1},
              {"id": "w3", "weight": 2}, {"id": "w4", "weight": 4}],
}

BALANCE_RING = {
    "formatVersion": "1.0", "topologyId": "balance-ring", "epoch": 1,
    "replication": {"factor": 1},
    "strategy": {"kind": "ring", "tokenAssignment": "derived", "tokensPerWeightUnit": 4,
                 "maxTokensPerNode": 4096},
    "nodes": [{"id": "t%d" % i, "weight": 64} for i in range(10)],
}

BALANCE_SLOT = {
    "formatVersion": "1.0", "topologyId": "balance-slot", "epoch": 1,
    "replication": {"factor": 1},
    "strategy": {"kind": "slot", "slotCount": 65536, "assignment": "derived"},
    "nodes": [{"id": "sl%d" % i, "weight": 1} for i in range(4)],
}

MOVEMENT_RV_BEFORE = {
    "formatVersion": "1.0", "topologyId": "movement-bound-rv", "epoch": 1,
    "replication": {"factor": 1},
    "strategy": {"kind": "rendezvous", "virtualNodesPerWeightUnit": 1},
    "nodes": [{"id": "b%d" % i, "weight": 1} for i in range(8)],
}
MOVEMENT_RV_AFTER = dict(MOVEMENT_RV_BEFORE, epoch=2,
                         nodes=MOVEMENT_RV_BEFORE["nodes"] + [{"id": "b8", "weight": 1}])

MOVEMENT_RING_BEFORE = {
    "formatVersion": "1.0", "topologyId": "movement-bound-ring", "epoch": 1,
    "replication": {"factor": 1},
    "strategy": {"kind": "ring", "tokenAssignment": "derived", "tokensPerWeightUnit": 4},
    "nodes": [{"id": "t%d" % i, "weight": 64} for i in range(10)],
}
MOVEMENT_RING_AFTER = dict(MOVEMENT_RING_BEFORE, epoch=2,
                           nodes=MOVEMENT_RING_BEFORE["nodes"] + [{"id": "t10", "weight": 64}])

PROPERTY_TOPOLOGIES = {
    "balance-rendezvous": BALANCE_RV,
    "balance-rendezvous-weighted": BALANCE_RV_WEIGHTED,
    "balance-ring": BALANCE_RING,
    "balance-slot": BALANCE_SLOT,
    "movement-bound-rv-before": MOVEMENT_RV_BEFORE,
    "movement-bound-rv-after": MOVEMENT_RV_AFTER,
    "movement-bound-ring-before": MOVEMENT_RING_BEFORE,
    "movement-bound-ring-after": MOVEMENT_RING_AFTER,
}


def first_candidate_counts(snapshot, keys):
    counts = Counter()
    for key in keys:
        winner = routing.first_candidate(snapshot, key)
        if winner is not None:
            counts[winner] += 1
    return counts


def rendezvous_virtual_nodes(snapshot):
    return {node.id: placement.rendezvous_count(snapshot, node)
            for node in snapshot.placement_set}


def ring_token_counts(snapshot):
    counts = Counter()
    for entry in placement.ring_entries(snapshot, snapshot.placement_set):
        counts[entry[3]] += 1
    return counts


def balance_witness(snapshot, counts, weights, sample_size, multiplier):
    """Evaluate `PROP-020` or `PROP-021`: `multiplier * |c_i * V - M * v_i| <= M * v_i`."""
    total = sum(weights.values())
    rows = []
    passed = True
    for node_id in sorted(weights):
        v = weights[node_id]
        c = counts.get(node_id, 0)
        left = multiplier * abs(c * total - sample_size * v)
        right = sample_size * v
        holds = left <= right
        passed = passed and holds
        rows.append({"node": node_id, "virtualNodes": v, "observed": c,
                     "left": left, "right": right, "holds": holds})
    return {"sumVirtualNodes": total, "sampleSize": sample_size,
            "multiplier": multiplier, "perNode": rows, "passed": passed,
            "precondition": {
                "form": "M * v_min >= 10000 * V",
                "vMin": min(weights.values()),
                "value": sample_size * min(weights.values()),
                "threshold": 10000 * total,
                "holds": sample_size * min(weights.values()) >= 10000 * total,
            }}


def movement_witness(before, after, keys, added, p_num, p_den, multiplier):
    """Evaluate `PROP-015`: `multiplier * |m * p_den - M * p_num| <= M * p_num`."""
    moved = 0
    sample_size = 0
    for key in keys:
        sample_size += 1
        a = routing.first_candidate(before, key)
        b = routing.first_candidate(after, key)
        if b != a and b != added:
            raise AssertionError("PROP-010 violated: %r moved from %s to %s" % (key, a, b))
        if a != b:
            moved += 1
    left = multiplier * abs(moved * p_den - sample_size * p_num)
    right = sample_size * p_num
    return {"sampleSize": sample_size, "movedCount": moved,
            "expectedFraction": {"numerator": p_num, "denominator": p_den},
            "multiplier": multiplier, "left": left, "right": right,
            "passed": left <= right,
            "precondition": {
                "form": "M * p_num >= 10000 * p_den",
                "value": sample_size * p_num, "threshold": 10000 * p_den,
                "holds": sample_size * p_num >= 10000 * p_den,
            }}


def build(root: Path, quick: bool):
    snapshots = {}
    for name, document in PROPERTY_TOPOLOGIES.items():
        write_json(root / "topologies" / ("%s.topology.json" % name), document)
        snapshots[name] = Snapshot(document)

    witnesses = []

    # --- the sample generator itself, so a port can check it before anything else
    rng = SplitMix64(SAMPLE_SEED)
    draws = [rng.next_u64() for _ in range(8)]
    witnesses.append({
        "name": "sample-generator",
        "property": "P-SAMPLE-001",
        "requirements": ["PROP-006"],
        "reference": "the deterministic sample `PROP-006` requires every bound to be drawn from",
        "note": "SplitMix64 at the suite seed.  The generator is the suite's, and `PROP-006` "
                "requires every sampled bound to be evaluated over it, so a port checks this "
                "first and a failing property is a placement defect rather than a different "
                "sample.",
        "expect": {
            "seed": "%016x" % SAMPLE_SEED,
            "firstDraws": ["%016x" % d for d in draws],
            "firstKeys": [k.hex() for k in sample_keys(SAMPLE_SEED, 4)],
        },
    })

    rv_sample = 100000 if not quick else 12000
    ring_sample = 120000 if not quick else 12000

    # --- PROP-020, rendezvous balance, equal weights
    snapshot = snapshots["balance-rendezvous"]
    weights = rendezvous_virtual_nodes(snapshot)
    counts = first_candidate_counts(snapshot, sample_keys(SAMPLE_SEED, rv_sample))
    witnesses.append({
        "name": "balance-rendezvous-equal-weights",
        "property": "P-BALANCE-001",
        "requirements": ["PROP-020", "PROP-030"],
        "topology": "topologies/balance-rendezvous.topology.json",
        "sample": {"generator": "splitmix64", "seed": "%016x" % SAMPLE_SEED,
                   "count": rv_sample, "keyOctets": 16},
        "expect": balance_witness(snapshot, counts, weights, rv_sample, 20),
    })

    # --- PROP-020 and PROP-030, rendezvous balance, unequal weights
    snapshot = snapshots["balance-rendezvous-weighted"]
    weights = rendezvous_virtual_nodes(snapshot)
    sample = 10000 * sum(weights.values()) // min(weights.values())
    sample = sample if not quick else 12000
    counts = first_candidate_counts(snapshot, sample_keys(SAMPLE_SEED, sample))
    witnesses.append({
        "name": "balance-rendezvous-weighted",
        "property": "P-BALANCE-001",
        "requirements": ["PROP-020", "PROP-030", "PROP-032", "PLACE-041"],
        "topology": "topologies/balance-rendezvous-weighted.topology.json",
        "note": "weights 1, 1, 2, 4.  `PROP-030` is exact proportionality, so the observed share "
                "tracks the virtual node share rather than the node count.",
        "sample": {"generator": "splitmix64", "seed": "%016x" % SAMPLE_SEED,
                   "count": sample, "keyOctets": 16},
        "expect": balance_witness(snapshot, counts, weights, sample, 20),
    })

    # --- PROP-021, ring balance at 256 tokens per node
    snapshot = snapshots["balance-ring"]
    tokens = ring_token_counts(snapshot)
    counts = first_candidate_counts(snapshot, sample_keys(SAMPLE_SEED, ring_sample))
    witness = balance_witness(snapshot, counts, tokens, ring_sample, 4)
    witness["tokensPerNode"] = min(tokens.values())
    witness["precondition"]["form"] = "M * t_min >= 10000 * T, and every node holds >= 256 tokens"
    witness["precondition"]["tokensPerNodeAtLeast256"] = min(tokens.values()) >= 256
    witnesses.append({
        "name": "balance-ring-256-tokens",
        "property": "P-BALANCE-002",
        "requirements": ["PROP-021"],
        "topology": "topologies/balance-ring.topology.json",
        "note": "weight 64 at four tokens per weight unit gives 256 tokens per node, which is "
                "the threshold below which `PROP-021` states no bound.",
        "sample": {"generator": "splitmix64", "seed": "%016x" % SAMPLE_SEED,
                   "count": ring_sample, "keyOctets": 16},
        "expect": witness,
    })

    # --- PROP-022, slot derived balance over every slot
    snapshot = snapshots["balance-slot"]
    slot_count = snapshot.strategy["slotCount"]
    weights = {node.id: min(node.weight, 1024) for node in snapshot.placement_set}
    slot_counts = Counter()
    limit = slot_count if not quick else 4096
    for index in range(limit):
        ordering = placement.slot_derived_candidates(snapshot, index, snapshot.placement_set)
        if ordering:
            slot_counts[ordering[0]] += 1
    witness = balance_witness(snapshot, slot_counts, weights, limit, 20)
    witness["precondition"]["form"] = "slotCount * v_min >= 10000 * V"
    witnesses.append({
        "name": "balance-slot-derived",
        "property": "P-BALANCE-003",
        "requirements": ["PROP-022", "PROP-033"],
        "topology": "topologies/balance-slot.topology.json",
        "note": "the sample is the set of all slots rather than a set of keys, under `PROP-022`.",
        "sample": {"generator": "allSlots", "count": limit},
        "expect": witness,
    })

    # --- PROP-013 and PROP-015, movement under rendezvous
    before, after = snapshots["movement-bound-rv-before"], snapshots["movement-bound-rv-after"]
    v_x, total = 1, 8
    sample = 10000 * (total + v_x) if not quick else 12000
    witnesses.append({
        "name": "movement-rendezvous-add-one",
        "property": "P-MOVEMENT-002",
        "requirements": ["PROP-013", "PROP-015"],
        "before": "topologies/movement-bound-rv-before.topology.json",
        "after": "topologies/movement-bound-rv-after.topology.json",
        "addedNode": "b8",
        "note": "eight equal nodes gain a ninth, so the expected moved fraction is 1/9.",
        "sample": {"generator": "splitmix64", "seed": "%016x" % SAMPLE_SEED,
                   "count": sample, "keyOctets": 16},
        "expect": movement_witness(before, after, sample_keys(SAMPLE_SEED, sample), "b8",
                                   v_x, total + v_x, 20),
    })

    # --- PROP-014 and PROP-015, movement under ring
    before = snapshots["movement-bound-ring-before"]
    after = snapshots["movement-bound-ring-after"]
    t_x = 256
    t_total = 10 * 256
    sample = (10000 * (t_total + t_x) + t_x - 1) // t_x if not quick else 12000
    witness = movement_witness(before, after, sample_keys(SAMPLE_SEED, sample), "t10",
                               t_x, t_total + t_x, 4)
    witness["tokensPerNode"] = t_x
    witnesses.append({
        "name": "movement-ring-add-one",
        "property": "P-MOVEMENT-003",
        "requirements": ["PROP-014", "PROP-015"],
        "before": "topologies/movement-bound-ring-before.topology.json",
        "after": "topologies/movement-bound-ring-after.topology.json",
        "addedNode": "t10",
        "note": "ten nodes of 256 tokens gain an eleventh, so the expected moved fraction is "
                "256/2816.  `PROP-015` sets the multiplier to 4 for `ring`.",
        "sample": {"generator": "splitmix64", "seed": "%016x" % SAMPLE_SEED,
                   "count": sample, "keyOctets": 16},
        "expect": witness,
    })

    write_json(root / "vectors/properties/witnesses.json", {
        "vectorSet": "property-witnesses",
        "kind": "propertyWitness",
        "description": "Observed counts and evaluated inequalities for every property the "
                       "reference can evaluate.  The sample is deterministic, so a port "
                       "reproduces these numbers exactly rather than approximately.",
        "requirements": sorted({r for w in witnesses for r in w["requirements"]}),
        "cases": witnesses,
    })

    failed = [w["name"] for w in witnesses
              if isinstance(w["expect"], dict) and w["expect"].get("passed") is False]
    print("wrote %d property witnesses%s"
          % (len(witnesses), ("; FAILED: " + ", ".join(failed)) if failed else ""))
    return failed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(HERE.parent))
    parser.add_argument("--quick", action="store_true",
                        help="use sample sizes below the specification's precondition; for "
                             "checking the script, never for publishing vectors")
    args = parser.parse_args()
    failed = build(Path(args.out), args.quick)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
