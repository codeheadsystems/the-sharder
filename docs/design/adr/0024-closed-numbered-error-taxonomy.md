# 0024. Closed numbered error taxonomy

Status: accepted, with the `noCandidate` causes ordered by
[`0076`](0076-ordered-rows-in-a-precedence-table.md). Date: 2026-09-16.

The closed set, the numbering, and the precedence of `ERR-008` are unchanged. `ERR-021` now
gives its causes as an ordered table of conditions rather than as a set of names, because more
than one condition holds for a key an override constrains over a strategy that would also have
produced no candidate.

## Context

The library is ported to several languages and each language has its own way of saying that a call
did not answer. Java throws, Go returns a second value, Rust returns a `Result`, and C returns an
integer. A taxonomy expressed as a class hierarchy is a Java artefact; a taxonomy expressed as a
string is not a taxonomy at all, because two ports will spell the same condition differently and a
caller that matches on the spelling breaks when it moves between them.

Three audiences read a failure. A caller decides whether to retry, and needs a decision it can make
without parsing prose. An operator reads a dashboard, and needs a stable label to group by. A
conformance author writes a vector that asserts a particular failure, and needs a value that is the
same in every port.

The specification produces about twenty distinct ways to decline, spread over three subject areas
written by three authors. Several pairs are close enough that an implementer would merge them. An
empty preference list and an exhausted attempt sequence are the sharpest pair: both mean the caller
has nowhere left to go, and they call for opposite responses. An empty preference list means the
topology has no node for this key and retrying changes nothing. An exhausted attempt sequence means
every node that could have served the key was tried and failed, which is a cluster condition that
may pass. ADR 0017 recorded that separation for the failover section; the taxonomy has to preserve
it rather than flatten it.

## Decision

The failure conditions of the library are a closed set of sixteen, each with a permanent numeric
code, a permanent name, and a fixed retryable flag. Codes are grouped by subject: 1xx for routing,
2xx for topology loading, 3xx for the recipient side of a fencing check, and 4xx for migration.

A binding maps every condition to one idiom consistently. It may group conditions into a hierarchy
whose leaves are these sixteen, and it may not introduce a leaf of its own. The code and the name
are what a conformance vector, a metric label, and a log line join on; `detail` is prose and is
never parsed.

Closely related conditions are separated by a `cause` drawn from a closed set rather than by
splitting the code. `noCandidate` carries why the ordering was empty, `exhausted` carries whether
the preference list, the attempt limit, or the retry budget ran out first, `planRefused` carries
which of seven plan preconditions failed, and `handoffFailed` carries one of the four failure kinds.
This keeps the top-level set small enough to memorise while preserving the distinctions an operator
acts on.

Four separations are made deliberately against the pull to merge them.

- `noCandidate` and `exhausted`, for the reason ADR 0017 gives.
- `staleDocument` and `staleSnapshot`. The first is an arriving document whose epoch is not above
  what is in force, which is a provider condition. The second is the snapshot in force having aged
  past its bound under a policy that refuses to serve it, which is a caller condition. Stage 1 named
  one condition, "stale topology", and the two turn out to be different events with different
  owners.
- `epochMismatch` and `identityMismatch`. A refresh resolves the first and never resolves the
  second, because epochs under two topology identifiers are incomparable.
- `notOwner` and `redirectExhausted`. The first is an instruction to try one more node; the second
  is the end of the walk.

A replication shortfall is not a condition. A preference list shorter than the replication factor is
a successful decision that the caller judges, reported through the decision and through an event.

Where several conditions hold for one call, the evaluation order is fixed: `invalidArgument`,
`unready`, `staleSnapshot`, `noCandidate`, `exhausted`. Without a fixed order two ports report
different codes for the same call against the same topology.

A condition does not carry the key or the routing key unless an integrator turns that on, because a
key is frequently a tenant identifier, an account number, or a user identifier, and a log is a wider
audience than a caller.

## Consequences

Sixteen codes is more than a caller wants to handle and fewer than an operator wants to distinguish.
The `cause` member absorbs the difference, and a caller that switches on the code alone behaves
correctly while an operator who groups by code and cause sees the finer picture.

Permanent codes mean a condition that turns out to be wrong cannot be removed, only marked withdrawn
with its code reserved. That is the cost of making the code a join key for tests and dashboards that
outlive any one version.

Numeric codes are readable in a language with no exception hierarchy and in a log aggregator that
never saw the library's source. They also invite a caller to hard-code an integer, which is why the
name is required alongside the code in every serialisation.

The separation of `staleDocument` from `staleSnapshot` costs a reader one moment of confusion at
first reading and saves an operator from chasing a provider fault when the fault is a staleness
policy, or the reverse.

Forbidding keys in diagnostics by default means the first debugging session with a misrouted key is
harder than it would otherwise be. The setting that turns it on exists for that session, and the
default is the one that is safe in a shared log.

## Alternatives

An exception hierarchy as the normative model, with other bindings emulating it. Rejected because it
is a Java artefact and because a hierarchy invites a caller to catch an intermediate node whose
membership changes in a later version, which is a compatibility hazard a flat set does not have.

Names alone, without numbers. Rejected because a metric label, a JSON payload, and a C binding all
want a small stable value, and because two ports will eventually spell a name differently while
neither will mistype an integer that a vector asserts.

An open set, extensible by a binding or by a registered strategy. Rejected because a caller cannot
write a complete handler against an open set, and because the value of the taxonomy is precisely
that a caller can enumerate it.

A single `RoutingError` with a discriminant field. Rejected because it makes every condition the
same type in every binding, which loses the one thing a language's own idiom is good at, and because
it makes the retryable decision a runtime read rather than a property of the thing caught.

Merging `noCandidate` and `exhausted` into one unroutable condition, which several routing libraries
do. Rejected under ADR 0017: the two ask for opposite responses, and a caller that retries a
`noCandidate` retries forever.

Deriving retryability from the condition at the call site rather than fixing it per condition.
Rejected because two callers would then reach different conclusions about the same failure, and
because the property is genuinely a property of the condition.
