# 0013. Range bounds over the routing key

Status: accepted. Date: 2026-09-16.

## Context

The `range` strategy compares a key against half-open interval bounds rather than hashing it. Every
other strategy consumes the routing key, which is the key after the key transform, and override
matching consumes the routing key as well. The range strategy could plausibly compare the bounds
against the original key instead, since its ordering is over the keyspace rather than over hash
space and a caller who declares range bounds is declaring them over keys the operator can read.

The two readings give different shards for the same key whenever a key transform is configured.

## Decision

The `range` strategy compares the routing key against its bounds. A topology that configures a key
transform and a range strategy therefore declares its bounds over transformed keys.

## Consequences

One definition of the routing key holds for the whole routing path. A key transform, an override
matcher, a hash strategy, and a range bound all see the same octets, so an explain record reports
one value rather than two.

A key transform under `range` co-locates keys the same way it does under a hash strategy. A
`prefixFields` transform with a separator of `:` and a count of 1 puts every key of one tenant in
one range, which is the behaviour an operator configuring both features expects.

An operator who declares range bounds over full keys and then adds a key transform changes every
shard boundary. That is a keyspace change and it moves data, in the same way that changing the hash
seed does.

A topology that wants bounds over untransformed keys declares no key transform and performs the
extraction in the caller.

## Alternatives

Comparing bounds against the original key. Rejected because the routing key would then mean one
thing for four strategies and for override matching, and another thing for one strategy, and a
reader of an explain record would have to know which strategy was configured to know which octets
the record refers to.

Declaring a key transform and a `range` strategy mutually exclusive. Rejected because co-locating a
tenant's keys in one range is a legitimate configuration, and because the exclusion would be a
validation rule that exists only to avoid stating this decision.

A per-strategy flag selecting which octets the bounds compare against. Rejected because it adds a
field whose two settings produce different data layouts for the same document, and because no use
case asked for the second setting.
