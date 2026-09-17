#!/usr/bin/env python3
"""A worked example of the driver contract, and the suite's self-check.

This driver reads `manifest.json`, dispatches on each vector file's `kind`, and compares each
case against the reference implementation.  A port writes the same thing against its own
implementation; the dispatch table below is the whole of the contract that
`docs/design/30-conformance.md` states.

Every conformance level comes from the manifest.  Each vector file and each scenario names the
level it belongs to, and the manifest's `levels` table states what each level requires, so this
driver holds no table of its own and a level added to the suite reaches it as data.

Running it proves two things and not a third.  It proves that every file in the suite parses and
that the contract is implementable as written, and it catches a vector file whose shape drifted
from the contract.  It does not prove the suite correct, because it drives the same reference
that computed the expectations.  Independent verification is a second port.

    python3 run_suite.py [--root <conformance root>] [--level core] [--verbose]
"""

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
GENERATOR = HERE.parent.parent / "generator"
sys.path.insert(0, str(GENERATOR))

from sharder_ref import fencing, formulas, hashing, placement, routing, split  # noqa: E402
from sharder_ref.jcs import canonicalise, digest as jcs_digest                 # noqa: E402
from sharder_ref.sample import SplitMix64, sample_keys                         # noqa: E402
from sharder_ref.siphash import siphash24                                      # noqa: E402
from sharder_ref.topology import Snapshot, accept, validate                    # noqa: E402


class Failure(Exception):
    pass


def decode_key(spec):
    if spec["encoding"] == "utf8":
        return spec["value"].encode("utf-8")
    return bytes.fromhex(spec["value"])


def compare(path, actual, expected):
    if actual != expected:
        raise Failure("%s: expected %r, got %r" % (path, expected, actual))


def compare_subset(prefix, actual, expected):
    """A driver compares only the fields a case carries."""
    for name, value in expected.items():
        if name not in actual:
            raise Failure("%s.%s: the implementation reported no such field" % (prefix, name))
        compare("%s.%s" % (prefix, name), actual[name], value)


def load_topology(root, relative):
    return Snapshot(json.loads((root / relative).read_text()))


# --------------------------------------------------------------------- handlers

def run_siphash(root, payload):
    for case in payload["cases"]:
        actual = siphash24(bytes.fromhex(case["key"]), bytes.fromhex(case["message"]))
        compare(case["name"], hashing.hex_u64(actual), case["expect"])


def run_hash(root, payload):
    functions = {
        "keyHash": lambda seed, f: hashing.key_hash(seed, f[1]),
        "ringToken": lambda seed, f: hashing.ring_token(
            seed, f[1], int.from_bytes(f[2], "big")),
        "rvScore": lambda seed, f: hashing.rv_score(
            seed, f[1], f[2], int.from_bytes(f[3], "big")),
        "slotScore": lambda seed, f: hashing.slot_score(
            seed, int.from_bytes(f[1], "big"), f[2], int.from_bytes(f[3], "big")),
        "rangeScore": lambda seed, f: hashing.range_score(
            seed, f[1], f[2], int.from_bytes(f[3], "big")),
    }
    for case in payload["cases"]:
        fields = [bytes.fromhex(f) for f in case["fields"]]
        seed = bytes.fromhex(case["seed"])
        compare(case["name"] + ".framedMessage",
                hashing.frame(*fields).hex(), case["framedMessage"])
        actual = functions[case["function"]](seed, fields)
        compare(case["name"], hashing.hex_u64(actual), case["expect"])


def run_key_transform(root, payload):
    snapshot = load_topology(root, payload["topology"])
    for case in payload["cases"]:
        actual = routing.routing_key_of(snapshot, decode_key(case["key"]))
        compare(case["name"], actual.hex(), case["expect"]["routingKey"])


def run_digest(root, payload):
    for case in payload["cases"]:
        document = case.get("document")
        if document is None:
            document = json.loads((root / case["topology"]).read_text())
        canonical = canonicalise(document)
        expected = case["expect"]
        compare(case["name"] + ".canonicalLength",
                len(canonical.encode("utf-8")), expected["canonicalLength"])
        compare(case["name"] + ".digest", jcs_digest(document), expected["digest"])
        if "canonicalForm" in expected:
            compare(case["name"] + ".canonicalForm", canonical, expected["canonicalForm"])


def run_validation(root, payload):
    for case in payload["cases"]:
        document = json.loads((root / case["document"]).read_text())
        errors = validate(document)
        expected = case["expect"]
        compare(case["name"] + ".valid", not errors, expected["valid"])
        compare(case["name"] + ".rules", sorted({e["rule"] for e in errors}), expected["rules"])


