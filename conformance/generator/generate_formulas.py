#!/usr/bin/env python3
"""Generate vectors for the integer formulas and for range split lineage.

The specification states a number of its rules as closed-form integer arithmetic: a truncating
division, a strict comparison, a shift with a clamp.  A routing vector does not reach any of
them, and a port that rounds where the specification truncates passes every placement vector and
still ejects the wrong node.  Each case here is one evaluation of one formula.

Range split lineage is derived from range bounds under `SPLIT-061` to `SPLIT-161`, which is a
pure function of two documents, so it is a golden vector rather than a scenario.

    python3 generate_formulas.py [--out <conformance root>]
"""

import argparse
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from generate import write_json                                   # noqa: E402
from sharder_ref import formulas, split                           # noqa: E402
from sharder_ref.topology import Snapshot                         # noqa: E402

ENTRIES = []


def emit(root, path, vector_set, description, requirements, cases):
    write_json(root / path, {
        "vectorSet": vector_set,
        "kind": "formula",
        "description": description,
        "requirements": sorted(set(requirements)),
        "cases": cases,
    })
    ENTRIES.append({"file": path, "vectorSet": vector_set, "kind": "formula",
                    "description": description, "topology": None, "caseCount": len(cases),
                    "requirements": sorted({r for c in cases for r in c["requirements"]}
                                           | set(requirements))})


def build_health_formulas(root):
    cases = []
    for successes, failures in [(0, 0), (1, 0), (0, 1), (2, 1), (1, 2), (99, 1), (1, 99),
                                (3, 4), (7, 3), (1, 1)]:
        cases.append({
            "name": "failurePercent/%d-%d" % (successes, failures),
            "requirements": ["HEALTH-021", "HEALTH-023"],
            "formula": "failurePercent",
            "inputs": {"successes": successes, "failures": failures},
            "note": "integer division truncating towards zero; 1 of 3 is 33, not 34",
            "expect": formulas.failure_percent(successes, failures),
        })

    for ejected, percent, size in [(0, 50, 3), (1, 50, 3), (1, 50, 4), (2, 50, 4), (1, 50, 6),
                                   (2, 50, 6), (3, 50, 6), (0, 0, 10), (5, 100, 10)]:
        cases.append({
            "name": "ejectionRefused/%d-%d-%d" % (ejected, percent, size),
            "requirements": ["CORE-005", "HEALTH-034"],
            "formula": "ejectionRefused",
            "inputs": {"ejected": ejected, "maxEjectionPercent": percent,
                       "placementSetSize": size},
            "note": "`(ejected + 1) * 100 > maxEjectionPercent * placementSetSize`, a strict "
                    "comparison, so exactly half the set may be ejected",
            "expect": formulas.ejection_refused(ejected, percent, size),
        })

    for count in [1, 2, 3, 4, 10, 11, 12, 20, 64]:
        cases.append({
            "name": "ejectionMillis/%d" % count,
            "requirements": ["HEALTH-050"],
            "formula": "ejectionMillis",
            "inputs": {"ejectionCount": count, "baseEjectionMillis": 30000,
                       "maxEjectionMillis": 300000},
            "note": "the shift is clamped at ten before it is applied, so it never overflows",
            "expect": formulas.ejection_millis(count, 30000, 300000),
        })

    for counter in [1, 2, 15, 16, 17, 32, 33]:
        cases.append({
            "name": "probeAdmitted/%d" % counter,
            "requirements": ["HEALTH-051"],
            "formula": "probeAdmitted",
            "inputs": {"probeCounterAfterIncrement": counter, "probationDivisor": 16},
            "note": "admitted when the incremented value modulo the divisor is 1, so the first "
                    "call after entering probation is admitted",
            "expect": formulas.probe_admitted(counter, 16),
        })

    for values in [[0, 10, 20], [0, 10, 20, 30], [5], [1, 2], [50, 50, 50, 50, 50, 50]]:
        cases.append({
            "name": "peerMedian/%s" % "-".join(str(v) for v in values),
            "requirements": ["HEALTH-031"],
            "formula": "peerMedian",
            "inputs": {"values": values},
            "note": "the lower of the two central values where the count is even",
            "expect": formulas.peer_median(values),
        })

    for value, median, margin in [(30, 0, 30), (29, 0, 30), (60, 30, 30), (59, 30, 30)]:
        cases.append({
            "name": "isOutlier/%d-%d-%d" % (value, median, margin),
            "requirements": ["HEALTH-032"],
            "formula": "isOutlier",
            "inputs": {"failurePercent": value, "peerMedian": median,
                       "outlierMarginPercent": margin},
            "expect": formulas.is_outlier(value, median, margin),
        })

    emit(root, "vectors/formulas/health.json", "formulas-health",
         "The closed-form integer arithmetic of the health state machine.",
         ["CORE-005", "HEALTH-021", "HEALTH-023", "HEALTH-031", "HEALTH-032", "HEALTH-034",
          "HEALTH-050",
          "HEALTH-051", "HEALTH-055"], cases)


