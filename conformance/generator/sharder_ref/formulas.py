"""The integer formulas the specification states in closed form.

Each is a direct transcription of one requirement.  They are the arithmetic a port most easily
gets wrong in a way no routing vector would catch: a truncating division written as a rounding
one, a strict comparison written as a non-strict one, or a shift that overflows.
"""


# ---------------------------------------------------------------------- health

def failure_percent(successes: int, failures: int) -> int:
    """`HEALTH-023`: `failures * 100 / total`, truncating, and 0 where the total is 0."""
    total = successes + failures
    return 0 if total == 0 else failures * 100 // total


def ejection_refused(ejected: int, max_ejection_percent: int, placement_set_size: int) -> bool:
    """`HEALTH-034`: `(ejected + 1) * 100 > maxEjectionPercent * placementSetSize`."""
    return (ejected + 1) * 100 > max_ejection_percent * placement_set_size


def ejection_millis(ejection_count: int, base: int, cap: int) -> int:
    """`HEALTH-050`: `min(base << min(count - 1, 10), cap)`."""
    return min(base << min(ejection_count - 1, 10), cap)


def probe_admitted(probe_counter_after_increment: int, divisor: int) -> bool:
    """`HEALTH-051`: attemptable exactly when the incremented value modulo the divisor is 1."""
    return probe_counter_after_increment % divisor == 1


def peer_median(values):
    """`HEALTH-031`: the lower of the two central values where the count is even."""
    ordered = sorted(values)
    return ordered[(len(ordered) - 1) // 2]


def is_outlier(value: int, median: int, margin_percent: int) -> bool:
    """`HEALTH-032`."""
    return value >= median + margin_percent


# ----------------------------------------------------------------- retry budget

def retry_permitted(retries: int, first_attempts: int, percent: int, minimum: int) -> bool:
    """`FAIL-031`: `retries * 100 <= retryBudgetPercent * firstAttempts + 100 * minimum`."""
    return retries * 100 <= percent * first_attempts + 100 * minimum


def default_attempt_limit(factor: int, sequence_length: int) -> int:
    """`FAIL-022`: `n + 2`, clamped to the length of the attempt sequence."""
    return min(factor + 2, sequence_length)


# -------------------------------------------------------------- migration rate

def budget_after_success(budget: int, increment: int, maximum: int) -> int:
    """`RATE-041`."""
    return min(budget + increment, maximum)


def budget_after_deferral(budget: int, minimum: int) -> int:
    """`RATE-041`, using unsigned integer division."""
    return max(budget // 2, minimum)


def retry_backoff_millis(attempt: int, base: int, cap: int) -> int:
    """`RATE-051`: `min(base * 2^(attempt - 1), cap)`."""
    return min(base * (2 ** (attempt - 1)), cap)


def policy_refused(min_step_budget: int, max_step_budget: int,
                   catch_up_threshold: int, retransfer_threshold: int):
    """`RATE-021`: the three conditions under which a policy is refused."""
    reasons = []
    if min_step_budget == 0:
        reasons.append("minStepBudgetZero")
    if max_step_budget < min_step_budget:
        reasons.append("maxBelowMinStepBudget")
    if retransfer_threshold <= catch_up_threshold:
        reasons.append("retransferThresholdNotAboveCatchUp")
    return reasons


# ------------------------------------------------------------------ skew and heat

def shard_is_hot(shard_requests: int, shard_count: int, total_requests: int,
                 hot_shard_factor_percent: int) -> bool:
    """`OBS-031`: `shardRequests * shardCount * 100 >= totalRequests * hotShardFactorPercent`."""
    if shard_count == 0:
        return False
    return (shard_requests * shard_count * 100
            >= total_requests * hot_shard_factor_percent)


def key_skew(hottest_key_requests: int, requests: int, skew_percent: int) -> bool:
    """`SPLIT-041` and `OBS-032`: `hottestKeyRequests * 100 >= requests * skewPercent`."""
    return hottest_key_requests * 100 >= requests * skew_percent


# ---------------------------------------------------------------------- fencing

def token_bytes(topology_id: bytes, epoch: int) -> bytes:
    """`FENCE-021`: `u32be(len(topologyId)) || topologyId || u64be(epoch)`."""
    return (len(topology_id).to_bytes(4, "big") + topology_id
            + epoch.to_bytes(8, "big"))


def token_fields(topology_id: str, epoch: int):
    """`FENCE-031`: the two named text fields."""
    return {"sharder-topology-id": topology_id, "sharder-epoch": str(epoch)}


# -------------------------------------------------------------- virtual nodes

def virtual_node_count(weight: int, per_weight_unit: int, cap: int) -> int:
    """`PLACE-050` and `PLACE-051`: the product is computed in at least 64 bits before clamping."""
    return min(weight * per_weight_unit, cap)


# ------------------------------------------------------------------- balance

def balance_bound(observed: int, virtual_nodes: int, total_virtual_nodes: int,
                  sample_size: int, multiplier: int):
    """`PROP-020` and `PROP-021`: `multiplier * |c_i * V - M * v_i| <= M * v_i`."""
    left = multiplier * abs(observed * total_virtual_nodes - sample_size * virtual_nodes)
    right = sample_size * virtual_nodes
    return {"left": left, "right": right, "holds": left <= right}


def movement_bound(moved: int, sample_size: int, p_num: int, p_den: int, multiplier: int):
    """`PROP-015`: `multiplier * |m * p_den - M * p_num| <= M * p_num`."""
    left = multiplier * abs(moved * p_den - sample_size * p_num)
    right = sample_size * p_num
    return {"left": left, "right": right, "holds": left <= right}
