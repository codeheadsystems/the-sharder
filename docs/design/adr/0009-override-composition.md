# 0009. Override composition

Status: accepted. Date: 2026-09-16.

## Context

Tenant routing has requirements that a hash function cannot express. A contract says that one
customer runs on dedicated clusters. A data residency law says that European tenants are served only
from European regions. A migration says that one noisy tenant moves to its own cluster this week and
back next week.

These are exceptions to placement, and they have to compose with placement rather than replace it.
An implementation that special-cases them before the strategy runs loses the strategy's balance and
movement properties for every key. An implementation that applies them after the strategy runs, by
reordering the result, produces a preference list whose relationship to the underlying placement is
unclear and whose movement under a topology change is unbounded.

The two requirements are also different in kind. A contractual pin names the destination. A
residency rule names a set the destination must come from, and leaves the choice within that set to
placement.

## Decision

Overrides are a layer above the strategy, declared in the topology document, matched against the
routing key, and offering two composition modes.

A pin replaces the candidate ordering with an explicit ordered node list. The strategy does not run
for a pinned key.

A constraint restricts the eligible node set before the strategy runs. The strategy then places the
key across the constrained set exactly as it would across the full set, so balance within the set
and movement under a change to the set both follow the strategy's own properties.

An entry may carry both, in which case the pin applies and the constraint filters the pinned list.
An entry may also carry a `factor` that supersedes the document-level replication factor for the
keys it matches, which is how a replication factor per tenant class is expressed.

Matching is against the routing key, after the key transform. A transform that extracts a tenant
identifier from a compound key therefore lets one entry cover every key belonging to that tenant.

Matchers are `exact` or `prefix` over bytes, with a `utf8` or `base16` encoding of the value. The
precedence rule is total: an exact match beats every prefix match; among prefix matches the longest
matching prefix in bytes wins; where two entries still tie, the lower array index wins; and two
entries with identical matchers make the document invalid.

A directory is the exhaustive form of the same table, declared as `strategy.kind` of `directory`,
where a routing key matching no entry has no route. It shares the matcher syntax and the precedence
rule.

Pinned keys are exempt from the balance bound and from the minimal movement bound, and a conformance
vector asserting either bound excludes them. Constrained keys carry both bounds within their
constrained node set.

## Consequences

An operator expressing residency writes one entry per tenant prefix rather than one per tenant, and
the tenants within it stay balanced across the regions that are legal for them. Adding a legal
region rebalances that prefix's tenants across the enlarged set and moves no tenant outside it.

An operator expressing a contract writes an explicit node list and owns its failover order. A pin to
a node that is later removed from `nodes` makes the document invalid, which turns a silent routing
failure into a rejected topology.

A large directory is a large document. A hundred thousand pinned tenants is a document of several
megabytes that is digested and canonicalised on every load. Matching is a prefix structure built
once per snapshot, so the routing cost is bounded by key length rather than by table size, and the
load cost is not.

The override layer is on the routing path for every key, including keys that match nothing. That
cost is one lookup in a prefix structure, and a topology with no overrides skips the layer.

## Alternatives

Regular expression matchers. Expressive and familiar. Rejected because no two languages agree on
regular expression semantics for anything beyond the simplest patterns, and because a routing
decision that depends on a backtracking engine is both non-deterministic across ports and a denial
of service surface.

Glob or suffix matchers. Rejected for format version 1 because prefix matching covers the key
conventions these use cases follow, and because suffix matching would need its own precedence rule
against prefix matching. Either can be added as a minor version.

Pinning as an attribute on the node, listing the keys it owns. Rejected because it inverts the
lookup, so finding the entry for a key requires scanning every node, and because it spreads one
tenant's configuration across the document.

Applying overrides to the key rather than the routing key. Rejected because a pin would then have to
be written once per key of a tenant rather than once per tenant.

Reordering the strategy's output rather than filtering its input. Rejected because the resulting
list is not the strategy's list, so its movement under a topology change is not the strategy's
movement, and because the explain record would have to describe an ordering that no strategy
produced.

A separate pinned-tenant lookup service outside the topology document. Rejected because a routing
decision would then depend on two sources with two versions, and the fencing token would cover only
one of them.
