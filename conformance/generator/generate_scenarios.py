#!/usr/bin/env python3
"""Generate the simulation scenarios.

A scenario is a sequence of actions a port replays against its own implementation, each carrying
the result the reference computed.  Scenarios are deterministic: every clock reading is an
explicit instant in the file, every health signal is an explicit action, and no step draws a
random value.

The expectations are computed by `sharder_ref`, whose `fencing`, `health`, and `handoff` modules
transcribe the specification's tables.  A scenario file therefore contains no state sequence that
was reasoned about rather than executed.

    python3 generate_scenarios.py [--out <conformance root>]
"""

import argparse
import copy
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from generate import write_json, key_spec                        # noqa: E402
from sharder_ref import fencing, hashing, routing                 # noqa: E402
from sharder_ref.handoff import COMMIT_TRIGGERS, Handoff, Plan, ownership_delta  # noqa: E402
from sharder_ref.health import HealthView                         # noqa: E402
from sharder_ref.jcs import digest as jcs_digest                  # noqa: E402
from sharder_ref.topology import Snapshot                         # noqa: E402

SCENARIOS = []
SCENARIO_TOPOLOGIES = {}

# The conformance level each scenario belongs to.  A scenario names the surface it drives, and
# `docs/design/30-conformance.md` states which surface each level covers, so the level is a
# property of the scenario rather than of the driver that runs it.  `register` refuses a scenario
# absent from this table.
LEVEL_OF_SCENARIO = {
    "topology-rollback": "core",
    "caller-three-epochs-stale": "fencing",
    "split-topology-view": "fencing",
    "redirect-walk-depth-limit": "fencing",
    "handoff-happy-path": "migration",
    "quiesce-lease-expiry": "migration",
    "node-dies-mid-migration": "migration",
    "abort-during-catching-up": "migration",
    "coordinator-death-and-recovery": "migration",
    "handoff-failure-kinds": "migration",
    "plan-superseded-by-new-epoch": "migration",
    "rebalance-survives-unrelated-epoch": "migration",
    "rebase-drops-a-handoff": "migration",
    "undetermined-resolves-both-ways": "migration",
    "migration-rate-control": "migration",
    "failover-and-recovery": "failover",
    "health-filter-fails-open": "failover",
    "ejection-ceiling": "failover",
    "probation-ramp": "failover",
    "outlier-comparison-set": "failover",
    "health-reset-on-reentry": "failover",
}


def register(name, description, requirements, steps, setup=None):
    if name not in LEVEL_OF_SCENARIO:
        raise KeyError("scenario %r names no conformance level" % name)
    SCENARIOS.append({
        "scenario": name,
        "level": LEVEL_OF_SCENARIO[name],
        "description": description,
        "requirements": sorted(set(requirements)),
        "deterministic": True,
        "setup": setup or {},
        "steps": steps,
    })


def topology(name, document):
    SCENARIO_TOPOLOGIES[name] = document
    return "topologies/%s.topology.json" % name


# ------------------------------------------------------------------ the cluster

def cluster(epoch, moving_nodes, n4_state="joining", topology_id="migration"):
    return {
        "formatVersion": "1.0",
        "topologyId": topology_id,
        "epoch": epoch,
        "domainLevels": ["zone"],
        "replication": {"factor": 2},
        "strategy": {
            "kind": "slot",
            "slotCount": 3,
            "assignment": "explicit",
            "assignments": [
                {"slots": ["0"], "nodes": ["n1", "n2"]},
                {"slots": ["1"], "nodes": moving_nodes},
                {"slots": ["2"], "nodes": ["n3", "n1"]},
            ],
        },
        "nodes": [
            {"id": "n1", "domains": {"zone": "za"}, "address": "10.0.0.1:7000"},
            {"id": "n2", "domains": {"zone": "zb"}, "address": "10.0.0.2:7000"},
            {"id": "n3", "domains": {"zone": "zc"}, "address": "10.0.0.3:7000"},
            {"id": "n4", "state": n4_state, "domains": {"zone": "zd"},
             "address": "10.0.0.4:7000"},
        ],
    }


EPOCH1 = cluster(1, ["n2", "n3"])
EPOCH2 = cluster(2, ["n4", "n3"], n4_state="active")
EPOCH3 = cluster(3, ["n4", "n1"], n4_state="active")
EPOCH4 = cluster(4, ["n1", "n3"], n4_state="active")
# A rollback republishes the epoch 1 assignment at a higher epoch, under `MOVE-471`.
EPOCH5_ROLLBACK = cluster(5, ["n2", "n3"], n4_state="draining")

# The probe key falls in shard `1` under every epoch above, which is the shard that moves.
PROBE_KEY = next(bytes([i]) for i in range(256)
                 if hashing.key_hash(hashing.ZERO_SEED, bytes([i])) % 3 == 1)

# A second, larger family for the rebase scenarios.  A rebalance over it outlives the epoch it
# was planned against, which is the case `MOVE-091` through `MOVE-099` exist for.
FLEET_ZONES = ["za", "zb", "zc", "zd", "ze", "zf", "zg"]


def fleet(epoch, assignment, node_count=6):
    return {
        "formatVersion": "1.0",
        "topologyId": "rebalance",
        "epoch": epoch,
        "domainLevels": ["zone"],
        "replication": {"factor": 2},
        "strategy": {
            "kind": "slot",
            "slotCount": 4,
            "assignment": "explicit",
            "assignments": [{"slots": [shard], "nodes": nodes}
                            for shard, nodes in assignment],
        },
        "nodes": [{"id": "n%d" % i, "domains": {"zone": FLEET_ZONES[i - 1]},
                   "address": "10.1.0.%d:7000" % i}
                  for i in range(1, node_count + 1)],
    }


# Epoch 10 is the plan's source and epoch 11 its target: shard `0` moves from n1 to n5 and shard
# `1` from n3 to n6.
FLEET_SHARDS_10 = [("0", ["n1", "n2"]), ("1", ["n2", "n3"]),
                   ("2", ["n3", "n4"]), ("3", ["n4", "n1"])]
FLEET_SHARDS_11 = [("0", ["n5", "n2"]), ("1", ["n2", "n6"]),
                   ("2", ["n3", "n4"]), ("3", ["n4", "n1"])]
# Epoch 12 is the ordinary fleet event the review names: a node joins and nothing is reassigned.
# Every shard's replica set is the one epoch 11 gives, so both handoffs still hold.
FLEET_SHARDS_12 = FLEET_SHARDS_11
# Epoch 13 returns shard `1` to n3, so that handoff's triple no longer holds and it cannot rebase.
FLEET_SHARDS_13 = [("0", ["n5", "n2"]), ("1", ["n2", "n3"]),
                   ("2", ["n3", "n4"]), ("3", ["n4", "n1"])]

FLEET10 = fleet(10, FLEET_SHARDS_10)
FLEET11 = fleet(11, FLEET_SHARDS_11)
FLEET12 = fleet(12, FLEET_SHARDS_12, node_count=7)
FLEET13 = fleet(13, FLEET_SHARDS_13, node_count=7)


def snapshots():
    return {name: Snapshot(document) for name, document in {
        "migration-epoch-1": EPOCH1, "migration-epoch-2": EPOCH2,
        "migration-epoch-3": EPOCH3, "migration-epoch-4": EPOCH4,
        "migration-epoch-5-rollback": EPOCH5_ROLLBACK,
    }.items()}


SNAP = snapshots()
for _name, _doc in [("migration-epoch-1", EPOCH1), ("migration-epoch-2", EPOCH2),
                    ("migration-epoch-3", EPOCH3), ("migration-epoch-4", EPOCH4),
                    ("migration-epoch-5-rollback", EPOCH5_ROLLBACK)]:
    topology(_name, _doc)

FLEET = {}
for _name, _doc in [("rebalance-epoch-10", FLEET10), ("rebalance-epoch-11", FLEET11),
                    ("rebalance-epoch-12", FLEET12), ("rebalance-epoch-13", FLEET13)]:
    FLEET[_name] = Snapshot(_doc)
    topology(_name, _doc)


def replica_sets(snapshot):
    """The replica set of every shard the snapshot enumerates, under `TOPO-211`.

    This is what `MOVE-096` classifies a handoff against: the handoff holds under the snapshot
    when its destination is a member and its source is not.
    """
    from sharder_ref import placement as placement_module
    sets = {}
    for shard in placement_module.shards(snapshot):
        ordering = placement_module.candidates_for_shard(snapshot, shard,
                                                         snapshot.placement_set)
        entries, count, _, _ = routing.build_preference_list(snapshot, ordering,
                                                             snapshot.factor)
        sets[shard] = [entry["node"] for entry in entries[:count]]
    return sets


def placement_identities(snapshot):
    """The placement set as node identities, which is what `HEALTH-016` delivers to a view."""
    return [node.id for node in snapshot.placement_set]


def route_expect(snapshot, key):
    decision = routing.route(snapshot, key)
    return {"shard": decision["shard"], "token": decision["token"],
            "preferenceList": [e["node"] for e in decision["preferenceList"]],
            "replicaCount": decision["replicaCount"]}


# ------------------------------------------------------------- topology lifecycle

def build_rollback_scenario():
    steps = []
    for name, document, outcome, condition in [
        ("migration-epoch-1", EPOCH1, "installed", None),
        ("migration-epoch-2", EPOCH2, "installed", None),
        ("migration-epoch-1", EPOCH1, "rejected",
         {"code": 203, "name": "staleDocument",
          "detail": "an epoch below the epoch in force"}),
        ("migration-epoch-2", EPOCH2, "noop", None),
    ]:
        steps.append({
            "action": "installTopology",
            "topology": "topologies/%s.topology.json" % name,
            "expect": {"outcome": outcome, "condition": condition,
                       "epochInForce": 2 if outcome != "installed" else document["epoch"],
                       "digest": jcs_digest(document)},
        })

    conflicting = copy.deepcopy(EPOCH2)
    conflicting["metadata"] = {"note": "same epoch, different content"}
    steps.append({
        "action": "installTopology",
        "document": conflicting,
        "expect": {"outcome": "rejected",
                   "condition": {"code": 202, "name": "topologyConflict",
                                 "detail": "an equal epoch whose digest differs"},
                   "epochInForce": 2,
                   "digest": jcs_digest(conflicting)},
    })

    foreign = copy.deepcopy(EPOCH3)
    foreign["topologyId"] = "some-other-cluster"
    steps.append({
        "action": "installTopology",
        "document": foreign,
        "expect": {"outcome": "rejected",
                   "condition": {"code": 202, "name": "topologyConflict",
                                 "detail": "a differing topologyId"},
                   "epochInForce": 2},
    })

    steps.append({
        "action": "installTopology",
        "topology": "topologies/migration-epoch-5-rollback.topology.json",
        "note": "the rollback: the epoch 1 assignment republished at epoch 5, because `TOPO-081` "
                "accepts no lower epoch",
        "expect": {"outcome": "installed", "condition": None, "epochInForce": 5,
                   "digest": jcs_digest(EPOCH5_ROLLBACK)},
    })
    steps.append({
        "action": "route",
        "key": key_spec(PROBE_KEY, "base16"),
        "expect": route_expect(SNAP["migration-epoch-5-rollback"], PROBE_KEY),
    })

    register("topology-rollback",
             "An authority reverts an assignment.  The library never accepts a lower epoch, so "
             "the revert arrives as a higher epoch carrying the former assignment.  The "
             "scenario also covers the equal-epoch digest conflict and the foreign identifier.",
             ["TOPO-051", "TOPO-061", "TOPO-081", "TOPO-091", "ERR-031", "ERR-032"], steps)


