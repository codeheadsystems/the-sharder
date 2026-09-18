# 0075. Affinity path length refusal

Status: accepted. Date: 2026-09-17.

Amends [`0014`](0014-read-affinity-as-a-separate-call.md), whose separate call, bounded window, and
stable partition all stand.

## Context

An `AffinityRequest` carries a `level` and a `path`. `READ-013` indexes `path` by `domainLevels`, so
it holds one identifier for every declared level from index 0 through the index of `level`. Record
[0042](0042-domain-path-scope.md) fixed that scope against the alternative of indexing the path
by `replication.spread`.

`READ-011` refused a `level` the document does not declare. Nothing refused a `path` of the wrong
length. A caller passing `["us-east"]` for a `level` of `zone`, which is the natural mistake once
the path spans the declared levels rather than the spread, compared a one-element tuple against
two-element domain paths. Nothing matched, the partition moved nothing, and the reordered list
equalled the preference list. `READ-023` states that exact outcome for a caller with no local
replica, so the two were indistinguishable: a caller with a wrong path read a wrong replica and
was told nothing.

The reference generator behaved the same way, taking `path[:cut]` and matching nothing.

## Decision

`READ-017` requires `path` to hold one identifier for each level of `domainLevels` from index 0
through the index of `level`, and refuses a request of any other length with the invalid-argument
condition. An implementation does not truncate the path, does not extend it, and does not fall back
to no affinity, which is what `READ-011` already requires for an undeclared level.

The refusal is an argument check and changes nothing about placement. A refused call returns no
decision, so the preference list, the replica prefix, the shard, and the fencing token are what they
were, and the rule of [`0014`](0014-read-affinity-as-a-separate-call.md) that read affinity never
changes ownership holds without qualification.

`ERR-025` is restated so that the two read-affinity triggers bind only an implementation that
exposes `readAffinity`. `ERR-025` belongs to `routing`, which every implementation exposes, and it
named `READ-011` unconditionally; an implementation declining the optional surface accepts no
affinity request and therefore raises the condition for none. That is the shape `SEC-032` was
repaired for, applied here.

## Consequences

A caller whose path is one level short learns so on the first call rather than through a latency
measurement months later. The condition is `invalidArgument`, whose caller response under `ERR-011`
is to correct the call, which is the correct response.

A caller that genuinely wants coarser affinity asks for it by naming the coarser `level`, which is
what `level` is for. Nothing is lost: the pair of a coarser level and its own path expresses every
request a short path could have been meant to express.

A port that declines `readAffinity` is bound by neither `READ-017` nor the clause of `ERR-025` that
names it, and a port that exposes the surface is bound by both.

The refusal is a behaviour change for a caller who was relying on the silence. No conformance vector
exercised a mismatched path, so nothing in the published suite moves, and a case is added that
asserts the refusal.

## Alternatives

Treating a mismatched path as no affinity, returning the preference list unchanged. Rejected because
that is the outcome `READ-023` gives a caller with no local replica, so the two are the same answer
to two different questions, and because `READ-011` already refuses to fall back to no affinity for
the neighbouring argument error.

Ignoring the extra or missing elements by truncating or padding. Rejected because a padded path
matches a domain the caller never named, and because truncation is what the reference did and is
what produced the defect.

Comparing only the levels the path supplies, so that a short path is a coarser request. Rejected
because it makes `level` and the path length two ways of saying the same thing that can disagree,
and because the comparison `READ-013` states is position by position through the index of `level`,
which a short path cannot satisfy.

Validating the length inside `READ-013` rather than as its own requirement. Rejected because that
requirement states the reordering, and a reader implementing it reads the length as a
precondition rather than as a check. A refusal needs a requirement of its own so that the
conformance suite can name it.
