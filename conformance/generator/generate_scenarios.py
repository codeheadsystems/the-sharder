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
from sharder_ref import fencing, routing                          # noqa: E402
from sharder_ref.handoff import Handoff, Plan, ownership_delta    # noqa: E402
from sharder_ref.health import HealthView                         # noqa: E402
from sharder_ref.jcs import digest as jcs_digest                  # noqa: E402
from sharder_ref.topology import Snapshot                         # noqa: E402

SCENARIOS = []
SCENARIO_TOPOLOGIES = {}


def register(name, description, requirements, steps, setup=None):
    SCENARIOS.append({
        "scenario": name,
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

def cluster(epoch, r1_nodes, n4_state="joining", topology_id="migration"):
    return {
        "formatVersion": "1.0",
        "topologyId": topology_id,
        "epoch": epoch,
        "domainLevels": ["zone"],
        "replication": {"factor": 2},
        "strategy": {
            "kind": "range",
            "assignment": "explicit",
            "ranges": [
                {"shardId": "r0", "start": None, "end": "40", "nodes": ["n1", "n2"]},
                {"shardId": "r1", "start": "40", "end": "80", "nodes": r1_nodes},
                {"shardId": "r2", "start": "80", "end": None, "nodes": ["n3", "n1"]},
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

PROBE_KEY = bytes([0x50])     # falls in r1 under every epoch above


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
        "note": "the rollback: the epoch 1 assignment republished at epoch 5, under `MOVE-471`",
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
             ["TOPO-051", "TOPO-061", "TOPO-081", "TOPO-091", "ERR-031", "ERR-032",
              "MOVE-471"], steps)


def build_stale_caller_scenario():
    """A caller three epochs behind, at a recipient holding the newest snapshot."""
    in_force = SNAP["migration-epoch-4"]
    retained = {1: SNAP["migration-epoch-1"], 2: SNAP["migration-epoch-2"],
                3: SNAP["migration-epoch-3"]}
    steps = [{
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

    # An unfenced request, and a sender ahead of the recipient.
    verdict = fencing.check({"topologyId": "migration", "epoch": 4}, PROBE_KEY, "n1",
                            in_force, retained)
    steps.append({
        "action": "recipientCheck",
        "token": None,
        "key": key_spec(PROBE_KEY, "base16"),
        "selfId": "n1",
        "recipientPolicy": "strict",
        "fenced": False,
        "expect": {"verdict": verdict,
                   "condition": fencing.policy_outcome(verdict, "strict", fenced=False),
                   "served": False},
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
        "expect": {"verdict": verdict,
                   "condition": fencing.policy_outcome(verdict, "stable"),
                   "served": False},
    })

    register("caller-three-epochs-stale",
             "A caller routes against epoch 1 while the recipient holds epoch 4.  Covers every "
             "recipient relation, both policies, a retained and an unretained token epoch, an "
             "unfenced request, and a sender ahead of the recipient.",
             ["FENCE-041", "FENCE-061", "FENCE-071", "FENCE-081", "FENCE-091", "FENCE-111",
              "FENCE-121", "FENCE-131", "FENCE-141", "FENCE-151", "TOPO-161", "TOPO-171",
              "ERR-040", "ERR-041", "ERR-042", "ERR-044"], steps)


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


def build_redirect_scenario():
    steps = []
    for name, refusals, bound, note in [
        ("served-immediately", {"n2": None}, 2, "the first node serves"),
        ("one-redirect", {"n2": "n4", "n4": None}, 2, "one hop"),
        ("bound-reached", {"n2": "n4", "n4": "n3", "n3": "n1", "n1": None}, 2,
         "`FENCE-181`: the walk stops at `maxRedirects`, defaulting to 2"),
        ("revisited-node", {"n2": "n4", "n4": "n2"}, 5,
         "`FENCE-191`: a redirect naming an already attempted node terminates the walk"),
        ("bound-zero", {"n2": "n4"}, 0, "a bound of 0 permits no redirect at all"),
    ]:
        result = fencing.redirect_walk("n2", refusals, bound)
        steps.append({
            "action": "redirectWalk",
            "name": name,
            "start": "n2",
            "refusals": refusals,
            "maxRedirects": bound,
            "note": note,
            "expect": result,
        })
    register("redirect-walk-depth-limit",
             "A caller follows `currentOwner` refusals, and the walk terminates at the redirect "
             "bound or on revisiting a node it already attempted.",
             ["FENCE-171", "FENCE-181", "FENCE-191", "FENCE-211", "ERR-043", "CFG-040"], steps)


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


def one_handoff():
    return Plan(1, 2, "migration", [Handoff("h-r1", "r1", "n2", "n4")])


def drive(plan, triggers, at_base=1000):
    """Apply a trigger sequence and return the steps with their computed outcomes."""
    steps = []
    for index, trigger in enumerate(triggers):
        at = at_base + index * 1000
        outcome = plan.step("h-r1", trigger, at=at)
        steps.append({"action": "handoffStep", "handoff": "h-r1", "trigger": trigger, "at": at,
                      "expect": {"outcome": outcome, "state": plan.state("h-r1")}})
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
                  "expect": {"handoffs": [{"id": "h-r1", "shard": "r1", "source": "n2",
                                           "destination": "n4", "state": "planned"}],
                             "guarantee": "linearisable"}})
    steps.extend(drive(plan, HAPPY_PATH))
    steps.append({"action": "expectSummary", "expect": plan.summary()})
    register("handoff-happy-path",
             "One shard moves from its source to its destination through every state of "
             "`MOVE-021`, with the ownership delta that produced the plan.",
             ["TOPO-211", "TOPO-221", "MOVE-001", "MOVE-021", "MOVE-031", "MOVE-041",
              "MOVE-051", "MOVE-071", "MOVE-121", "MOVE-131", "MOVE-181", "MOVE-241"], steps)


def build_node_dies_scenario():
    """The destination dies mid-transfer: retries exhaust, compensation runs, ownership stays."""
    plan = one_handoff()
    steps = list(drive(plan, ["admittedByRatePolicy", "prepareSuccess"]))

    health = HealthView(placement_set_size=4)
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
              "MOVE-421", "MOVE-481", "MOVE-491"], steps)


def build_abort_during_catchup_scenario():
    plan = one_handoff()
    steps = list(drive(plan, ["admittedByRatePolicy", "prepareSuccess", "noBulkRemaining"]))
    outcome = plan.abort("h-r1", "operator requested", at=9000)
    steps.append({"action": "handoffAbort", "handoff": "h-r1", "reason": "operator requested",
                  "at": 9000, "expect": {"outcome": outcome, "state": plan.state("h-r1")}})
    repeat = plan.abort("h-r1", "operator requested again", at=9500)
    steps.append({"action": "handoffAbort", "handoff": "h-r1", "reason": "again", "at": 9500,
                  "note": "`MOVE-491`: an abort is idempotent",
                  "expect": {"outcome": repeat, "state": plan.state("h-r1")}})
    steps.extend(drive(plan, ["rollbackSuccess"], at_base=10000))
    steps.append({"action": "expectSummary", "expect": plan.summary()})
    register("abort-during-catching-up",
             "An abort requested in `catchingUp` moves the handoff to `aborting` and runs "
             "compensation.  A second abort has no further effect.",
             ["MOVE-021", "MOVE-421", "MOVE-491"], steps)


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
                    "noRecordSourceQuiesced", "recordOwnedByDestinationAtTargetEpoch",
                    "recordNamesAnotherOwnerOrEpoch"]
    for died_in, triggers in reached:
        for observation in observations:
            plan = one_handoff()
            for index, trigger in enumerate(triggers):
                plan.step("h-r1", trigger, at=1000 + index * 100)
            assert plan.state("h-r1") == died_in
            resumed = plan.recover({"h-r1": observation}, at=20000)
            steps.append({
                "action": "coordinatorRestart",
                "diedInState": died_in,
                "triggersBeforeDeath": triggers,
                "observation": observation,
                "at": 20000,
                "note": "`MOVE-221`: the plan is rebuilt from the same two snapshots and "
                        "`recover` runs before the first `step`",
                "expect": {"resumedState": resumed["h-r1"]},
            })

    plan = one_handoff()
    for index, trigger in enumerate(HAPPY_PATH[:4]):
        plan.step("h-r1", trigger, at=1000 + index * 100)
    assert plan.state("h-r1") == "cutover"
    resumed = plan.recover({"h-r1": "unavailable"}, at=30000)
    steps.append({
        "action": "coordinatorRestart",
        "diedInState": "cutover",
        "triggersBeforeDeath": HAPPY_PATH[:4],
        "observation": "unavailable",
        "at": 30000,
        "note": "`MOVE-231`: an unavailable or undetermined observation in `cutover` fails the "
                "handoff and forbids any further `cleanup`, `rollback`, or `commitCutover`",
        "expect": {"resumedState": resumed["h-r1"],
                   "failureKind": plan.handoffs["h-r1"].failure_kind},
    })

    register("coordinator-death-and-recovery",
             "The coordinator dies in each non-terminal state and rebuilds its plan.  The "
             "resumed state is a function of the integrator's durable observation, never of the "
             "coordinator's own record.",
             ["MOVE-201", "MOVE-211", "MOVE-221", "MOVE-231", "MOVE-151", "MOVE-161"], steps)


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
            plan.step("h-r1", trigger, at=1000 + index * 100)
        handoff = plan.handoffs["h-r1"]
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
            plan.step("h-r1", "cleanupSuccess", at=99000)
        except Exception as exc:                       # `MOVE-031`: terminal states are terminal
            further = type(exc).__name__
        steps.append({"action": "expectTerminal", "failureKind": kind,
                      "expect": {"furtherTransitionRefused": True, "error": further}})

    register("handoff-failure-kinds",
             "Each of the four failure kinds of `MOVE-011`, with the trigger sequence that "
             "reaches it and the condition it raises.  A terminal state admits no further "
             "transition.",
             ["MOVE-011", "MOVE-021", "MOVE-031", "MOVE-231", "MOVE-451", "MOVE-461",
              "ERR-052", "ERR-053"], steps)


def build_supersession_scenario():
    plan = Plan(1, 2, "migration", [
        Handoff("h-a", "r1", "n2", "n4"),
        Handoff("h-b", "r2", "n3", "n1"),
        Handoff("h-c", "r0", "n1", "n3"),
    ])
    plan.policy["maxConcurrentHandoffs"] = 4
    steps = []
    for handoff_id, triggers in [("h-a", HAPPY_PATH[:5]),
                                 ("h-b", ["admittedByRatePolicy", "prepareSuccess"]),
                                 ("h-c", [])]:
        for index, trigger in enumerate(triggers):
            outcome = plan.step(handoff_id, trigger, at=1000 + index * 100)
            steps.append({"action": "handoffStep", "handoff": handoff_id, "trigger": trigger,
                          "at": 1000 + index * 100,
                          "expect": {"outcome": outcome, "state": plan.state(handoff_id)}})
    result = plan.on_snapshot_installed("migration", 4, at=50000)
    steps.append({
        "action": "snapshotInstalled",
        "topologyId": "migration", "epoch": 4, "at": 50000,
        "note": "`MOVE-091`: an epoch that is neither the plan's source nor its target "
                "supersedes the plan.  Handoffs at `cutover` or beyond run to a terminal state.",
        "expect": {"result": result, "states": {k: v.state for k, v in
                                                sorted(plan.handoffs.items())}},
    })
    steps.append({"action": "expectSummary", "expect": plan.summary()})
    register("plan-superseded-by-new-epoch",
             "A third epoch arrives while three handoffs are in flight.  The plan is superseded, "
             "pre-cutover handoffs abort, and the handoff past cutover runs on.",
             ["MOVE-091", "MOVE-101", "MOVE-481", "TOPO-111", "TOPO-131"], steps)


def build_concurrency_scenario():
    plan = Plan(1, 2, "migration", [
        Handoff("h-a", "r0", "n1", "n4"),
        Handoff("h-b", "r1", "n1", "n3"),
    ])
    steps = []
    outcome = plan.step("h-a", "admittedByRatePolicy", at=1000)
    steps.append({"action": "handoffStep", "handoff": "h-a",
                  "trigger": "admittedByRatePolicy", "at": 1000,
                  "expect": {"outcome": outcome, "state": plan.state("h-a")}})
    outcome = plan.step("h-b", "admittedByRatePolicy", at=1100)
    steps.append({"action": "handoffStep", "handoff": "h-b",
                  "trigger": "admittedByRatePolicy", "at": 1100,
                  "note": "`maxConcurrentPerSourceNode` defaults to 1 and both handoffs leave n1",
                  "expect": {"outcome": outcome, "state": plan.state("h-b")}})
    for level in ["soft", "hard"]:
        outcome = plan.step("h-b", "admittedByRatePolicy", at=1200, pressure=level)
        steps.append({"action": "handoffStep", "handoff": "h-b",
                      "trigger": "admittedByRatePolicy", "at": 1200, "pressure": level,
                      "note": "`RATE-081` and `RATE-091`: neither level admits a handoff out of "
                              "`planned`",
                      "expect": {"outcome": outcome, "state": plan.state("h-b")}})
    outcome = plan.step("h-a", "prepareSuccess", at=1300, pressure="hard")
    steps.append({"action": "handoffStep", "handoff": "h-a", "trigger": "prepareSuccess",
                  "at": 1300, "pressure": "hard",
                  "note": "`RATE-091` withholds `transfer` and `catchUp` and no other hook",
                  "expect": {"outcome": outcome, "state": plan.state("h-a")}})
    outcome = plan.step("h-a", "noBulkRemaining", at=1400, pressure="hard")
    steps.append({"action": "handoffStep", "handoff": "h-a", "trigger": "noBulkRemaining",
                  "at": 1400, "pressure": "hard",
                  "expect": {"outcome": outcome, "state": plan.state("h-a")}})
    register("migration-rate-control",
             "Concurrency bounds and backpressure decide what a `step` admits.  Neither pressure "
             "level moves a handoff out of `planned`, and `hard` withholds only the two bulk "
             "hooks.",
             ["RATE-001", "RATE-011", "RATE-071", "RATE-081", "RATE-091", "RATE-111",
              "MOVE-071"], steps)


# ------------------------------------------------------------------------ failover

def build_failover_scenario():
    snapshot = SNAP["migration-epoch-1"]
    decision = routing.route(snapshot, PROBE_KEY)
    preference = decision["preferenceList"]
    health = HealthView(placement_set_size=len(snapshot.placement_set))
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

    # The second replica fails too, and the ejection ceiling refuses its ejection.
    for entry in preference[1:]:
        for index in range(5):
            health.report(entry["node"], "refused", at + 100 + index * 10)
    health.advance(at + 500)
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
    admissions = [health.attemptable(primary) for _ in range(20)]
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

    health = HealthView(placement_set_size=len(snapshot.placement_set))
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
    health = HealthView(placement_set_size=len(snapshot.placement_set))
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
             ["HEALTH-025", "HEALTH-034", "HEALTH-043", "SEC-031"], steps)
    _ = snapshot


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
    build_node_dies_scenario()
    build_abort_during_catchup_scenario()
    build_coordinator_death_scenarios()
    build_failure_kind_scenarios()
    build_supersession_scenario()
    build_concurrency_scenario()
    build_failover_scenario()
    build_filter_fails_open_scenario()
    build_ejection_ceiling_scenario()

    for name, document in SCENARIO_TOPOLOGIES.items():
        write_json(root / "topologies" / ("%s.topology.json" % name), document)

    index = []
    for scenario in SCENARIOS:
        path = "scenarios/%s.json" % scenario["scenario"]
        write_json(root / path, scenario)
        index.append({"file": path, "scenario": scenario["scenario"],
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
