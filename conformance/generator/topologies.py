"""Every topology document the vectors route against.

Documents are defined here rather than hand-edited under `conformance/topologies/` so that
regenerating the suite reproduces the inputs as well as the expectations.  `generate.py` writes
each one out and records its digest in the manifest.

Tie-break documents are built at generation time from the collision search results, so they are
not defined here; see `build_tie_vectors` in `generate.py`.  Documents that exist only to carry one
vector set are defined next to that set, in `generate_extra.py` and `generate_scenarios.py`.
"""

STORAGE_SEED = "9e3779b97f4a7c159e3779b97f4a7c15"


def _node(node_id, **kwargs):
    node = {"id": node_id}
    node.update(kwargs)
    return node


# --------------------------------------------------------------------------- ring

RING_DERIVED_PLAIN = {
    "formatVersion": "1.0",
    "topologyId": "ring-plain",
    "epoch": 1,
    "strategy": {"kind": "ring", "tokenAssignment": "derived", "tokensPerWeightUnit": 1,
                 "maxTokensPerNode": 4096},
    "nodes": [_node("n1"), _node("n2"), _node("n3"), _node("n4")],
}

RING_DERIVED_ZONED = {
    "formatVersion": "1.0",
    "topologyId": "ring-zoned",
    "epoch": 12,
    "domainLevels": ["zone", "rack"],
    "replication": {"factor": 3, "spread": ["zone"], "spreadPolicy": "relaxed"},
    "strategy": {"kind": "ring", "tokenAssignment": "derived", "tokensPerWeightUnit": 4,
                 "maxTokensPerNode": 4096},
    "nodes": [
        _node("store-a1", weight=2, domains={"zone": "za", "rack": "r01"}),
        _node("store-a2", weight=1, domains={"zone": "za", "rack": "r02"}),
        _node("store-b1", weight=1, domains={"zone": "zb", "rack": "r11"}),
        _node("store-b2", weight=3, domains={"zone": "zb", "rack": "r12"}),
        _node("store-c1", weight=1, domains={"zone": "zc", "rack": "r21"}),
        _node("store-c2", weight=1, domains={"zone": "zc", "rack": "r22"}),
    ],
}

RING_EXPLICIT = {
    "formatVersion": "1.0",
    "topologyId": "ring-explicit",
    "epoch": 3,
    "replication": {"factor": 2},
    "strategy": {"kind": "ring", "tokenAssignment": "explicit"},
    "nodes": [
        _node("n1", tokens=["1000000000000000", "8000000000000000"]),
        _node("n2", tokens=["4000000000000000", "c000000000000000"]),
        _node("n3", weight=0, tokens=["2000000000000000"]),
    ],
}

RING_ADMIN_STATES = {
    "formatVersion": "1.0",
    "topologyId": "ring-admin-states",
    "epoch": 5,
    "replication": {"factor": 3},
    "strategy": {"kind": "ring", "tokenAssignment": "derived", "tokensPerWeightUnit": 2},
    "nodes": [
        _node("active-1", state="active"),
        _node("active-2", state="active"),
        _node("draining-1", state="draining"),
        _node("joining-1", state="joining"),
        _node("leaving-1", state="leaving"),
    ],
}

RING_SEEDED = {
    "formatVersion": "1.0",
    "topologyId": "ring-seeded",
    "epoch": 118,
    "hash": {"algorithm": "siphash-2-4", "seed": STORAGE_SEED},
    "replication": {"factor": 2},
    "strategy": {"kind": "ring", "tokenAssignment": "derived", "tokensPerWeightUnit": 1},
    "nodes": [_node("n1"), _node("n2"), _node("n3"), _node("n4")],
}

# --------------------------------------------------------------------- rendezvous

