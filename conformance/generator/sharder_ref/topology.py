"""Topology document handling: defaults, semantic validation, and the snapshot.

Validation implements the rules of `20-topology-format.md` and the load-time rules the
specification states under `TOPO-*`, `OVR-015`, and `OVR-028`.  It does not implement JSON Schema
validation; the schema file is authoritative for that and the suite checks documents against it
separately.
"""

import itertools

from .jcs import digest as jcs_digest

SUPPORTED_FORMAT_MAJOR = 1
SUPPORTED_FORMAT_MINOR = 0

PLACEMENT_STATES = ("active", "draining")

STRATEGY_DEFAULTS = {
    "ring": {"tokenAssignment": "derived", "tokensPerWeightUnit": 4, "maxTokensPerNode": 4096},
    "rendezvous": {"virtualNodesPerWeightUnit": 1, "maxVirtualNodesPerNode": 1024},
    "slot": {"assignment": "explicit"},
    "directory": {},
}


_SERIAL = itertools.count()


class ValidationError(Exception):
    def __init__(self, errors):
        self.errors = errors
        super().__init__("; ".join("%s: %s" % (e["path"], e["rule"]) for e in errors))


def _err(path, rule, detail=""):
    return {"path": path, "rule": rule, "detail": detail}


def decode_matcher_value(matcher):
    encoding = matcher.get("encoding", "utf8")
    if encoding == "utf8":
        return matcher["value"].encode("utf-8")
    return bytes.fromhex(matcher["value"])


def parse_slot_range(text):
    if "-" in text:
        low, high = text.split("-", 1)
        return int(low), int(high)
    value = int(text)
    return value, value


class Node:
    def __init__(self, raw, domain_levels):
        self.id = raw["id"]
        self.id_bytes = self.id.encode("utf-8")
        self.state = raw.get("state", "active")
        self.weight = raw.get("weight", 1)
        self.domains = raw.get("domains", {})
        self.address = raw.get("address")
        self.tags = raw.get("tags", {})
        self.tokens = [int(t, 16) for t in raw.get("tokens", [])]
        self.raw_tokens = raw.get("tokens", [])
        self.domain_levels = domain_levels

    def domain_path(self, level):
        """The tuple of identifiers from the coarsest declared level through `level`."""
        cut = self.domain_levels.index(level) + 1
        return tuple(self.domains.get(name, "").encode("utf-8")
                     for name in self.domain_levels[:cut])

    def virtual_node_count(self, per_weight_unit, cap):
        return min(self.weight * per_weight_unit, cap)


class Snapshot:
    """The immutable, validated, prepared form of one topology document.

    `Snapshot(document)` performs stages 2 through 6 of `TOPO-001`: it validates the document,
    refuses an invalid one, and prepares placement over the rest.  `Snapshot.prepared(document)`
    performs stage 6 alone, over a document whose validity the caller already established.  The
    digest is computed on first use rather than at construction, so a caller that prepares
    placement and reads no digest runs without the canonical form of `TOPO-020` and without
    SHA-256.  That is the `place` conformance level of `30-conformance.md`.
    """

    def __init__(self, document, validated=False):
        if not validated:
            errors = validate(document)
            if errors:
                raise ValidationError(errors)
        self.document = document
        self._digest = None
        self.cache_key = next(_SERIAL)
        self.topology_id = document["topologyId"]
        self.epoch = document["epoch"]
        self.hash_config = dict({"algorithm": "siphash-2-4",
                                 "seed": "00000000000000000000000000000000"},
                                **document.get("hash", {}))
        self.seed = bytes.fromhex(self.hash_config["seed"])
        self.seed_is_default = self.seed == bytes(16)
        self.key_transform = document.get("keyTransform", {"kind": "none"})
        self.domain_levels = document.get("domainLevels", [])
        replication = document.get("replication", {})
        self.factor = replication.get("factor", 1)
        self.spread = replication.get("spread", [])
        self.spread_policy = replication.get("spreadPolicy", "relaxed")
        self.strategy = dict(STRATEGY_DEFAULTS[document["strategy"]["kind"]],
                             **document["strategy"])
        self.overrides = document.get("overrides", [])
        self.nodes = [Node(raw, self.domain_levels) for raw in document["nodes"]]
        self.by_id = {node.id: node for node in self.nodes}
        self.placement_set = [n for n in self.nodes if n.state in PLACEMENT_STATES]

    @classmethod
    def prepared(cls, document):
        """Placement over a document already known to be valid, without stages 1 through 5."""
        return cls(document, validated=True)

    @property
    def digest(self):
        """`TOPO-021`: the SHA-256 of the canonical form, computed on first use."""
        if self._digest is None:
            self._digest = jcs_digest(self.document)
        return self._digest

    @property
    def token(self):
        return {"topologyId": self.topology_id, "epoch": self.epoch}


