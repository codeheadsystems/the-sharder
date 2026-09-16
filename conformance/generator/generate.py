#!/usr/bin/env python3
"""Generate every golden vector under `conformance/vectors`, and the manifest.

Nothing in the output is written by hand.  Each expected value is computed by running
`sharder_ref`, which implements the specification's arithmetic, over the topology documents in
`topologies.py`.  Run `verify_siphash.py` first; this script refuses to run if the hash has not
been checked against the published vectors in the same invocation of `run.sh`.

    python3 generate.py [--out <conformance root>]
"""

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import topologies as T                                    # noqa: E402
from sharder_ref import hashing, placement, routing        # noqa: E402
from sharder_ref.jcs import canonicalise, digest as jcs_digest  # noqa: E402
from sharder_ref.topology import Snapshot, validate        # noqa: E402
from sharder_ref.transforms import apply_transform         # noqa: E402

ZERO = hashing.ZERO_SEED

# Every topology document the suite routes against, by the file it is written to.
TOPOLOGY_FILES = {
    "ring-plain": T.RING_DERIVED_PLAIN,
    "ring-zoned": T.RING_DERIVED_ZONED,
    "ring-explicit": T.RING_EXPLICIT,
    "ring-admin-states": T.RING_ADMIN_STATES,
    "ring-seeded": T.RING_SEEDED,
    "rendezvous-tenant": T.RENDEZVOUS_TENANT,
    "rendezvous-cache": T.RENDEZVOUS_CACHE,
    "rendezvous-plain": T.RENDEZVOUS_PLAIN,
    "rendezvous-capped": T.RENDEZVOUS_CAPPED,
    "slot-explicit": T.SLOT_EXPLICIT,
    "slot-derived": T.SLOT_DERIVED,
    "slot-small": T.SLOT_SMALL,
    "range-explicit": T.RANGE_EXPLICIT,
    "range-derived": T.RANGE_DERIVED,
    "directory-tenants": T.DIRECTORY_TENANTS,
    "directory-sparse": T.DIRECTORY_SPARSE,
    "empty-nodes": T.EMPTY_NODES,
    "empty-nodes-ring": T.EMPTY_NODES_RING,
    "single-node": T.SINGLE_NODE,
    "factor-exceeds-nodes": T.FACTOR_EXCEEDS_NODES,
    "one-domain-relaxed": T.ALL_ONE_DOMAIN_RELAXED,
    "one-domain-strict": T.ALL_ONE_DOMAIN_STRICT,
    "spread-ladder": T.SPREAD_LADDER,
    "weight-zero": T.WEIGHT_ZERO,
    "weight-zero-all": T.WEIGHT_ZERO_ALL,
    "weight-zero-pinned": T.WEIGHT_ZERO_PINNED,
    "overrides-precedence": T.OVERRIDES_PRECEDENCE,
    "read-affinity": T.READ_AFFINITY,
    "kt-none": T.KEY_TRANSFORM_NONE,
    "kt-brace": T.KEY_TRANSFORM_BRACE,
    "kt-brace-same": T.KEY_TRANSFORM_BRACE_SAME,
    "kt-prefix-1": T.KEY_TRANSFORM_PREFIX_1,
    "kt-prefix-2": T.KEY_TRANSFORM_PREFIX_2,
    "kt-continuation": T.KEY_TRANSFORM_CONTINUATION,
    "movement-ring-before": T.MOVEMENT_RING_BEFORE,
    "movement-ring-after": T.MOVEMENT_RING_AFTER,
    "movement-rv-before": T.MOVEMENT_RV_BEFORE,
    "movement-rv-after": T.MOVEMENT_RV_AFTER,
    "movement-slot-before": T.MOVEMENT_SLOT_BEFORE,
    "movement-slot-after": T.MOVEMENT_SLOT_AFTER,
    "movement-range-before": T.MOVEMENT_RANGE_BEFORE,
    "movement-range-after": T.MOVEMENT_RANGE_AFTER,
}

MANIFEST_ENTRIES = []
SNAPSHOTS = {}


# --------------------------------------------------------------------------- output

