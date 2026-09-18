#!/usr/bin/env python3
"""Generate vectors for the integer formulas the specification states in closed form.

The specification states a number of its rules as closed-form integer arithmetic: a truncating
division, a strict comparison, a shift with a clamp.  A routing vector does not reach any of
them, and a port that rounds where the specification truncates passes every placement vector and
still ejects the wrong node.  Each case here is one evaluation of one formula.

    python3 generate_formulas.py [--out <conformance root>]
"""

import argparse
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from generate import write_json                                   # noqa: E402
from sharder_ref import formulas                                 # noqa: E402
from sharder_ref.topology import Snapshot                         # noqa: E402

ENTRIES = []


def emit(root, path, vector_set, level, description, requirements, cases):
    write_json(root / path, {
        "vectorSet": vector_set,
        "kind": "formula",
        "level": level,
        "description": description,
        "requirements": sorted(set(requirements)),
        "cases": cases,
    })
    ENTRIES.append({"file": path, "vectorSet": vector_set, "kind": "formula", "level": level,
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

    emit(root, "vectors/formulas/health.json", "formulas-health", "failover",
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
            "requirements": ["CORE-048", "FAIL-021", "FAIL-022"],
            "formula": "defaultAttemptLimit",
            "inputs": {"factor": factor, "attemptSequenceLength": length},
            "note": "`n + 2`, clamped to the length of the attempt sequence",
            "expect": formulas.default_attempt_limit(factor, length),
        })

    for supplied, configured, factor, length in [
            (None, None, 3, 10), (None, 5, 3, 10), (7, 5, 3, 10), (None, 5, 3, 4),
            (1, None, 3, 10), (None, 12, 1, 6)]:
        cases.append({
            "name": "resolvedAttemptLimit/%s-%s-%d-%d"
                    % ("none" if supplied is None else supplied,
                       "none" if configured is None else configured, factor, length),
            "requirements": ["CORE-045", "CORE-048", "FAIL-021", "FAIL-022", "CFG-020"],
            "formula": "resolvedAttemptLimit",
            "inputs": {"routeOptionsAttemptLimit": supplied,
                       "configuredAttemptLimit": configured,
                       "factor": factor, "attemptSequenceLength": length},
            "note": "the call's limit, else the configured one, else `n + 2`, then clamped to "
                    "the attempt sequence length",
            "expect": formulas.resolved_attempt_limit(supplied, configured, factor, length),
        })

    emit(root, "vectors/formulas/failover.json", "formulas-failover", "failover",
         "The retry budget, the default attempt limit, and the order in which a routing call "
         "resolves the limit it carries.",
         ["CORE-005", "CORE-045", "CORE-048", "FAIL-021", "FAIL-022", "FAIL-030", "FAIL-031",
          "FAIL-032", "FAIL-033", "FAIL-034", "FAIL-035", "CFG-020", "CFG-021"], cases)


def build_attempt_limit_formulas(root):
    """`CORE-048`, the resolution a routing decision carries before the clamp of `FAIL-022`.

    `CORE-048` belongs to the `routing` surface and `CORE-046` makes the length of `entries` a
    function of it, so a port that exposes no attempt walk resolves the same limit.  These cases
    sit at `place` because that is the first level at which a port materialises a prefix, and they
    reach the `RouteOptions` and `CFG-020` branches that the `materialisedEntries` of a placement
    vector never leaves the default of.
    """
    cases = []
    for supplied, configured, factor in [(None, None, 1), (None, None, 3), (None, None, 5),
                                         (None, 5, 3), (7, 5, 3), (1, None, 3), (None, 12, 1)]:
        cases.append({
            "name": "resolvedAttemptLimit/%s-%s-%d"
                    % ("none" if supplied is None else supplied,
                       "none" if configured is None else configured, factor),
            "requirements": ["CORE-048", "CFG-020", "CFG-021"],
            "formula": "resolvedAttemptLimitBeforeClamp",
            "inputs": {"routeOptionsAttemptLimit": supplied,
                       "configuredAttemptLimit": configured,
                       "factor": factor},
            "note": "the call's limit, else the configured one, else `n + 2`, with no clamp; "
                    "`FAIL-022` clamps this value for the attempt walk alone",
            "expect": formulas.resolved_attempt_limit_before_clamp(supplied, configured, factor),
        })

    emit(root, "vectors/formulas/attempt-limit.json", "formulas-attempt-limit", "place",
         "The attempt limit a routing decision resolves, in the order `CORE-048` states and "
         "before the clamp the attempt walk applies.",
         ["CORE-048", "CFG-020", "CFG-021"], cases)


def build_virtual_node_count_formulas(root):
    cases = []
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
    emit(root, "vectors/placement/virtual-node-count.json",
         "placement-virtual-node-count", "place",
         "The virtual node count a weight yields under a per-unit multiplier and a cap.",
         ["PLACE-050", "PLACE-051", "PLACE-052"], cases)


def build_rate_formulas(root):
    cases = []
    for attempt in [1, 2, 3, 6, 7, 8, 20]:
        cases.append({
            "name": "retryBackoffMillis/%d" % attempt,
            "requirements": ["RATE-051", "MOVE-171"],
            "formula": "retryBackoffMillis",
            "inputs": {"attempt": attempt, "retryBackoffBaseMillis": 1000,
                       "retryBackoffCapMillis": 60000},
            "expect": formulas.retry_backoff_millis(attempt, 1000, 60000),
        })
    for policy in [(1, 0, 18446744073709551615), (0, 0, 10), (1, 10, 10), (1, 10, 5),
                   (0, 10, 5)]:
        cases.append({
            "name": "policyRefused/%s" % "-".join(str(v) for v in policy),
            "requirements": ["RATE-021", "ERR-050", "CFG-050", "CFG-052"],
            "formula": "policyRefused",
            "inputs": {"initialStepBudget": policy[0],
                       "catchUpResidualThreshold": policy[1],
                       "reTransferResidualThreshold": policy[2]},
            "expect": formulas.policy_refused(*policy),
        })
    emit(root, "vectors/formulas/migration-rate.json", "formulas-migration-rate", "migration",
         "Retry backoff and migration policy validation.",
         ["RATE-021", "RATE-051", "MOVE-171"], cases)


def build_skew_formulas(root):
    cases = []
    for shard_requests, shard_count, total, percent in [
        (100, 10, 1000, 400), (400, 10, 1000, 400), (399, 10, 1000, 400),
        (1, 1, 1, 400), (0, 16384, 1000000, 400),
        # `CORE-005`: both products exceed 64 bits at the declared u64 range of `OBS-036`.
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
            "requirements": ["CORE-005", "OBS-032"],
            "formula": "keySkew",
            "inputs": {"hottestKeyRequests": hottest, "requests": requests,
                       "keySkewPercent": percent},
            "note": "the left-hand side reaches 71 bits, so a 64-bit product is not conforming",
            "expect": formulas.key_skew(hottest, requests, percent),
        })
    emit(root, "vectors/formulas/skew-detection.json", "formulas-skew-detection", "core",
         "Hot shard detection and key skew detection.",
         ["CORE-005", "OBS-031", "OBS-032", "OBS-033"], cases)


def build_fencing_token_formulas(root):
    cases = []
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
    emit(root, "vectors/formulas/fencing-token.json", "formulas-fencing-token", "fencing",
         "The canonical fencing token encoding.",
         ["FENCE-001", "FENCE-021", "FENCE-031"], cases)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(HERE.parent))
    args = parser.parse_args()
    root = Path(args.out)

    build_health_formulas(root)
    build_failover_formulas(root)
    build_attempt_limit_formulas(root)
    build_virtual_node_count_formulas(root)
    build_rate_formulas(root)
    build_skew_formulas(root)
    build_fencing_token_formulas(root)

    print("wrote %d formula vector files, %d cases"
          % (len(ENTRIES), sum(e["caseCount"] for e in ENTRIES)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