def build_stale_caller_scenario():
    """A caller three epochs behind, at a recipient holding the newest snapshot."""
    in_force = SNAP["migration-epoch-4"]
    retained = {1: SNAP["migration-epoch-1"], 2: SNAP["migration-epoch-2"],
                3: SNAP["migration-epoch-3"]}
    # `FENCE-083`: before any document is installed the recipient holds no preference list, so
    # `ownership` is `unknown` and neither `currentOwner` nor `localToken` is present.
    unready_token = {"topologyId": "migration", "epoch": 1}
    steps = [{
        "action": "recipientCheck",
        "token": unready_token,
        "key": key_spec(PROBE_KEY, "base16"),
        "selfId": "n1",
        "recipientPolicy": "strict",
        "noSnapshot": True,
        "note": "`FENCE-083`: no snapshot is in force, so `ownership` is `unknown`",
        "expect": {"verdict": fencing.check(unready_token, PROBE_KEY, "n1", None),
                   "condition": fencing.policy_outcome(
                       fencing.check(unready_token, PROBE_KEY, "n1", None), "strict"),
                   "served": False},
    }]
    steps += [{
        "action": "installTopology",
        "topology": "topologies/migration-epoch-%d.topology.json" % epoch,
        "expect": {"outcome": "installed", "condition": None, "epochInForce": epoch},
    } for epoch in (1, 2, 3, 4)]
    steps.append({"action": "setRetentionDepth", "depth": 3,
                  "expect": {"retainedEpochs": [3, 2, 1]}})

    for self_id in ["n1", "n3", "n2"]:
        for policy in ["strict", "stable"]:
            token = {"topologyId": "migration", "epoch": 1}
            verdict = fencing.check(token, PROBE_KEY, self_id, in_force, retained)
            condition = fencing.policy_outcome(verdict, policy)
            steps.append({
                "action": "recipientCheck",
                "token": token,
                "key": key_spec(PROBE_KEY, "base16"),
                "selfId": self_id,
                "recipientPolicy": policy,
                "note": "the caller is three epochs behind; epoch 1 is retained",
                "expect": {"verdict": verdict, "condition": condition,
                           "served": condition is None},
            })

    # The same caller against a recipient whose retention no longer reaches epoch 1.
    verdict = fencing.check({"topologyId": "migration", "epoch": 1}, PROBE_KEY, "n3",
                            in_force, retained={})
    steps.append({
        "action": "recipientCheck",
        "token": {"topologyId": "migration", "epoch": 1},
        "key": key_spec(PROBE_KEY, "base16"),
        "selfId": "n3",
        "recipientPolicy": "stable",
        "retainedEpochs": [],
        "note": "`FENCE-091`: where the token's epoch is not retained, `ownershipStable` is "
                "false whatever the ownership is",
        "expect": {"verdict": verdict,
                   "condition": fencing.policy_outcome(verdict, "stable"),
                   "served": False},
    })

    # An unfenced request, and a sender ahead of the recipient.  `FENCE-042`: the recipient
    # computes ownership for an unfenced request, and `FENCE-121` refuses a non-owner whatever
    # the policy is, so `stable` serves one only where the recipient owns the key.
    for self_id, policy in [("n1", "strict"), ("n1", "stable"),
                            ("n2", "strict"), ("n2", "stable")]:
        verdict = fencing.check({"topologyId": "migration", "epoch": 4}, PROBE_KEY, self_id,
                                in_force, retained)
        condition = fencing.policy_outcome(verdict, policy, fenced=False)
        steps.append({
            "action": "recipientCheck",
            "token": None,
            "key": key_spec(PROBE_KEY, "base16"),
            "selfId": self_id,
            "recipientPolicy": policy,
            "fenced": False,
            "note": "`FENCE-042`: the request carries no token, so there is no relation.  "
                    "Ownership is computed against the snapshot in force, and `ERR-045` puts "
                    "`notOwner` ahead of the unfenced `epochMismatch` of `ERR-044`.",
            "expect": {"verdict": verdict, "condition": condition,
                       "served": condition is None},
        })
    ahead = {"topologyId": "migration", "epoch": 9}
    verdict = fencing.check(ahead, PROBE_KEY, "n1", in_force, retained)
    steps.append({
        "action": "recipientCheck",
        "token": ahead,
        "key": key_spec(PROBE_KEY, "base16"),
        "selfId": "n1",
        "recipientPolicy": "strict",
        "refreshWaitMillis": 0,
        "expect": {"verdict": verdict,
                   "condition": fencing.policy_outcome(verdict, "strict"),
                   "served": False},
    })
    mismatch = {"topologyId": "another-cluster", "epoch": 4}
    verdict = fencing.check(mismatch, PROBE_KEY, "n1", in_force, retained)
    steps.append({
        "action": "recipientCheck",
        "token": mismatch,
        "key": key_spec(PROBE_KEY, "base16"),
        "selfId": "n1",
        "recipientPolicy": "stable",
        "note": "`FENCE-082`: epochs under two identifiers are incomparable and the routing key "
                "was derived under the sender's topology, so `ownership` is `unknown` and no "
                "owner is named",
        "expect": {"verdict": verdict,
                   "condition": fencing.policy_outcome(verdict, "stable"),
                   "served": False},
    })

    register("caller-three-epochs-stale",
             "A caller routes against epoch 1 while the recipient holds epoch 4.  Covers every "
             "recipient relation, both policies, a retained and an unretained token epoch, an "
             "unfenced request at an owner and at a non-owner, a sender ahead of the recipient, "
             "and the two relations under which ownership is `unknown`.",
             ["FENCE-041", "FENCE-042", "FENCE-061", "FENCE-071", "FENCE-081", "FENCE-082",
              "FENCE-083", "FENCE-084", "FENCE-091", "FENCE-111", "FENCE-121", "FENCE-131",
              "FENCE-141", "FENCE-151", "TOPO-161", "TOPO-171", "ERR-040", "ERR-041",
              "ERR-042", "ERR-044", "ERR-045"], steps)


def build_split_view_scenario():
    """Half the cluster on epoch 1, half on epoch 3."""
    steps = []
    old, new = SNAP["migration-epoch-1"], SNAP["migration-epoch-3"]
    steps.append({"action": "declareView", "view": "old",
                  "topology": "topologies/migration-epoch-1.topology.json"})
    steps.append({"action": "declareView", "view": "new",
                  "topology": "topologies/migration-epoch-3.topology.json"})

    for view_name, snapshot in [("old", old), ("new", new)]:
        steps.append({"action": "routeInView", "view": view_name,
                      "key": key_spec(PROBE_KEY, "base16"),
                      "expect": route_expect(snapshot, PROBE_KEY)})

    old_owners, _ = fencing.replica_set(old, PROBE_KEY)
    new_owners, _ = fencing.replica_set(new, PROBE_KEY)
    steps.append({
        "action": "compareOwnership",
        "key": key_spec(PROBE_KEY, "base16"),
        "note": "the two halves disagree about who owns the key, which is what fencing detects",
        "expect": {"oldReplicaSet": old_owners, "newReplicaSet": new_owners,
                   "disagree": old_owners != new_owners},
    })

    for sender_view, recipient_view, recipient, snapshot in [
        ("old", "new", "n2", new), ("old", "new", "n4", new),
        ("new", "old", "n4", old), ("new", "old", "n2", old),
    ]:
        token = {"topologyId": "migration",
                 "epoch": 1 if sender_view == "old" else 3}
        verdict = fencing.check(token, PROBE_KEY, recipient, snapshot, retained={})
        steps.append({
            "action": "recipientCheck",
            "senderView": sender_view,
            "recipientView": recipient_view,
            "token": token,
            "key": key_spec(PROBE_KEY, "base16"),
            "selfId": recipient,
            "recipientPolicy": "strict",
            "note": "where `relation` is `senderAhead` and `ownership` is `notOwner`, both "
                    "`FENCE-121` and `FENCE-141` require a refusal, and `ERR-045` puts "
                    "`notOwner` ahead of `epochMismatch` so the refusal names `currentOwner`.",
            "expect": {"verdict": verdict,
                       "condition": fencing.policy_outcome(verdict, "strict"),
                       "served": fencing.policy_outcome(verdict, "strict") is None},
        })

    register("split-topology-view",
             "Half the cluster holds epoch 1 and half holds epoch 3, and the two halves disagree "
             "about the replica set of one key.  Every crossing request is refused, and the "
             "refusal names the owner the recipient believes in.",
             ["FENCE-051", "FENCE-071", "FENCE-081", "FENCE-111", "FENCE-121", "FENCE-131",
              "FENCE-141", "ERR-040", "ERR-041", "ERR-045", "CORE-053", "TOPO-121"], steps)


REDIRECT_NODES = ["n1", "n2", "n3", "n4"]

# The retry budget window a redirect is accounted against, under `FENCE-231`.  `percent` and
# `minimum` are the defaults of `FAIL-035`.
def redirect_budget(first_attempts, retries):
    return {"firstAttempts": first_attempts, "retries": retries, "percent": 20, "minimum": 3}


