# 0027. Hash seed exposure and tenancy

Status: accepted. Date: 2026-09-16.

## Context

ADR 0001 chose SipHash-2-4 partly for its keyed construction, and then defaulted the key to sixteen
zero bytes so that conformance vectors are reproducible across ports. Those two decisions are in
tension, and Stage 1 recorded the tension as an unresolved item rather than resolving it.

Placement is a pure function of the snapshot and the routing key. Every property the library sells
follows from that: two callers agree, a restart changes nothing, and a port in another language
produces the same answer. The same property is what an adversary uses. A party who knows the node
set and the seed computes `keyHash` offline and manufactures as many keys as it likes that land on
one shard, which is a denial of service against one node rather than against the cluster.

With the default seed, the seed is known to everyone. It is in this repository, in every conformance
vector, and in every port's test suite. A deployment that never sets `hash.seed` is running a
placement function whose inputs are entirely public.

Multi-tenancy sharpens the question in two directions. A tenant who can choose keys is exactly the
adversary above. A tenant who can read a routing decision or an explain record learns the cluster's
membership, its failure domain layout, and, where a pin matched, which nodes are dedicated to whom.

The library has no principal. It sees octets and a node set, and it cannot tell a tenant's key from
an operator's. Anything it did to defend a shard would be a guess applied to every caller.

## Decision

The exposure is stated normatively. The library is not to be relied upon to bound the load that
keys of a caller's choosing place on one node, and it does not refuse, rewrite, rate limit, or
reorder a key it judges adversarial. Admission control belongs to the caller, which sees the
principal behind a request.

The defence is a seed the adversary cannot read, plus detection after the fact. The seed is treated
as a secret: it is never logged, never placed in an error's detail, never placed in an event
payload, never placed in an explain record, and never exposed through an accessor on a snapshot or a
router. A comparison that reads the seed does not short-circuit on its octets.

The zero default is kept, because conformance vectors have to be reproducible and because a library
that generated a seed of its own would break `PROP-045` the moment two callers generated different
ones. The library never derives a seed from a topology identifier, a host name, a process
identifier, or a clock.

The exposure is surfaced instead of being suppressed. A snapshot carries `seedIsDefault`, so a
caller or an operator can always ask. An event is emitted once per accepted snapshot whose seed is
sixteen zero octets and whose document carries at least one override entry or a `directory`
strategy, because either is evidence that the topology separates tenants and therefore that keys are
likely to come from more than one principal. Stage 1 recommended this as a log line in the Java
binding; it is a normative event so that every port emits it.

Disclosure is stated as a caller obligation with library support. A routing decision names node
identities and is not assumed to be redacted, and a decision never carries an address, a node's
tags, an override's note, or the document's metadata. A decision names the index of the override
entry that matched and never the entries that did not. The explain record, which names the whole
eligible set, the domain layout, and the strategy's arithmetic, is an operator surface and is not to
be exposed on a path a tenant reaches. A fencing token carries the content digest only when an
integrator asks for it.

The library provides no tenant isolation. An override constraint restricts where a tenant's keys are
placed and does not prevent a caller from routing another tenant's key; it is a placement rule and
not an access control rule. Health state stays caller-local, so one tenant's traffic cannot eject a
node from another caller's view. A retry budget is per router across every key, so an integrator who
needs a per-tenant budget holds a router per tenant.

Resource bounds are stated where they are cheap. A key is bounded by `maxKeyBytes`, which bounds the
hash cost of one call, and an implementation indexes the exact matchers of a directory table and an
override table so that matching cost does not grow with the table for every key.

## Consequences

An operator who reads nothing gets a deployment with no adversarial resistance and one event saying
so, emitted only where the topology suggests more than one tenant. That is a compromise: it is
quieter than warning on every zero-seed topology, and it misses a multi-tenant deployment that
expresses tenancy through neither an override nor a directory.

Rotating a seed moves every key, so seed rotation is a full data migration rather than an
operational routine. A deployment that starts with the zero seed and later wants resistance pays a
migration to get it, which is the strongest argument for setting a seed on day one and the reason
`seedIsDefault` is on the snapshot rather than buried in a log.

Treating the seed as a secret means it cannot appear in an explain record, which removes the one
place an operator would naturally look to confirm which seed is in force. `seedIsDefault` answers
the question that matters without disclosing the value.

Declining to defend against crafted keys means the library cannot be pointed at as the control in a
threat model. The hot-shard detection of the observability contract is what it offers instead:
evidence that concentration is happening, which the caller acts on with the principal information
the library does not have.

Refusing to reveal unmatched override entries in a decision costs a debugging affordance. `explain`
has it, and `explain` is an operator surface.

## Alternatives

Generating a random seed at first load when none is configured. Rejected because two callers would
generate different seeds and compute different owners for one key at one epoch, which `PROP-045`
forbids outright. It is the most tempting alternative and the most clearly wrong.

Deriving the seed from the topology identifier, so that it is agreed without being configured.
Rejected because a topology identifier is not a secret. It appears in a fencing token on the wire,
so deriving the seed from it publishes the seed.

Requiring a non-zero seed, with a validation failure on sixteen zero octets. Rejected because
conformance vectors need the zero seed and because it would make the smallest possible topology
invalid, which is a poor first experience for a cache deployment with no adversary.

Rate limiting keys that concentrate on one shard. Rejected because the library has no principal, so
it would be limiting a shard rather than an attacker, which is the denial of service the attacker
wanted. It also would make placement depend on observed traffic, which breaks purity.

Adding a per-key secret salt that the caller supplies per call. Rejected because it makes placement
depend on something the caller varies, so two callers disagree about ownership, which is the same
objection as a generated seed.

Redacting node identities from a routing decision by default and requiring a caller to opt in.
Rejected because a routing decision whose node identities are redacted is not a routing decision.
The caller has to know where to send the request.

Warning on every zero-seed topology rather than on the ones that suggest tenancy. Rejected because a
single-tenant cache is the largest expected population and a warning that fires for all of them is a
warning every operator learns to suppress.