def build_failover_formulas(root):
    cases = []
    for retries, first, percent, minimum in [
        (0, 0, 20, 3), (3, 0, 20, 3), (4, 0, 20, 3), (5, 10, 20, 3), (6, 10, 20, 3),
        (23, 100, 20, 3), (24, 100, 20, 3), (0, 1000, 0, 0), (1, 1000, 0, 0),
    ]:
        cases.append({
            "name": "retryPermitted/%d-%d-%d-%d" % (retries, first, percent, minimum),
            "requirements": ["CORE-005", "FAIL-030", "FAIL-031", "FAIL-032", "FAIL-035"],
            "formula": "retryPermitted",
            "inputs": {"retries": retries, "firstAttempts": first,
                       "retryBudgetPercent": percent, "retryBudgetMinimum": minimum},
            "note": "`retries * 100 <= retryBudgetPercent * firstAttempts + 100 * minimum`",
            "expect": formulas.retry_permitted(retries, first, percent, minimum),
        })

    for factor, length in [(1, 10), (3, 10), (3, 4), (1, 2), (5, 5), (1, 1)]:
        cases.append({
            "name": "defaultAttemptLimit/%d-%d" % (factor, length),
            "requirements": ["FAIL-021", "FAIL-022"],
            "formula": "defaultAttemptLimit",
            "inputs": {"factor": factor, "attemptSequenceLength": length},
            "note": "`n + 2`, clamped to the length of the attempt sequence",
            "expect": formulas.default_attempt_limit(factor, length),
        })

    emit(root, "vectors/formulas/failover.json", "formulas-failover",
         "The retry budget and the default attempt limit.",
         ["CORE-005", "FAIL-021", "FAIL-022", "FAIL-030", "FAIL-031", "FAIL-032", "FAIL-033",
          "FAIL-034", "FAIL-035", "CFG-020", "CFG-021"], cases)


def build_rate_formulas(root):
    cases = []
    for budget, increment, maximum in [(1, 1, 64), (63, 1, 64), (64, 1, 64), (60, 8, 64)]:
        cases.append({
            "name": "budgetAfterSuccess/%d-%d-%d" % (budget, increment, maximum),
            "requirements": ["RATE-041"],
            "formula": "budgetAfterSuccess",
            "inputs": {"budget": budget, "budgetIncrement": increment,
                       "maxStepBudget": maximum},
            "expect": formulas.budget_after_success(budget, increment, maximum),
        })
    for budget, minimum in [(64, 1), (3, 1), (2, 1), (1, 1), (9, 4)]:
        cases.append({
            "name": "budgetAfterDeferral/%d-%d" % (budget, minimum),
            "requirements": ["RATE-041", "RATE-101"],
            "formula": "budgetAfterDeferral",
            "inputs": {"budget": budget, "minStepBudget": minimum},
            "note": "unsigned integer division, so 3 halves to 1 rather than to 2",
            "expect": formulas.budget_after_deferral(budget, minimum),
        })
    for attempt in [1, 2, 3, 6, 7, 8, 20]:
        cases.append({
            "name": "retryBackoffMillis/%d" % attempt,
            "requirements": ["RATE-051", "MOVE-171"],
            "formula": "retryBackoffMillis",
            "inputs": {"attempt": attempt, "retryBackoffBaseMillis": 1000,
                       "retryBackoffCapMillis": 60000},
            "expect": formulas.retry_backoff_millis(attempt, 1000, 60000),
        })
    for policy in [(1, 64, 0, 18446744073709551615), (0, 64, 0, 10), (8, 4, 0, 10),
                   (1, 64, 10, 10), (1, 64, 10, 5)]:
        cases.append({
            "name": "policyRefused/%s" % "-".join(str(v) for v in policy[:3]),
            "requirements": ["RATE-021", "ERR-050", "CFG-050", "CFG-052"],
            "formula": "policyRefused",
            "inputs": {"minStepBudget": policy[0], "maxStepBudget": policy[1],
                       "catchUpResidualThreshold": policy[2],
                       "reTransferResidualThreshold": policy[3]},
            "expect": formulas.policy_refused(*policy),
        })
    for weight, per_unit, cap in [(1, 4, 4096), (0, 4, 4096), (1024, 4, 4096),
                                  (1025, 4, 4096), (1000000, 4096, 65536), (1, 1, 1024)]:
        cases.append({
            "name": "virtualNodeCount/%d-%d-%d" % (weight, per_unit, cap),
            "requirements": ["PLACE-050", "PLACE-051", "PLACE-052"],
            "formula": "virtualNodeCount",
            "inputs": {"weight": weight, "perWeightUnit": per_unit, "cap": cap},
            "note": "the product reaches 4096000000 at the configured maxima, which overflows a "
                    "signed 32-bit integer, so it is computed in at least 64 bits",
            "expect": formulas.virtual_node_count(weight, per_unit, cap),
        })
    emit(root, "vectors/formulas/migration-rate.json", "formulas-migration-rate",
         "Step budget adjustment, retry backoff, policy validation, and the virtual node count.",
         ["RATE-021", "RATE-041", "RATE-051", "RATE-101", "PLACE-050", "PLACE-051",
          "PLACE-052", "MOVE-171"], cases)