def build_redirect_scenario():
    steps = []
    for name, refusals, bound, known, budget, note in [
        ("served-immediately", {"n2": None}, 2, None, None, "the first node serves"),
        ("one-redirect", {"n2": "n4", "n4": None}, 2, None, None, "one hop"),
        ("bound-reached", {"n2": "n4", "n4": "n3", "n3": "n1", "n1": None}, 2, None, None,
         "`FENCE-181`: the walk stops at `maxRedirects`, defaulting to 2"),
        ("revisited-node", {"n2": "n4", "n4": "n2"}, 5, None, None,
         "`FENCE-191`: a redirect naming an already attempted node terminates the walk"),
        ("bound-zero", {"n2": "n4"}, 0, None, None, "a bound of 0 permits no redirect at all"),
        ("unknown-node", {"n2": "n9"}, 2, REDIRECT_NODES, None,
         "`FENCE-171`: the named identity is absent from the caller's own `nodes` list"),
        ("budget-permits", {"n2": "n4", "n4": "n3", "n3": None}, 5, REDIRECT_NODES,
         redirect_budget(100, 0),
         "`FENCE-231`: each followed redirect is a retry, and the window has room for both"),
        ("budget-refuses-second", {"n2": "n4", "n4": "n3", "n3": None}, 5, REDIRECT_NODES,
         redirect_budget(10, 5),
         "the first redirect spends the window and the second is refused"),
        ("budget-refuses-first", {"n2": "n4", "n4": None}, 5, REDIRECT_NODES,
         redirect_budget(0, 4),
         "`FAIL-032` exempts a first attempt and not a redirect, so the walk never starts"),
        ("bound-before-budget", {"n2": "n4"}, 0, REDIRECT_NODES, redirect_budget(0, 4),
         "`FENCE-221`: the bound is evaluated before the budget, so the budget is not spent"),
    ]:
        result = fencing.redirect_walk("n2", refusals, bound, known, budget)
        step = {
            "action": "redirectWalk",
            "name": name,
            "start": "n2",
            "refusals": refusals,
            "maxRedirects": bound,
            "note": note,
            "expect": result,
        }
        if known is not None:
            step["nodes"] = known
        if budget is not None:
            step["budget"] = budget
        steps.append(step)
    register("redirect-walk-depth-limit",
             "A caller follows `currentOwner` refusals, and the walk terminates at the redirect "
             "bound, on revisiting a node it already attempted, on an identity its own snapshot "
             "does not carry, or where the retry budget refuses the redirect.",
             ["FENCE-171", "FENCE-181", "FENCE-191", "FENCE-211", "FENCE-221", "FENCE-231",
              "FAIL-030", "FAIL-031", "ERR-043", "CFG-040"], steps)


# ---------------------------------------------------------------------- migration

def delta_steps():
    before, after = SNAP["migration-epoch-1"], SNAP["migration-epoch-2"]
    rows = ownership_delta(before, after, lambda s: s.factor)
    return [{
        "action": "ownershipDelta",
        "from": "topologies/migration-epoch-1.topology.json",
        "to": "topologies/migration-epoch-2.topology.json",
        "expect": {"shardsChanged": len(rows), "delta": rows},
    }], rows


def plan_setup(plan):
    """The handoffs a scenario drives, for a plan no step of it builds.

    `MOVE-221` makes plan construction a pure function of the two snapshots and the policy, and a
    scenario that drives a plan without building one states the plan it drives.  Without it the
    handoffs come from nowhere and a driver replaying the steps cannot reach the expectations.
    """
    return {
        "plan": {
            "topologyId": plan.topology_id,
            "fromEpoch": plan.source_epoch,
            "toEpoch": plan.initial_target_epoch,
            "policy": dict(plan.policy),
            "handoffs": [
                {"id": handoff.id, "shard": handoff.shard,
                 "source": handoff.source, "destination": handoff.destination}
                for handoff in plan.handoffs.values()
            ],
        },
    }


def one_handoff():
    return Plan(1, 2, "migration", [Handoff("h-1", "1", "n2", "n4")])


# `MOVE-336`: a lease at or below `quiesceLeaseMarginMillis` plus `commitDeadlineMillis` admits no
# commit at all, and at the defaults of `CFG-050` that sum is 31000.  A scenario that means to
# reach a cutover grants a minute.
QUIESCE_LEASE_MILLIS = 60000


def quiesce_step(plan, handoff_id, at, lease=QUIESCE_LEASE_MILLIS):
    """A recorded `quiesce`, whose instant `MOVE-332` takes before the hook call."""
    outcome = plan.quiesce(handoff_id, lease, at)
    return {"action": "handoffQuiesce", "handoff": handoff_id, "leaseMillis": lease, "at": at,
            "expect": {"outcome": outcome, "state": plan.state(handoff_id)}}


def advance(plan, handoff_id, trigger, at=None, **kwargs):
    """`plan.step`, with the `quiesce` that `MOVE-331` requires before a commit taken first.

    `MOVE-332` reads the quiesce instant immediately before the hook call, so it is taken one
    millisecond ahead of the reading the commit is attempted at.  A scenario that asserts the
    quiesce records it as a step of its own instead, through `quiesce_step`.
    """
    if (trigger in COMMIT_TRIGGERS and plan.state(handoff_id) == "cutover"
            and plan.handoffs[handoff_id].quiesce_instant is None):
        plan.quiesce(handoff_id, QUIESCE_LEASE_MILLIS, (at or 0) - 1)
    return plan.step(handoff_id, trigger, at=at, **kwargs)


def drive(plan, triggers, at_base=1000):
    """Apply a trigger sequence and return the steps with their computed outcomes."""
    steps = []
    for index, trigger in enumerate(triggers):
        at = at_base + index * 1000
        if trigger in COMMIT_TRIGGERS and plan.state("h-1") == "cutover":
            steps.append(quiesce_step(plan, "h-1", at - 500))
        outcome = plan.step("h-1", trigger, at=at)
        steps.append({"action": "handoffStep", "handoff": "h-1", "trigger": trigger, "at": at,
                      "expect": {"outcome": outcome, "state": plan.state("h-1")}})
    return steps


HAPPY_PATH = ["admittedByRatePolicy", "prepareSuccess", "noBulkRemaining",
              "residueAtOrBelowThreshold", "cutoverCommitted", "verifySuccess",
              "cleanupSuccess"]


def build_happy_path_scenario():
    steps, rows = delta_steps()
    plan = one_handoff()
    steps.append({"action": "plan",
                  "from": "topologies/migration-epoch-1.topology.json",
                  "to": "topologies/migration-epoch-2.topology.json",
                  "policy": plan.policy,
                  "expect": {"handoffs": [{"id": "h-1", "shard": "1", "source": "n2",
                                           "destination": "n4", "state": "planned"}]}})
    steps.extend(drive(plan, HAPPY_PATH))
    steps.append({"action": "expectSummary", "expect": plan.summary()})
    register("handoff-happy-path",
             "One shard moves from its source to its destination through every state of "
             "`MOVE-021`, with the ownership delta that produced the plan.",
             ["TOPO-211", "TOPO-221", "MOVE-001", "MOVE-021", "MOVE-031", "MOVE-041",
              "MOVE-051", "MOVE-071", "MOVE-121", "MOVE-131", "MOVE-181", "MOVE-241",
              "MOVE-331", "MOVE-332", "MOVE-333"], steps)


def build_quiesce_lease_scenario():
    """`MOVE-331` through `MOVE-336`: the commit horizon, and a lease too short to carry one."""
    steps = []

    # The lease runs out before the coordinator reaches `commitCutover`, so the commit is not
    # called and a fresh `quiesce` restores the window.
    plan = one_handoff()
    steps.extend(drive(plan, HAPPY_PATH[:4]))
    steps.append(quiesce_step(plan, "h-1", 5000, lease=40000))
    outcome = advance(plan, "h-1", "cutoverCommitted", at=20000)
    steps.append({
        "action": "handoffStep", "handoff": "h-1", "trigger": "cutoverCommitted", "at": 20000,
        "note": "`MOVE-333`: the reading plus `commitDeadlineMillis` is above the commit "
                "horizon, so `commitCutover` is not called and `MOVE-331` re-quiesces",
        "expect": {"outcome": outcome, "state": plan.state("h-1")},
    })
    steps.append(quiesce_step(plan, "h-1", 21000, lease=40000))
    outcome = advance(plan, "h-1", "cutoverCommitted", at=22000)
    steps.append({
        "action": "handoffStep", "handoff": "h-1", "trigger": "cutoverCommitted", "at": 22000,
        "note": "the fresh lease admits the whole window, so the commit is called",
        "expect": {"outcome": outcome, "state": plan.state("h-1")},
    })
    steps.extend(drive(plan, ["verifySuccess", "cleanupSuccess"], at_base=23000))
    steps.append({"action": "expectSummary", "expect": plan.summary()})

    # A lease at the sum of the margin and the commit deadline admits no reading at all.
    short = one_handoff()
    for index, trigger in enumerate(HAPPY_PATH[:4]):
        advance(short, "h-1", trigger, at=1000 + index * 100)
    outcome = short.quiesce("h-1", 31000, 5000)
    steps.append({
        "action": "handoffQuiesce", "handoff": "h-1", "leaseMillis": 31000, "at": 5000,
        "note": "`MOVE-336`: the lease is at `quiesceLeaseMarginMillis` plus "
                "`commitDeadlineMillis`, so it is a failed quiesce rather than a call to repeat",
        "expect": {"outcome": outcome, "state": short.state("h-1")},
    })
    outcome = advance(short, "h-1", "rollbackSuccess", at=6000)
    steps.append({"action": "handoffStep", "handoff": "h-1", "trigger": "rollbackSuccess",
                  "at": 6000,
                  "expect": {"outcome": outcome, "state": short.state("h-1")}})
    steps.append({"action": "expectSummary", "expect": short.summary()})

    register("quiesce-lease-expiry",
             "A quiesce lease runs out before the coordinator reaches `commitCutover`, so the "
             "commit is refused and a fresh quiesce restores the window.  A lease too short to "
             "carry the commit deadline and the margin is a failed quiesce.",
             ["MOVE-021", "MOVE-311", "MOVE-331", "MOVE-332", "MOVE-333", "MOVE-336",
              "CFG-050", "CFG-055"], steps,
             setup=plan_setup(plan))