RENDEZVOUS_TENANT = {
    "formatVersion": "1.0",
    "topologyId": "tenant-router",
    "epoch": 42,
    "keyTransform": {"kind": "prefixFields", "separator": "3a", "count": 1},
    "domainLevels": ["region"],
    "replication": {"factor": 1},
    "strategy": {"kind": "rendezvous", "virtualNodesPerWeightUnit": 1},
    "nodes": [
        _node("cluster-eu-1", weight=1, domains={"region": "eu-west"},
              address="https://eu1.internal", tags={"tier": "standard"}),
        _node("cluster-eu-2", weight=1, domains={"region": "eu-central"},
              address="https://eu2.internal", tags={"tier": "standard"}),
        _node("cluster-us-1", weight=2, domains={"region": "us-east"},
              address="https://us1.internal", tags={"tier": "standard"}),
        _node("cluster-us-2", weight=1, domains={"region": "us-east"},
              address="https://us2.internal", tags={"tier": "dedicated"}),
    ],
    "overrides": [
        {"match": {"kind": "exact", "value": "acme"},
         "pin": ["cluster-us-2", "cluster-us-1"], "note": "contract 8841, dedicated tier"},
        {"match": {"kind": "prefix", "value": "eu-"},
         "constrain": {"domains": {"region": ["eu-west", "eu-central"]}}, "note": "residency"},
    ],
}

RENDEZVOUS_CACHE = {
    "formatVersion": "1.0",
    "topologyId": "session-cache",
    "epoch": 7,
    "keyTransform": {"kind": "braceTag"},
    "replication": {"factor": 2},
    "strategy": {"kind": "rendezvous", "virtualNodesPerWeightUnit": 8,
                 "maxVirtualNodesPerNode": 512},
    "nodes": [
        _node("cache-01", weight=1, address="10.4.0.1:11211"),
        _node("cache-02", weight=1, address="10.4.0.2:11211"),
        _node("cache-03", weight=1, address="10.4.0.3:11211"),
        _node("cache-04", weight=1, address="10.4.0.4:11211"),
        _node("cache-05", weight=2, address="10.4.0.5:11211"),
        _node("cache-06", weight=2, address="10.4.0.6:11211"),
        _node("cache-07", weight=2, address="10.4.0.7:11211"),
        _node("cache-08", weight=0, state="draining", address="10.4.0.8:11211"),
    ],
}

RENDEZVOUS_PLAIN = {
    "formatVersion": "1.0",
    "topologyId": "rv-plain",
    "epoch": 1,
    "replication": {"factor": 3},
    "strategy": {"kind": "rendezvous"},
    "nodes": [_node("n1"), _node("n2"), _node("n3"), _node("n4"), _node("n5")],
}

RENDEZVOUS_CAPPED = {
    "formatVersion": "1.0",
    "topologyId": "rv-capped",
    "epoch": 2,
    "replication": {"factor": 2},
    "strategy": {"kind": "rendezvous", "virtualNodesPerWeightUnit": 4,
                 "maxVirtualNodesPerNode": 6},
    "nodes": [
        _node("small", weight=1),
        _node("medium", weight=2),
        _node("clamped", weight=100),
    ],
}

# --------------------------------------------------------------------------- slot

SLOT_EXPLICIT = {
    "formatVersion": "1.0",
    "topologyId": "slot-explicit",
    "epoch": 4,
    "replication": {"factor": 2},
    "strategy": {
        "kind": "slot",
        "slotCount": 16384,
        "assignment": "explicit",
        "assignments": [
            {"slots": ["0-5460"], "nodes": ["shard-a", "shard-b"]},
            {"slots": ["5461-10922"], "nodes": ["shard-b", "shard-c"]},
            {"slots": ["10923-16383"], "nodes": ["shard-c", "shard-a"]},
        ],
    },
    "nodes": [_node("shard-a"), _node("shard-b"), _node("shard-c")],
}

SLOT_SMALL = {
    "formatVersion": "1.0",
    "topologyId": "slot-small",
    "epoch": 1,
    "replication": {"factor": 1},
    "strategy": {
        "kind": "slot",
        "slotCount": 7,
        "assignment": "explicit",
        "assignments": [
            {"slots": ["0-2"], "nodes": ["a", "b"]},
            {"slots": ["3-4"], "nodes": ["b", "c"]},
            {"slots": ["5-6"], "nodes": ["c", "a"]},
        ],
    },
    "nodes": [_node("a"), _node("b"), _node("c")],
}