def run_routing(root, payload):
    snapshot = load_topology(root, payload["topology"])
    compare(payload["vectorSet"] + ".topologyDigest", snapshot.digest, payload["topologyDigest"])
    for case in payload["cases"]:
        key = decode_key(case["key"])
        try:
            decision = routing.route(snapshot, key)
        except routing.NoCandidate as failure:
            if "expectError" not in case:
                raise Failure("%s: unexpected %s" % (case["name"], failure))
            compare_subset(case["name"], {"code": failure.code, "name": failure.name,
                                          "cause": failure.cause}, case["expectError"])
            continue
        if "expectError" in case:
            raise Failure("%s: expected %r, the call succeeded" % (case["name"],
                                                                   case["expectError"]))
        compare_subset(case["name"], decision, case["expect"])


def run_shards(root, payload):
    for case in payload["cases"]:
        snapshot = load_topology(root, case["topology"])
        expected = case["expect"]
        enumerated = placement.shards(snapshot)
        compare(case["name"] + ".shardCount", len(enumerated), expected["shardCount"])
        limit = len(expected["shards"])
        compare(case["name"] + ".shards", enumerated[:limit], expected["shards"])
        for row in expected["perKey"]:
            key = decode_key(row["key"])
            routing_key = routing.routing_key_of(snapshot, key)
            shard = placement.shard_of(snapshot, routing_key)
            shard_text = None if shard is placement.NO_SHARD else shard
            compare(case["name"] + ".shard", shard_text, row["shard"])
            compare(case["name"] + ".candidates",
                    placement.candidates(snapshot, routing_key, snapshot.placement_set),
                    row["candidates"])
            actual = ([] if shard_text is None
                      else placement.candidates_for_shard(snapshot, shard_text,
                                                          snapshot.placement_set))
            compare(case["name"] + ".candidatesForShard", actual, row["candidatesForShard"])


def run_permutation(root, payload):
    for case in payload["cases"]:
        base = load_topology(root, case["topology"])
        permuted = Snapshot(case["permutedDocument"])
        for row in case["expect"]["identicalCandidates"]:
            key = decode_key(row["key"])
            left = routing.route(base, key)["candidates"]
            right = routing.route(permuted, key)["candidates"]
            compare(case["name"] + ".candidates", left, row["candidates"])
            compare(case["name"] + ".permuted", right, row["candidates"])


def run_colliding_keys(root, payload):
    for case in payload["cases"]:
        snapshot = load_topology(root, case["topology"])
        keys = [decode_key(k) for k in case["keys"]]
        orderings = [routing.route(snapshot, k)["candidates"] for k in keys]
        compare(case["name"] + ".candidates", orderings, case["expect"]["candidates"])
        compare(case["name"] + ".identical",
                all(o == orderings[0] for o in orderings), case["expect"]["identical"])
        if "keyHash" in case["expect"]:
            values = {hashing.hex_u64(hashing.key_hash(snapshot.seed, k)) for k in keys}
            compare(case["name"] + ".keyHash", sorted(values), [case["expect"]["keyHash"]])


def run_tie_break(root, payload):
    for case in payload["cases"]:
        snapshot = load_topology(root, case["topology"])
        expected = case["expect"]
        for row in expected["candidates"]:
            key = decode_key(row["key"])
            compare(case["name"] + ".candidates",
                    routing.route(snapshot, key)["candidates"], row["candidates"])
        if "collidingToken" in expected:
            tokens = {hashing.hex_u64(e[0])
                      for e in placement.ring_entries(snapshot, snapshot.placement_set)}
            if expected["collidingToken"] not in tokens:
                raise Failure("%s: the colliding token is absent from the ring" % case["name"])


def run_movement(root, payload):
    for case in payload["cases"]:
        before = load_topology(root, case["before"])
        after = load_topology(root, case["after"])
        expected = case["expect"]
        for row in expected["firstCandidates"]:
            key = decode_key(row["key"])
            compare(case["name"] + ".before", routing.first_candidate(before, key), row["before"])
            compare(case["name"] + ".after", routing.first_candidate(after, key), row["after"])
        for row in expected["orderingSubsequence"]:
            key = decode_key(row["key"])
            stripped = [n for n in routing.route(after, key)["candidates"]
                        if n != case["addedNode"]]
            compare(case["name"] + ".subsequence", stripped, row["afterWithoutAddedNode"])