def build_node_dies_scenario():
    """The destination dies mid-transfer: retries exhaust, compensation runs, ownership stays."""
    plan = one_handoff()
    steps = list(drive(plan, ["admittedByRatePolicy", "prepareSuccess"]))

    health = HealthView(placement_set=["n1", "n2", "n3", "n4"])
    at = 10000
    for index in range(6):
        at = 10000 + index * 500
        health.report("n4", "timeout", at)
        steps.append({"action": "reportHealth", "node": "n4", "outcome": "timeout", "at": at,
                      "expect": {"state": health.state_of("n4")}})
    steps.append({"action": "expectHealth", "node": "n4",
                  "expect": {"state": health.state_of("n4"),
                             "attemptable": health.state_of("n4") != "unavailable"}})

    steps.extend(drive(plan, ["attemptsExhausted"], at_base=at + 1000))
    steps.extend(drive(plan, ["rollbackSuccess"], at_base=at + 3000))
    steps.append({
        "action": "routeInView", "view": "source",
        "topology": "topologies/migration-epoch-1.topology.json",
        "key": key_spec(PROBE_KEY, "base16"),
        "note": "`MOVE-421`: the source is untouched by the abort, so the shard is still served",
        "expect": route_expect(SNAP["migration-epoch-1"], PROBE_KEY),
    })
    steps.append({"action": "expectSummary", "expect": plan.summary()})
    register("node-dies-mid-migration",
             "The destination stops answering during `transferring`.  The health view ejects it, "
             "the step attempts exhaust, the handoff compensates, and the source keeps ownership "
             "because no cutover record was committed.",
             ["HEALTH-005", "HEALTH-041", "HEALTH-043", "HEALTH-048", "MOVE-021", "MOVE-171",
              "MOVE-421", "MOVE-481", "MOVE-491"], steps,
             setup=plan_setup(plan))


def build_abort_during_catchup_scenario():
    plan = one_handoff()
    steps = list(drive(plan, ["admittedByRatePolicy", "prepareSuccess", "noBulkRemaining"]))
    outcome = plan.abort("h-1", "operator requested", at=9000)
    steps.append({"action": "handoffAbort", "handoff": "h-1", "reason": "operator requested",
                  "at": 9000, "expect": {"outcome": outcome, "state": plan.state("h-1")}})
    repeat = plan.abort("h-1", "operator requested again", at=9500)
    steps.append({"action": "handoffAbort", "handoff": "h-1", "reason": "again", "at": 9500,
                  "note": "`MOVE-491`: an abort is idempotent",
                  "expect": {"outcome": repeat, "state": plan.state("h-1")}})
    steps.extend(drive(plan, ["rollbackSuccess"], at_base=10000))
    steps.append({"action": "expectSummary", "expect": plan.summary()})
    register("abort-during-catching-up",
             "An abort requested in `catchingUp` moves the handoff to `aborting` and runs "
             "compensation.  A second abort has no further effect.",
             ["MOVE-021", "MOVE-421", "MOVE-491"], steps,
             setup=plan_setup(plan))


def build_coordinator_death_scenarios():
    """`MOVE-211`: recovery from each non-terminal state through the observation mapping."""
    steps = []
    reached = [
        ("preparing", ["admittedByRatePolicy"]),
        ("transferring", ["admittedByRatePolicy", "prepareSuccess"]),
        ("catchingUp", ["admittedByRatePolicy", "prepareSuccess", "noBulkRemaining"]),
        ("cutover", ["admittedByRatePolicy", "prepareSuccess", "noBulkRemaining",
                     "residueAtOrBelowThreshold"]),
        ("verifying", HAPPY_PATH[:5]),
        ("cleanup", HAPPY_PATH[:6]),
    ]
    observations = ["noRecordDestinationNotPrepared", "noRecordDestinationPrepared",
                    "noRecordSourceQuiesced", "recordBelongingToHandoff",
                    "recordNotBelongingToHandoff"]
    for died_in, triggers in reached:
        for observation in observations:
            plan = one_handoff()
            for index, trigger in enumerate(triggers):
                advance(plan, "h-1", trigger, at=1000 + index * 100)
            assert plan.state("h-1") == died_in
            report = plan.recover({"h-1": observation}, at=20000)
            steps.append({
                "action": "coordinatorRestart",
                "diedInState": died_in,
                "triggersBeforeDeath": triggers,
                "observation": observation,
                "at": 20000,
                "note": "`MOVE-221`: the plan is rebuilt from the source snapshot and the "
                        "latest snapshot it was rebased onto, and `recover` runs before the "
                        "first `step`",
                "expect": {"resumedState": plan.state("h-1"), "report": report},
            })

    # `MOVE-231`: an `unavailable` answer is retried across successive `recover` calls, and
    # only a handoff in `cutover` whose attempts are spent reaches `failed`.
    plan = one_handoff()
    for index, trigger in enumerate(HAPPY_PATH[:4]):
        advance(plan, "h-1", trigger, at=1000 + index * 100)
    assert plan.state("h-1") == "cutover"
    for attempt in range(1, 6):
        at = 30000 + attempt * 1000
        report = plan.recover({"h-1": "unavailable"}, at=at)
        steps.append({
            "action": "coordinatorRestart",
            "diedInState": "cutover",
            "triggersBeforeDeath": HAPPY_PATH[:4],
            "observation": "unavailable",
            "observeAttempt": attempt,
            "at": at,
            "note": "`MOVE-231`: an `unavailable` observation is retried under "
                    "`maxAttemptsPerStep` with the backoff of `RATE-051`, and the handoff stays "
                    "where it is until the attempts are spent",
            "expect": {"resumedState": plan.state("h-1"), "report": report,
                       "failureKind": plan.handoffs["h-1"].failure_kind},
        })
    assert plan.state("h-1") == "failed"

    # A definite `undetermined` answer fails a handoff in `cutover` on the first call.
    plan = one_handoff()
    for index, trigger in enumerate(HAPPY_PATH[:4]):
        advance(plan, "h-1", trigger, at=1000 + index * 100)
    report = plan.recover({"h-1": "undetermined"}, at=40000)
    steps.append({
        "action": "coordinatorRestart",
        "diedInState": "cutover",
        "triggersBeforeDeath": HAPPY_PATH[:4],
        "observation": "undetermined",
        "at": 40000,
        "note": "`MOVE-112`: `undetermined` states that the durable state was read and does not "
                "establish whether a record exists, which is not the same answer as "
                "`unavailable`",
        "expect": {"resumedState": plan.state("h-1"), "report": report,
                   "failureKind": plan.handoffs["h-1"].failure_kind},
    })

    # An `unavailable` answer for a handoff short of `cutover` never fails it.
    plan = one_handoff()
    advance(plan, "h-1", "admittedByRatePolicy", at=1000)
    advance(plan, "h-1", "prepareSuccess", at=1100)
    for attempt in range(1, 6):
        report = plan.recover({"h-1": "unavailable"}, at=50000 + attempt * 1000)
    steps.append({
        "action": "coordinatorRestart",
        "diedInState": "transferring",
        "triggersBeforeDeath": ["admittedByRatePolicy", "prepareSuccess"],
        "observation": "unavailable",
        "observeAttempt": 5,
        "at": 55000,
        "note": "`MOVE-231`: a handoff outside `cutover` whose attempts are spent stays in the "
                "state it held and is reported as unresolved",
        "expect": {"resumedState": plan.state("h-1"), "report": report,
                   "failureKind": plan.handoffs["h-1"].failure_kind},
    })

    register("coordinator-death-and-recovery",
             "The coordinator dies in each non-terminal state and rebuilds its plan.  The "
             "resumed state is a function of the integrator's durable observation, never of the "
             "coordinator's own record.  An observation that could not be taken is retried "
             "rather than treated as an answer.",
             ["MOVE-201", "MOVE-211", "MOVE-212", "MOVE-221", "MOVE-231", "MOVE-232",
              "MOVE-112", "MOVE-151", "MOVE-161", "RATE-051"], steps,
             setup=plan_setup(plan))


def build_failure_kind_scenarios():
    """One handoff reaching `failed` with each of the four kinds of `MOVE-011`."""
    steps = []
    recipes = [
        ("unverified", HAPPY_PATH[:5] + ["verifyMismatch"],
         "`MOVE-451`: the source copy stays in place and `cleanup` never runs for it"),
        ("residue", HAPPY_PATH[:6] + ["attemptsExhausted"],
         "`MOVE-461`: ownership moved correctly and a copy is left at the source"),
        ("undetermined", HAPPY_PATH[:4] + ["commitUndetermined"],
         "`MOVE-231`: the outcome of `commitCutover` was not established by the deadline"),
        ("rollbackFailed", ["admittedByRatePolicy", "prepareSuccess", "abort",
                            "attemptsExhausted"],
         "compensation itself failed, so an operator releases the destination's partial copy"),
    ]
    for kind, triggers, note in recipes:
        plan = one_handoff()
        for index, trigger in enumerate(triggers):
            advance(plan, "h-1", trigger, at=1000 + index * 100)
        handoff = plan.handoffs["h-1"]
        assert handoff.state == "failed", (kind, handoff.state)
        assert handoff.failure_kind == kind, (kind, handoff.failure_kind)
        steps.append({
            "action": "driveToFailure",
            "failureKind": kind,
            "triggers": triggers,
            "note": note,
            "expect": {
                "state": handoff.state,
                "failureKind": handoff.failure_kind,
                "condition": {"code": 403, "name": "handoffFailed", "cause": kind},
                "history": handoff.history,
                "terminal": True,
            },
        })
        further = None
        try:
            advance(plan, "h-1", "cleanupSuccess", at=99000)
        except Exception as exc:                       # `MOVE-031`: terminal states are terminal
            further = type(exc).__name__
        steps.append({"action": "expectTerminal", "failureKind": kind,
                      "expect": {"furtherTransitionRefused": True, "error": further}})

    register("handoff-failure-kinds",
             "Each of the four failure kinds of `MOVE-011`, with the trigger sequence that "
             "reaches it and the condition it raises.  A terminal state admits no further "
             "transition.",
             ["MOVE-011", "MOVE-021", "MOVE-031", "MOVE-231", "MOVE-451", "MOVE-461",
              "ERR-052", "ERR-053"], steps,
             setup=plan_setup(plan))