# ---------------------------------------------------------------------- directory

DIRECTORY_TENANTS = {
    "formatVersion": "1.0",
    "topologyId": "directory-tenants",
    "epoch": 11,
    "replication": {"factor": 2},
    "strategy": {
        "kind": "directory",
        "entries": [
            {"match": {"kind": "prefix", "value": ""}, "nodes": ["cluster-default"],
             "note": "catch-all, PLACE-063"},
            {"match": {"kind": "prefix", "value": "gov-"}, "nodes": ["cluster-gov-1"]},
            {"match": {"kind": "prefix", "value": "gov-hi"}, "nodes": ["cluster-gov-2"]},
            {"match": {"kind": "exact", "value": "gov-high"}, "nodes": ["cluster-gov-3"]},
            {"match": {"kind": "exact", "value": "acme"}, "nodes": ["cluster-eu-1"]},
            {"match": {"kind": "prefix", "value": "ff00", "encoding": "base16"},
             "nodes": ["cluster-binary"]},
        ],
    },
    "nodes": [
        _node("cluster-default"), _node("cluster-gov-1"), _node("cluster-gov-2"),
        _node("cluster-gov-3"), _node("cluster-eu-1"), _node("cluster-binary"),
    ],
}

DIRECTORY_SPARSE = {
    "formatVersion": "1.0",
    "topologyId": "directory-sparse",
    "epoch": 1,
    "replication": {"factor": 1},
    "strategy": {
        "kind": "directory",
        "entries": [
            {"match": {"kind": "exact", "value": "known"}, "nodes": ["c1"]},
            {"match": {"kind": "prefix", "value": "pre"}, "nodes": ["c2", "c1"]},
            {"match": {"kind": "exact", "value": "gone"}, "nodes": ["c-leaving"]},
        ],
    },
    "nodes": [_node("c1"), _node("c2"), _node("c-leaving", state="leaving")],
}

# ---------------------------------------------------------------------- adversarial

EMPTY_NODES = {
    "formatVersion": "1.0",
    "topologyId": "empty-topology",
    "epoch": 1,
    "replication": {"factor": 3},
    "strategy": {"kind": "rendezvous"},
    "nodes": [],
}

EMPTY_NODES_RING = {
    "formatVersion": "1.0",
    "topologyId": "empty-topology-ring",
    "epoch": 1,
    "replication": {"factor": 1},
    "strategy": {"kind": "ring"},
    "nodes": [],
}

SINGLE_NODE = {
    "formatVersion": "1.0",
    "topologyId": "single-node",
    "epoch": 1,
    "domainLevels": ["zone"],
    "replication": {"factor": 3, "spread": ["zone"]},
    "strategy": {"kind": "rendezvous"},
    "nodes": [_node("only", domains={"zone": "za"})],
}

FACTOR_EXCEEDS_NODES = {
    "formatVersion": "1.0",
    "topologyId": "factor-exceeds-nodes",
    "epoch": 1,
    "domainLevels": ["zone"],
    "replication": {"factor": 5, "spread": ["zone"], "spreadPolicy": "relaxed"},
    "strategy": {"kind": "rendezvous"},
    "nodes": [
        _node("n1", domains={"zone": "za"}),
        _node("n2", domains={"zone": "zb"}),
        _node("n3", domains={"zone": "zc"}),
    ],
}

ALL_ONE_DOMAIN_RELAXED = {
    "formatVersion": "1.0",
    "topologyId": "one-domain-relaxed",
    "epoch": 1,
    "domainLevels": ["zone"],
    "replication": {"factor": 3, "spread": ["zone"], "spreadPolicy": "relaxed"},
    "strategy": {"kind": "rendezvous"},
    "nodes": [
        _node("n1", domains={"zone": "za"}),
        _node("n2", domains={"zone": "za"}),
        _node("n3", domains={"zone": "za"}),
        _node("n4", domains={"zone": "za"}),
    ],
}