def build_detection_formulas(root):
    cases = []
    for shard_requests, shard_count, total, percent in [
        (100, 10, 1000, 400), (400, 10, 1000, 400), (399, 10, 1000, 400),
        (1, 1, 1, 400), (0, 16384, 1000000, 400),
        # `CORE-005`: both products exceed 64 bits at the declared u64 range of `SPLIT-021`.
        (2 ** 50, 1048576, 2 ** 63, 400), (2 ** 40, 1048576, 2 ** 63, 400),
        (2 ** 63 - 1, 1048576, 2 ** 64 - 1, 400),
    ]:
        cases.append({
            "name": "shardIsHot/%d-%d-%d" % (shard_requests, shard_count, total),
            "requirements": ["CORE-005", "OBS-031", "OBS-033"],
            "formula": "shardIsHot",
            "inputs": {"shardRequests": shard_requests, "shardCount": shard_count,
                       "totalRequests": total, "hotShardFactorPercent": percent},
            "expect": formulas.shard_is_hot(shard_requests, shard_count, total, percent),
        })
    for hottest, requests, percent in [(50, 100, 50), (49, 100, 50), (1, 1, 50), (0, 0, 50),
                                       # `CORE-005`: `hottest * 100` exceeds 64 bits here.
                                       (2 ** 63, 2 ** 64 - 1, 50),
                                       (2 ** 62, 2 ** 64 - 1, 50)]:
        cases.append({
            "name": "keySkew/%d-%d" % (hottest, requests),
            "requirements": ["CORE-005", "SPLIT-041", "OBS-032"],
            "formula": "keySkew",
            "inputs": {"hottestKeyRequests": hottest, "requests": requests,
                       "keySkewPercent": percent},
            "note": "a shard showing key skew is marked as not addressable by a split",
            "expect": formulas.key_skew(hottest, requests, percent),
        })
    for topology_id, epoch in [("migration", 0), ("migration", 1), ("objects-prod", 118),
                               ("", 9007199254740991), ("a:b|c", 42)]:
        octets = formulas.token_bytes(topology_id.encode("utf-8"), epoch)
        cases.append({
            "name": "tokenBytes/%s-%d" % (topology_id or "empty", epoch),
            "requirements": ["FENCE-001", "FENCE-021", "FENCE-031"],
            "formula": "tokenBytes",
            "inputs": {"topologyId": topology_id, "epoch": epoch},
            "note": "a length-prefixed encoding, because `topologyId` may contain any character",
            "expect": {"tokenBytes": octets.hex(),
                       "textFields": formulas.token_fields(topology_id, epoch)},
        })
    emit(root, "vectors/formulas/detection-and-fencing.json", "formulas-detection-and-fencing",
         "Hot shard detection, key skew detection, and the canonical fencing token encoding.",
         ["CORE-005", "OBS-031", "OBS-032", "OBS-033", "SPLIT-041", "FENCE-001", "FENCE-021",
          "FENCE-031"], cases)


# ------------------------------------------------------------------ split lineage

def range_document(topology_id, epoch, ranges):
    return {
        "formatVersion": "1.0",
        "topologyId": topology_id,
        "epoch": epoch,
        "replication": {"factor": 2},
        "strategy": {"kind": "range", "assignment": "explicit", "ranges": ranges},
        "nodes": [{"id": "n1"}, {"id": "n2"}, {"id": "n3"}],
    }


PARENT = range_document("lineage", 1, [
    {"shardId": "a", "start": None, "end": "80", "nodes": ["n1", "n2"]},
    {"shardId": "b", "start": "80", "end": None, "nodes": ["n2", "n3"]},
])