def build_supersession_scenario():
    plan = Plan(1, 2, "migration", [
        Handoff("h-a", "1", "n2", "n4"),
        Handoff("h-b", "2", "n3", "n1"),
        Handoff("h-c", "0", "n1", "n3"),
    ])
    plan.policy["maxConcurrentHandoffs"] = 4
    steps = []
    for handoff_id, triggers in [("h-a", HAPPY_PATH[:5]),
                                 ("h-b", ["admittedByRatePolicy", "prepareSuccess"]),
                                 ("h-c", [])]:
        for index, trigger in enumerate(triggers):
            outcome = advance(plan, handoff_id, trigger, at=1000 + index * 100)
            steps.append({"action": "handoffStep", "handoff": handoff_id, "trigger": trigger,
                          "at": 1000 + index * 100,
                          "expect": {"outcome": outcome, "state": plan.state(handoff_id)}})
    # A comparable epoch above the target marks the plan rather than superseding it, and the
    # mark holds `step` short of any new work under `MOVE-093`.
    result = plan.on_snapshot_installed("migration", 4, at=50000)
    steps.append({
        "action": "snapshotInstalled",
        "topologyId": "migration", "epoch": 4, "at": 50000,
        "note": "`MOVE-091`: a comparable epoch above the plan's target marks the plan rebase "
                "pending and aborts nothing",
        "expect": {"result": result, "states": {k: v.state for k, v in
                                                sorted(plan.handoffs.items())}},
    })
    outcome = advance(plan, "h-c", "admittedByRatePolicy", at=50100)
    steps.append({"action": "handoffStep", "handoff": "h-c",
                  "trigger": "admittedByRatePolicy", "at": 50100,
                  "note": "`MOVE-093`: no handoff leaves `planned` while a rebase is pending",
                  "expect": {"outcome": outcome, "state": plan.state("h-c")}})

    # A foreign identifier supersedes, because two identifiers are not comparable at all.
    result = plan.on_snapshot_installed("some-other-cluster", 9, at=60000)
    steps.append({
        "action": "snapshotInstalled",
        "topologyId": "some-other-cluster", "epoch": 9, "at": 60000,
        "note": "`MOVE-091`: a differing `topologyId` supersedes the plan.  Handoffs at "
                "`cutover` or beyond run to a terminal state.",
        "expect": {"result": result, "states": {k: v.state for k, v in
                                                sorted(plan.handoffs.items())}},
    })
    steps.append({"action": "expectSummary", "expect": plan.summary()})
    register("plan-superseded-by-new-epoch",
             "A third epoch arrives while three handoffs are in flight.  A comparable epoch "
             "marks the plan rebase pending and aborts nothing; an incomparable one supersedes "
             "it, pre-cutover handoffs abort, and the handoff past cutover runs on.",
             ["MOVE-091", "MOVE-092", "MOVE-093", "MOVE-101", "MOVE-481", "TOPO-111",
              "TOPO-131", "TOPO-231"], steps,
             setup=plan_setup(plan))


def build_concurrency_scenario():
    plan = Plan(1, 2, "migration", [
        Handoff("h-a", "0", "n1", "n4"),
        Handoff("h-b", "1", "n1", "n3"),
    ])
    steps = []
    outcome = advance(plan, "h-a", "admittedByRatePolicy", at=1000)
    steps.append({"action": "handoffStep", "handoff": "h-a",
                  "trigger": "admittedByRatePolicy", "at": 1000,
                  "expect": {"outcome": outcome, "state": plan.state("h-a")}})
    outcome = advance(plan, "h-b", "admittedByRatePolicy", at=1100)
    steps.append({"action": "handoffStep", "handoff": "h-b",
                  "trigger": "admittedByRatePolicy", "at": 1100,
                  "note": "`maxConcurrentPerSourceNode` defaults to 1 and both handoffs leave n1",
                  "expect": {"outcome": outcome, "state": plan.state("h-b")}})
    for level in ["soft", "hard"]:
        outcome = advance(plan, "h-b", "admittedByRatePolicy", at=1200, pressure=level)
        steps.append({"action": "handoffStep", "handoff": "h-b",
                      "trigger": "admittedByRatePolicy", "at": 1200, "pressure": level,
                      "note": "`RATE-081` and `RATE-091`: neither level admits a handoff out of "
                              "`planned`",
                      "expect": {"outcome": outcome, "state": plan.state("h-b")}})
    outcome = advance(plan, "h-a", "prepareSuccess", at=1300, pressure="hard")
    steps.append({"action": "handoffStep", "handoff": "h-a", "trigger": "prepareSuccess",
                  "at": 1300, "pressure": "hard",
                  "note": "`RATE-091` withholds `transfer` and `catchUp` and no other hook",
                  "expect": {"outcome": outcome, "state": plan.state("h-a")}})
    outcome = advance(plan, "h-a", "noBulkRemaining", at=1400, pressure="hard")
    steps.append({"action": "handoffStep", "handoff": "h-a", "trigger": "noBulkRemaining",
                  "at": 1400, "pressure": "hard",
                  "expect": {"outcome": outcome, "state": plan.state("h-a")}})
    register("migration-rate-control",
             "Concurrency bounds and backpressure decide what a `step` admits.  Neither pressure "
             "level moves a handoff out of `planned`, and `hard` withholds only the two bulk "
             "hooks.",
             ["RATE-001", "RATE-011", "RATE-071", "RATE-081", "RATE-091", "RATE-111",
              "MOVE-071"], steps,
             setup=plan_setup(plan))


# ------------------------------------------------------------------------ failover

def build_failover_scenario():
    snapshot = SNAP["migration-epoch-1"]
    decision = routing.route(snapshot, PROBE_KEY)
    preference = decision["preferenceList"]
    health = HealthView(placement_set=placement_identities(snapshot))
    steps = [{
        "action": "route",
        "topology": "topologies/migration-epoch-1.topology.json",
        "key": key_spec(PROBE_KEY, "base16"),
        "expect": route_expect(snapshot, PROBE_KEY),
    }]

    sequence, failed_open = health.attempt_sequence(preference)
    steps.append({"action": "attemptSequence", "key": key_spec(PROBE_KEY, "base16"),
                  "note": "`HEALTH-004`: no signal has been ingested, so nothing is filtered",
                  "expect": {"attemptSequence": [e["node"] for e in sequence],
                             "filterFailedOpen": failed_open}})

    primary = preference[0]["node"]
    at = 0
    for index in range(5):
        at = 1000 + index * 200
        health.report(primary, "failure", at)
        steps.append({"action": "reportHealth", "node": primary, "outcome": "failure", "at": at,
                      "expect": {"state": health.state_of(primary)}})

    sequence, failed_open = health.attempt_sequence(preference)
    steps.append({
        "action": "attemptSequence", "key": key_spec(PROBE_KEY, "base16"),
        "note": "`FAIL-002`: the filter removes entries and never reorders them, so the primary "
                "of the decision is unchanged",
        "expect": {"attemptSequence": [e["node"] for e in sequence],
                   "filterFailedOpen": failed_open,
                   "primaryUnchanged": preference[0]["node"] == primary,
                   "healthOfPrimary": health.state_of(primary)},
    })

    # The second replica fails too, and the ejection ceiling refuses its ejection.  Every signal
    # below carries a step of its own: a scenario states what it depends on, and a driver replaying
    # the steps reaches the state the expectations were computed against.
    for entry in preference[1:]:
        for index in range(5):
            health.report(entry["node"], "refused", at + 100 + index * 10)
        steps.append({
            "action": "reportHealthSeries", "node": entry["node"],
            "outcomes": ["refused"] * 5, "firstAt": at + 100, "stepMillis": 10,
            "note": "`HEALTH-022`: `refused` counts as a failure.",
            "expect": {"state": health.state_of(entry["node"])},
        })
    health.advance(at + 500)
    steps.append({
        "action": "advanceClock", "to": at + 500,
        "note": "`HEALTH-047`: timers are evaluated on each advance as well as on each signal.",
        "expect": {"states": {e["node"]: health.state_of(e["node"]) for e in preference}},
    })
    sequence, failed_open = health.attempt_sequence(preference)
    steps.append({
        "action": "attemptSequence", "key": key_spec(PROBE_KEY, "base16"),
        "at": at + 500,
        "note": "`HEALTH-034`: the placement set holds three nodes, so a second ejection would "
                "take the ejected share above `maxEjectionPercent` and is refused.  The refused "
                "node holds `suspect`, which is attemptable.",
        "expect": {"attemptSequence": [e["node"] for e in sequence],
                   "filterFailedOpen": failed_open,
                   "placementSetSize": len(snapshot.placement_set),
                   "ejectionsRefused": [e for e in health.events
                                        if e["event"] == "sharder.health.ejection_refused"],
                   "states": {e["node"]: health.state_of(e["node"]) for e in preference}},
    })

    # Recovery: the ejection interval elapses, probation admits one probe in `probationDivisor`.
    recovered_at = at + 500 + 30000
    health.advance(recovered_at)
    steps.append({"action": "advanceClock", "to": recovered_at,
                  "note": "`HEALTH-044`: `baseEjectionMillis` elapses and the node reaches "
                          "`probation`",
                  "expect": {"states": {e["node"]: health.state_of(e["node"])
                                        for e in preference}}})
    admissions = [health.admit_probe(primary) for _ in range(20)]
    steps.append({"action": "probeAdmission", "node": primary, "calls": 20,
                  "note": "`HEALTH-051`: one attempt in `probationDivisor` is admitted",
                  "expect": {"admitted": admissions,
                             "admittedCount": sum(1 for a in admissions if a)}})
    healed_at = recovered_at + 30000
    health.advance(healed_at)
    steps.append({"action": "advanceClock", "to": healed_at,
                  "note": "`HEALTH-045`: `probationMillis` elapses with no failure",
                  "expect": {"states": {e["node"]: health.state_of(e["node"])
                                        for e in preference}}})

    register("failover-and-recovery",
             "A replica fails, is ejected, is probed under probation, and returns.  The "
             "preference list never changes; only the attempt sequence does.",
             ["FAIL-001", "FAIL-002", "FAIL-003", "FAIL-004", "FAIL-012", "FAIL-013",
              "HEALTH-004", "HEALTH-005", "HEALTH-020", "HEALTH-023", "HEALTH-024",
              "HEALTH-040", "HEALTH-041", "HEALTH-043", "HEALTH-044", "HEALTH-045",
              "HEALTH-050", "HEALTH-051", "PROP-041", "PROP-045"], steps)


def build_filter_fails_open_scenario():
    """`FAIL-012`: every entry skipped yields the whole preference list, not an empty sequence."""
    document = {
        "formatVersion": "1.0", "topologyId": "fail-open", "epoch": 1,
        "replication": {"factor": 2},
        "strategy": {"kind": "rendezvous"},
        "nodes": [{"id": "f%d" % i} for i in range(6)],
    }
    path = topology("fail-open", document)
    snapshot = Snapshot(document)
    key = b"fail-open-probe"
    decision = routing.route(snapshot, key)
    preference = decision["preferenceList"]
    replicas = [e["node"] for e in preference[:decision["replicaCount"]]]

    health = HealthView(placement_set=placement_identities(snapshot))
    steps = [{"action": "route", "topology": path, "key": key_spec(key),
              "expect": route_expect(snapshot, key)}]

    at = 0
    for node in replicas:
        for _ in range(5):
            at += 100
            health.report(node, "failure", at)
        steps.append({"action": "reportHealth", "node": node, "outcome": "failure",
                      "repeat": 5, "at": at,
                      "expect": {"state": health.state_of(node)}})

    truncated = preference[:len(replicas)]
    sequence, failed_open = health.attempt_sequence(truncated)
    steps.append({
        "action": "attemptSequence", "key": key_spec(key),
        "preferenceListPrefix": len(replicas),
        "note": "`FAIL-012`: every entry of this list is `unavailable`, so the filter fails open "
                "and the attempt sequence is the whole list in preference list order.  The "
                "decision records that it failed open.",
        "expect": {"attemptSequence": [e["node"] for e in sequence],
                   "filterFailedOpen": failed_open,
                   "placementSetSize": len(snapshot.placement_set),
                   "states": {e["node"]: health.state_of(e["node"]) for e in truncated}},
    })
    register("health-filter-fails-open",
             "Every replica of a shard is ejected.  The health filter returns the whole "
             "preference list rather than nothing, and the decision records that it failed open.",
             ["FAIL-002", "FAIL-012", "FAIL-013", "HEALTH-005", "HEALTH-034", "OBS-010"], steps)