ALL_ONE_DOMAIN_STRICT = dict(ALL_ONE_DOMAIN_RELAXED,
                             topologyId="one-domain-strict",
                             replication={"factor": 3, "spread": ["zone"],
                                          "spreadPolicy": "strict"})

SPREAD_LADDER = {
    "formatVersion": "1.0",
    "topologyId": "spread-ladder",
    "epoch": 1,
    "domainLevels": ["region", "zone", "rack"],
    "replication": {"factor": 4, "spread": ["region", "zone", "rack"],
                    "spreadPolicy": "relaxed"},
    "strategy": {"kind": "rendezvous"},
    "nodes": [
        _node("eu-a-1", domains={"region": "eu", "zone": "a", "rack": "r1"}),
        _node("eu-a-2", domains={"region": "eu", "zone": "a", "rack": "r2"}),
        _node("eu-b-1", domains={"region": "eu", "zone": "b", "rack": "r1"}),
        _node("eu-b-2", domains={"region": "eu", "zone": "b", "rack": "r1"}),
        _node("us-a-1", domains={"region": "us", "zone": "a", "rack": "r1"}),
        _node("us-a-2", domains={"region": "us", "zone": "a", "rack": "r1"}),
    ],
}

# `SPREAD-006` scopes a domain path to `domainLevels` and not to `replication.spread`, so a
# `spread` that names only the finest level still compares the whole declared path.  Racks `k1` and
# `k9` are reused across zones and regions here, so the two readings of the scope disagree: under
# the declared scope `a1` and `a2` sit in different rack domains, and under a scope taken from
# `spread` they share rack `k1`.
SPREAD_SKIPPED_LEVEL = {
    "formatVersion": "1.0",
    "topologyId": "spread-skipped-level",
    "epoch": 1,
    "domainLevels": ["region", "zone", "rack"],
    "replication": {"factor": 2, "spread": ["rack"], "spreadPolicy": "relaxed"},
    "strategy": {"kind": "rendezvous"},
    "nodes": [
        _node("a1", domains={"region": "r1", "zone": "z1", "rack": "k1"}),
        _node("a2", domains={"region": "r1", "zone": "z2", "rack": "k1"}),
        _node("b1", domains={"region": "r2", "zone": "z3", "rack": "k1"}),
        _node("b2", domains={"region": "r2", "zone": "z3", "rack": "k9"}),
        _node("c1", domains={"region": "r1", "zone": "z1", "rack": "k9"}),
    ],
}

WEIGHT_ZERO = {
    "formatVersion": "1.0",
    "topologyId": "weight-zero",
    "epoch": 1,
    "replication": {"factor": 2},
    "strategy": {"kind": "rendezvous"},
    "nodes": [
        _node("live-1", weight=1),
        _node("live-2", weight=1),
        _node("zero-1", weight=0),
        _node("zero-2", weight=0, state="draining"),
    ],
}

WEIGHT_ZERO_ALL = {
    "formatVersion": "1.0",
    "topologyId": "weight-zero-all",
    "epoch": 1,
    "replication": {"factor": 1},
    "strategy": {"kind": "rendezvous"},
    "nodes": [_node("z1", weight=0), _node("z2", weight=0)],
}

WEIGHT_ZERO_PINNED = {
    "formatVersion": "1.0",
    "topologyId": "weight-zero-pinned",
    "epoch": 1,
    "replication": {"factor": 2},
    "strategy": {"kind": "rendezvous"},
    "nodes": [_node("live-1", weight=1), _node("zero-1", weight=0)],
    "overrides": [
        {"match": {"kind": "exact", "value": "pinned"}, "pin": ["zero-1", "live-1"]},
    ],
}