def write_json(path: Path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n")


def key_spec(value, encoding="utf8"):
    """Render key octets for a vector file.

    A key is octets, and `KEY-013` permits octets that are not valid text, so a key that does not
    decode as UTF-8 is written as `base16`.  A port reads the encoding rather than guessing.
    """
    if encoding == "utf8":
        try:
            return {"encoding": "utf8", "value": value.decode("utf-8")}
        except UnicodeDecodeError:
            pass
    return {"encoding": "base16", "value": value.hex()}


def decode_key(spec):
    if spec["encoding"] == "utf8":
        return spec["value"].encode("utf-8")
    return bytes.fromhex(spec["value"])


# --------------------------------------------------------------------- vector build

def routing_case(snapshot, key, name, requirements, note=None, encoding="utf8"):
    case = {"name": name, "requirements": sorted(set(requirements)),
            "key": key_spec(key, encoding)}
    if note:
        case["note"] = note
    try:
        decision = routing.route(snapshot, key)
    except routing.NoCandidate as failure:
        case["expectError"] = {"code": failure.code, "name": failure.name,
                               "cause": failure.cause}
        return case
    case["expect"] = decision
    return case


def routing_set(out, path, vector_set, description, topology_name, requirements, cases):
    snapshot = SNAPSHOTS[topology_name]
    payload = {
        "vectorSet": vector_set,
        "kind": "routing",
        "description": description,
        "requirements": sorted(set(requirements)),
        "topology": "topologies/%s.topology.json" % topology_name,
        "topologyDigest": snapshot.digest,
        "cases": cases,
    }
    write_json(out / path, payload)
    MANIFEST_ENTRIES.append({
        "file": path, "vectorSet": vector_set, "kind": "routing",
        "description": description, "topology": payload["topology"],
        "caseCount": len(cases),
        "requirements": sorted({r for c in cases for r in c["requirements"]} | set(requirements)),
    })


def simple_set(out, path, vector_set, kind, description, requirements, cases, topology=None):
    payload = {
        "vectorSet": vector_set,
        "kind": kind,
        "description": description,
        "requirements": sorted(set(requirements)),
        "cases": cases,
    }
    if topology:
        payload["topology"] = topology
    write_json(out / path, payload)
    MANIFEST_ENTRIES.append({
        "file": path, "vectorSet": vector_set, "kind": kind, "description": description,
        "topology": topology, "caseCount": len(cases),
        "requirements": sorted({r for c in cases for r in c.get("requirements", [])}
                               | set(requirements)),
    })


# ------------------------------------------------------------------ hash vectors

HASH_CONSTRUCTION = ["HASH-021", "HASH-022", "HASH-023", "HASH-030", "HASH-031", "HASH-032"]


def build_hash_vectors(out):
    """The framed, domain-tagged construction of `HASH-001` through `HASH-044`.

    Each case carries the framed message alongside the result, so a port that fails one reads off
    whether its framing or its SipHash is wrong.
    """
    cases = []

    def case(name, function, seed, fields, value, extra=()):
        requirements = list(HASH_CONSTRUCTION) + list(extra)
        requirements += ["HASH-011"] if seed == ZERO else ["HASH-010"]
        if any(len(f) == 0 for f in fields):
            requirements.append("HASH-024")
        cases.append({
            "name": name,
            "requirements": sorted(set(requirements)),
            "function": function,
            "seed": seed.hex(),
            "fields": [f.hex() for f in fields],
            "framedMessage": hashing.frame(*fields).hex(),
            "expect": hashing.hex_u64(value),
        })

    for label, rk in [("empty", b""), ("ascii", b"user-42"), ("tenant", b"acme"),
                      ("binary", bytes([0x00, 0xff, 0x7f, 0x80])),
                      ("sixteen-octets", bytes(range(16))),
                      ("utf8-multibyte", "café-日本".encode("utf-8"))]:
        case("keyHash/%s" % label, "keyHash", ZERO, [hashing.TAG_KEY, rk],
             hashing.key_hash(ZERO, rk))

    seeded = bytes.fromhex(T.STORAGE_SEED)
    for label, rk in [("empty", b""), ("ascii", b"user-42")]:
        case("keyHash/seeded/%s" % label, "keyHash", seeded, [hashing.TAG_KEY, rk],
             hashing.key_hash(seeded, rk))

    for node_id in [b"n1", b"store-a1", b""]:
        for index in [0, 1, 255, 65536]:
            if node_id == b"" and index != 0:
                continue
            case("ringToken/%s/%d" % (node_id.decode() or "empty", index), "ringToken", ZERO,
                 [hashing.TAG_RING_TOKEN, node_id, hashing.u32be(index)],
                 hashing.ring_token(ZERO, node_id, index), ("HASH-020", "PLACE-053"))

    for rk in [b"", b"user-42"]:
        for node_id in [b"n1", b"n2"]:
            for index in [0, 3]:
                case("rvScore/%s/%s/%d" % (rk.decode() or "empty", node_id.decode(), index),
                     "rvScore", ZERO,
                     [hashing.TAG_RENDEZVOUS, rk, node_id, hashing.u32be(index)],
                     hashing.rv_score(ZERO, rk, node_id, index), ("HASH-020", "PLACE-053"))

    for slot in [0, 1, 16383, 1048575]:
        case("slotScore/%d/s1/0" % slot, "slotScore", ZERO,
             [hashing.TAG_SLOT, hashing.u32be(slot), b"s1", hashing.u32be(0)],
             hashing.slot_score(ZERO, slot, b"s1", 0), ("HASH-020", "PLACE-053"))

    for shard in [b"r0", b"range-with-a-longer-name"]:
        case("rangeScore/%s/n1/0" % shard.decode(), "rangeScore", ZERO,
             [hashing.TAG_RANGE, shard, b"n1", hashing.u32be(0)],
             hashing.range_score(ZERO, shard, b"n1", 0), ("HASH-020", "PLACE-053", "PLACE-034"))

    simple_set(out, "vectors/hash/construction.json", "hash-construction", "hash",
               "The framed, domain-tagged hash construction of `HASH-020` through `HASH-032`, "
               "including the framed message octets so that a port can separate a framing defect "
               "from a SipHash defect.",
               sorted(set(HASH_CONSTRUCTION + ["HASH-010", "HASH-011", "HASH-020", "HASH-024",
                                               "HASH-044", "PLACE-053"])), cases)

    # The SipHash-2-4 primitive on its own, taken from the published paper.
    primitive = []
    paper_key = bytes(range(16))
    from verify_siphash import PAPER_VECTORS_LE
    for length in [0, 1, 7, 8, 15, 16, 31, 32, 63]:
        message = bytes(range(length))
        expected = int.from_bytes(bytes.fromhex(PAPER_VECTORS_LE[length]), "little")
        primitive.append({
            "name": "siphash-2-4/paper/%d" % length,
            "requirements": ["HASH-001", "HASH-002", "HASH-003", "SEC-015"],
            "reference": "Aumasson and Bernstein, SipHash, reference vectors",
            "key": paper_key.hex(),
            "message": message.hex(),
            "expect": hashing.hex_u64(expected),
        })
    simple_set(out, "vectors/hash/siphash-primitive.json", "siphash-primitive", "siphash",
               "SipHash-2-4 itself, at the published key and messages of `HASH-003`, so that a "
               "port that fails the construction vectors can tell which layer is wrong.",
               ["HASH-001", "HASH-002", "HASH-003", "HASH-040", "HASH-044", "SEC-015"], primitive)


# ---------------------------------------------------------- key transform vectors

def build_key_transform_vectors(out):
    def transform_cases(config, rows, requirements):
        cases = []
        for name, key, note in rows:
            cases.append({
                "name": name,
                "requirements": requirements,
                "key": key_spec(key),
                "note": note,
                "expect": {"routingKey": apply_transform(config, key).hex()},
            })
        return cases

    none_rows = [
        ("empty", b"", "KEY-004: a key of zero octets routes like any other"),
        ("ascii", b"user-42", "the key unchanged"),
        ("binary", bytes([0, 0xff, 0x7b, 0x7d]),
         "KEY-001 and KEY-002: octets, carried through with no encoding applied"),
        ("invalid-utf8", bytes([0xc3, 0x28, 0xff]),
         "KEY-013: octets that are not a valid encoding of text are routed, not refused"),
        ("nul-octets", bytes([0x00, 0x00]), "KEY-001: a NUL octet is an octet like any other"),
    ]
    simple_set(out, "vectors/keytransform/none.json", "keytransform-none", "keyTransform",
               "The `none` transform returns the key octet for octet.",
               ["KEY-001", "KEY-002", "KEY-004", "KEY-010", "KEY-013", "KEY-020"],
               transform_cases({"kind": "none"}, none_rows,
                               ["KEY-001", "KEY-002", "KEY-013", "KEY-020"]),
               topology="topologies/kt-none.topology.json")

    continuation = {"kind": "prefixFields", "separator": "a9", "count": 1}
    continuation_rows = [
        ("splits-inside-a-character", "caf\u00e9:x".encode("utf-8"),
         "KEY-012: the separator octet 0xa9 is the second octet of é, and the transform treats "
         "it like any other occurrence"),
        ("no-occurrence", b"plain-key", "KEY-042"),
        ("leading", bytes([0xa9, 0x61]), "KEY-043 and KEY-014: an empty routing key"),
    ]
    simple_set(out, "vectors/keytransform/continuation-octet.json",
               "keytransform-continuation-octet", "keyTransform",
               "A separator that is a UTF-8 continuation octet, so the routing key is an "
               "incomplete encoding of text.",
               ["KEY-011", "KEY-012", "KEY-013", "KEY-014", "KEY-043"],
               transform_cases(continuation, continuation_rows,
                               ["KEY-012", "KEY-013", "KEY-014"]),
               topology="topologies/kt-continuation.topology.json")

    brace = {"kind": "braceTag", "open": "7b", "close": "7d"}
    brace_rows = [
        ("tagged", b"a{b}c", "the tagged octets"),
        ("delimiter-at-zero", b"{b}", "KEY-034: position zero is not special"),
        ("empty-tag", b"{}", "KEY-033: no octet between the delimiters"),
        ("no-close", b"{abc", "KEY-032"),
        ("no-open", b"abc}", "KEY-031"),
        ("close-before-open", b"}a{b}", "KEY-035: the earlier close is ignored"),
        ("no-nesting", b"{a{b}c}", "KEY-036"),
        ("empty-key", b"", "KEY-031: no open octet"),
        ("redis-style", b"session:{u-9912}:profile", "the documented hash tag case"),
        ("open-at-end", b"abc{", "KEY-032"),
        ("adjacent-pair", b"{}{x}", "KEY-033 applies to the first pair"),
        ("multibyte-inside", "a{café}b".encode("utf-8"),
         "KEY-012: the delimiter search is over octets"),
    ]
    simple_set(out, "vectors/keytransform/brace-tag.json", "keytransform-brace-tag",
               "keyTransform",
               "The `braceTag` transform at the default delimiters.",
               ["KEY-030", "KEY-031", "KEY-032", "KEY-033", "KEY-034", "KEY-035", "KEY-036"],
               transform_cases(brace, brace_rows, ["KEY-030"]),
               topology="topologies/kt-brace.topology.json")

    same = {"kind": "braceTag", "open": "7c", "close": "7c"}
    same_rows = [
        ("pair", b"a|b|c", "KEY-037: one octet serving as both delimiters"),
        ("single", b"a|b", "no close octet after the open"),
        ("adjacent", b"a||b", "KEY-033: nothing between the two"),
    ]
    simple_set(out, "vectors/keytransform/brace-tag-same-octet.json",
               "keytransform-brace-tag-same-octet", "keyTransform",
               "The `braceTag` transform where `open` and `close` are the same octet.",
               ["KEY-037"], transform_cases(same, same_rows, ["KEY-037"]),
               topology="topologies/kt-brace-same.topology.json")

    prefix1 = {"kind": "prefixFields", "separator": "3a", "count": 1}
    prefix1_rows = [
        ("tenant", b"acme:orders:99", "the octets before the first separator"),
        ("no-separator", b"acme", "KEY-042"),
        ("trailing-separator", b"acme:", "the whole prefix, with an empty remainder"),
        ("separator-at-zero", b":x", "KEY-043 and KEY-014: the empty routing key, not the "
         "whole key"),
        ("empty-key", b"", "KEY-042"),
        ("only-separator", b":", "KEY-043"),
    ]
    simple_set(out, "vectors/keytransform/prefix-fields-1.json",
               "keytransform-prefix-fields-1", "keyTransform",
               "The `prefixFields` transform at a count of 1.",
               ["KEY-011", "KEY-014", "KEY-040", "KEY-041", "KEY-042", "KEY-043"],
               transform_cases(prefix1, prefix1_rows, ["KEY-041"]),
               topology="topologies/kt-prefix-1.topology.json")

    prefix2 = {"kind": "prefixFields", "separator": "3a", "count": 2}
    prefix2_rows = [
        ("two-fields", b"acme:orders:99", "the octets before the second separator"),
        ("consecutive", b"::", "KEY-044: consecutive separators count separately"),
        ("too-few", b"a:b", "KEY-042: fewer than `count` occurrences"),
        ("exactly-two", b"a:b:", "the prefix up to the second separator"),
    ]
    simple_set(out, "vectors/keytransform/prefix-fields-2.json",
               "keytransform-prefix-fields-2", "keyTransform",
               "The `prefixFields` transform at a count of 2.",
               ["KEY-041", "KEY-042", "KEY-044"],
               transform_cases(prefix2, prefix2_rows, ["KEY-044"]),
               topology="topologies/kt-prefix-2.topology.json")


# ------------------------------------------------------------------- digest vectors

def build_digest_vectors(out):
    cases = []
    for name in sorted(TOPOLOGY_FILES):
        document = TOPOLOGY_FILES[name]
        canonical = canonicalise(document)
        case = {
            "name": name,
            "requirements": ["TOPO-001", "CORE-020"],
            "topology": "topologies/%s.topology.json" % name,
            "expect": {
                "canonicalLength": len(canonical.encode("utf-8")),
                "digest": jcs_digest(document),
            },
        }
        if len(canonical) <= 600:
            case["expect"]["canonicalForm"] = canonical
        cases.append(case)

    escaping = {
        "formatVersion": "1.0",
        "topologyId": "digest-escaping",
        "epoch": 1,
        "strategy": {"kind": "rendezvous"},
        "nodes": [{"id": "n1"}],
        "metadata": {
            "quote": "a \"quoted\" word",
            "backslash": "a\\b",
            "controls": "tab\there\nnewline",
            "unicode": "café 日本",
            "zwj": "",
            "": "empty member name sorts first",
        },
    }
    cases.append({
        "name": "escaping-and-member-order",
        "requirements": ["TOPO-001"],
        "note": "RFC 8785 string escaping and member name ordering, inline rather than a file "
                "so that a port reads the document from the vector rather than from disk.",
        "document": escaping,
        "expect": {
            "canonicalForm": canonicalise(escaping),
            "canonicalLength": len(canonicalise(escaping).encode("utf-8")),
            "digest": jcs_digest(escaping),
        },
    })

    simple_set(out, "vectors/digest/canonical-form.json", "digest-canonical-form", "digest",
               "RFC 8785 canonical form and the SHA-256 topology digest of every document the "
               "suite uses.",
               ["TOPO-001", "TOPO-061"], cases)


# --------------------------------------------------------------- validation vectors

def _schema_errors(document):
    """The stage 2 errors of `TOPO-001`, where `jsonschema` is available to compute them."""
    try:
        import jsonschema
    except ImportError:
        return None
    schema_path = HERE.parent.parent / "docs/design/topology-v1.schema.json"
    validator = jsonschema.Draft202012Validator(json.loads(schema_path.read_text()))
    return [e.message for e in validator.iter_errors(document)]


def build_validation_vectors(out, root):
    cases = []
    for name in sorted(T.INVALID_DOCUMENTS):
        document = T.INVALID_DOCUMENTS[name]
        errors = validate(document)
        assert errors, "document %s was expected to be invalid" % name
        path = "topologies/invalid/%s.topology.json" % name
        write_json(root / path, document)
        schema_errors = _schema_errors(document)
        stage = "semantic" if not schema_errors else "schema"
        cases.append({
            "name": name,
            "requirements": ["TOPO-001", "TOPO-011", "TOPO-191", "ERR-030"],
            "document": path,
            "stage": stage,
            "note": "rejected at stage %s of TOPO-001" % ("2, schema validation"
                                                          if stage == "schema"
                                                          else "3, the semantic rules"),
            "expect": {
                "valid": False,
                "condition": {"code": 201, "name": "invalidTopology"},
                "rules": sorted({e["rule"] for e in errors}),
                "errors": errors,
            },
        })

    for name in sorted(TOPOLOGY_FILES):
        cases.append({
            "name": "valid/%s" % name,
            "requirements": ["TOPO-001"],
            "document": "topologies/%s.topology.json" % name,
            "stage": "none",
            "expect": {"valid": True, "rules": [], "errors": []},
        })

    simple_set(out, "vectors/validation/documents.json", "validation-documents", "validation",
               "Documents that fail a load-time rule, each with the rules it breaks, and every "
               "valid document in the suite as a negative control.  A port reports every error "
               "rather than the first, under `ERR-030`.",
               ["TOPO-001", "TOPO-011", "TOPO-191", "ERR-030", "OVR-015", "OVR-028",
                "PLACE-067", "REPL-004"], cases)


# ------------------------------------------------------------------ routing vectors

def build_ring_vectors(out):
    snapshot = SNAPSHOTS["ring-plain"]
    cases = [routing_case(snapshot, k, n, r, note) for n, k, r, note in [
        ("walk/user-42", b"user-42", ["RING-020", "RING-021", "RING-023"],
         "the ordinary ring walk"),
        ("walk/empty-key", b"", ["RING-020", "KEY-004"], None),
        ("walk/wrap", b"key-000042", ["RING-020", "RING-021"],
         "included so that some case in the set wraps past the last ring entry"),
        ("walk/binary-key", bytes([0xff] * 8), ["RING-020"], None),
    ]]
    for i in range(12):
        cases.append(routing_case(snapshot, ("k%d" % i).encode(), "walk/k%d" % i,
                                  ["RING-020", "RING-021", "RING-022"]))
    routing_set(out, "vectors/ring/derived-walk.json", "ring-derived-walk",
                "The ring walk under derived tokens: every eligible node appears exactly once, "
                "in ring order from the owning entry, wrapping once.",
                "ring-plain",
                ["RING-001", "RING-010", "RING-013", "RING-020", "RING-021", "RING-022",
                 "RING-023", "RING-025", "RING-030", "PLACE-013", "PLACE-017", "TOPO-151",
                 "CORE-040"], cases)

    snapshot = SNAPSHOTS["ring-zoned"]
    cases = [routing_case(snapshot, ("tenant-%d" % i).encode(), "zoned/tenant-%d" % i,
                          ["RING-020", "REPL-012", "SPREAD-011", "SPREAD-012"])
             for i in range(10)]
    routing_set(out, "vectors/ring/derived-zoned.json", "ring-derived-zoned",
                "Ring placement with weights, two domain levels, factor 3, and a zone spread. "
                "Exercises the preference list builder over a ring candidate ordering.",
                "ring-zoned",
                ["RING-001", "PLACE-041", "PLACE-050", "REPL-012", "REPL-013", "REPL-014",
                 "REPL-017", "SPREAD-001", "SPREAD-011", "SPREAD-012", "SPREAD-015"], cases)

    snapshot = SNAPSHOTS["ring-explicit"]
    cases = [routing_case(snapshot, k, n, r, note) for n, k, r, note in [
        ("explicit/below-first", b"a", ["RING-003", "RING-020"], None),
        ("explicit/k1", b"k1", ["RING-003", "RING-020"], None),
        ("explicit/k2", b"k2", ["RING-003", "RING-020"], None),
        ("explicit/k3", b"k3", ["RING-003", "RING-020"], None),
        ("explicit/weight-zero-owns-tokens", b"k4", ["RING-004", "PLACE-043"],
         "n3 carries weight 0 and explicit tokens, so it owns ranges and appears"),
    ]]
    for i in range(8):
        cases.append(routing_case(snapshot, ("e%d" % i).encode(), "explicit/e%d" % i,
                                  ["RING-003", "RING-020", "RING-021"]))
    routing_set(out, "vectors/ring/explicit-tokens.json", "ring-explicit-tokens",
                "Ring placement under authored tokens, including a node of weight 0 that carries "
                "tokens and therefore appears in the ordering.",
                "ring-explicit",
                ["RING-003", "RING-004", "RING-005", "RING-020", "RING-021", "PLACE-043",
                 "PLACE-044"], cases)

    snapshot = SNAPSHOTS["ring-admin-states"]
    cases = [routing_case(snapshot, ("s%d" % i).encode(), "states/s%d" % i,
                          ["PLACE-001", "PLACE-002"],
                          "joining-1 and leaving-1 never appear; draining-1 does")
             for i in range(6)]
    routing_set(out, "vectors/ring/administrative-states.json", "ring-administrative-states",
                "The placement set is `active` plus `draining`.  A `joining` or `leaving` node "
                "never reaches a candidate ordering.",
                "ring-admin-states",
                ["PLACE-001", "PLACE-002", "RING-005"], cases)

    snapshot = SNAPSHOTS["ring-seeded"]
    cases = [routing_case(snapshot, ("seeded-%d" % i).encode(), "seeded/%d" % i,
                          ["SEC-010", "SEC-012"],
                          "a non-default seed, which moves every key relative to the zero seed")
             for i in range(6)]
    routing_set(out, "vectors/ring/non-default-seed.json", "ring-non-default-seed",
                "Ring placement under a non-default hash seed.  Every other vector in the suite "
                "uses the sixteen zero octets.",
                "ring-seeded", ["SEC-010", "SEC-011", "SEC-012"], cases)


def build_rendezvous_vectors(out):
    snapshot = SNAPSHOTS["rendezvous-plain"]
    cases = [routing_case(snapshot, ("rv-%d" % i).encode(), "plain/%d" % i,
                          ["RV-003", "RV-010", "RV-011"]) for i in range(14)]
    cases.append(routing_case(snapshot, b"", "plain/empty-key", ["KEY-004", "RV-003"]))
    routing_set(out, "vectors/rendezvous/ordering.json", "rendezvous-ordering",
                "Rendezvous ordering by descending score with the node identity tie-break, at "
                "one virtual node per node.",
                "rendezvous-plain",
                ["RV-001", "RV-003", "RV-004", "RV-010", "RV-011", "RV-013", "RV-020", "RV-021",
                 "PLACE-020", "PLACE-023"], cases)

    snapshot = SNAPSHOTS["rendezvous-capped"]
    cases = [routing_case(snapshot, ("cap-%d" % i).encode(), "capped/%d" % i,
                          ["PLACE-050", "PLACE-051", "PLACE-052", "RV-001"],
                          "`clamped` asks for 400 virtual nodes and is granted 6")
             for i in range(8)]
    routing_set(out, "vectors/rendezvous/virtual-node-cap.json", "rendezvous-virtual-node-cap",
                "The virtual node count is `min(weight * perWeightUnit, cap)`.  Clamping is an "
                "event rather than a validation failure, so the node still places.",
                "rendezvous-capped",
                ["PLACE-050", "PLACE-051", "PLACE-052", "PLACE-054", "RV-001"], cases)

    snapshot = SNAPSHOTS["rendezvous-tenant"]
    cases = [routing_case(snapshot, k, n, r, note) for n, k, r, note in [
        ("tenant/acme-pinned", b"acme:orders:99",
         ["KEY-041", "OVR-002", "OVR-010", "OVR-011"],
         "the worked example of 20-topology-format.md: pinned to cluster-us-2 then cluster-us-1"),
        ("tenant/eu-constrained", b"eu-bank:ledger:7",
         ["OVR-020", "OVR-022", "PLACE-003"],
         "the worked example: placed by rendezvous across the two European clusters only"),
        ("tenant/unmatched", b"globex:orders:1", ["OVR-004"], None),
        ("tenant/acme-exact-only", b"acmecorp:x:1", ["PLACE-061"],
         "an exact matcher does not match a longer routing key"),
        ("tenant/eu-prefix-boundary", b"eu-:x", ["PLACE-062"], None),
    ]]
    for i in range(6):
        cases.append(routing_case(snapshot, ("tenant-%d:orders:1" % i).encode(),
                                  "tenant/hashed-%d" % i, ["KEY-041", "RV-010"]))
    routing_set(out, "vectors/rendezvous/tenant-router.json", "rendezvous-tenant-router",
                "The tenant routing example of `20-topology-format.md`, with the key transform, "
                "an exact pin, and a residency constraint.",
                "rendezvous-tenant",
                ["KEY-041", "OVR-001", "OVR-002", "OVR-004", "OVR-010", "OVR-011", "OVR-020",
                 "OVR-022", "PLACE-003", "PLACE-061", "PLACE-062", "PLACE-065", "REPL-002"],
                cases)

    snapshot = SNAPSHOTS["rendezvous-cache"]
    cases = [routing_case(snapshot, k, n, r, note) for n, k, r, note in [
        ("cache/tag-profile", b"session:{u-9912}:profile", ["KEY-030", "PLACE-010"],
         "co-located with the cart key below: both transform to `u-9912`"),
        ("cache/tag-cart", b"session:{u-9912}:cart", ["KEY-030", "PLACE-010"],
         "co-located with the profile key above"),
        ("cache/no-tag", b"session:u-9912", ["KEY-031"], None),
        ("cache/draining-zero-weight", b"anything", ["PLACE-042", "PLACE-043"],
         "cache-08 is draining at weight 0, so it is in the placement set and places nothing"),
    ]]
    for i in range(8):
        cases.append(routing_case(snapshot, ("session:{u-%d}:x" % i).encode(),
                                  "cache/u-%d" % i, ["KEY-030", "RV-010"]))
    routing_set(out, "vectors/rendezvous/cache-hash-tags.json", "rendezvous-cache-hash-tags",
                "The cache example of `20-topology-format.md`: Redis-compatible hash tags, mixed "
                "weights, and a draining node at weight 0.",
                "rendezvous-cache",
                ["KEY-030", "KEY-031", "PLACE-042", "PLACE-043", "RV-001", "RV-010"], cases)


def build_slot_vectors(out):
    snapshot = SNAPSHOTS["slot-explicit"]
    cases = [routing_case(snapshot, ("slot-%d" % i).encode(), "explicit/%d" % i,
                          ["SLOT-001", "SLOT-010", "SLOT-011", "SLOT-012", "SLOT-030"])
             for i in range(12)]
    routing_set(out, "vectors/slot/explicit-assignment.json", "slot-explicit-assignment",
                "Slot placement under an authored assignment table, at the Redis Cluster slot "
                "count.  The covering entry's `nodes` array is the candidate ordering.",
                "slot-explicit",
                ["SLOT-001", "SLOT-002", "SLOT-003", "SLOT-010", "SLOT-011", "SLOT-012",
                 "SLOT-014", "SLOT-030", "PLACE-013", "PLACE-014"], cases)

    snapshot = SNAPSHOTS["slot-derived"]
    cases = [routing_case(snapshot, ("d%d" % i).encode(), "derived/%d" % i,
                          ["SLOT-020", "SLOT-021", "SLOT-022", "SLOT-023"]) for i in range(12)]
    routing_set(out, "vectors/slot/derived-assignment.json", "slot-derived-assignment",
                "Slot placement under derived assignment: the ordering is the rendezvous "
                "ordering over the slot index, so two keys in one slot order identically.",
                "slot-derived",
                ["SLOT-020", "SLOT-021", "SLOT-022", "SLOT-023", "SLOT-030", "SLOT-031",
                 "PLACE-042"], cases)

    snapshot = SNAPSHOTS["slot-small"]
    cases = [routing_case(snapshot, ("m%d" % i).encode(), "modulus/%d" % i,
                          ["SLOT-001", "SLOT-003"],
                          "a slot count that is not a power of two")
             for i in range(14)]
    routing_set(out, "vectors/slot/non-power-of-two-modulus.json", "slot-non-power-of-two",
                "`keyHash mod slotCount` at a slot count of 7, which a bitwise mask cannot "
                "reproduce.",
                "slot-small", ["SLOT-001", "SLOT-002", "SLOT-003", "PROP-024"], cases)


def build_range_vectors(out):
    snapshot = SNAPSHOTS["range-explicit"]
    cases = [routing_case(snapshot, k, n, r, note, enc) for n, k, r, note, enc in [
        ("explicit/empty-key", b"", ["RANGE-004", "RANGE-010"],
         "the empty sequence compares below every non-empty one, so it falls in the first range",
         "utf8"),
        ("explicit/below-first-bound", b"a", ["RANGE-011"], None, "utf8"),
        ("explicit/at-bound", bytes([0x6d]), ["RANGE-012"],
         "a key equal to a `start` falls inside that range", "base16"),
        ("explicit/just-below-bound", bytes([0x6c, 0xff]), ["RANGE-011"], None, "base16"),
        ("explicit/prefix-of-bound", bytes([0x7a]), ["RANGE-003"],
         "`7a` compares less than the bound `7a00`, so it stays in r1", "base16"),
        ("explicit/extends-bound", bytes([0x7a, 0x00]), ["RANGE-003", "RANGE-012"],
         "`7a00` equals the bound and falls in the following range", "base16"),
        ("explicit/high-octet", bytes([0xff]), ["RANGE-002"],
         "an octet above 0x7f compares as unsigned, so it is above the bound", "base16"),
        ("explicit/last-range", b"zzz", ["RANGE-010"], None, "utf8"),
    ]]
    routing_set(out, "vectors/range/explicit-bounds.json", "range-explicit-bounds",
                "Unsigned bytewise bound comparison over half-open intervals, including the "
                "prefix rule and the half-open endpoints.",
                "range-explicit",
                ["RANGE-001", "RANGE-002", "RANGE-003", "RANGE-004", "RANGE-005", "RANGE-010",
                 "RANGE-011", "RANGE-012", "RANGE-013", "RANGE-020", "RANGE-040"], cases)

    snapshot = SNAPSHOTS["range-derived"]
    cases = []
    for label, key in [("00", bytes([0x00])), ("3f", bytes([0x3f])), ("40", bytes([0x40])),
                       ("7f", bytes([0x7f])), ("80", bytes([0x80])), ("c0", bytes([0xc0])),
                       ("ff", bytes([0xff]))]:
        cases.append(routing_case(snapshot, key, "derived/%s" % label,
                                  ["RANGE-030", "RANGE-031", "RANGE-032", "RANGE-033"],
                                  "the ordering depends on the key only through the shardId",
                                  encoding="base16"))
    cases.append(routing_case(snapshot, bytes([0x41, 0x99]), "derived/41-99",
                              ["RANGE-033"],
                              "a second key in r1, which orders identically to `40`",
                              encoding="base16"))
    routing_set(out, "vectors/range/derived-assignment.json", "range-derived-assignment",
                "Range placement under derived assignment, where the ordering is the rendezvous "
                "ordering over the `shardId`.",
                "range-derived",
                ["RANGE-030", "RANGE-031", "RANGE-032", "RANGE-033", "RANGE-040"], cases)


def build_directory_vectors(out):
    snapshot = SNAPSHOTS["directory-tenants"]
    cases = [routing_case(snapshot, k, n, r, note, enc) for n, k, r, note, enc in [
        ("exact-beats-prefix", b"gov-high", ["PLACE-065", "DIR-002"],
         "the exact matcher wins over `gov-hi` and `gov-`", "utf8"),
        ("longest-prefix", b"gov-hire", ["PLACE-065", "DIR-002"],
         "`gov-hi` beats `gov-` and the empty prefix", "utf8"),
        ("shorter-prefix", b"gov-low", ["PLACE-065"], None, "utf8"),
        ("empty-prefix-catch-all", b"anything-else", ["PLACE-063", "DIR-001"],
         "the empty prefix matches every routing key", "utf8"),
        ("exact-tenant", b"acme", ["PLACE-061"], None, "utf8"),
        ("base16-matcher", bytes([0xff, 0x00, 0x01]), ["PLACE-060"],
         "a matcher whose value is not text", "base16"),
        ("empty-key", b"", ["PLACE-063"],
         "the empty prefix matches the empty routing key", "utf8"),
    ]]
    routing_set(out, "vectors/directory/matcher-precedence.json", "directory-matcher-precedence",
                "Directory evaluation and the reachable clauses of the matcher precedence rule.",
                "directory-tenants",
                ["DIR-001", "DIR-002", "DIR-003", "DIR-004", "DIR-005", "DIR-020",
                 "PLACE-060", "PLACE-061", "PLACE-062", "PLACE-063", "PLACE-064",
                 "PLACE-065"], cases)

    snapshot = SNAPSHOTS["directory-sparse"]
    cases = [routing_case(snapshot, k, n, r, note) for n, k, r, note in [
        ("known", b"known", ["DIR-003"], None),
        ("prefixed", b"prefixed-key", ["DIR-003"], None),
        ("no-match", b"unknown", ["DIR-010", "DIR-011", "ERR-020", "ERR-021"],
         "a directory is exhaustive by construction; no match is no route"),
        ("entry-names-leaving-node", b"gone", ["PLACE-002", "PLACE-014", "ERR-020", "ERR-021"],
         "the entry's only node is `leaving`, so the ordering is empty and `ERR-021` names "
         "`authoredListExcludedAll`"),
    ]]
    routing_set(out, "vectors/directory/no-match.json", "directory-no-match",
                "A routing key that no directory entry matches, and an entry whose every node is "
                "outside the placement set.",
                "directory-sparse",
                ["DIR-010", "DIR-011", "DIR-012", "DIR-020", "ERR-020", "ERR-021",
                 "PLACE-002", "PLACE-014", "REPL-005"], cases)


def build_override_vectors(out):
    snapshot = SNAPSHOTS["overrides-precedence"]
    cases = [routing_case(snapshot, k, n, r, note) for n, k, r, note in [
        ("exact-beats-prefix", b"abcd", ["PLACE-065", "OVR-002", "OVR-020", "OVR-040"],
         "the exact entry wins and supersedes the document factor with its own"),
        ("longest-prefix", b"abce", ["PLACE-065", "OVR-010"],
         "`abc` beats the single octet 0xab and the empty prefix"),
        ("empty-prefix", b"zzz", ["PLACE-063", "OVR-010"],
         "the empty prefix is the only match"),
        ("constrain-by-tag", b"tag-anything", ["OVR-023", "OVR-021"], None),
        ("constrain-by-node-list", b"nodes-anything", ["OVR-024"], None),
        ("constrain-excludes-all", b"none-anything",
         ["OVR-027", "ERR-020", "ERR-021"],
         "no node satisfies the constraint, so the call answers noCandidate"),
        ("pin-filtered-to-empty", b"gone-anything",
         ["OVR-011", "OVR-014", "ERR-020", "ERR-021"],
         "the pinned node is `leaving`, so the pin empties and the strategy does not run"),
        ("pin-and-constrain", b"both-anything", ["OVR-030", "OVR-031"],
         "the pin applies and the constraint filters it; n-eu-1 is dropped"),
    ]]
    routing_set(out, "vectors/overrides/precedence-and-composition.json",
                "overrides-precedence-and-composition",
                "Override matching, pinning, constraints, their composition, and the override "
                "replication factor.",
                "overrides-precedence",
                ["OVR-001", "OVR-002", "OVR-003", "OVR-004", "OVR-005", "OVR-006", "OVR-010",
                 "OVR-011", "OVR-012", "OVR-013", "OVR-014", "OVR-020", "OVR-021", "OVR-022",
                 "OVR-023", "OVR-024", "OVR-025", "OVR-026", "OVR-027", "OVR-030", "OVR-031",
                 "OVR-040", "OVR-041", "PLACE-063", "PLACE-065", "PLACE-066",
                 "REPL-003", "REPL-005"], cases)

    snapshot = SNAPSHOTS["weight-zero-pinned"]
    cases = [routing_case(snapshot, b"pinned", "pin-admits-weight-zero",
                          ["PLACE-043", "OVR-010"],
                          "a node of weight 0 is eligible for a pin and appears in the ordering"),
             routing_case(snapshot, b"unpinned", "strategy-excludes-weight-zero",
                          ["PLACE-042"], None)]
    routing_set(out, "vectors/overrides/pin-admits-weight-zero.json",
                "overrides-pin-admits-weight-zero",
                "A node of weight 0 places nothing under a derived assignment and still serves a "
                "pin.",
                "weight-zero-pinned", ["PLACE-042", "PLACE-043", "OVR-010"], cases)


def build_replication_vectors(out):
    snapshot = SNAPSHOTS["factor-exceeds-nodes"]
    cases = [routing_case(snapshot, ("x%d" % i).encode(), "factor-above-node-count/%d" % i,
                          ["REPL-020", "REPL-021", "REPL-025"],
                          "factor 5 over 3 nodes: r is 3 and the cause is `nodes`")
             for i in range(5)]
    routing_set(out, "vectors/replication/factor-exceeds-node-count.json",
                "replication-factor-exceeds-node-count",
                "A replication factor above the eligible node count produces a shorter replica "
                "prefix, never a repeated node, and never a failed call.",
                "factor-exceeds-nodes",
                ["REPL-011", "REPL-020", "REPL-021", "REPL-025", "ERR-009"], cases)

    snapshot = SNAPSHOTS["single-node"]
    cases = [routing_case(snapshot, ("one-%d" % i).encode(), "single-node/%d" % i,
                          ["REPL-020", "REPL-021"],
                          "one node, factor 3: r is 1, the tail is empty, the cause is `nodes`")
             for i in range(3)]
    routing_set(out, "vectors/replication/single-node.json", "replication-single-node",
                "A topology of one node at factor 3.",
                "single-node", ["REPL-020", "REPL-021", "REPL-025", "SPREAD-013"], cases)

    snapshot = SNAPSHOTS["empty-nodes"]
    cases = [routing_case(snapshot, b"anything", "empty/rendezvous",
                          ["PLACE-005", "ERR-020", "ERR-021"],
                          "an empty node list is a valid topology that routes to nothing"),
             routing_case(snapshot, b"", "empty/empty-key", ["PLACE-005", "ERR-020"], None)]
    routing_set(out, "vectors/adversarial/empty-topology.json", "adversarial-empty-topology",
                "An empty `nodes` array is valid at load time and yields no candidate at route "
                "time.",
                "empty-nodes", ["PLACE-005", "ERR-020", "ERR-021"], cases)

    snapshot = SNAPSHOTS["empty-nodes-ring"]
    cases = [routing_case(snapshot, b"anything", "empty-ring", ["RING-024", "PLACE-005"],
                          "no eligible node owns a token")]
    routing_set(out, "vectors/adversarial/empty-topology-ring.json",
                "adversarial-empty-topology-ring",
                "The same empty topology under `ring`, where the ring order itself is empty.",
                "empty-nodes-ring", ["RING-024", "PLACE-005", "ERR-020"], cases)

    snapshot = SNAPSHOTS["weight-zero"]
    cases = [routing_case(snapshot, ("w%d" % i).encode(), "weight-zero/%d" % i,
                          ["PLACE-042", "REPL-021"],
                          "the two weight-0 nodes are in the placement set and place nothing")
             for i in range(4)]
    routing_set(out, "vectors/adversarial/weight-zero.json", "adversarial-weight-zero",
                "Nodes of weight 0 stay in the placement set, receive no virtual node, and never "
                "reach a candidate ordering under a derived assignment.",
                "weight-zero", ["PLACE-042", "PLACE-043", "RV-002", "REPL-020"], cases)

    snapshot = SNAPSHOTS["weight-zero-all"]
    cases = [routing_case(snapshot, b"anything", "all-weight-zero",
                          ["RV-012", "ERR-020", "ERR-021"],
                          "every eligible node has a virtual node count of 0")]
    routing_set(out, "vectors/adversarial/weight-zero-all.json", "adversarial-weight-zero-all",
                "Every node at weight 0: the candidate ordering is empty and the cause is "
                "`zeroVirtualNodes`.",
                "weight-zero-all", ["RV-002", "RV-012", "ERR-020", "ERR-021"], cases)


def build_spread_vectors(out):
    snapshot = SNAPSHOTS["one-domain-relaxed"]
    cases = [routing_case(snapshot, ("r%d" % i).encode(), "relaxed/%d" % i,
                          ["SPREAD-012", "SPREAD-013", "SPREAD-015"],
                          "no zone spread is possible, so stage 1 is chosen and `zone` is "
                          "reported relaxed")
             for i in range(5)]
    routing_set(out, "vectors/spread/all-nodes-one-domain-relaxed.json",
                "spread-one-domain-relaxed",
                "Every node in one failure domain under `relaxed`: the builder degrades to "
                "distinctness alone and reports the relaxed level.",
                "one-domain-relaxed",
                ["SPREAD-001", "SPREAD-010", "SPREAD-011", "SPREAD-012", "SPREAD-013",
                 "SPREAD-015", "SPREAD-021"], cases)

    snapshot = SNAPSHOTS["one-domain-strict"]
    cases = [routing_case(snapshot, ("r%d" % i).encode(), "strict/%d" % i,
                          ["SPREAD-014", "REPL-021"],
                          "`strict` evaluates stage 0 alone, so r is 1 and the cause is "
                          "`domains`")
             for i in range(5)]
    routing_set(out, "vectors/spread/all-nodes-one-domain-strict.json",
                "spread-one-domain-strict",
                "The same node set under `strict`: a shorter replica prefix rather than a "
                "degraded spread.",
                "one-domain-strict",
                ["SPREAD-014", "SPREAD-015", "REPL-020", "REPL-021"], cases)

    snapshot = SNAPSHOTS["spread-ladder"]
    cases = [routing_case(snapshot, ("ladder-%d" % i).encode(), "ladder/%d" % i,
                          ["SPREAD-010", "SPREAD-011", "SPREAD-012", "SPREAD-015",
                           "SPREAD-017"],
                          "three spread levels and factor 4 over six nodes")
             for i in range(12)]
    routing_set(out, "vectors/spread/degradation-ladder.json", "spread-degradation-ladder",
                "Three spread levels, factor 4, and a node set that forces the relaxation ladder "
                "past stage 0.  Stage k of `SPREAD-010` enforces the finest m-k levels, which "
                "`SPREAD-018` makes equivalent to enforcing `spread[k]` alone, so the chosen "
                "stage is the coarsest level at which a full replica prefix exists.  "
                "`vectors/spread/relaxation-stages.json` records each stage separately.",
                "spread-ladder",
                ["SPREAD-005", "SPREAD-010", "SPREAD-011", "SPREAD-012", "SPREAD-013",
                 "SPREAD-015", "SPREAD-016", "SPREAD-017", "SPREAD-018", "SPREAD-019",
                 "SPREAD-020", "SPREAD-021", "REPL-013"], cases)

    # Every relaxation stage, recorded as data.  `SPREAD-010` enforces the finest `m-k` levels at
    # stage `k`, and `SPREAD-011` compares a domain path that runs from the coarsest declared level
    # through the level being tested, so the coarsest enforced level dominates and stage `k` admits
    # exactly what `spread[k]` alone admits.  `distinctStageOutcomes` is the count of distinct rungs
    # and `SPREAD-019` requires the ladder to be ordered from strongest to weakest.
    ladder_cases = []
    for name in ["spread-ladder", "one-domain-relaxed"]:
        snapshot = SNAPSHOTS[name]
        spread = snapshot.spread
        m = len(spread)
        for i in range(4):
            key = ("stage-%d" % i).encode()
            routing_key = routing.routing_key_of(snapshot, key)
            ordering = placement.candidates(snapshot, routing_key, snapshot.placement_set)
            nodes = [snapshot.by_id[n] for n in ordering]
            stages = []
            for k in range(m + 1):
                levels = spread[k:]
                selected = routing.select_stage(snapshot, nodes, snapshot.factor, levels)
                stages.append({"stage": k, "enforces": levels,
                               "relaxes": spread[:k] if k else [],
                               "selected": [n.id for n in selected],
                               "reachesFactor": len(selected) == snapshot.factor})
            decision = routing.route(snapshot, key)
            ladder_cases.append({
                "name": "%s/stage-%d" % (name, i),
                "requirements": ["SPREAD-010", "SPREAD-011", "SPREAD-012", "SPREAD-013",
                                 "SPREAD-015", "SPREAD-017", "SPREAD-018", "SPREAD-019"],
                "topology": "topologies/%s.topology.json" % name,
                "key": key_spec(key),
                "expect": {
                    "candidates": ordering,
                    "factor": snapshot.factor,
                    "stages": stages,
                    "chosenStage": decision["spreadStage"],
                    "relaxedLevels": decision["relaxedLevels"],
                    "preferenceList": decision["preferenceList"],
                    "distinctStageOutcomes": len({tuple(s["selected"]) for s in stages}),
                },
            })
    simple_set(out, "vectors/spread/relaxation-stages.json", "spread-relaxation-stages",
               "stages",
               "Every relaxation stage of `SPREAD-010`, with the entries it admits and whether "
               "it reaches the effective replication factor.  `distinctStageOutcomes` counts how "
               "many of the m+1 stages differ, and `SPREAD-018` makes it m+1 where the topology "
               "has a distinct domain at each level.",
               ["SPREAD-005", "SPREAD-010", "SPREAD-011", "SPREAD-012", "SPREAD-013",
                "SPREAD-015", "SPREAD-017", "SPREAD-018", "SPREAD-019", "SPREAD-020"],
               ladder_cases)

    snapshot = SNAPSHOTS["ring-zoned"]
    cases = [routing_case(snapshot, ("tail-%d" % i).encode(), "tail/%d" % i,
                          ["REPL-013", "REPL-014", "REPL-017", "SPREAD-021"],
                          "entries skipped for spread reappear in the tail at their original "
                          "relative position")
             for i in range(6)]
    routing_set(out, "vectors/replication/fallback-tail.json", "replication-fallback-tail",
                "The fallback tail is the candidate ordering with the replica prefix removed, in "
                "candidate ordering order, and carries no spread property.",
                "ring-zoned",
                ["REPL-013", "REPL-014", "REPL-015", "REPL-017", "SPREAD-021"], cases)


def build_read_affinity_vectors(out):
    snapshot = SNAPSHOTS["read-affinity"]
    cases = []
    for i in range(6):
        key = ("read-%d" % i).encode()
        for level, path, window, label in [
            ("region", ["eu"], None, "eu"),
            ("region", ["us"], None, "us"),
            ("region", ["ap"], 2, "ap-window-2"),
            ("region", ["antarctica"], None, "absent"),
        ]:
            decision = routing.route(snapshot, key,
                                     affinity={"level": level, "path": path, "window": window})
            cases.append({
                "name": "read-%d/%s" % (i, label),
                "requirements": ["READ-012", "READ-013", "READ-014", "READ-016", "READ-023"],
                "key": key_spec(key),
                "affinity": {"level": level, "path": path, "window": window},
                "expect": {
                    "preferenceList": decision["preferenceList"],
                    "ordered": decision["ordered"],
                    "replicaCount": decision["replicaCount"],
                    "shard": decision["shard"],
                },
            })
    simple_set(out, "vectors/read/affinity.json", "read-affinity", "readAffinity",
               "`routeForRead` partitions the first `window` entries towards a domain path, "
               "stably, and leaves everything at or beyond `window` untouched.",
               ["READ-001", "READ-010", "READ-012", "READ-013", "READ-014", "READ-015",
                "READ-016", "READ-022", "READ-023"], cases,
               topology="topologies/read-affinity.topology.json")


def build_shard_vectors(out):
    """`shards()` and `candidatesForShard`, including the `PLACE-033` agreement."""
    cases = []
    for name, keys in [("ring-plain", [b"user-42", b"k3", b""]),
                       ("slot-explicit", [b"slot-1", b"slot-7"]),
                       ("slot-derived", [b"d1", b"d5"]),
                       ("range-explicit", [b"a", b"zzz"]),
                       ("range-derived", [bytes([0x50])]),
                       ("directory-tenants", [b"gov-high", b"anything-else"])]:
        snapshot = SNAPSHOTS[name]
        enumerated = placement.shards(snapshot)
        entry = {
            "name": name,
            "requirements": ["PLACE-031", "PLACE-033"],
            "topology": "topologies/%s.topology.json" % name,
            "expect": {
                "shardCount": len(enumerated),
                "shards": enumerated if len(enumerated) <= 64 else enumerated[:64],
                "shardsTruncated": len(enumerated) > 64,
                "perKey": [],
            },
        }
        for key in keys:
            routing_key = routing.routing_key_of(snapshot, key)
            shard = placement.shard_of(snapshot, routing_key)
            shard_text = None if shard is placement.NO_SHARD else shard
            direct = placement.candidates(snapshot, routing_key, snapshot.placement_set)
            for_shard = ([] if shard_text is None
                         else placement.candidates_for_shard(snapshot, shard_text,
                                                             snapshot.placement_set))
            entry["expect"]["perKey"].append({
                "key": key_spec(key),
                "shard": shard_text,
                "candidates": direct,
                "candidatesForShard": for_shard,
                "agrees": direct == for_shard,
            })
        cases.append(entry)

    snapshot = SNAPSHOTS["rendezvous-plain"]
    cases.append({
        "name": "rendezvous-enumerates-no-shard",
        "requirements": ["RV-020", "RV-021", "RV-022", "PLACE-032", "MOVE-241"],
        "topology": "topologies/rendezvous-plain.topology.json",
        "note": "`shards()` is empty, so `rendezvous` drives no ownership delta and no handoff.",
        "expect": {
            "shardCount": 0, "shards": [], "shardsTruncated": False,
            "perKey": [{
                "key": key_spec(b"rv-1"),
                "shard": placement.shard_of(snapshot, b"rv-1"),
                "candidates": placement.candidates(snapshot, b"rv-1", snapshot.placement_set),
                "candidatesForShard": placement.candidates_for_shard(
                    snapshot, placement.shard_of(snapshot, b"rv-1"), snapshot.placement_set),
                "agrees": True,
            }],
        },
    })

    simple_set(out, "vectors/placement/shard-enumeration.json", "placement-shard-enumeration",
               "shards",
               "Shard naming, shard enumeration order, and the agreement between `candidates` "
               "and `candidatesForShard` that `PLACE-033` requires.",
               ["PLACE-030", "PLACE-031", "PLACE-032", "PLACE-033", "RING-030", "RING-031",
                "RING-032", "RV-020", "RV-021", "RV-022", "SLOT-030", "SLOT-031", "SLOT-032",
                "RANGE-040", "RANGE-041", "RANGE-042", "DIR-020", "DIR-021", "DIR-022"], cases)


def build_permutation_vectors(out):
    """`PROP-005`: a candidate ordering survives a permuted `nodes` array and a changed epoch."""
    cases = []
    for name in ["ring-plain", "rendezvous-plain", "slot-derived", "range-derived"]:
        base = TOPOLOGY_FILES[name]
        permuted = dict(base)
        permuted["nodes"] = list(reversed(base["nodes"]))
        permuted["epoch"] = base["epoch"] + 1000
        permuted["metadata"] = {"note": "permuted, and at a different epoch"}
        permuted["topologyId"] = base["topologyId"] + "-permuted"
        snapshot_a = SNAPSHOTS[name]
        snapshot_b = Snapshot(permuted)
        rows = []
        for i in range(6):
            key = ("perm-%d" % i).encode()
            a = routing.route(snapshot_a, key)
            b = routing.route(snapshot_b, key)
            assert a["candidates"] == b["candidates"], name
            rows.append({"key": key_spec(key), "candidates": a["candidates"]})
        cases.append({
            "name": name,
            "requirements": ["PROP-005", "RING-013", "RV-013", "CORE-002"],
            "topology": "topologies/%s.topology.json" % name,
            "permutedDocument": permuted,
            "expect": {"identicalCandidates": rows},
        })
    simple_set(out, "vectors/determinism/node-array-permutation.json",
               "determinism-node-array-permutation", "permutation",
               "Reversing the `nodes` array, changing the epoch, changing `topologyId`, and "
               "adding `metadata` leave every candidate ordering unchanged.",
               ["PROP-005", "CORE-002", "RING-013", "RV-013"], cases)


def build_colliding_key_vectors(out, kh_collision):
    """Keys that collide, at each level the specification makes collision observable."""
    cases = []

    # Two keys that transform to one routing key.
    snapshot = SNAPSHOTS["rendezvous-cache"]
    a, b = b"session:{u-9912}:profile", b"session:{u-9912}:cart"
    cases.append({
        "name": "transform-collision",
        "requirements": ["KEY-030", "PLACE-010"],
        "topology": "topologies/rendezvous-cache.topology.json",
        "note": "distinct keys, one routing key, therefore one preference list",
        "keys": [key_spec(a), key_spec(b)],
        "expect": {
            "routingKeys": [routing.routing_key_of(snapshot, a).hex(),
                            routing.routing_key_of(snapshot, b).hex()],
            "candidates": [routing.route(snapshot, a)["candidates"],
                           routing.route(snapshot, b)["candidates"]],
            "identical": True,
        },
    })

    # Two keys in one slot.
    snapshot = SNAPSHOTS["slot-small"]
    buckets = {}
    for i in range(4000):
        key = ("c%d" % i).encode()
        buckets.setdefault(placement.slot_index(snapshot, key), []).append(key)
    slot, pair = next((s, v[:2]) for s, v in sorted(buckets.items()) if len(v) >= 2)
    cases.append({
        "name": "slot-collision",
        "requirements": ["SLOT-001", "SLOT-023"],
        "topology": "topologies/slot-small.topology.json",
        "note": "distinct keys reduced to one slot index, found by enumeration",
        "keys": [key_spec(k) for k in pair],
        "expect": {
            "slotIndex": slot,
            "candidates": [routing.route(snapshot, k)["candidates"] for k in pair],
            "identical": True,
        },
    })

    # Two keys in one ring token range.
    snapshot = SNAPSHOTS["ring-plain"]
    owners = {}
    for i in range(4000):
        key = ("c%d" % i).encode()
        owners.setdefault(placement.ring_shard_of(snapshot, key), []).append(key)
    token, pair = next((t, v[:2]) for t, v in sorted(owners.items()) if len(v) >= 2)
    cases.append({
        "name": "ring-token-range-collision",
        "requirements": ["RING-020", "RING-030"],
        "topology": "topologies/ring-plain.topology.json",
        "note": "distinct keys owned by one token range, found by enumeration",
        "keys": [key_spec(k) for k in pair],
        "expect": {
            "shard": token,
            "candidates": [routing.route(snapshot, k)["candidates"] for k in pair],
            "identical": True,
        },
    })

    if kh_collision:
        left, right = kh_collision
        snapshot = SNAPSHOTS["ring-plain"]
        value = hashing.key_hash(ZERO, left)
        assert value == hashing.key_hash(ZERO, right)
        cases.append({
            "name": "key-hash-collision",
            "requirements": ["SLOT-001", "RING-020", "SEC-001"],
            "topology": "topologies/ring-plain.topology.json",
            "note": "two distinct keys with an identical 64-bit key hash, found by a Pollard rho "
                    "search over the `sharder/key/v1` domain.  Every strategy that consumes "
                    "`keyHash` places them identically.",
            "keys": [key_spec(left), key_spec(right)],
            "expect": {
                "keyHash": hashing.hex_u64(value),
                "shard": placement.ring_shard_of(snapshot, left),
                "candidates": [routing.route(snapshot, k)["candidates"] for k in (left, right)],
                "identical": True,
                "slotIndexAt7": value % 7,
            },
        })

    simple_set(out, "vectors/adversarial/colliding-keys.json", "adversarial-colliding-keys",
               "collidingKeys",
               "Distinct keys that collide at the transform, at the slot index, at the ring "
               "token range, and at the 64-bit key hash itself.",
               ["KEY-030", "SLOT-001", "RING-020", "SEC-001", "PROP-003"], cases)


def movement_keys(label):
    """The sample a movement case draws.

    Under `range` the shard is the covering interval of the raw routing key, so a sample of keys
    that share a leading octet falls in one range and exercises nothing.  That case draws keys
    that span the keyspace instead.
    """
    if label == "range-derived":
        keys = [bytes([i]) for i in range(256)]
        keys += [bytes([i, 0x80]) for i in range(0, 256, 2)]
        return keys[:400]
    return [("mv-%d" % i).encode() for i in range(400)]


def build_movement_vectors(out):
    cases = []
    for label, before_name, after_name, added in [
        ("ring", "movement-ring-before", "movement-ring-after", "m6"),
        ("rendezvous", "movement-rv-before", "movement-rv-after", "m6"),
        ("slot-derived", "movement-slot-before", "movement-slot-after", "m6"),
        ("range-derived", "movement-range-before", "movement-range-after", "m6"),
    ]:
        before, after = SNAPSHOTS[before_name], SNAPSHOTS[after_name]
        sample = movement_keys(label)
        rows, moved, unmoved = [], 0, 0
        for i, key in enumerate(sample):
            a = routing.first_candidate(before, key)
            b = routing.first_candidate(after, key)
            assert b == a or b == added, "PROP-010 violated at %s %s" % (label, key)
            if a != b:
                moved += 1
            else:
                unmoved += 1
            if i < 24:
                rows.append({"key": key_spec(key), "before": a, "after": b,
                             "moved": a != b})
        # PROP-012: the whole ordering is preserved as a subsequence.
        subsequence_rows = []
        for key in sample[:8]:
            before_order = routing.route(before, key)["candidates"]
            after_order = routing.route(after, key)["candidates"]
            stripped = [n for n in after_order if n != added]
            assert stripped == before_order, "PROP-012 violated at %s %s" % (label, key)
            subsequence_rows.append({
                "key": key_spec(key),
                "beforeCandidates": before_order,
                "afterCandidates": after_order,
                "afterWithoutAddedNode": stripped,
            })
        cases.append({
            "name": label,
            "requirements": ["PROP-010", "PROP-011", "PROP-012", "PROP-016"],
            "before": "topologies/%s.topology.json" % before_name,
            "after": "topologies/%s.topology.json" % after_name,
            "addedNode": added,
            "expect": {
                "sampleSize": len(sample),
                "movedCount": moved,
                "unmovedCount": unmoved,
                "firstCandidates": rows,
                "orderingSubsequence": subsequence_rows,
            },
        })
    simple_set(out, "vectors/movement/add-one-node.json", "movement-add-one-node", "movement",
               "Adding one node to a topology of five.  Every key keeps its first candidate or "
               "moves to the added node, and the whole candidate ordering is preserved as a "
               "subsequence.  The counts are golden, so a port reproduces them exactly.",
               ["PROP-010", "PROP-011", "PROP-012", "PROP-016", "PROP-019"], cases)


def build_tie_vectors(out, root, collisions):
    """Vectors for the tie-break paths, built from the collision search results."""
    if not collisions:
        return

    cases = []
    if "ring" in collisions:
        a, b = collisions["ring"]
        token = hashing.ring_token(ZERO, a.encode(), 0)
        assert token == hashing.ring_token(ZERO, b.encode(), 0)
        document = {
            "formatVersion": "1.0",
            "topologyId": "ring-token-collision",
            "epoch": 1,
            "replication": {"factor": 2},
            "strategy": {"kind": "ring", "tokenAssignment": "derived",
                         "tokensPerWeightUnit": 1, "maxTokensPerNode": 1},
            "nodes": [{"id": a}, {"id": b}, {"id": "zz-third"}],
            "metadata": {"note": "the first two identities derive the same token at index 0"},
        }
        write_json(root / "topologies/ring-token-collision.topology.json", document)
        snapshot = Snapshot(document)
        lower = min(a, b, key=lambda s: s.encode())
        entries = placement.ring_entries(snapshot, snapshot.placement_set)
        colliding = [e for e in entries if e[0] == token]
        rows = []
        for i in range(10):
            key = ("tie-%d" % i).encode()
            rows.append({"key": key_spec(key),
                         "candidates": routing.route(snapshot, key)["candidates"]})
        enumerated = placement.shards(snapshot)
        cases.append({
            "name": "ring-token-collision",
            "requirements": ["RING-010", "RING-011", "RING-031", "PLACE-020", "PLACE-021",
                             "PLACE-023"],
            "topology": "topologies/ring-token-collision.topology.json",
            "note": "two node identities deriving one token value, found by a Pollard rho "
                    "search.  `RING-011` puts the lower identity first in ring order, and "
                    "`RING-031` enumerates each distinct token value once, so three ring "
                    "entries name two shards.",
            "expect": {
                "collidingToken": hashing.hex_u64(token),
                "owners": sorted([a, b]),
                "lowerIdentity": lower,
                "ringOrderAtCollision": [{"token": hashing.hex_u64(e[0]), "owner": e[3],
                                          "index": e[2]} for e in colliding],
                "shardEnumeration": {
                    "ringEntryCount": len(placement.ring_entries(snapshot,
                                                                 snapshot.placement_set)),
                    "shardCount": len(enumerated),
                    "shards": enumerated,
                    "collapsedTokens": [hashing.hex_u64(token)],
                },
                "candidates": rows,
            },
        })

    for kind, (tag, builder) in {
        "rendezvous": ("rendezvous", None),
        "slot": ("slot", None),
        "range": ("range", None),
    }.items():
        if kind not in collisions:
            continue
        a, b = collisions[kind]
        if kind == "rendezvous":
            key = b"tie-probe"
            score = hashing.rv_score(ZERO, key, a.encode(), 0)
            assert score == hashing.rv_score(ZERO, key, b.encode(), 0)
            document = {
                "formatVersion": "1.0", "topologyId": "rendezvous-score-tie", "epoch": 1,
                "replication": {"factor": 2},
                "strategy": {"kind": "rendezvous", "virtualNodesPerWeightUnit": 1,
                             "maxVirtualNodesPerNode": 1},
                "nodes": [{"id": a}, {"id": b}, {"id": "zz-third"}],
                "metadata": {"note": "the first two identities score identically for `tie-probe`"},
            }
            path = "topologies/rendezvous-score-tie.topology.json"
            requirements = ["RV-003", "RV-010", "PLACE-020", "PLACE-023"]
            extra = {"routingKey": key.hex(), "equalScore": hashing.hex_u64(score)}
        elif kind == "slot":
            index = 0
            score = hashing.slot_score(ZERO, index, a.encode(), 0)
            assert score == hashing.slot_score(ZERO, index, b.encode(), 0)
            document = {
                "formatVersion": "1.0", "topologyId": "slot-score-tie", "epoch": 1,
                "replication": {"factor": 2},
                "strategy": {"kind": "slot", "slotCount": 64, "assignment": "derived"},
                "nodes": [{"id": a}, {"id": b}, {"id": "zz-third"}],
                "metadata": {"note": "the first two identities score identically for slot 0"},
            }
            path = "topologies/slot-score-tie.topology.json"
            requirements = ["SLOT-021", "SLOT-022", "PLACE-020", "PLACE-023"]
            extra = {"slotIndex": index, "equalScore": hashing.hex_u64(score)}
            key = None
        else:
            shard = "r0"
            score = hashing.range_score(ZERO, shard.encode(), a.encode(), 0)
            assert score == hashing.range_score(ZERO, shard.encode(), b.encode(), 0)
            document = {
                "formatVersion": "1.0", "topologyId": "range-score-tie", "epoch": 1,
                "replication": {"factor": 2},
                "strategy": {"kind": "range", "assignment": "derived",
                             "ranges": [{"shardId": "r0", "start": None, "end": "80"},
                                        {"shardId": "r1", "start": "80", "end": None}]},
                "nodes": [{"id": a}, {"id": b}, {"id": "zz-third"}],
                "metadata": {"note": "the first two identities score identically for shard r0"},
            }
            path = "topologies/range-score-tie.topology.json"
            requirements = ["RANGE-031", "RANGE-032", "PLACE-020", "PLACE-023"]
            extra = {"shardId": shard, "equalScore": hashing.hex_u64(score)}
            key = None

        write_json(root / path, document)
        snapshot = Snapshot(document)
        lower = min(a, b, key=lambda s: s.encode())
        if kind == "rendezvous":
            probe_keys = [key]
        elif kind == "slot":
            probe_keys = [k for k in (("t%d" % i).encode() for i in range(4000))
                          if placement.slot_index(snapshot, k) == 0][:2]
        else:
            probe_keys = [bytes([0x00]), bytes([0x7f])]

        rows = []
        for probe in probe_keys:
            decision = routing.route(snapshot, probe)
            rows.append({"key": key_spec(probe, "base16" if kind == "range" else "utf8"),
                         "candidates": decision["candidates"],
                         "shard": decision["shard"]})
        cases.append({
            "name": "%s-score-tie" % kind,
            "requirements": requirements,
            "topology": path,
            "note": "two node identities with an equal maximum score, found by a Pollard rho "
                    "search.  The comparator falls through to ascending node identity.",
            "expect": dict(extra, tiedNodes=sorted([a, b]), lowerIdentity=lower,
                           candidates=rows),
        })

    # The file-level requirement list names only what the cases present exercise, so a mode
    # whose collision search has not produced a result does not appear as covered.
    simple_set(out, "vectors/determinism/tie-breaks.json", "determinism-tie-breaks", "tieBreak",
               "The tie-break paths a total order needs but a random input almost never reaches. "
               "The file holds %s. Every case was found by the "
               "Pollard rho search in `rho_search.c` and was recomputed through the reference "
               "before it was written."
               % ", ".join(c["name"] for c in cases),
               ["PLACE-016", "PLACE-020", "PLACE-021", "PLACE-023"], cases)


# --------------------------------------------------------------------------- main

def load_collisions(directory: Path):
    """Read the rho search output, and verify every collision through the Python reference."""
    found = {}
    if not directory.is_dir():
        return found, None
    for path in sorted(directory.glob("*.txt")):
        text = path.read_text().strip()
        if not text or "unknown mode" in text or "no collision" in text:
            continue
        rows = dict(line.split()[0:1] + [line.split()[1:]] for line in []) if False else {}
        identities = []
        for line in text.splitlines():
            parts = line.split()
            if parts and parts[0] in ("a", "b"):
                identities.append(parts[1])
        if len(identities) == 2:
            rows = identities
            found[path.stem] = (rows[0], rows[1])

    verified = {}
    for mode, (a, b) in found.items():
        if a == b:
            continue
        left, right = a.encode("ascii"), b.encode("ascii")
        if mode == "ring":
            ok = hashing.ring_token(ZERO, left, 0) == hashing.ring_token(ZERO, right, 0)
        elif mode == "rendezvous":
            ok = (hashing.rv_score(ZERO, b"tie-probe", left, 0)
                  == hashing.rv_score(ZERO, b"tie-probe", right, 0))
        elif mode == "slot":
            ok = (hashing.slot_score(ZERO, 0, left, 0)
                  == hashing.slot_score(ZERO, 0, right, 0))
        elif mode == "range":
            ok = (hashing.range_score(ZERO, b"r0", left, 0)
                  == hashing.range_score(ZERO, b"r0", right, 0))
        elif mode == "keyHash":
            ok = hashing.key_hash(ZERO, left) == hashing.key_hash(ZERO, right)
        else:
            ok = False
        if ok:
            verified[mode] = (a, b)
        else:
            print("  collision for %s did not verify; it is dropped" % mode)

    key_hash_pair = None
    if "keyHash" in verified:
        a, b = verified.pop("keyHash")
        key_hash_pair = (a.encode("ascii"), b.encode("ascii"))
    return verified, key_hash_pair


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(HERE.parent))
    parser.add_argument("--collisions", default=str(HERE / "collisions"))
    args = parser.parse_args()
    root = Path(args.out)

    for name, document in TOPOLOGY_FILES.items():
        write_json(root / "topologies" / ("%s.topology.json" % name), document)
        SNAPSHOTS[name] = Snapshot(document)

    collisions, key_hash_pair = load_collisions(Path(args.collisions))
    print("collisions available: %s%s" % (sorted(collisions),
                                          " plus keyHash" if key_hash_pair else ""))

    build_hash_vectors(root)
    build_key_transform_vectors(root)
    build_digest_vectors(root)
    build_validation_vectors(root, root)
    build_ring_vectors(root)
    build_rendezvous_vectors(root)
    build_slot_vectors(root)
    build_range_vectors(root)
    build_directory_vectors(root)
    build_override_vectors(root)
    build_replication_vectors(root)
    build_spread_vectors(root)
    build_read_affinity_vectors(root)
    build_shard_vectors(root)
    build_permutation_vectors(root)
    build_colliding_key_vectors(root, key_hash_pair)
    build_movement_vectors(root)
    build_tie_vectors(root, root, collisions)

    total_cases = sum(e["caseCount"] for e in MANIFEST_ENTRIES)
    covered = sorted({r for e in MANIFEST_ENTRIES for r in e["requirements"]})
    manifest = {
        "suite": "sharder conformance vectors",
        "specification": "docs/design/10-specification.md",
        "generator": "conformance/generator/generate.py",
        "vectorFileCount": len(MANIFEST_ENTRIES),
        "caseCount": total_cases,
        "requirementCount": len(covered),
        "requirementsCovered": covered,
        "topologies": {
            name: {"file": "topologies/%s.topology.json" % name,
                   "digest": SNAPSHOTS[name].digest,
                   "topologyId": SNAPSHOTS[name].topology_id,
                   "epoch": SNAPSHOTS[name].epoch,
                   "strategy": document["strategy"]["kind"]}
            for name, document in TOPOLOGY_FILES.items()
        },
        "files": sorted(MANIFEST_ENTRIES, key=lambda e: e["file"]),
    }
    write_json(root / "manifest.json", manifest)

    print("wrote %d vector files, %d cases, %d requirement identifiers"
          % (len(MANIFEST_ENTRIES), total_cases, len(covered)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