def build_ejection_ceiling_scenario():
    snapshot = SNAP["migration-epoch-1"]
    health = HealthView(placement_set=placement_identities(snapshot))
    steps = []
    at = 0
    for node in ["n1", "n2", "n3"]:
        for index in range(6):
            at += 100
            health.report(node, "failure", at)
        steps.append({"action": "reportHealth", "node": node, "outcome": "failure",
                      "repeat": 6, "at": at,
                      "expect": {"state": health.state_of(node),
                                 "states": {n: health.state_of(n)
                                            for n in sorted(health.nodes)}}})
    steps.append({
        "action": "expectHealthCeiling",
        "note": "`HEALTH-034`: a transition into `unavailable` is refused where it would take "
                "the ejected count above `maxEjectionPercent` of the placement set, and the "
                "refused node holds `suspect`",
        "expect": {
            "placementSetSize": len(snapshot.placement_set),
            "maxEjectionPercent": 50,
            "states": {n: health.state_of(n) for n in sorted(health.nodes)},
            "ejectedCount": sum(1 for n in health.nodes.values()
                                if n.state == "unavailable"),
            "refusals": [e for e in health.events
                         if e["event"] == "sharder.health.ejection_refused"],
        },
    })
    register("ejection-ceiling",
             "Every node of the placement set fails.  The ceiling refuses the third ejection so that a "
             "correlated failure cannot empty the cluster's attemptable set.",
             ["HEALTH-025", "HEALTH-034", "HEALTH-043", "SEC-031"], steps,
             # `HEALTH-034` counts the ejected against the placement set `HEALTH-016` delivers, and
             # every step below turns on it.  No step of this scenario installs a snapshot, so the
             # setup names the one the expectations were computed against; a driver that ignored it
             # would hold no placement set and refuse no ejection.
             setup={"topology": "topologies/migration-epoch-1.topology.json"})


# ------------------------------------------------------------------- rebase across an epoch

FLEET_PATH = "topologies/rebalance-epoch-%d.topology.json"


def fleet_plan():
    """The plan the delta between epoch 10 and epoch 11 produces."""
    rows = ownership_delta(FLEET["rebalance-epoch-10"], FLEET["rebalance-epoch-11"],
                           lambda s: s.factor)
    handoffs = []
    for row in rows:
        for gained in row["gained"]:
            for lost in row["lost"]:
                handoffs.append(Handoff("h-%s" % row["shard"], row["shard"], lost, gained))
    return Plan(10, 11, "rebalance", handoffs), rows


def fleet_step(plan, steps, handoff_id, triggers, at_base):
    for index, trigger in enumerate(triggers):
        at = at_base + index * 100
        if trigger in COMMIT_TRIGGERS and plan.state(handoff_id) == "cutover":
            steps.append(quiesce_step(plan, handoff_id, at - 50))
        outcome = advance(plan, handoff_id, trigger, at=at)
        steps.append({"action": "handoffStep", "handoff": handoff_id, "trigger": trigger,
                      "at": at, "expect": {"outcome": outcome, "state": plan.state(handoff_id)}})


def build_rebase_survives_scenario():
    plan, rows = fleet_plan()
    steps = [{
        "action": "ownershipDelta",
        "from": FLEET_PATH % 10, "to": FLEET_PATH % 11,
        "expect": {"shardsChanged": len(rows), "delta": rows},
    }, {
        "action": "plan",
        "from": FLEET_PATH % 10, "to": FLEET_PATH % 11,
        "policy": plan.policy,
        "expect": {"handoffs": [{"id": h.id, "shard": h.shard, "source": h.source,
                                 "destination": h.destination, "state": h.state,
                                 "targetEpoch": h.target_epoch}
                                for h in sorted(plan.handoffs.values(), key=lambda x: x.id)],
                   "rebaseInterval": plan.rebase_interval()},
    }]
    fleet_step(plan, steps, "h-0", ["admittedByRatePolicy", "prepareSuccess",
                                     "noBulkRemaining"], 1000)
    fleet_step(plan, steps, "h-1", ["admittedByRatePolicy", "prepareSuccess"], 2000)

    # A node joins.  Nothing is reassigned, and today's rule would abort both handoffs.
    result = plan.on_snapshot_installed("rebalance", 12, at=10000)
    steps.append({
        "action": "snapshotInstalled",
        "topology": FLEET_PATH % 12, "topologyId": "rebalance", "epoch": 12, "at": 10000,
        "note": "`MOVE-091` and `MOVE-092`: the epoch is comparable and above the plan's "
                "target, so the plan is marked rebase pending, no handoff is aborted, and no "
                "preference list is evaluated",
        "expect": {"result": result, "states": {k: v.state for k, v in
                                                sorted(plan.handoffs.items())}},
    })
    outcome = advance(plan, "h-0", "residueAtOrBelowThreshold", at=10100)
    steps.append({"action": "handoffStep", "handoff": "h-0",
                  "trigger": "residueAtOrBelowThreshold", "at": 10100,
                  "note": "`MOVE-093`: a rebase-pending plan admits no handoff into `cutover`",
                  "expect": {"outcome": outcome, "state": plan.state("h-0")}})

    sets = replica_sets(FLEET["rebalance-epoch-12"])
    result = plan.rebase("rebalance", 12, sets, at=11000)
    steps.append({
        "action": "rebase",
        "to": FLEET_PATH % 12, "at": 11000,
        "replicaSets": sets,
        "note": "`MOVE-096`: every handoff's shard, source, and destination still hold under "
                "epoch 12, so every one of them rebases and keeps the state it was in",
        "expect": {"result": result,
                   "states": {k: v.state for k, v in sorted(plan.handoffs.items())},
                   "targetEpochs": {k: v.target_epoch
                                    for k, v in sorted(plan.handoffs.items())}},
    })

    # The rebalance finishes under the epoch that arrived after it started.
    fleet_step(plan, steps, "h-0", ["residueAtOrBelowThreshold", "cutoverCommitted",
                                     "verifySuccess", "cleanupSuccess"], 12000)
    fleet_step(plan, steps, "h-1", ["noBulkRemaining", "residueAtOrBelowThreshold",
                                     "cutoverCommitted", "verifySuccess", "cleanupSuccess"],
               13000)
    steps.append({"action": "expectSummary", "expect": plan.summary()})
    register("rebalance-survives-unrelated-epoch",
             "A node joins the fleet while a two-shard rebalance is in flight.  The plan is "
             "marked rebase pending rather than superseded, admits no new work until the "
             "integrator rebases it, and then finishes under the newer epoch.",
             ["TOPO-211", "MOVE-091", "MOVE-092", "MOVE-093", "MOVE-094", "MOVE-096",
              "MOVE-097", "MOVE-101", "MOVE-102", "MOVE-103", "RATE-001"], steps)


def build_rebase_drops_handoff_scenario():
    plan, _ = fleet_plan()
    steps = []
    fleet_step(plan, steps, "h-0", ["admittedByRatePolicy", "prepareSuccess"], 1000)
    fleet_step(plan, steps, "h-1", ["admittedByRatePolicy", "prepareSuccess",
                                     "noBulkRemaining"], 2000)

    # Three refusals, before any admissible rebase.
    for label, topology_id, epoch, comparable, note in [
        ("foreignIdentifier", "some-other-cluster", 13, True,
         "`MOVE-095`: a differing `topologyId` refuses with `topologyMismatch`"),
        ("incomparableShards", "rebalance", 13, False,
         "`MOVE-095`: `TOPO-231` gates a rebase exactly as it gates a plan"),
        ("epochNotAdvancing", "rebalance", 10, True,
         "`MOVE-095`: a target at or below the plan's source epoch refuses"),
    ]:
        refused = plan.rebase(topology_id, epoch, {}, at=5000, comparable=comparable)
        steps.append({
            "action": "rebase", "name": label, "topologyId": topology_id, "epoch": epoch,
            "comparable": comparable, "at": 5000, "note": note,
            "expect": {"result": refused,
                       "states": {k: v.state for k, v in sorted(plan.handoffs.items())}},
        })

    result = plan.on_snapshot_installed("rebalance", 13, at=6000)
    steps.append({
        "action": "snapshotInstalled",
        "topology": FLEET_PATH % 13, "topologyId": "rebalance", "epoch": 13, "at": 6000,
        "expect": {"result": result, "states": {k: v.state for k, v in
                                               sorted(plan.handoffs.items())}},
    })

    sets = replica_sets(FLEET["rebalance-epoch-13"])
    result = plan.rebase("rebalance", 13, sets, at=7000)
    steps.append({
        "action": "rebase",
        "to": FLEET_PATH % 13, "at": 7000,
        "replicaSets": sets,
        "note": "`MOVE-096`: epoch 13 returns shard `1` to its source, so that handoff's triple "
                "no longer holds and `MOVE-098` compensates it while shard `0` rebases",
        "expect": {"result": result,
                   "states": {k: v.state for k, v in sorted(plan.handoffs.items())},
                   "targetEpochs": {k: v.target_epoch
                                    for k, v in sorted(plan.handoffs.items())}},
    })
    fleet_step(plan, steps, "h-1", ["rollbackSuccess"], 8000)
    steps.append({
        "action": "routeInView", "view": "target",
        "topology": FLEET_PATH % 13,
        "key": key_spec(bytes([0x50]), "base16"),
        "note": "`MOVE-098` and `MOVE-421`: the source of the aborted handoff is untouched, and "
                "epoch 13 routes the key to it",
        "expect": route_expect(FLEET["rebalance-epoch-13"], bytes([0x50])),
    })
    fleet_step(plan, steps, "h-0", ["noBulkRemaining", "residueAtOrBelowThreshold",
                                     "cutoverCommitted", "verifySuccess", "cleanupSuccess"],
               9000)
    steps.append({"action": "expectSummary", "expect": plan.summary()})
    register("rebase-drops-a-handoff",
             "An epoch arrives that reverses one of two moves in flight.  The handoff whose "
             "shard, source, and destination no longer hold is aborted and compensated; the "
             "other rebases and completes.  Three refusals cover the rebase preconditions.",
             ["MOVE-094", "MOVE-095", "MOVE-096", "MOVE-097", "MOVE-098", "MOVE-099",
              "MOVE-102", "MOVE-421", "MOVE-481", "TOPO-231", "ERR-050"], steps,
             setup=plan_setup(plan))