OVERRIDES_PRECEDENCE = {
    "formatVersion": "1.0",
    "topologyId": "overrides-precedence",
    "epoch": 1,
    "domainLevels": ["region"],
    "replication": {"factor": 2},
    "strategy": {"kind": "rendezvous"},
    "nodes": [
        _node("n-eu-1", domains={"region": "eu"}, tags={"tier": "gold"}),
        _node("n-eu-2", domains={"region": "eu"}, tags={"tier": "silver"}),
        _node("n-us-1", domains={"region": "us"}, tags={"tier": "gold"}),
        _node("n-us-2", domains={"region": "us"}, tags={"tier": "silver"}),
        _node("n-pin-1", domains={"region": "us"}),
        _node("n-pin-2", domains={"region": "eu"}),
        _node("n-gone", domains={"region": "eu"}, state="leaving"),
    ],
    "overrides": [
        # PLACE-065 clause 3 cannot be reached in a valid document: two entries of one table that
        # both match a routing key and survive clauses 1 and 2 have the same kind and the same
        # decoded octets, which PLACE-067 makes a validation failure.  The entries below cover the
        # clauses that are reachable, and `overrides-identical-matcher` in the validation vectors
        # covers the document that clause 3 would otherwise have to arbitrate.
        {"match": {"kind": "prefix", "value": ""}, "pin": ["n-pin-1", "n-pin-2"],
         "note": "empty prefix, PLACE-063, loses to every longer prefix"},
        {"match": {"kind": "prefix", "value": "ab", "encoding": "base16"},
         "pin": ["n-pin-2", "n-pin-1"], "note": "decodes to the single octet 0xab"},
        {"match": {"kind": "prefix", "value": "abc"}, "pin": ["n-pin-2"],
         "note": "three octets, beats the empty prefix"},
        {"match": {"kind": "exact", "value": "abcd"}, "constrain": {"domains": {"region": ["eu"]}},
         "factor": 1, "note": "exact beats every prefix"},
        {"match": {"kind": "prefix", "value": "tag-"}, "constrain": {"tags": {"tier": ["gold"]}}},
        {"match": {"kind": "prefix", "value": "nodes-"}, "constrain": {"nodes": ["n-us-1"]}},
        {"match": {"kind": "prefix", "value": "none-"},
         "constrain": {"domains": {"region": ["antarctica"]}}},
        {"match": {"kind": "prefix", "value": "gone-"}, "pin": ["n-gone"]},
        {"match": {"kind": "prefix", "value": "both-"}, "pin": ["n-us-1", "n-eu-1", "n-us-2"],
         "constrain": {"domains": {"region": ["us"]}}, "note": "pin filtered by constraint"},
    ],
}

READ_AFFINITY = {
    "formatVersion": "1.0",
    "topologyId": "read-affinity",
    "epoch": 1,
    "domainLevels": ["region", "zone"],
    "replication": {"factor": 4, "spread": ["region"], "spreadPolicy": "relaxed"},
    "strategy": {"kind": "rendezvous"},
    "nodes": [
        _node("eu-a", domains={"region": "eu", "zone": "a"}),
        _node("eu-b", domains={"region": "eu", "zone": "b"}),
        _node("us-a", domains={"region": "us", "zone": "a"}),
        _node("us-b", domains={"region": "us", "zone": "b"}),
        _node("ap-a", domains={"region": "ap", "zone": "a"}),
        _node("ap-b", domains={"region": "ap", "zone": "b"}),
    ],
}

# The key transform vectors need no node set, but a vector file names a topology, so each
# transform gets a minimal one.
KEY_TRANSFORM_NONE = {
    "formatVersion": "1.0", "topologyId": "kt-none", "epoch": 1,
    "keyTransform": {"kind": "none"},
    "strategy": {"kind": "rendezvous"}, "nodes": [_node("n1")],
}

KEY_TRANSFORM_BRACE = dict(KEY_TRANSFORM_NONE, topologyId="kt-brace",
                           keyTransform={"kind": "braceTag"})

KEY_TRANSFORM_BRACE_SAME = dict(KEY_TRANSFORM_NONE, topologyId="kt-brace-same",
                                keyTransform={"kind": "braceTag", "open": "7c", "close": "7c"})

KEY_TRANSFORM_PREFIX_1 = dict(KEY_TRANSFORM_NONE, topologyId="kt-prefix-1",
                              keyTransform={"kind": "prefixFields", "separator": "3a",
                                            "count": 1})

KEY_TRANSFORM_PREFIX_2 = dict(KEY_TRANSFORM_NONE, topologyId="kt-prefix-2",
                              keyTransform={"kind": "prefixFields", "separator": "3a",
                                            "count": 2})