def run_formula(root, payload):
    table = {
        "failurePercent": lambda i: formulas.failure_percent(i["successes"], i["failures"]),
        "ejectionRefused": lambda i: formulas.ejection_refused(
            i["ejected"], i["maxEjectionPercent"], i["placementSetSize"]),
        "ejectionMillis": lambda i: formulas.ejection_millis(
            i["ejectionCount"], i["baseEjectionMillis"], i["maxEjectionMillis"]),
        "probeAdmitted": lambda i: formulas.probe_admitted(
            i["probeCounterAfterIncrement"], i["probationDivisor"]),
        "peerMedian": lambda i: formulas.peer_median(i["values"]),
        "isOutlier": lambda i: formulas.is_outlier(
            i["failurePercent"], i["peerMedian"], i["outlierMarginPercent"]),
        "retryPermitted": lambda i: formulas.retry_permitted(
            i["retries"], i["firstAttempts"], i["retryBudgetPercent"], i["retryBudgetMinimum"]),
        "defaultAttemptLimit": lambda i: formulas.default_attempt_limit(
            i["factor"], i["attemptSequenceLength"]),
        "resolvedAttemptLimit": lambda i: formulas.resolved_attempt_limit(
            i["routeOptionsAttemptLimit"], i["configuredAttemptLimit"],
            i["factor"], i["attemptSequenceLength"]),
        "budgetAfterSuccess": lambda i: formulas.budget_after_success(
            i["budget"], i["budgetIncrement"], i["maxStepBudget"]),
        "budgetAfterDeferral": lambda i: formulas.budget_after_deferral(
            i["budget"], i["minStepBudget"]),
        "retryBackoffMillis": lambda i: formulas.retry_backoff_millis(
            i["attempt"], i["retryBackoffBaseMillis"], i["retryBackoffCapMillis"]),
        "policyRefused": lambda i: formulas.policy_refused(
            i["minStepBudget"], i["maxStepBudget"], i["catchUpResidualThreshold"],
            i["reTransferResidualThreshold"]),
        "virtualNodeCount": lambda i: formulas.virtual_node_count(
            i["weight"], i["perWeightUnit"], i["cap"]),
        "shardIsHot": lambda i: formulas.shard_is_hot(
            i["shardRequests"], i["shardCount"], i["totalRequests"],
            i["hotShardFactorPercent"]),
        "keySkew": lambda i: formulas.key_skew(
            i["hottestKeyRequests"], i["requests"], i["keySkewPercent"]),
        "compareNodeIdentity": lambda i: (
            "equal" if i["left"].encode("utf-8") == i["right"].encode("utf-8")
            else ("less" if i["left"].encode("utf-8") < i["right"].encode("utf-8")
                  else "greater")),
        "tokenBytes": lambda i: {
            "tokenBytes": formulas.token_bytes(i["topologyId"].encode("utf-8"),
                                               i["epoch"]).hex(),
            "textFields": formulas.token_fields(i["topologyId"], i["epoch"])},
    }
    for case in payload["cases"]:
        if "formula" not in case:
            run_split_lineage_case(root, case)
            continue
        actual = table[case["formula"]](case["inputs"])
        compare(case["name"], actual, case["expect"])


def run_split_lineage_case(root, case):
    if "before" not in case:
        return                                  # the unsupported-strategies case carries no pair
    before = load_topology(root, case["before"])
    after = load_topology(root, case["after"])
    rows = split.classify(before, after)
    compare(case["name"] + ".classifications", rows, case["expect"]["classifications"])
    compare(case["name"] + ".planVerdict", split.plannable(rows), case["expect"]["planVerdict"])


def run_stages(root, payload):
    for case in payload["cases"]:
        snapshot = load_topology(root, case["topology"])
        key = decode_key(case["key"])
        routing_key = routing.routing_key_of(snapshot, key)
        ordering = placement.candidates(snapshot, routing_key, snapshot.placement_set)
        compare(case["name"] + ".candidates", ordering, case["expect"]["candidates"])
        nodes = [snapshot.by_id[n] for n in ordering]
        spread = snapshot.spread
        for expected in case["expect"]["stages"]:
            k = expected["stage"]
            # `SPREAD-010`: stage k enforces the finest m-k levels, which are spread[k:].
            selected = routing.select_stage(snapshot, nodes, snapshot.factor, spread[k:])
            compare("%s.stage%d" % (case["name"], k),
                    [n.id for n in selected], expected["selected"])
        decision = routing.route(snapshot, key)
        compare(case["name"] + ".chosenStage", decision["spreadStage"],
                case["expect"]["chosenStage"])
        compare(case["name"] + ".relaxedLevels", decision["relaxedLevels"],
                case["expect"]["relaxedLevels"])