# ------------------------------------------------- re-observation after an undetermined cutover

def record(owner, epoch, shard="1"):
    return {"shardId": shard, "topologyId": "migration", "epoch": epoch, "owner": owner,
            "opaque": ""}


def observation(cutover_record=None, prepared=True, quiesced=True, residue=False):
    return {"cutoverRecord": cutover_record, "destinationPrepared": prepared,
            "sourceQuiesced": quiesced, "sourceResidue": residue}


def to_undetermined(plan, steps, at_base):
    """`MOVE-021`: drive the single handoff to `failed` with the kind `undetermined`."""
    for index, trigger in enumerate(HAPPY_PATH[:4] + ["commitUndetermined"]):
        at = at_base + index * 100
        outcome = advance(plan, "h-1", trigger, at=at)
        steps.append({"action": "handoffStep", "handoff": "h-1", "trigger": trigger, "at": at,
                      "expect": {"outcome": outcome, "state": plan.state("h-1")}})


def build_undetermined_recovery_scenario():
    steps = []

    # The cutover did land.  A later observation says so, and the handoff completes.
    plan = one_handoff()
    to_undetermined(plan, steps, 1000)
    outcome = plan.reobserve("h-1", observation(record("n4", 2)), at=20000)
    steps.append({
        "action": "reobserve", "handoff": "h-1", "at": 20000,
        "observation": observation(record("n4", 2)),
        "note": "`MOVE-234`: the record belongs to the handoff under `MOVE-102`, so the mapping "
                "of `MOVE-211` resumes it at `verifying`",
        "expect": {"outcome": outcome, "state": plan.state("h-1"),
                   "targetEpoch": plan.handoffs["h-1"].target_epoch},
    })
    for index, trigger in enumerate(["verifySuccess", "cleanupSuccess"]):
        at = 21000 + index * 100
        result = advance(plan, "h-1", trigger, at=at)
        steps.append({"action": "handoffStep", "handoff": "h-1", "trigger": trigger, "at": at,
                      "note": "`MOVE-236`: `cleanup` still never precedes a successful `verify`",
                      "expect": {"outcome": result, "state": plan.state("h-1")}})
    steps.append({"action": "expectSummary", "name": "recordExisted", "expect": plan.summary()})

    # The cutover did not land.  The store is silent once, then answers, and the handoff
    # re-enters `cutover`, where `MOVE-331` requires a fresh `quiesce`.
    plan = one_handoff()
    to_undetermined(plan, steps, 30000)
    outcome = plan.reobserve("h-1", "unavailable", at=31000)
    steps.append({
        "action": "reobserve", "handoff": "h-1", "at": 31000, "observation": "unavailable",
        "note": "`MOVE-234`: an `unavailable` answer leaves the handoff where it is and "
                "`MOVE-238` makes the call free of effect",
        "expect": {"outcome": outcome, "state": plan.state("h-1"),
                   "failureKind": plan.handoffs["h-1"].failure_kind},
    })
    outcome = plan.reobserve("h-1", observation(None, quiesced=True), at=32000)
    steps.append({
        "action": "reobserve", "handoff": "h-1", "at": 32000,
        "observation": observation(None, quiesced=True),
        "note": "`MOVE-236`: an observation reporting no record resumes the handoff at "
                "`cutover`, and `MOVE-331` requires a fresh successful `quiesce` before the "
                "next `commitCutover`",
        "expect": {"outcome": outcome, "state": plan.state("h-1")},
    })
    steps.append(quiesce_step(plan, "h-1", 32500))
    for index, trigger in enumerate(["cutoverCommitted", "verifySuccess", "cleanupSuccess"]):
        at = 33000 + index * 100
        result = advance(plan, "h-1", trigger, at=at)
        steps.append({"action": "handoffStep", "handoff": "h-1", "trigger": trigger, "at": at,
                      "expect": {"outcome": result, "state": plan.state("h-1")}})
    steps.append({"action": "expectSummary", "name": "noRecord", "expect": plan.summary()})

    # Another destination won.  The observation says so and the handoff compensates.
    plan = one_handoff()
    to_undetermined(plan, steps, 40000)
    outcome = plan.reobserve("h-1", observation(record("n3", 2)), at=41000)
    steps.append({
        "action": "reobserve", "handoff": "h-1", "at": 41000,
        "observation": observation(record("n3", 2)),
        "note": "`MOVE-102`: the record names another owner, so it does not belong to this "
                "handoff and `MOVE-236` admits the compensation",
        "expect": {"outcome": outcome, "state": plan.state("h-1")},
    })
    # A record outside the rebase interval is the same case.
    plan = one_handoff()
    to_undetermined(plan, steps, 45000)
    outcome = plan.reobserve("h-1", observation(record("n4", 7)), at=46000)
    steps.append({
        "action": "reobserve", "handoff": "h-1", "at": 46000,
        "observation": observation(record("n4", 7)),
        "note": "`MOVE-102`: the plan's rebase interval is the epochs above 1 and at or below "
                "2, so a record at epoch 7 belongs to another plan",
        "expect": {"outcome": outcome, "state": plan.state("h-1"),
                   "rebaseInterval": plan.rebase_interval()},
    })

    # `MOVE-237`: the other three kinds are refused.
    for kind, triggers in [
        ("unverified", HAPPY_PATH[:5] + ["verifyMismatch"]),
        ("residue", HAPPY_PATH[:6] + ["attemptsExhausted"]),
        ("rollbackFailed", ["admittedByRatePolicy", "prepareSuccess", "abort",
                            "attemptsExhausted"]),
    ]:
        plan = one_handoff()
        for index, trigger in enumerate(triggers):
            advance(plan, "h-1", trigger, at=60000 + index * 100)
        assert plan.handoffs["h-1"].failure_kind == kind
        outcome = plan.reobserve("h-1", observation(record("n4", 2)), at=61000)
        steps.append({
            "action": "reobserve", "handoff": "h-1", "failureKind": kind, "at": 61000,
            "triggers": triggers,
            "observation": observation(record("n4", 2)),
            "note": "`MOVE-237`: each of these kinds names a duty outside the library, which a "
                    "further observation cannot discharge",
            "expect": {"outcome": outcome, "state": plan.state("h-1"),
                       "failureKind": plan.handoffs["h-1"].failure_kind},
        })

    # `MOVE-233`: a handoff that is not in `failed` is refused.
    plan = one_handoff()
    advance(plan, "h-1", "admittedByRatePolicy", at=70000)
    outcome = plan.reobserve("h-1", observation(record("n4", 2)), at=70100)
    steps.append({
        "action": "reobserve", "handoff": "h-1", "at": 70100,
        "observation": observation(record("n4", 2)),
        "note": "`MOVE-233`: a re-observation is admitted only from `failed`",
        "expect": {"outcome": outcome, "state": plan.state("h-1")},
    })

    register("undetermined-resolves-both-ways",
             "A cutover whose outcome the library never established is looked at again.  The "
             "same failure resolves to `verifying` where the record landed, to `cutover` where "
             "it did not, and to `aborting` where another destination won.  The three failure "
             "kinds that need an operator are refused.",
             ["MOVE-011", "MOVE-021", "MOVE-031", "MOVE-102", "MOVE-112", "MOVE-181",
              "MOVE-211", "MOVE-212", "MOVE-233", "MOVE-234", "MOVE-236",
              "MOVE-237", "MOVE-238", "MOVE-331", "ERR-052"], steps,
             setup=plan_setup(plan))


# ------------------------------------------------------- probation, peers, and re-entry

def build_probation_ramp_scenario():
    """`FAIL-012`, `FAIL-015`, and `HEALTH-017` over a replica set coming out of ejection."""
    document = {
        "formatVersion": "1.0", "topologyId": "probation-ramp", "epoch": 1,
        "replication": {"factor": 2},
        "strategy": {"kind": "rendezvous"},
        "nodes": [{"id": "r%d" % index} for index in range(6)],
    }
    path = topology("probation-ramp", document)
    snapshot = Snapshot(document)
    key = b"probation-ramp-probe"
    decision = routing.route(snapshot, key)
    replicas = decision["preferenceList"][:decision["replicaCount"]]
    health = HealthView(placement_set=placement_identities(snapshot))

    steps = [{"action": "route", "topology": path, "key": key_spec(key),
              "expect": route_expect(snapshot, key)}]
    at = 0
    for entry in replicas:
        for _ in range(5):
            at += 100
            health.report(entry["node"], "failure", at)
        steps.append({"action": "reportHealth", "node": entry["node"], "outcome": "failure",
                      "repeat": 5, "at": at,
                      "expect": {"state": health.state_of(entry["node"])}})

    sequence, failed_open = health.attempt_sequence(replicas)
    steps.append({
        "action": "attemptSequence", "key": key_spec(key),
        "preferenceListPrefix": len(replicas),
        "note": "`FAIL-012`: every entry is `unavailable`, so the filter fails open over the "
                "whole list",
        "expect": {"attemptSequence": [e["node"] for e in sequence],
                   "filterFailedOpen": failed_open,
                   "states": {e["node"]: health.state_of(e["node"]) for e in replicas}},
    })

    recovered_at = at + 30000
    health.advance(recovered_at)
    steps.append({"action": "advanceClock", "to": recovered_at,
                  "note": "`HEALTH-044`: `baseEjectionMillis` elapses and both entries reach "
                          "`probation`",
                  "expect": {"states": {e["node"]: health.state_of(e["node"])
                                        for e in replicas}}})

    sequence, failed_open = health.attempt_sequence(replicas)
    steps.append({
        "action": "attemptSequence", "key": key_spec(key),
        "preferenceListPrefix": len(replicas),
        "note": "`FAIL-012`: an entry in `probation` is attemptable under `HEALTH-005`, so the "
                "filter holds both entries and does not fail open while the two recover",
        "expect": {"attemptSequence": [e["node"] for e in sequence],
                   "filterFailedOpen": failed_open,
                   "states": {e["node"]: health.state_of(e["node"]) for e in replicas}},
    })

    walks = [health.walk(sequence) for _ in range(18)]
    steps.append({
        "action": "attemptWalk", "key": key_spec(key), "calls": len(walks),
        "note": "`HEALTH-017`: the walk consumes one probe for each entry in `probation` it "
                "reaches, so the walk that follows entry into `probation` attempts both entries "
                "and the next fifteen attempt the head alone under `FAIL-015`.  The two probe "
                "counters stay equal, because the entry `FAIL-015` answers consumes none.",
        "expect": {"walks": walks,
                   "probeCounters": {e["node"]: health.nodes[e["node"]].probe_counter
                                     for e in replicas}},
    })
    register("probation-ramp",
             "A replica set is ejected and reaches `probation` together.  The filter holds the "
             "entries rather than failing open, and one attempt in `probationDivisor` reaches "
             "each of them.",
             ["FAIL-002", "FAIL-003", "FAIL-012", "FAIL-015", "HEALTH-005", "HEALTH-017",
              "HEALTH-044", "HEALTH-051", "HEALTH-052", "HEALTH-053"], steps)


