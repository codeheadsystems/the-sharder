"""Recipient-side fencing, per `FENCE-061` through `FENCE-151` and the redirect walk.

Every rule here is a table the specification states.  Nothing is inferred.
"""

from . import routing

RELATION_IDENTITY_MISMATCH = "identityMismatch"
RELATION_UNKNOWN_EPOCH = "unknownEpoch"
RELATION_SAME = "same"
RELATION_BEHIND = "senderBehind"
RELATION_AHEAD = "senderAhead"


def relation(token, in_force):
    """`FENCE-071`."""
    if in_force is None:
        if token["topologyId"] is None:
            return RELATION_UNKNOWN_EPOCH
        return RELATION_UNKNOWN_EPOCH
    if token["topologyId"] != in_force.topology_id:
        return RELATION_IDENTITY_MISMATCH
    if token["epoch"] == in_force.epoch:
        return RELATION_SAME
    if token["epoch"] < in_force.epoch:
        return RELATION_BEHIND
    return RELATION_AHEAD


def replica_set(snapshot, routing_key_bytes):
    """The first `factor` entries of the preference list, per `FENCE-081` and `TOPO-211`."""
    try:
        decision = routing.route(snapshot, routing_key_bytes)
    except routing.NoCandidate:
        return [], None
    entries = [e["node"] for e in decision["preferenceList"]]
    return entries[:decision["factor"]], (entries[0] if entries else None)


def check(token, key, self_id, in_force, retained=None):
    """`FENCE-061`: the recipient-side verdict."""
    retained = retained or {}
    rel = relation(token, in_force)
    verdict = {
        "relation": rel,
        "ownership": "unknown",
        "ownershipStable": False,
        "currentOwner": None,
        "localToken": None if in_force is None else in_force.token,
    }
    if in_force is None:
        # `FENCE-083`: no snapshot, so no preference list and no local token.
        return verdict
    if rel == RELATION_IDENTITY_MISMATCH:
        # `FENCE-082`: the routing key was derived under the sender's topology, so the local
        # preference list says nothing about the sender's shard and is not evaluated.
        return verdict

    owners, primary = replica_set(in_force, routing.routing_key_of(in_force, key))
    verdict["currentOwner"] = primary
    verdict["ownership"] = "owner" if self_id in owners else "notOwner"

    if rel == RELATION_BEHIND:
        held = retained.get(token["epoch"])
        if held is not None:
            then, _ = replica_set(held, routing.routing_key_of(held, key))
            verdict["ownershipStable"] = self_id in then and self_id in owners
    return verdict


def policy_outcome(verdict, policy, fenced=True):
    """`FENCE-111` through `FENCE-151`, and `ERR-040` through `ERR-045`.

    Returns the condition a recipient reports, or `None` where it serves the request.  The order of
    the tests is the precedence `ERR-045` states: `identityMismatch`, `unready`, `notOwner`, then
    `epochMismatch`.  `ERR-040` carries the owner in `currentOwner` rather than in `cause`.
    """
    if not fenced:
        # `FENCE-041`: an unfenced request takes the same policy as a `senderBehind` one.
        if policy == "stable":
            return None
        return {"code": 302, "name": "epochMismatch", "cause": "unfenced", "currentOwner": None}

    if verdict["relation"] == RELATION_IDENTITY_MISMATCH:
        return {"code": 303, "name": "identityMismatch", "cause": None, "currentOwner": None}
    if verdict["relation"] == RELATION_UNKNOWN_EPOCH:
        return {"code": 103, "name": "unready", "cause": None, "currentOwner": None}
    if verdict["ownership"] == "notOwner":
        return {"code": 301, "name": "notOwner", "cause": None,
                "currentOwner": verdict["currentOwner"]}
    if verdict["relation"] == RELATION_AHEAD:
        return {"code": 302, "name": "epochMismatch", "cause": "senderAhead", "currentOwner": None}
    if verdict["relation"] == RELATION_BEHIND:
        if verdict["ownershipStable"] and policy == "stable":
            return None
        return {"code": 302, "name": "epochMismatch", "cause": "senderBehind", "currentOwner": None}
    return None


def redirect_walk(start, refusals, max_redirects):
    """`FENCE-171`, `FENCE-181`, `FENCE-191`, and `ERR-043`.

    `refusals` maps a node identity to the `currentOwner` it names, or to `None` where it serves.
    Returns the attempted identities in order and the terminal outcome.
    """
    attempted = [start]
    followed = 0
    node = start
    while True:
        owner = refusals.get(node, None)
        if owner is None:
            return {"attempted": attempted, "outcome": "served", "redirectsFollowed": followed}
        if followed >= max_redirects:
            return {"attempted": attempted, "outcome": "redirectExhausted",
                    "cause": "boundReached", "redirectsFollowed": followed,
                    "condition": {"code": 304, "name": "redirectExhausted"}}
        if owner in attempted:
            return {"attempted": attempted, "outcome": "redirectExhausted",
                    "cause": "revisitedNode", "redirectsFollowed": followed,
                    "condition": {"code": 304, "name": "redirectExhausted"}}
        attempted.append(owner)
        followed += 1
        node = owner