def run_error_taxonomy(root, payload):
    """A port checks its own condition enum against the closed set; this driver checks shape."""
    for case in payload["cases"]:
        expected = case["expect"]
        if "conditions" in expected:
            compare("closed-set.count", len(expected["conditions"]),
                    expected["conditionCount"])
            compare("closed-set.codes", sorted(c["code"] for c in expected["conditions"]),
                    expected["codes"])
            compare("closed-set.names", sorted(c["name"] for c in expected["conditions"]),
                    expected["names"])


def run_defaults(root, payload):
    for case in payload["cases"]:
        left = load_topology(root, case["omittedDocument"])
        right = load_topology(root, case["explicitDocument"])
        for row in case["expect"]["rows"]:
            key = decode_key(row["key"])
            a = routing.route(left, key)["candidates"]
            b = routing.route(right, key)["candidates"]
            compare(case["name"] + ".omitted", a, row["candidates"])
            compare(case["name"] + ".explicit", b, row["candidates"])


def run_ownership_delta(root, payload):
    from sharder_ref.handoff import ownership_delta
    for case in payload["cases"]:
        if "delta" not in case["expect"] or not case["expect"].get("delta"):
            continue                       # the incomparable and empty cases carry no rows
        before = load_topology(root, case["before"])
        after = load_topology(root, case["after"])
        rows = ownership_delta(before, after, lambda s: s.factor)
        compare(case["name"] + ".delta", rows, case["expect"]["delta"])


def run_pin_shard(root, payload):
    for case in payload["cases"]:
        snapshot = load_topology(root, "topologies/ring-pinned.topology.json")
        key = decode_key(case["key"])
        decision = routing.route(snapshot, key)
        routing_key = routing.routing_key_of(snapshot, key)
        shard = placement.shard_of(snapshot, routing_key)
        compare(case["name"] + ".shard", shard, case["expect"]["shard"])
        compare(case["name"] + ".candidates", decision["candidates"],
                case["expect"]["candidates"])
        compare(case["name"] + ".candidatesForShard",
                placement.candidates_for_shard(snapshot, shard, snapshot.placement_set),
                case["expect"]["candidatesForShard"])


def run_read_affinity(root, payload):
    snapshot = load_topology(root, payload["topology"])
    for case in payload["cases"]:
        decision = routing.route(snapshot, decode_key(case["key"]),
                                 affinity=case["affinity"])
        compare_subset(case["name"], decision, case["expect"])


def run_property_witness(root, payload):
    for case in payload["cases"]:
        if case["name"] != "sample-generator":
            continue                            # the balance witnesses are the slow layer
        rng = SplitMix64(int(case["expect"]["seed"], 16))
        draws = ["%016x" % rng.next_u64() for _ in range(len(case["expect"]["firstDraws"]))]
        compare("sample-generator.firstDraws", draws, case["expect"]["firstDraws"])
        keys = [k.hex() for k in sample_keys(int(case["expect"]["seed"], 16),
                                             len(case["expect"]["firstKeys"]))]
        compare("sample-generator.firstKeys", keys, case["expect"]["firstKeys"])


HANDLERS = {
    "siphash": run_siphash,
    "hash": run_hash,
    "keyTransform": run_key_transform,
    "digest": run_digest,
    "validation": run_validation,
    "routing": run_routing,
    "shards": run_shards,
    "permutation": run_permutation,
    "collidingKeys": run_colliding_keys,
    "tieBreak": run_tie_break,
    "movement": run_movement,
    "formula": run_formula,
    "stages": run_stages,
    "errorTaxonomy": run_error_taxonomy,
    "defaults": run_defaults,
    "ownershipDelta": run_ownership_delta,
    "pinShard": run_pin_shard,
    "readAffinity": run_read_affinity,
    "propertyWitness": run_property_witness,
}


def declared_levels(manifest, level):
    """The levels a port declaring `level` runs: the level itself and what it requires.

    The manifest states the level structure, so the closure below is the whole of what this
    driver knows about levels.  Naming none runs every level the manifest lists.
    """
    requires = {row["level"]: row["requires"] for row in manifest["levels"]}
    if level is None:
        return set(requires)
    if level not in requires:
        raise SystemExit("unknown conformance level %r; the manifest lists %s"
                         % (level, ", ".join(requires)))
    closure = set()
    pending = [level]
    while pending:
        name = pending.pop()
        if name in closure:
            continue
        closure.add(name)
        pending.extend(requires[name])
    return closure