HEALTH_PEERS = ["p1", "p2", "p3", "p4", "p5"]
PEER_ABSENTEES = ["a1", "a2", "a3"]


def health_peers_document(epoch, leaving=()):
    return {
        "formatVersion": "1.0", "topologyId": "health-peers", "epoch": epoch,
        "replication": {"factor": 2},
        "strategy": {"kind": "rendezvous"},
        "nodes": [dict({"id": node_id}, **({"state": "leaving"} if node_id in leaving else {}))
                  for node_id in HEALTH_PEERS],
    }


def peers_topology(epoch, leaving=()):
    document = health_peers_document(epoch, leaving)
    path = topology("health-peers-epoch-%d" % epoch, document)
    return path, Snapshot(document)


def install_step(path, epoch):
    return {"action": "installTopology", "topology": path,
            "expect": {"outcome": "installed", "condition": None, "epochInForce": epoch}}


def report_series(health, steps, node_id, failures, total, first_at, note=None):
    """Report `total` signals ten milliseconds apart, failing at the indices `failures` names."""
    outcomes = ["failure" if index in failures else "success" for index in range(total)]
    for index, outcome in enumerate(outcomes):
        health.report(node_id, outcome, first_at + index * 10)
    last_at = first_at + (total - 1) * 10
    step = {"action": "reportHealthSeries", "node": node_id, "outcomes": outcomes,
            "firstAt": first_at, "stepMillis": 10,
            "expect": {"state": health.state_of(node_id),
                       "failurePercent": health.nodes[node_id].failure_percent(last_at, 30000)}}
    if note is not None:
        step["note"] = note
    steps.append(step)
    return last_at


def build_outlier_comparison_scenario():
    """`HEALTH-030`: an identity outside the placement set is ingested and is not a peer."""
    path, snapshot = peers_topology(1)
    health = HealthView(placement_set=placement_identities(snapshot))
    steps = [install_step(path, 1),
             {"action": "healthSnapshotInstalled", "topology": path,
              "placementSet": placement_identities(snapshot),
              "note": "`HEALTH-016`: the placement set reaches the health view with the "
                      "snapshot, and is the set `HEALTH-030` compares against",
              "expect": {"placementSetSize": len(snapshot.placement_set)}}]

    at = 1000
    for node_id in ["p1", "p2", "p3"]:
        at = report_series(health, steps, node_id, set(), 20, at) + 1000
    for node_id in PEER_ABSENTEES:
        at = report_series(health, steps, node_id, set(range(20)), 20, at,
                           note="`HEALTH-011`: a signal is accepted for an identity absent from "
                                "the snapshot in force") + 1000
    at = report_series(health, steps, "p4", {0, 4, 8, 12, 16, 19}, 20, at) + 1000
    last = report_series(health, steps, "p5", {0, 3, 6, 9, 12, 15, 17, 19}, 20, at,
                         note="`HEALTH-032`: `f(x)` reaches the peer median plus "
                              "`outlierMarginPercent`, so the node is ejected as an outlier")

    steps.append({
        "action": "expectComparisonSet", "at": last,
        "note": "`HEALTH-030`: the three identities absent from the placement set carry a "
                "failure percentage of 100 and enter neither the comparison set nor the median.  "
                "Over every entry the view holds, the median would be 30 and `p5` would not be "
                "an outlier.  `HEALTH-034` counts the ejected over the placement set alone, so "
                "the three absent ejections do not refuse the ejection of `p5`.",
        "expect": {"comparisonSet": health.comparison_set(last),
                   "peerMedian": health.peer_median(last),
                   "outlierMarginPercent": 30,
                   "placementSetSize": len(snapshot.placement_set),
                   "failurePercent": {node_id: health.nodes[node_id].failure_percent(last, 30000)
                                      for node_id in sorted(health.nodes)},
                   "states": {node_id: health.state_of(node_id)
                              for node_id in sorted(health.nodes)},
                   "ejectedInPlacementSet": sum(1 for node_id in placement_identities(snapshot)
                                                if health.state_of(node_id) == "unavailable")},
    })
    register("outlier-comparison-set",
             "Signals arrive for three identities the placement set does not hold.  They move "
             "neither the peer median nor the ejection ceiling, and the one node that is an "
             "outlier against its peers is ejected.",
             ["HEALTH-006", "HEALTH-011", "HEALTH-016", "HEALTH-030", "HEALTH-031",
              "HEALTH-032", "HEALTH-033", "HEALTH-034", "HEALTH-043"], steps)


def build_reentry_reset_scenario():
    """`HEALTH-007`: the health entry of an identity that leaves the placement set and returns."""
    steps = []
    epoch = 1
    for reset in [False, True]:
        steps.append({
            "action": "configureHealth",
            "parameters": {"resetOnPlacementReentry": reset},
            "note": "`CFG-030` accepts the parameters of `HEALTH-055`, and a fresh health view "
                    "holds no entry",
        })
        present, absent, returning = epoch, epoch + 1, epoch + 2
        epoch += 3
        path_present, snapshot_present = peers_topology(present)
        path_absent, snapshot_absent = peers_topology(absent, leaving=["p1"])
        path_returning, snapshot_returning = peers_topology(returning)
        health = HealthView(parameters={"resetOnPlacementReentry": reset},
                            placement_set=placement_identities(snapshot_present))
        steps.append(install_step(path_present, present))
        steps.append({"action": "healthSnapshotInstalled", "topology": path_present,
                      "placementSet": placement_identities(snapshot_present),
                      "expect": {"placementSetSize": len(snapshot_present.placement_set)}})

        at = 1000
        for index in range(6):
            at = 1000 + index * 100
            health.report("p1", "failure", at)
        steps.append({"action": "reportHealth", "node": "p1", "outcome": "failure",
                      "repeat": 6, "at": at,
                      "note": "`HEALTH-043`: the consecutive failure threshold ejects the node",
                      "expect": {"state": health.state_of("p1"),
                                 "ejectionCount": health.nodes["p1"].ejections}})

        health.on_snapshot_installed(placement_identities(snapshot_absent), at=at + 1000)
        steps.append({"action": "installTopology", "topology": path_absent,
                      "expect": {"outcome": "installed", "condition": None,
                                 "epochInForce": absent}})
        steps.append({"action": "healthSnapshotInstalled", "topology": path_absent,
                      "placementSet": placement_identities(snapshot_absent), "at": at + 1000,
                      "note": "`HEALTH-006`: the entry survives the absence and affects no "
                              "routing call while the identity is outside the placement set",
                      "expect": {"placementSetSize": len(snapshot_absent.placement_set),
                                 "state": health.state_of("p1")}})

        health.on_snapshot_installed(placement_identities(snapshot_returning), at=at + 2000)
        steps.append({"action": "installTopology", "topology": path_returning,
                      "expect": {"outcome": "installed", "condition": None,
                                 "epochInForce": returning}})
        steps.append({
            "action": "healthSnapshotInstalled", "topology": path_returning,
            "placementSet": placement_identities(snapshot_returning), "at": at + 2000,
            "note": "`HEALTH-007`: the identity re-enters the placement set.  Where "
                    "`resetOnPlacementReentry` is true the entry is discarded and the identity "
                    "holds `unknown`; where it is false the entry and its ejection count "
                    "survive.",
            "expect": {"resetOnPlacementReentry": reset,
                       "state": health.state_of("p1"),
                       "attemptable": health.attemptable("p1"),
                       "ejectionCount": (health.nodes["p1"].ejections
                                         if "p1" in health.nodes else 0)},
        })
    register("health-reset-on-reentry",
             "A node is ejected, leaves the placement set, is re-imaged, and returns under the "
             "same identity.  The entry survives the absence under the default parameter and is "
             "discarded where the integrator asks for it.",
             ["HEALTH-004", "HEALTH-006", "HEALTH-007", "HEALTH-016", "HEALTH-043",
              "HEALTH-055", "CFG-030", "FAIL-010"], steps)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(HERE.parent))
    args = parser.parse_args()
    root = Path(args.out)

    build_rollback_scenario()
    build_stale_caller_scenario()
    build_split_view_scenario()
    build_redirect_scenario()
    build_happy_path_scenario()
    build_quiesce_lease_scenario()
    build_node_dies_scenario()
    build_abort_during_catchup_scenario()
    build_coordinator_death_scenarios()
    build_failure_kind_scenarios()
    build_supersession_scenario()
    build_rebase_survives_scenario()
    build_rebase_drops_handoff_scenario()
    build_undetermined_recovery_scenario()
    build_concurrency_scenario()
    build_failover_scenario()
    build_filter_fails_open_scenario()
    build_ejection_ceiling_scenario()
    build_probation_ramp_scenario()
    build_outlier_comparison_scenario()
    build_reentry_reset_scenario()

    for name, document in SCENARIO_TOPOLOGIES.items():
        write_json(root / "topologies" / ("%s.topology.json" % name), document)

    index = []
    for scenario in SCENARIOS:
        path = "scenarios/%s.json" % scenario["scenario"]
        write_json(root / path, scenario)
        index.append({"file": path, "scenario": scenario["scenario"],
                      "level": scenario["level"],
                      "description": scenario["description"],
                      "stepCount": len(scenario["steps"]),
                      "requirements": scenario["requirements"]})

    write_json(root / "scenarios/index.json", {
        "suite": "sharder simulation scenarios",
        "scenarioCount": len(index),
        "stepCount": sum(e["stepCount"] for e in index),
        "requirementsCovered": sorted({r for e in index for r in e["requirements"]}),
        "scenarios": index,
    })
    print("wrote %d scenarios, %d steps, %d requirement identifiers"
          % (len(index), sum(e["stepCount"] for e in index),
             len({r for e in index for r in e["requirements"]})))
    return 0


if __name__ == "__main__":
    sys.exit(main())
