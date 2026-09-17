#!/usr/bin/env python3
"""Write `conformance/properties/properties.json`, the property definitions.

A property is a claim over a sample rather than over one key, so it is stated as data a port
turns into a test: the requirement identifiers it proves, the topologies it runs against, the
sample it draws, the precondition the specification attaches to its bound, and the bound itself
as an integer inequality with named terms.

Every bound and every sample size below is taken from `PROP-*`.  Where the specification states
no bound for a configuration, the entry says so rather than inventing one.

    python3 property_definitions.py [--out <conformance root>]
"""

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

SAMPLE = {"generator": "splitmix64", "seed": "5348415244455201", "keyOctets": 16,
          "requirement": "PROP-006",
          "note": "two draws per key, each written most significant octet first"}

PROPERTIES = [
    {
        "id": "P-DETERMINISM-001",
        "name": "Candidate ordering determinism",
        "requirements": ["PROP-001", "PROP-002", "PROP-003", "PLACE-010", "PLACE-012"],
        "level": "core",
        "statement": "For one document and one key, two conforming implementations produce the "
                     "same routing key, matched override entry, eligible node set, shard "
                     "identifier, and candidate ordering.",
        "quantifier": "for every topology in the suite and every key of the sample",
        "sample": dict(SAMPLE, count=10000),
        "check": {"form": "equality",
                  "left": "the candidate ordering serialised as a JSON array of strings",
                  "right": "the value in the corresponding golden vector, or the value a second "
                           "implementation computed",
                  "comparison": "byte identical, under PROP-002"},
        "witness": "every routing vector file",
    },
    {
        "id": "P-DETERMINISM-002",
        "name": "Lazy and eager agreement",
        "requirements": ["PROP-004", "PLACE-015", "REPL-015"],
        "level": "core",
        "statement": "Consuming the first p entries of a lazily computed candidate ordering "
                     "yields the first p entries of the eagerly computed one, for every p.",
        "quantifier": "for every topology in the suite, every key of the sample, and every p "
                      "from 0 to the length of the ordering",
        "sample": dict(SAMPLE, count=1000),
        "check": {"form": "equality",
                  "left": "prefix of length p taken lazily",
                  "right": "prefix of length p of the eager ordering"},
        "witness": None,
        "note": "A port that computes eagerly satisfies this trivially and still runs it, "
                "because the preference list builder may consume lazily even where the "
                "strategy does not.",
    },
    {
        "id": "P-DETERMINISM-003",
        "name": "Document permutation invariance",
        "requirements": ["PROP-005", "CORE-002", "RING-013", "RV-013"],
        "level": "core",
        "statement": "Permuting the nodes array, changing the epoch, changing the topology "
                     "identifier, changing any node address, and changing metadata leave every "
                     "candidate ordering unchanged.",
        "quantifier": "for every topology in the suite and every key of the sample",
        "sample": dict(SAMPLE, count=1000),
        "check": {"form": "equality",
                  "left": "the candidate ordering under the document",
                  "right": "the candidate ordering under the permuted document"},
        "witness": "vectors/determinism/node-array-permutation.json",
    },
    {
        "id": "P-PURITY-001",
        "name": "Health independence of ownership",
        "requirements": ["PROP-040", "PROP-041", "PROP-045", "FAIL-001", "FAIL-011",
                         "REPL-016"],
        "level": "core",
        "statement": "Replacing the health view with any other health view changes no candidate "
                     "ordering, no shard identifier, no eligible node set, and no preference "
                     "list.  Two callers holding one snapshot compute one preference list.",
        "quantifier": "for every topology in the suite, every key of the sample, and every "
                      "assignment of health states to nodes",
        "sample": dict(SAMPLE, count=1000),
        "check": {"form": "equality",
                  "left": "the preference list under an empty health view",
                  "right": "the preference list under an arbitrary health view"},
        "witness": "scenarios/failover-and-recovery.json",
    },
    {
        "id": "P-PURITY-002",
        "name": "Environment independence",
        "requirements": ["PROP-042", "PROP-043", "PROP-044", "PLACE-011", "CORE-004"],
        "level": "core",
        "statement": "Advancing the clock, changing the time zone, changing the monotonic "
                     "source, reseeding any random source, and changing caller identity, thread "
                     "identity, process identity, locale, or default character encoding change "
                     "no candidate ordering.",
        "quantifier": "for every topology in the suite and every key of the sample",
        "sample": dict(SAMPLE, count=1000),
        "check": {"form": "equality",
                  "left": "the candidate ordering before the environment change",
                  "right": "the candidate ordering after it"},
        "witness": None,
        "note": "A port runs this in at least two locales and two time zones, because a locale "
                "sensitive string comparison is the defect it is written to catch.",
    },
    {
        "id": "P-MOVEMENT-001",
        "name": "First candidate stability",
        "requirements": ["PROP-010", "PROP-011"],
        "level": "core",
        "statement": "On the addition of node x, the first candidate of every key is either its "
                     "first candidate before the addition or x.  On removal, every key whose "
                     "first candidate was not x keeps it.",
        "quantifier": "for the derived configurations of ring, rendezvous, slot, and range, and "
                      "every key of the sample",
        "sample": dict(SAMPLE, count=10000),
        "check": {"form": "membership",
                  "statement": "firstCandidateAfter in { firstCandidateBefore, x }"},
        "witness": "vectors/movement/add-one-node.json",
    },
    {
        "id": "P-MOVEMENT-002",
        "name": "Movement fraction under scoring strategies",
        "requirements": ["PROP-006", "PROP-013", "PROP-015"],
        "level": "core",
        "statement": "Under rendezvous, slot with derived assignment, and range with derived "
                     "assignment, the fraction of keys that change first candidate is "
                     "v_x / (V + v_x) on addition and v_x / V on removal.",
        "quantifier": "over the sample, per topology pair",
        "sample": dict(SAMPLE, count="10000 * p_den / p_num, rounded up"),
        "check": {"form": "integerBound",
                  "inequality": "20 * |m * p_den - M * p_num| <= M * p_num",
                  "terms": {"m": "count of sampled keys whose first candidate differs",
                            "M": "sample size",
                            "p_num": "numerator of the expected fraction",
                            "p_den": "denominator of the expected fraction"},
                  "precondition": "M * p_num >= 10000 * p_den",
                  "band": "five per cent"},
        "witness": "vectors/properties/witnesses.json#movement-rendezvous-add-one",
    },
    {
        "id": "P-MOVEMENT-003",
        "name": "Movement fraction under ring",
        "requirements": ["PROP-014", "PROP-015"],
        "level": "core",
        "statement": "Under ring, the fraction of keys that change first candidate is "
                     "t_x / (T + t_x) on addition and t_x / T on removal.",
        "quantifier": "over the sample, per topology pair, where every node holds at least 256 "
                      "tokens",
        "sample": dict(SAMPLE, count="10000 * p_den / p_num, rounded up"),
        "check": {"form": "integerBound",
                  "inequality": "4 * |m * p_den - M * p_num| <= M * p_num",
                  "terms": {"m": "count of sampled keys whose first candidate differs",
                            "M": "sample size", "p_num": "t_x", "p_den": "T + t_x"},
                  "precondition": "M * p_num >= 10000 * p_den, and every node holds at least "
                                  "256 tokens",
                  "band": "twenty five per cent"},
        "witness": "vectors/properties/witnesses.json#movement-ring-add-one",
    },
    {
        "id": "P-MOVEMENT-004",
        "name": "Ordering preserved as a subsequence",
        "requirements": ["PROP-012"],
        "level": "core",
        "statement": "The candidate ordering after adding x, with x deleted from it, equals the "
                     "candidate ordering before adding x.  The whole ordering is preserved, not "
                     "only its first entry.",
        "quantifier": "for the derived configurations of ring, rendezvous, slot, and range, and "
                      "every key of the sample",
        "sample": dict(SAMPLE, count=10000),
        "check": {"form": "equality",
                  "left": "the ordering after, with x removed",
                  "right": "the ordering before"},
        "witness": "vectors/movement/add-one-node.json",
    },
    {
        "id": "P-MOVEMENT-005",
        "name": "Weight change locality",
        "requirements": ["PROP-019"],
        "level": "core",
        "statement": "Raising node x's weight moves keys only to x, and a key whose first "
                     "candidate was x keeps x.  Lowering a weight is the same statement with the "
                     "two documents exchanged.",
        "quantifier": "for the derived configurations of ring, rendezvous, slot, and range, and "
                      "every key of the sample",
        "sample": dict(SAMPLE, count=10000),
        "check": {"form": "membership",
                  "statement": "firstCandidateAfter in { firstCandidateBefore, x }, and "
                               "firstCandidateBefore == x implies firstCandidateAfter == x"},
        "witness": None,
    },
    {
        "id": "P-MOVEMENT-006",
        "name": "Shard stability under membership change",
        "requirements": ["PROP-016", "PROP-017", "PROP-018", "SLOT-004"],
        "level": "core",
        "statement": "Under slot, range, rendezvous, and directory, adding or removing a node "
                     "changes no key's shard.  Under ring it may, and where it does the new "
                     "value is one of the added node's tokens.",
        "quantifier": "for every topology pair and every key of the sample",
        "sample": dict(SAMPLE, count=10000),
        "check": {"form": "equality",
                  "left": "shardOf(k) before", "right": "shardOf(k) after",
                  "exception": "under ring, membership of the added node's token set"},
        "witness": "vectors/movement/add-one-node.json",
    },
    {
        "id": "P-BALANCE-001",
        "name": "Rendezvous balance band",
        "requirements": ["PROP-006", "PROP-020", "PROP-030", "PROP-031", "PROP-032"],
        "level": "core",
        "statement": "Each node's share of first-candidate decisions lies within five per cent "
                     "of its share of virtual nodes.  The proportionality is exact rather than "
                     "asymptotic and holds for every distribution of weights.",
        "quantifier": "for every eligible node of the topology, over the sample",
        "sample": dict(SAMPLE, count="10000 * V / v_min, rounded up"),
        "check": {"form": "integerBound",
                  "inequality": "20 * |c_i * V - M * v_i| <= M * v_i",
                  "terms": {"c_i": "count of sampled keys whose ordering begins with node i",
                            "v_i": "node i's virtual node count",
                            "V": "sum of virtual node counts over the eligible set",
                            "M": "sample size"},
                  "precondition": "M * v_min >= 10000 * V",
                  "band": "five per cent",
                  "tieBreakAllowance": "PROP-031 bounds the perturbation from the node identity "
                                       "tie-break at V^2 / 2^65, so no test asserts more "
                                       "tightly than that"},
        "witness": "vectors/properties/witnesses.json#balance-rendezvous-equal-weights",
    },
    {
        "id": "P-BALANCE-002",
        "name": "Ring balance band",
        "requirements": ["PROP-021"],
        "level": "core",
        "statement": "Each node's share of first-candidate decisions lies within twenty five per "
                     "cent of its share of tokens, where every eligible node holds at least 256 "
                     "tokens.  Below 256 tokens per node the specification states no bound.",
        "quantifier": "for every eligible node of the topology, over the sample",
        "sample": dict(SAMPLE, count="10000 * T / t_min, rounded up"),
        "check": {"form": "integerBound",
                  "inequality": "4 * |c_i * T - M * t_i| <= M * t_i",
                  "terms": {"c_i": "count of sampled keys whose ordering begins with node i",
                            "t_i": "node i's token count",
                            "T": "sum of token counts over the eligible set",
                            "M": "sample size"},
                  "precondition": "every eligible node holds at least 256 tokens, and "
                                  "M * t_min >= 10000 * T",
                  "band": "twenty five per cent"},
        "witness": "vectors/properties/witnesses.json#balance-ring-256-tokens",
    },
    {
        "id": "P-BALANCE-003",
        "name": "Slot derived balance band",
        "requirements": ["PROP-022", "PROP-024", "PROP-033"],
        "level": "core",
        "statement": "The bound of P-BALANCE-001 holds with the sample replaced by the set of "
                     "all slots and c_i replaced by the count of slots whose candidate ordering "
                     "begins with node i.",
        "quantifier": "for every eligible node, over every slot of the topology",
        "sample": {"generator": "allSlots",
                   "count": "slotCount, which satisfies slotCount * v_min >= 10000 * V"},
        "check": {"form": "integerBound",
                  "inequality": "20 * |c_i * V - slotCount * v_i| <= slotCount * v_i",
                  "terms": {"c_i": "count of slots whose ordering begins with node i",
                            "v_i": "node i's virtual node count", "V": "sum of v_j"},
                  "precondition": "slotCount * v_min >= 10000 * V",
                  "band": "five per cent"},
        "witness": "vectors/properties/witnesses.json#balance-slot-derived",
    },
    {
        "id": "P-BALANCE-004",
        "name": "Range derived balance band",
        "requirements": ["PROP-023", "PROP-026"],
        "level": "core",
        "statement": "The bound of P-BALANCE-001 holds with the sample replaced by the set of "
                     "all ranges and c_i replaced by the count of ranges whose candidate "
                     "ordering begins with node i.",
        "quantifier": "for every eligible node, over every range of the topology",
        "sample": {"generator": "allRanges",
                   "count": "the range count, which must satisfy rangeCount * v_min >= "
                            "10000 * V"},
        "check": {"form": "integerBound",
                  "inequality": "20 * |c_i * V - rangeCount * v_i| <= rangeCount * v_i",
                  "terms": {"c_i": "count of ranges whose ordering begins with node i",
                            "v_i": "node i's virtual node count", "V": "sum of v_j"},
                  "precondition": "rangeCount * v_min >= 10000 * V",
                  "band": "five per cent"},
        "witness": None,
        "gap": "The suite ships no witness, and `PROP-026` states that the bound carries no "
               "executable test.  The precondition needs at least 10000 * V / v_min ranges, "
               "which is 20000 ranges for the smallest topology on which the bound says "
               "anything, and a range is an authored document entry rather than a derived one. "
               "A port that wants the bound authors that document itself.",
    },
    {
        "id": "P-BALANCE-005",
        "name": "Authored assignment carries no bound",
        "requirements": ["PROP-017", "PROP-025", "PLACE-044"],
        "level": "core",
        "statement": "Under slot with explicit assignment, range with explicit assignment, and "
                     "directory, no balance bound and no movement bound applies.  Movement "
                     "between two epochs is exactly the difference between the authored tables, "
                     "and observed balance is reported against weight without changing any "
                     "ordering.",
        "quantifier": "for every topology under an authored assignment",
        "sample": {"generator": "none",
                   "count": "the property asserts the absence of a bound rather than a bound"},
        "check": {"form": "invariant",
                  "statement": "a port evaluates no balance or movement inequality against "
                               "these configurations, and a document change moves exactly the "
                               "keys the table change implies"},
        "witness": "vectors/placement/document-defaults.json",
        "note": "Stated so that a port does not invent a bound the specification declines to "
                "state, and so that the coverage table does not count PROP-025 as untested.",
    },
    {
        "id": "P-REPLICA-001",
        "name": "Replica distinctness",
        "requirements": ["REPL-011", "REPL-020", "SPREAD-002", "PLACE-013"],
        "level": "core",
        "statement": "No node identity appears twice in a preference list.  A shortfall produces "
                     "a shorter replica prefix rather than a repeated or synthesised node.",
        "quantifier": "for every topology in the suite and every key of the sample",
        "sample": dict(SAMPLE, count=10000),
        "check": {"form": "invariant",
                  "statement": "len(set(preferenceList)) == len(preferenceList), and "
                               "replicaCount <= factor"},
        "witness": "vectors/replication/factor-exceeds-node-count.json",
    },
    {
        "id": "P-SPREAD-001",
        "name": "Spread at the chosen stage",
        "requirements": ["SPREAD-001", "SPREAD-010", "SPREAD-011", "SPREAD-018", "SPREAD-020"],
        "level": "core",
        "statement": "No two entries of the replica prefix share a failure domain at any level "
                     "the chosen relaxation stage enforces.  Two callers holding one snapshot "
                     "choose one stage.",
        "quantifier": "for every topology declaring a spread, and every key of the sample",
        "sample": dict(SAMPLE, count=10000),
        "check": {"form": "invariant",
                  "statement": "for every enforced level L and every pair (x, y) in the replica "
                               "prefix, domainPath(x, L) != domainPath(y, L)"},
        "witness": "vectors/spread/degradation-ladder.json",
    },
    {
        "id": "P-SPREAD-002",
        "name": "Minimal relaxation",
        "requirements": ["SPREAD-012", "SPREAD-013", "SPREAD-015", "SPREAD-017"],
        "level": "core",
        "statement": "Under relaxed, the chosen stage is the smallest k for which select(k) "
                     "reaches the effective replication factor, and stage m where no stage does. "
                     "No stage below the chosen one reaches the factor.",
        "quantifier": "for every topology declaring a spread, and every key of the sample",
        "sample": dict(SAMPLE, count=10000),
        "check": {"form": "invariant",
                  "statement": "for every j below the chosen stage, len(select(j)) < n; and the "
                               "reported relaxed levels are the k coarsest levels of spread"},
        "witness": "vectors/spread/degradation-ladder.json",
    },
    {
        "id": "P-SPREAD-003",
        "name": "Strict policy shortfall",
        "requirements": ["SPREAD-014", "REPL-020", "REPL-021"],
        "level": "core",
        "statement": "Under strict, the replica prefix is select(0) and no other stage is "
                     "evaluated.  A prefix shorter than the factor is a shortfall with cause "
                     "domains, and the relaxed level list is empty.",
        "quantifier": "for every topology declaring a strict spread, and every key of the sample",
        "sample": dict(SAMPLE, count=1000),
        "check": {"form": "invariant",
                  "statement": "relaxedLevels == [] and, where replicaCount < factor, "
                               "shortfall == 'domains' unless the candidate ordering holds fewer "
                               "than factor identities"},
        "witness": "vectors/spread/all-nodes-one-domain-strict.json",
    },
    {
        "id": "P-PREFERENCE-001",
        "name": "Preference list composition",
        "requirements": ["REPL-012", "REPL-013", "REPL-014", "REPL-017", "SPREAD-021"],
        "level": "core",
        "statement": "The preference list is a permutation of the candidate ordering in which "
                     "the replica prefix and the fallback tail each preserve candidate ordering "
                     "order, and every entry carries its zero-based position and its role.",
        "quantifier": "for every topology in the suite and every key of the sample",
        "sample": dict(SAMPLE, count=10000),
        "check": {"form": "invariant",
                  "statement": "set(preferenceList) == set(candidates); the prefix is a "
                               "subsequence of candidates; the tail is candidates with the "
                               "prefix removed; role is replica below replicaCount and fallback "
                               "at or above it"},
        "witness": "vectors/replication/fallback-tail.json",
    },
    {
        "id": "P-EXEMPT-001",
        "name": "Pinned key exemption",
        "requirements": ["PROP-050", "PROP-052", "OVR-010", "OVR-013"],
        "level": "core",
        "statement": "A key matched by an override carrying a pin is excluded from every balance "
                     "and movement bound, and a test that asserts one states which entry it "
                     "excluded.  Spread still applies to a pinned ordering, by filtering it.",
        "quantifier": "for every topology carrying a pin, and every key of the sample",
        "sample": dict(SAMPLE, count=1000),
        "check": {"form": "invariant",
                  "statement": "the sample is partitioned by matched override before any bound "
                               "is evaluated, and pinned keys are excluded"},
        "witness": "vectors/spread/applies-to-pinned-ordering.json",
    },
    {
        "id": "P-EXEMPT-002",
        "name": "Constrained key bounds",
        "requirements": ["PROP-051", "OVR-020", "OVR-032"],
        "level": "core",
        "statement": "A key matched by an override carrying a constraint and no pin carries "
                     "every balance and movement bound within its constrained eligible set, with "
                     "E, V, and T computed over that set.",
        "quantifier": "per matched override entry, over the partitioned sample",
        "sample": dict(SAMPLE, count="10000 * V / v_min per partition, rounded up"),
        "check": {"form": "integerBound",
                  "inequality": "the bound of P-BALANCE-001 with E restricted to the "
                                "constrained set"},
        "witness": None,
    },
    {
        "id": "P-EPOCH-001",
        "name": "Monotonic epochs",
        "requirements": ["TOPO-051", "TOPO-061", "TOPO-071", "TOPO-081", "TOPO-091"],
        "level": "core",
        "statement": "The sequence of installed epochs under one topology identifier is strictly "
                     "increasing.  No document below the epoch in force, below minEpoch, or "
                     "under a differing identifier is ever installed.",
        "quantifier": "over any sequence of documents a provider delivers",
        "sample": {"generator": "scenario", "count": "the scenario's document sequence"},
        "check": {"form": "invariant",
                  "statement": "for consecutive installed snapshots s and s', "
                               "s'.epoch > s.epoch and s'.topologyId == s.topologyId"},
        "witness": "scenarios/topology-rollback.json",
    },
    {
        "id": "P-EPOCH-002",
        "name": "Digest decides content equality",
        "requirements": ["TOPO-061", "ERR-031", "ERR-034"],
        "level": "core",
        "statement": "An arriving document whose epoch equals the epoch in force is a no-op "
                     "where its digest matches and a topology conflict where it differs.",
        "quantifier": "over any sequence of documents a provider delivers",
        "sample": {"generator": "scenario", "count": "the scenario's document sequence"},
        "check": {"form": "invariant",
                  "statement": "equal epoch and equal digest yields no condition; equal epoch "
                               "and differing digest yields topologyConflict"},
        "witness": "scenarios/topology-rollback.json",
    },
    {
        "id": "P-HANDOFF-001",
        "name": "Idempotent handoff steps",
        "requirements": ["MOVE-151", "MOVE-161", "MOVE-171"],
        "level": "migration",
        "statement": "Calling prepare, transfer, catchUp, quiesce, verify, cleanup, rollback, or "
                     "observe twice with one context leaves the same result as calling it once. "
                     "A second commitCutover returns alreadyCommitted with the first record, or "
                     "lost with another destination's record, and commits no second record.",
        "quantifier": "for every hook and every state from which the coordinator calls it",
        "sample": {"generator": "scenario", "count": "every hook, called twice per state"},
        "check": {"form": "equality",
                  "left": "the coordinator state and the hook's durable effect after one call",
                  "right": "the same after two calls with one context"},
        "witness": "scenarios/coordinator-death-and-recovery.json",
    },
    {
        "id": "P-HANDOFF-002",
        "name": "Recovery from durable observation",
        "requirements": ["MOVE-201", "MOVE-211", "MOVE-221", "MOVE-231"],
        "level": "migration",
        "statement": "A restarted coordinator rebuilds its plan from the same two snapshots and "
                     "assigns each non-terminal handoff a state from its observation alone.  The "
                     "coordinator's own record is never the authority.",
        "quantifier": "for every non-terminal state and every observation",
        "sample": {"generator": "scenario", "count": "six states times five observations"},
        "check": {"form": "equality",
                  "left": "the resumed state",
                  "right": "the state the MOVE-211 mapping gives for the observation"},
        "witness": "scenarios/coordinator-death-and-recovery.json",
    },
    {
        "id": "P-HANDOFF-003",
        "name": "Terminal state finality",
        "requirements": ["MOVE-011", "MOVE-021", "MOVE-031", "MOVE-441", "MOVE-491"],
        "level": "migration",
        "statement": "complete, aborted, and failed admit no transition out.  A handoff in "
                     "failed carries exactly one of the four failure kinds.  An abort is "
                     "idempotent and is not admitted at or beyond verifying.",
        "quantifier": "for every terminal state and every trigger",
        "sample": {"generator": "scenario", "count": "every trigger from every terminal state"},
        "check": {"form": "invariant",
                  "statement": "a transition out of a terminal state is refused, and a handoff "
                               "in failed carries one kind from { unverified, residue, "
                               "undetermined, rollbackFailed }"},
        "witness": "scenarios/handoff-failure-kinds.json",
    },
    {
        "id": "P-FENCE-001",
        "name": "Verdict purity",
        "requirements": ["FENCE-101", "FENCE-071", "FENCE-081", "FENCE-091"],
        "level": "core",
        "statement": "A recipient verdict is a function of the token, the routing key, the node "
                     "identity, and the retained snapshots alone.  It reads no health, no clock, "
                     "no randomness, and no handoff state, and uses no floating point.",
        "quantifier": "for every token, key, and recipient of the scenarios",
        "sample": {"generator": "scenario", "count": "the scenario's check sequence"},
        "check": {"form": "equality",
                  "left": "the verdict",
                  "right": "the verdict recomputed after advancing the clock and replacing the "
                           "health view"},
        "witness": "scenarios/caller-three-epochs-stale.json",
    },
    {
        "id": "P-ATTEMPT-001",
        "name": "Attempt sequence subsequence",
        "requirements": ["FAIL-002", "FAIL-003", "FAIL-004", "FAIL-012", "FAIL-014"],
        "level": "core",
        "statement": "The attempt sequence is an order-preserving subsequence of the whole "
                     "preference list and not of the materialised prefix the decision carries.  "
                     "The primary is the head of the preference list whatever its health state.  "
                     "A non-empty preference list never yields an empty attempt sequence.",
        "quantifier": "for every topology, every key of the sample, and every assignment of "
                      "health states",
        "sample": dict(SAMPLE, count=1000),
        "check": {"form": "invariant",
                  "statement": "attemptSequence is a subsequence of preferenceList; "
                               "primary == preferenceList[0]; attemptSequence is non-empty "
                               "wherever preferenceList is; an entry beyond materialisedEntries "
                               "is attemptable where the health filter admits it"},
        "witness": "scenarios/health-filter-fails-open.json",
    },
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(HERE.parent))
    args = parser.parse_args()
    root = Path(args.out)
    path = root / "properties/properties.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "suite": "sharder conformance properties",
        "propertyCount": len(PROPERTIES),
        "keySample": SAMPLE,
        "levels": {
            "core": "required of every port declaring conformance",
            "migration": "required of a port that implements the handoff coordinator",
        },
        "requirementsCovered": sorted({r for p in PROPERTIES for r in p["requirements"]}),
        "withoutWitness": [p["id"] for p in PROPERTIES if not p["witness"]],
        "properties": PROPERTIES,
    }
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n")
    print("wrote %d property definitions, %d requirement identifiers"
          % (len(PROPERTIES), len(payload["requirementsCovered"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