# A separator that is a UTF-8 continuation octet.  The transform splits inside a multi-byte
# sequence and produces a routing key that is not valid text, which `KEY-012` and `KEY-013`
# require an implementation to accept.
KEY_TRANSFORM_CONTINUATION = dict(KEY_TRANSFORM_NONE, topologyId="kt-continuation",
                                  keyTransform={"kind": "prefixFields", "separator": "a9",
                                                "count": 1})

# --------------------------------------------------------- movement document pairs

def _movement_pair(topology_id, strategy, ids, added, weight=1, **extra):
    base = {
        "formatVersion": "1.0",
        "topologyId": topology_id,
        "epoch": 1,
        "replication": {"factor": 3},
        "strategy": strategy,
        "nodes": [_node(i, weight=weight) for i in ids],
    }
    base.update(extra)
    grown = dict(base, epoch=2,
                 nodes=[_node(i, weight=weight) for i in ids] + [_node(added, weight=weight)])
    return base, grown


MOVEMENT_RING_BEFORE, MOVEMENT_RING_AFTER = _movement_pair(
    "movement-ring",
    {"kind": "ring", "tokenAssignment": "derived", "tokensPerWeightUnit": 16},
    ["m1", "m2", "m3", "m4", "m5"], "m6")

MOVEMENT_RV_BEFORE, MOVEMENT_RV_AFTER = _movement_pair(
    "movement-rv", {"kind": "rendezvous", "virtualNodesPerWeightUnit": 1},
    ["m1", "m2", "m3", "m4", "m5"], "m6")

# --------------------------------------------------------------------- invalid documents

