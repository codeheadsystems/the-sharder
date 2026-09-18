#!/usr/bin/env python3
"""The two large topologies of the `scale` conformance level, and their vectors.

Every other topology in the suite is small enough to route against by hand.  These two are not:
each carries a thousand nodes, and each is configured so that the structure a port prepares is the
one `PLACE-070` charges for at the sizes `CFG-010` sets its thresholds at.  The expected values are
exact, as they are everywhere else in the suite, and nothing here asserts a timing.  What the level
proves is that a port arrives at the right answers over a topology whose orderings it cannot afford
to materialise.

The documents are defined here rather than in `topologies.py` because they exist to carry these
vector sets alone, and because a thousand nodes is built rather than written out.

    python3 generate_scale.py [--out <conformance root>]

Generating the ring document costs a ring build of over a million tokens in the reference, which
takes tens of seconds.  It is the slowest step of `run.sh` after the property witnesses.
"""

import argparse
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from generate import key_spec, write_json                       # noqa: E402
from sharder_ref import placement, routing                      # noqa: E402
from sharder_ref.observability import placement_total           # noqa: E402
from sharder_ref.topology import Snapshot                       # noqa: E402

# A thousand nodes over four regions, twenty zones, and ninety-seven racks.  The rack count is
# coprime with the region and zone counts so that a rack identifier repeats under distinct zones,
# which is the domain path scope `SPREAD-006` fixes, at a scale where a port that reads the scope
# from `replication.spread` diverges on many keys rather than on four.
NODE_COUNT = 1000
REGIONS = ["r-north", "r-south", "r-east", "r-west"]
ZONES_PER_REGION = 5
RACKS = 97

# The last four nodes carry a weight whose requested token count is above `maxTokensPerNode`, so
# `PLACE-052` clamps each of them and reports the clamp at publication.
CLAMPED_NODES = 4
CLAMPED_WEIGHT = 9

# The candidate ordering under either document is a thousand entries long, and so is the
# preference list `REPL-014` builds from it.  A case asserts a prefix of each and not the whole of
# either: `CORE-047` answers the whole ordering on demand and `PLACE-015` makes a prefix of it the
# cost a routing call pays, so a vector that asserted the thousand would ask for exactly the
# materialisation this level exists to discourage.  The prefix is longer than the replication
# factor, so it reaches the fallback tail of `REPL-017` as well as the replica prefix.
CANDIDATE_PREFIX = 8

# The shard identifiers a ring of this size enumerates are as numerous as its tokens, so the case
# asserts the cardinality and the first few identifiers.
SHARD_PREFIX = 8

KEYS = [b"tenant-0001:orders:38291", b"tenant-0042:sessions:a", b"acme", b"\x00\x01\x02\x03",
        b"tenant-0999:ledger:0000000000", b"zz-last-tenant"]