SPLIT_TARGET = range_document("lineage", 2, [
    {"shardId": "a0", "start": None, "end": "40", "nodes": ["n1", "n2"]},
    {"shardId": "a1", "start": "40", "end": "80", "nodes": ["n3", "n2"]},
    {"shardId": "b", "start": "80", "end": None, "nodes": ["n2", "n3"]},
])

MERGE_TARGET = range_document("lineage", 3, [
    {"shardId": "whole", "start": None, "end": None, "nodes": ["n1", "n2"]},
])

UNALIGNED_TARGET = range_document("lineage", 4, [
    {"shardId": "a", "start": None, "end": "90", "nodes": ["n1", "n2"]},
    {"shardId": "b", "start": "90", "end": None, "nodes": ["n2", "n3"]},
])

LOCAL_ONLY_TARGET = range_document("lineage", 5, [
    {"shardId": "a0", "start": None, "end": "40", "nodes": ["n1", "n2"]},
    {"shardId": "a1", "start": "40", "end": "80", "nodes": ["n1", "n2"]},
    {"shardId": "b", "start": "80", "end": None, "nodes": ["n2", "n3"]},
])


def replica_sets(document):
    return {entry["shardId"]: entry["nodes"][:document["replication"]["factor"]]
            for entry in document["strategy"]["ranges"]}


def build_split_lineage(root):
    cases = []
    write_json(root / "topologies/lineage-parent.topology.json", PARENT)
    before = Snapshot(PARENT)
    for label, target, path, note in [
        ("split", SPLIT_TARGET, "lineage-split",
         "shard `a` is replaced by two children covering it exactly; `a1` gains n3"),
        ("merged", MERGE_TARGET, "lineage-merged",
         "both shards are contained in one target shard"),
        ("unaligned", UNALIGNED_TARGET, "lineage-unaligned",
         "the boundary moved from 0x80 to 0x90, which is neither a split nor a merge"),
        ("split-local-only", LOCAL_ONLY_TARGET, "lineage-split-local-only",
         "`SPLIT-161`: the children keep the parent's replica set, so the split is a local step "
         "with no handoff"),
    ]:
        write_json(root / ("topologies/%s.topology.json" % path), target)
        after = Snapshot(target)
        rows = split.classify(before, after)
        verdict = split.plannable(rows)
        steps = split.decompose(rows, replica_sets(PARENT), replica_sets(target))
        cases.append({
            "name": label,
            "requirements": ["SPLIT-061", "SPLIT-071", "SPLIT-081", "SPLIT-091", "SPLIT-101",
                             "SPLIT-111", "SPLIT-121", "SPLIT-131", "SPLIT-161"],
            "before": "topologies/lineage-parent.topology.json",
            "after": "topologies/%s.topology.json" % path,
            "note": note,
            "expect": {"classifications": rows, "planVerdict": verdict, "decomposition": steps},
        })

    cases.append({
        "name": "unsupported-strategies",
        "requirements": ["SPLIT-001", "MOVE-241", "MOVE-251", "ERR-050"],
        "note": "`SPLIT-001`: split and merge are available under `range` alone, and a strategy "
                "that enumerates no shard supports no orchestrated migration at all.",
        "expect": {
            "splitSupported": {"range": True, "ring": False, "rendezvous": False,
                               "slot": False, "directory": False},
            "orchestratedMigrationSupported": {"ring": True, "slot": True, "range": True,
                                               "directory": True, "rendezvous": False},
            "planRefusalCause": "strategyUnsupported",
            "condition": {"code": 401, "name": "planRefused", "cause": "strategyUnsupported"},
        },
    })

    emit(root, "vectors/split/lineage.json", "split-lineage",
         "Range split and merge lineage derived from bounds, the classification of each source "
         "shard, the plan verdict, and the step decomposition each classification implies.",
         ["SPLIT-001", "SPLIT-011", "SPLIT-061", "SPLIT-071", "SPLIT-081", "SPLIT-091",
          "SPLIT-101", "SPLIT-111", "SPLIT-121", "SPLIT-131", "SPLIT-161", "SPLIT-171",
          "MOVE-241", "MOVE-251", "ERR-050"], cases)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(HERE.parent))
    args = parser.parse_args()
    root = Path(args.out)

    build_health_formulas(root)
    build_failover_formulas(root)
    build_rate_formulas(root)
    build_detection_formulas(root)
    build_split_lineage(root)

    print("wrote %d formula and lineage vector files, %d cases"
          % (len(ENTRIES), sum(e["caseCount"] for e in ENTRIES)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