INVALID_DOCUMENTS = {
    "duplicate-node-id": {
        "formatVersion": "1.0", "topologyId": "invalid-duplicate-id", "epoch": 1,
        "strategy": {"kind": "rendezvous"},
        "nodes": [_node("dup"), _node("other"), _node("dup")],
    },
    "duplicate-override-matcher": {
        "formatVersion": "1.0", "topologyId": "invalid-duplicate-matcher", "epoch": 1,
        "strategy": {"kind": "rendezvous"}, "nodes": [_node("n1"), _node("n2")],
        "overrides": [
            {"match": {"kind": "exact", "value": "same"}, "pin": ["n1"]},
            {"match": {"kind": "exact", "value": "same"}, "pin": ["n2"]},
        ],
    },
    "duplicate-matcher-across-encodings": {
        "formatVersion": "1.0", "topologyId": "invalid-matcher-encodings", "epoch": 1,
        "strategy": {"kind": "rendezvous"}, "nodes": [_node("n1"), _node("n2")],
        "overrides": [
            {"match": {"kind": "exact", "value": "AB"}, "pin": ["n1"]},
            {"match": {"kind": "exact", "value": "4142", "encoding": "base16"}, "pin": ["n2"]},
        ],
    },
    "pin-names-absent-node": {
        "formatVersion": "1.0", "topologyId": "invalid-pin-absent", "epoch": 1,
        "strategy": {"kind": "rendezvous"}, "nodes": [_node("n1")],
        "overrides": [{"match": {"kind": "exact", "value": "k"}, "pin": ["ghost"]}],
    },
    "constrain-undeclared-level": {
        "formatVersion": "1.0", "topologyId": "invalid-constrain-level", "epoch": 1,
        "domainLevels": ["zone"], "strategy": {"kind": "rendezvous"},
        "nodes": [_node("n1", domains={"zone": "za"})],
        "overrides": [{"match": {"kind": "prefix", "value": "k"},
                       "constrain": {"domains": {"region": ["eu"]}}}],
    },
    "node-missing-domain-level": {
        "formatVersion": "1.0", "topologyId": "invalid-missing-domain", "epoch": 1,
        "domainLevels": ["zone", "rack"], "strategy": {"kind": "rendezvous"},
        "nodes": [_node("n1", domains={"zone": "za"})],
    },
    "node-undeclared-domain-level": {
        "formatVersion": "1.0", "topologyId": "invalid-extra-domain", "epoch": 1,
        "domainLevels": ["zone"], "strategy": {"kind": "rendezvous"},
        "nodes": [_node("n1", domains={"zone": "za", "rack": "r1"})],
    },
    "spread-level-order": {
        "formatVersion": "1.0", "topologyId": "invalid-spread-order", "epoch": 1,
        "domainLevels": ["region", "zone"],
        "replication": {"factor": 2, "spread": ["zone", "region"]},
        "strategy": {"kind": "rendezvous"},
        "nodes": [_node("n1", domains={"region": "eu", "zone": "a"})],
    },
    "spread-undeclared-level": {
        "formatVersion": "1.0", "topologyId": "invalid-spread-level", "epoch": 1,
        "domainLevels": ["zone"], "replication": {"factor": 2, "spread": ["rack"]},
        "strategy": {"kind": "rendezvous"},
        "nodes": [_node("n1", domains={"zone": "za"})],
    },
    "ring-explicit-duplicate-token": {
        "formatVersion": "1.0", "topologyId": "invalid-duplicate-token", "epoch": 1,
        "strategy": {"kind": "ring", "tokenAssignment": "explicit"},
        "nodes": [_node("n1", tokens=["0000000000000001"]),
                  _node("n2", tokens=["0000000000000001"])],
    },
    "ring-explicit-missing-tokens": {
        "formatVersion": "1.0", "topologyId": "invalid-missing-tokens", "epoch": 1,
        "strategy": {"kind": "ring", "tokenAssignment": "explicit"},
        "nodes": [_node("n1", tokens=["0000000000000001"]), _node("n2")],
    },
    "ring-derived-carries-tokens": {
        "formatVersion": "1.0", "topologyId": "invalid-derived-tokens", "epoch": 1,
        "strategy": {"kind": "ring", "tokenAssignment": "derived"},
        "nodes": [_node("n1", tokens=["0000000000000001"])],
    },
    "slot-not-covered": {
        "formatVersion": "1.0", "topologyId": "invalid-slot-coverage", "epoch": 1,
        "strategy": {"kind": "slot", "slotCount": 8, "assignment": "explicit",
                     "assignments": [{"slots": ["0-5"], "nodes": ["n1"]}]},
        "nodes": [_node("n1")],
    },
    "slot-covered-twice": {
        "formatVersion": "1.0", "topologyId": "invalid-slot-overlap", "epoch": 1,
        "strategy": {"kind": "slot", "slotCount": 8, "assignment": "explicit",
                     "assignments": [{"slots": ["0-5"], "nodes": ["n1"]},
                                     {"slots": ["4-7"], "nodes": ["n1"]}]},
        "nodes": [_node("n1")],
    },
    "directory-unknown-node": {
        "formatVersion": "1.0", "topologyId": "invalid-directory-node", "epoch": 1,
        "strategy": {"kind": "directory",
                     "entries": [{"match": {"kind": "exact", "value": "k"},
                                  "nodes": ["ghost"]}]},
        "nodes": [_node("n1")],
    },
    "unsupported-format-version": {
        "formatVersion": "2.0", "topologyId": "invalid-format-version", "epoch": 1,
        "strategy": {"kind": "rendezvous"}, "nodes": [_node("n1")],
    },
    "unsupported-minor-version": {
        "formatVersion": "1.7", "topologyId": "invalid-minor-version", "epoch": 1,
        "strategy": {"kind": "rendezvous"}, "nodes": [_node("n1")],
    },
    "unsupported-hash-algorithm": {
        "formatVersion": "1.0", "topologyId": "invalid-hash-algorithm", "epoch": 1,
        "hash": {"algorithm": "xxhash64"},
        "strategy": {"kind": "rendezvous"}, "nodes": [_node("n1")],
    },
    "malformed-seed": {
        "formatVersion": "1.0", "topologyId": "invalid-seed", "epoch": 1,
        "hash": {"algorithm": "siphash-2-4", "seed": "ABCD"},
        "strategy": {"kind": "rendezvous"}, "nodes": [_node("n1")],
    },
}