def _nodes(weight_of):
    nodes = []
    for index in range(NODE_COUNT):
        region = REGIONS[index % len(REGIONS)]
        zone = "%s-z%d" % (region, (index // len(REGIONS)) % ZONES_PER_REGION)
        nodes.append({"id": "node-%04d" % index, "weight": weight_of(index),
                      "domains": {"region": region, "zone": zone,
                                  "rack": "rk-%03d" % (index % RACKS)}})
    return nodes


def _weight(index):
    """Weights 1, 2, and 3 in rotation, with the last few nodes above the token cap."""
    if index >= NODE_COUNT - CLAMPED_NODES:
        return CLAMPED_WEIGHT
    return 1 + (index % 3)


# `tokensPerWeightUnit` of 512 over these weights puts the token total above the `ringWarnTokens`
# default of 1000000, so the document is one `PLACE-073` reports on and one whose ring a port that
# stores it badly cannot hold.
SCALE_RING = {
    "formatVersion": "1.0",
    "topologyId": "scale-ring-1000",
    "epoch": 1,
    "domainLevels": ["region", "zone", "rack"],
    "replication": {"factor": 3, "spread": ["zone"], "spreadPolicy": "relaxed"},
    "strategy": {"kind": "ring", "tokenAssignment": "derived", "tokensPerWeightUnit": 512,
                 "maxTokensPerNode": 4096},
    "nodes": _nodes(_weight),
}

# `virtualNodesPerWeightUnit` of 4 puts the virtual node total above the
# `rendezvousWarnVirtualNodes` default of 4096.  Under `rendezvous` the total is what one routing
# call costs rather than what preparation costs, which `PLACE-071` states and laziness does not
# remove, so this document measures the other half of the cost model.
SCALE_RENDEZVOUS = {
    "formatVersion": "1.0",
    "topologyId": "scale-rendezvous-1000",
    "epoch": 1,
    "domainLevels": ["region", "zone", "rack"],
    "replication": {"factor": 3, "spread": ["zone"], "spreadPolicy": "relaxed"},
    "strategy": {"kind": "rendezvous", "virtualNodesPerWeightUnit": 4,
                 "maxVirtualNodesPerNode": 16},
    "nodes": _nodes(lambda index: 1 + (index % 3)),
}


def routing_rows(snapshot):
    """One row per key: the decision, with a prefix of the ordering rather than the whole."""
    rows = []
    for key in KEYS:
        decision = routing.route(snapshot, key)
        rows.append({
            "key": key_spec(key),
            "routingKey": decision["routingKey"],
            "shard": decision["shard"],
            "factor": decision["factor"],
            "replicaCount": decision["replicaCount"],
            "candidatePrefix": decision["candidates"][:CANDIDATE_PREFIX],
            "preferenceListPrefix": decision["preferenceList"][:CANDIDATE_PREFIX],
            "materialisedEntries": decision["materialisedEntries"],
            "relaxedLevels": decision["relaxedLevels"],
            "spreadStage": decision["spreadStage"],
            "shortfall": decision["shortfall"],
        })
    return rows


def build_ring(root, entries):
    snapshot = Snapshot(SCALE_RING)
    setting, _, total = placement_total(SCALE_RING)
    started = time.time()
    shards = placement.ring_shards(snapshot)
    case = {
        "name": "ring-1000-nodes",
        "requirements": ["CORE-046", "CORE-047", "PLACE-015", "PLACE-050", "PLACE-052",
                         "PLACE-070", "PLACE-071", "PLACE-074", "RING-010", "RING-020",
                         "RING-021", "RING-026", "RING-031"],
        "note": "`PLACE-070` charges preparation at `T` hash evaluations and `T log T` "
                "comparisons, and one routing call at a walk of up to `T` entries.  A port that "
                "walks the whole ordering on every call pays the second figure on every key.",
        "topology": "topologies/scale-ring-1000.topology.json",
        "expect": {
            "nodeCount": len(SCALE_RING["nodes"]),
            "placementSetCount": len(snapshot.placement_set),
            "total": total,
            "totalSetting": setting,
            "shardCount": len(shards),
            "shardPrefix": shards[:SHARD_PREFIX],
            "candidatesForFirstShard": placement.ring_candidates_for_shard(
                snapshot, shards[0], snapshot.placement_set)[:CANDIDATE_PREFIX],
            "rows": routing_rows(snapshot),
        },
    }
    elapsed = time.time() - started
    write_json(root / "vectors/scale/ring-1000.json", {
        "vectorSet": "scale-ring-1000",
        "kind": "scale",
        "level": "scale",
        "description": "A thousand nodes on a ring of over a million derived tokens: the token "
                       "total, the shard cardinality, and six routing decisions over it.",
        "requirements": sorted(case["requirements"]),
        "topology": "topologies/scale-ring-1000.topology.json",
        "topologyDigest": snapshot.digest,
        "cases": [case],
    })
    entries.append(("vectors/scale/ring-1000.json", elapsed))


def build_rendezvous(root, entries):
    snapshot = Snapshot(SCALE_RENDEZVOUS)
    setting, _, total = placement_total(SCALE_RENDEZVOUS)
    started = time.time()
    case = {
        "name": "rendezvous-1000-nodes",
        "requirements": ["CORE-046", "CORE-047", "PLACE-032", "PLACE-050", "PLACE-070",
                         "PLACE-071", "PLACE-074", "RV-003", "RV-010", "RV-013"],
        "note": "`PLACE-071` states that laziness removes the ordering term under `rendezvous` "
                "and leaves the `V` scoring term, which is the dominant one.  A routing call here "
                "scores every eligible node at every virtual node index before it knows its "
                "first candidate.",
        "topology": "topologies/scale-rendezvous-1000.topology.json",
        "expect": {
            "nodeCount": len(SCALE_RENDEZVOUS["nodes"]),
            "placementSetCount": len(snapshot.placement_set),
            "total": total,
            "totalSetting": setting,
            "shardCount": len(placement.shards(snapshot)),
            "shardPrefix": [],
            "candidatesForFirstShard": [],
            "rows": routing_rows(snapshot),
        },
    }
    elapsed = time.time() - started
    write_json(root / "vectors/scale/rendezvous-1000.json", {
        "vectorSet": "scale-rendezvous-1000",
        "kind": "scale",
        "level": "scale",
        "description": "A thousand nodes scored at four virtual nodes per weight unit: the "
                       "virtual node total, the empty shard set of `PLACE-032`, and six routing "
                       "decisions over it.",
        "requirements": sorted(case["requirements"]),
        "topology": "topologies/scale-rendezvous-1000.topology.json",
        "topologyDigest": snapshot.digest,
        "cases": [case],
    })
    entries.append(("vectors/scale/rendezvous-1000.json", elapsed))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(HERE.parent))
    args = parser.parse_args()
    root = Path(args.out)

    write_json(root / "topologies/scale-ring-1000.topology.json", SCALE_RING)
    write_json(root / "topologies/scale-rendezvous-1000.topology.json", SCALE_RENDEZVOUS)

    entries = []
    build_ring(root, entries)
    build_rendezvous(root, entries)
    for path, elapsed in entries:
        print("  %-38s %6.1fs in the reference" % (path, elapsed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