#: The members every document carries, which `topology-v1.schema.json` also requires.  A rule
#: below reads each of them, so a document missing one is reported as missing rather than read.
REQUIRED_MEMBERS = ("formatVersion", "topologyId", "epoch", "strategy", "nodes")


def validate(document):
    """Return every validation error the document produces, or an empty list."""
    errors = []

    missing = [name for name in REQUIRED_MEMBERS if name not in document]
    if missing:
        # Stage 2 of `TOPO-001` refuses the document whole, and the rules below read the members
        # it is missing, so the absence is the whole answer rather than the first of several.
        return [_err(name, "missingMember", "the member is absent") for name in missing]

    version = document.get("formatVersion")
    if not isinstance(version, str) or "." not in version:
        errors.append(_err("formatVersion", "malformed", repr(version)))
    else:
        major, minor = version.split(".", 1)
        if not major.isdigit() or not minor.isdigit():
            errors.append(_err("formatVersion", "malformed", version))
        elif int(major) != SUPPORTED_FORMAT_MAJOR or int(minor) > SUPPORTED_FORMAT_MINOR:
            errors.append(_err("formatVersion", "unsupportedVersion", version))

    topology_id = document.get("topologyId")
    if not isinstance(topology_id, str) or not 1 <= len(topology_id.encode("utf-8")) <= 128:
        errors.append(_err("topologyId", "lengthOutOfRange", repr(topology_id)))

    epoch = document.get("epoch")
    if not isinstance(epoch, int) or isinstance(epoch, bool) or not 0 <= epoch <= 9007199254740991:
        errors.append(_err("epoch", "outOfRange", repr(epoch)))

    hash_config = document.get("hash", {})
    if hash_config.get("algorithm", "siphash-2-4") != "siphash-2-4":
        errors.append(_err("hash.algorithm", "unsupportedAlgorithm",
                           hash_config.get("algorithm")))
    seed = hash_config.get("seed", "0" * 32)
    if len(seed) != 32 or any(c not in "0123456789abcdef" for c in seed):
        errors.append(_err("hash.seed", "malformedSeed", seed))

    levels = document.get("domainLevels", [])
    if len(levels) > 8:
        errors.append(_err("domainLevels", "tooManyLevels", str(len(levels))))
    if len(set(levels)) != len(levels):
        errors.append(_err("domainLevels", "duplicateLevel", ""))

    replication = document.get("replication", {})
    spread = replication.get("spread", [])
    if len(set(spread)) != len(spread):
        errors.append(_err("replication.spread", "duplicateLevel", ""))
    for name in spread:
        if name not in levels:
            errors.append(_err("replication.spread", "undeclaredLevel", name))
    declared_order = [levels.index(n) for n in spread if n in levels]
    if declared_order != sorted(declared_order):
        errors.append(_err("replication.spread", "levelOrder", ",".join(spread)))
    if replication.get("factor", 1) < 1:
        errors.append(_err("replication.factor", "factorBelowOne", ""))

    nodes = document.get("nodes", [])
    seen_ids = set()
    for index, raw in enumerate(nodes):
        path = "nodes[%d]" % index
        node_id = raw.get("id")
        if node_id in seen_ids:
            errors.append(_err(path + ".id", "duplicateNodeId", node_id))
        seen_ids.add(node_id)
        domains = raw.get("domains", {})
        for name in levels:
            if name not in domains:
                errors.append(_err(path + ".domains", "missingLevel", name))
        for name in domains:
            if name not in levels:
                errors.append(_err(path + ".domains", "undeclaredLevel", name))

    errors.extend(_validate_strategy(document, nodes, seen_ids))
    errors.extend(_validate_overrides(document, levels, seen_ids))
    return errors


def _referenced(errors, path, names, known):
    for name in names:
        if name not in known:
            errors.append(_err(path, "unknownNodeId", name))


def _validate_matcher_table(errors, path, entries):
    seen = set()
    for index, entry in enumerate(entries):
        matcher = entry["match"]
        if matcher.get("encoding", "utf8") == "base16":
            value = matcher["value"]
            if len(value) % 2 or any(c not in "0123456789abcdef" for c in value):
                errors.append(_err("%s[%d].match.value" % (path, index), "malformedBase16", value))
                continue
        identity = (matcher["kind"], decode_matcher_value(matcher))
        if identity in seen:
            errors.append(_err("%s[%d].match" % (path, index), "duplicateMatcher",
                               matcher["value"]))
        seen.add(identity)