def run_scenarios(root, levels, known, verbose):
    """Replay the scenario actions this driver implements."""
    index = json.loads((root / "scenarios/index.json").read_text())
    checked = skipped = 0
    failures = []
    for entry in index["scenarios"]:
        if entry["level"] not in known:
            failures.append("%s: unknown conformance level %r" % (entry["file"], entry["level"]))
            continue
        if entry["level"] not in levels:
            continue
        scenario = json.loads((root / entry["file"]).read_text())
        retained = {}
        in_force = None
        for step in scenario["steps"]:
            action = step["action"]
            try:
                if action == "installTopology":
                    document = step.get("document")
                    if document is None:
                        document = json.loads((root / step["topology"]).read_text())
                    if "digest" in step["expect"]:
                        compare(action + ".digest", jcs_digest(document),
                                step["expect"]["digest"])
                    candidate = Snapshot(document)
                    outcome, condition = accept(candidate, in_force)
                    compare(action + ".outcome", outcome, step["expect"]["outcome"])
                    compare(action + ".condition", condition, step["expect"]["condition"])
                    if outcome == "installed":
                        if in_force is not None:
                            retained[in_force.epoch] = in_force
                        in_force = candidate
                    compare(action + ".epochInForce", in_force.epoch,
                            step["expect"]["epochInForce"])
                    checked += 1
                elif action == "recipientCheck":
                    snapshot = None if step.get("noSnapshot") else in_force
                    if snapshot is None and not step.get("noSnapshot"):
                        skipped += 1
                        continue
                    token = step["token"] or {"topologyId": snapshot.topology_id,
                                              "epoch": snapshot.epoch}
                    held = {} if snapshot is None else (
                        retained if step.get("retainedEpochs") != [] else {})
                    verdict = fencing.check(token, decode_key(step["key"]), step["selfId"],
                                            snapshot, held)
                    compare(action + ".verdict", verdict, step["expect"]["verdict"])
                    checked += 1
                elif action == "redirectWalk":
                    result = fencing.redirect_walk(step["start"], step["refusals"],
                                                   step["maxRedirects"], step.get("nodes"),
                                                   step.get("budget"))
                    compare(action + "." + step["name"], result, step["expect"])
                    checked += 1
                else:
                    skipped += 1
            except Failure as failure:
                failures.append("%s: %s" % (entry["scenario"], failure))
    if verbose:
        print("  scenarios: %d steps checked, %d steps not implemented by this driver"
              % (checked, skipped))
    return failures


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=str(HERE.parent.parent))
    parser.add_argument("--level", default=None,
                        help="run the vector files and scenarios a port declaring this "
                             "conformance level runs, which is the level together with the "
                             "levels it requires")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    root = Path(args.root)
    manifest = json.loads((root / "manifest.json").read_text())
    known = {row["level"] for row in manifest["levels"]}
    levels = declared_levels(manifest, args.level)

    failures = []
    files = cases = 0
    per_level = {name: [0, 0] for name in sorted(known)}
    for entry in manifest["vectorFiles"]:
        kind = entry["kind"]
        level = entry["level"]
        if level not in known:
            failures.append("%s: unknown conformance level %r" % (entry["file"], level))
            continue
        if kind not in HANDLERS:
            failures.append("%s: unknown kind %r" % (entry["file"], kind))
            continue
        if level not in levels:
            continue
        payload = json.loads((root / entry["file"]).read_text())
        try:
            HANDLERS[kind](root, payload)
            files += 1
            cases += entry["caseCount"]
            per_level[level][0] += 1
            per_level[level][1] += entry["caseCount"]
            if args.verbose:
                print("  ok  %-56s %-13s %3d cases"
                      % (entry["file"], level, entry["caseCount"]))
        except Failure as failure:
            failures.append("%s: %s" % (entry["file"], failure))

    failures.extend(run_scenarios(root, levels, known, args.verbose))

    for row in manifest["levels"]:
        name = row["level"]
        if name not in levels:
            print("  %-13s not run" % name)
            continue
        print("  %-13s %2d of %2d vector files, %3d of %3d cases"
              % (name, per_level[name][0], row["vectorFiles"],
                 per_level[name][1], row["vectorCases"]))
    print("%d vector files, %d cases" % (files, cases))
    if failures:
        print("%d failures:" % len(failures))
        for failure in failures:
            print("  " + failure)
        return 1
    print("no failures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