def _validate_strategy(document, nodes, known_ids):
    errors = []
    strategy = document.get("strategy", {})
    kind = strategy.get("kind")
    placement_ids = {n.get("id") for n in nodes if n.get("state", "active") in PLACEMENT_STATES}

    if kind == "ring":
        assignment = strategy.get("tokenAssignment", "derived")
        if assignment == "explicit":
            for field in ("tokensPerWeightUnit", "maxTokensPerNode"):
                if field in strategy:
                    errors.append(_err("strategy." + field, "sizingUnderExplicit", ""))
            seen_tokens = {}
            for index, raw in enumerate(nodes):
                tokens = raw.get("tokens", [])
                if raw.get("id") in placement_ids and raw.get("weight", 1) != 0 and not tokens:
                    errors.append(_err("nodes[%d].tokens" % index, "missingTokens", raw.get("id")))
                for token in tokens:
                    if token in seen_tokens:
                        errors.append(_err("nodes[%d].tokens" % index, "duplicateToken", token))
                    seen_tokens[token] = raw.get("id")
        else:
            for index, raw in enumerate(nodes):
                if "tokens" in raw:
                    errors.append(_err("nodes[%d].tokens" % index, "tokensUnderDerived",
                                       raw.get("id")))

    elif kind == "slot":
        slot_count = strategy.get("slotCount")
        covered = {}
        for e_index, entry in enumerate(strategy.get("assignments", [])):
            path = "strategy.assignments[%d]" % e_index
            _referenced(errors, path + ".nodes", entry.get("nodes", []), known_ids)
            for text in entry.get("slots", []):
                low, high = parse_slot_range(text)
                if low > high:
                    errors.append(_err(path + ".slots", "slotRangeInverted", text))
                    continue
                if high >= slot_count:
                    errors.append(_err(path + ".slots", "slotAboveCount", text))
                    continue
                for slot in range(low, high + 1):
                    if slot in covered:
                        errors.append(_err(path + ".slots", "slotCoveredTwice", str(slot)))
                    covered[slot] = e_index
        missing = [s for s in range(slot_count) if s not in covered]
        if missing:
            errors.append(_err("strategy.assignments", "slotNotCovered",
                               "%d slots, first %d" % (len(missing), missing[0])))

    elif kind == "directory":
        entries = strategy.get("entries", [])
        _validate_matcher_table(errors, "strategy.entries", entries)
        for index, entry in enumerate(entries):
            _referenced(errors, "strategy.entries[%d].nodes" % index,
                        entry.get("nodes", []), known_ids)

    return errors


def _validate_overrides(document, levels, known_ids):
    errors = []
    overrides = document.get("overrides", [])
    _validate_matcher_table(errors, "overrides", overrides)
    for index, entry in enumerate(overrides):
        path = "overrides[%d]" % index
        _referenced(errors, path + ".pin", entry.get("pin", []), known_ids)
        constrain = entry.get("constrain")
        if constrain:
            for name in constrain.get("domains", {}):
                if name not in levels:
                    errors.append(_err(path + ".constrain.domains", "undeclaredLevel", name))
            _referenced(errors, path + ".constrain.nodes", constrain.get("nodes", []), known_ids)
        if "factor" in entry and entry["factor"] < 1:
            errors.append(_err(path + ".factor", "factorBelowOne", ""))
    return errors


def accept(candidate, in_force, min_epoch=None, expected_topology_id=None):
    """`TOPO-061`: the acceptance outcome, which is exactly one of five values.

    Returns `(outcome, condition)` where `outcome` is `installed`, `noop`, or `rejected`.  The
    comparison reads the epoch as an integer and the topology identifier as octets, and reads no
    clock, no provider revision, and no floating point value, under `TOPO-051`.
    """
    identity = expected_topology_id
    if identity is None and in_force is not None:
        identity = in_force.topology_id

    if identity is not None and candidate.topology_id != identity:
        return "rejected", {"code": 202, "name": "topologyConflict",
                            "detail": "a differing topologyId"}
    if min_epoch is not None and candidate.epoch < min_epoch:
        return "rejected", {"code": 203, "name": "staleDocument",
                            "detail": "an epoch below minEpoch"}
    if in_force is None:
        return "installed", None
    if candidate.epoch < in_force.epoch:
        return "rejected", {"code": 203, "name": "staleDocument",
                            "detail": "an epoch below the epoch in force"}
    if candidate.epoch == in_force.epoch:
        if candidate.digest == in_force.digest:
            return "noop", None
        return "rejected", {"code": 202, "name": "topologyConflict",
                            "detail": "an equal epoch whose digest differs"}
    return "installed", None
