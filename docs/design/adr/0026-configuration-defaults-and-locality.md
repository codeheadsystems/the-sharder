# 0026. Configuration defaults and locality

Status: accepted, with the step budget adjustment withdrawn by
[`0057`](0057-step-budget-adjustment-withdrawal.md) and the split advice withdrawn by
[`0054`](0054-range-strategy-withdrawal.md). Date: 2026-09-16.

The locality rule, the units convention, the refusal of an out-of-range setting, and the rest of the
defaults stand. Three settings named below no longer exist. `budgetIncrement` and `maxStepBudget`
are withdrawn with `RATE-041` by `0057`, and `initialStepBudget` is now passed unchanged to every
step, so the additive increase the Consequences section weighs is not performed. The split advice
thresholds are withdrawn with `SPLIT-051` by `0054` along with the rest of the split surface. The
paragraphs below are kept as the record of what was decided on the date above.

## Context

The specification accumulates roughly fifty settings: provider timing, staleness policy, attempt
depth, a retry budget, thirteen health parameters, fencing policy, sixteen migration policy members,
and the reporting hooks. Each arrived with the section that needed it, and nobody chose the set as a
whole.

Two questions follow from that. The first is where a setting lives. The topology document already
carries a replication factor, a spread policy, and a strategy configuration, and every one of those
is agreed between callers by construction. A setting that changed placement and lived outside the
document would let two callers holding one snapshot compute different owners for one key, which
`PROP-045` forbids.

The second question is what a default means. The integrator who reads no documentation is the
integrator whose defaults matter, and a default is the library's opinion about the common case
stated in a place nobody reads. A default that is wrong in the common case is worse than a required
setting, because a required setting is a question and a wrong default is an answer.

Three settings carry that weight unevenly. The retention depth, the recipient fencing policy, and
`requireLinearisableCutover` each decide whether the library is safe or convenient when a cluster is
changing shape. The migration step budget is expressed in the integrator's own unit, which the
library cannot interpret at all, so any default for it is a guess about a number with no dimension.

## Decision

Every setting is supplied by the integrator at construction and none appears in a topology document.
A document member whose name matches a setting is an unknown member and a validation failure, which
the format's unknown-member rule already delivers.

No setting may change a candidate ordering, a shard identifier, a preference list, an effective
replication factor, or an ownership delta. Two routers configured differently that hold the same
snapshot compute the same preference list for the same key. A setting decides whether a call is
served at all, how far a caller walks, and what is reported, and nothing else. That line is what
keeps the local and the agreed apart.

A setting outside its range refuses construction rather than being clamped or ignored. Clamping
produces a running system with behaviour the operator did not ask for, and silently ignoring an
unrecognised setting produces a running system with behaviour the operator thinks they changed.

Every duration is an integer count of milliseconds, every size an integer count of octets, every
share an integer percentage. No setting is floating point, and a name carries its unit where the
unit is not implied. Stage 1 recommended `pollInterval`, `staleAfter`, and their neighbours without
units; the specification renames them to `pollIntervalMillis` and `staleAfterMillis` so that the
whole surface reads one way rather than two.

Defaults are chosen for the integrator who sets none of them.

- `stalePolicy` of `serve`, because the cache and tenant cases tolerate a briefly old view and the
  storage case has the fencing token to refuse what the router was willing to compute.
- `recipientPolicy` of `strict`, in the opposite direction, because the deployment that fences is
  the deployment whose worst failure is two nodes believing they own one shard.
- `requireLinearisableCutover` of true, so a plan built on hooks that cannot exclude two owners
  refuses rather than proceeds. The weaker guarantee is available and has to be asked for.
- `retentionDepth` of 3, enough for a recipient to judge ownership stability across a rolling
  topology change and small enough to bound memory.
- `maxRedirects` of 2, which covers the ordinary case of a caller one epoch behind a single move and
  stops a misconfigured cluster from turning a redirect into a walk.
- `refreshWaitMillis` of 0, so a recipient adds no hidden latency to a request it will probably
  refuse.
- `maxKeyBytes` of 65536, bounding the hash cost of one call without troubling any key anyone
  intends to route.
- `includeKeysInDiagnostics` of false, because a key is frequently a tenant identifier and a log is
  a wider audience than a caller.
- `initialStepBudget` of 1 with `budgetIncrement` of 1 and `maxStepBudget` of 64, so a migration in
  an unknown unit starts slowly and finds its rate rather than saturating a cluster on its first
  step.
- `catchUpResidualThreshold` of 0 and `reTransferResidualThreshold` of the largest u64, so a cutover
  waits for an empty residue and a handoff never returns to transferring until an integrator states
  a threshold in their own unit.
- The split advice thresholds unset, so no advice is emitted until an operator states one. The
  library has no basis for choosing a shard size.

Where the library needs periodic work it takes an executor from the integrator. Where none is
supplied it does not poll, and `refresh` is the call through which a document arrives.

## Consequences

Fifty settings is a large surface for a library whose core is a pure function. Most of it is the
health state machine and the migration policy, and both are optional: an integrator who ingests no
health signals and never plans a migration touches none of it.

Renaming Stage 1's provider settings to carry units breaks nothing yet, because no implementation
exists, and it means an operator reading a configuration dump never has to ask whether a number is
seconds or milliseconds.

Refusing construction on an out-of-range setting means a deployment fails at start rather than
behaving oddly in production. That is the intended trade and it makes a rolling restart with a bad
configuration fail fast and visibly.

The step budget defaults are the weakest part of the set. A budget of one unit per step is correct
for an integrator whose unit is a gigabyte and absurd for one whose unit is a row. The additive
increase to a ceiling of 64 recovers from the second case in a few steps and never overwhelms the
first, which is the best a library that cannot interpret the unit can do.

The default of `serve` for staleness and `strict` for the recipient is the same decision from two
ends: the router is permissive because it cannot know the consequences, and the recipient is strict
because it can.

## Alternatives

Carrying timing and policy settings in the topology document, so that an operator configures one
place. Rejected because the document is agreed between callers and these settings are local to one,
and because a health threshold in a document would be a health threshold every caller is forced to
share.

Clamping an out-of-range setting to its bound and emitting a warning. Rejected because the running
system then differs from the manifest and the difference is discoverable only in a log nobody reads.
Weight clamping is treated this way, and it is treated this way because a cluster-wide virtual node
cap is a legitimate cost control rather than a mistake.

A profile mechanism, with named bundles such as `cache`, `tenant`, and `storage`. Rejected for the
first version because a profile hides which setting produced a behaviour, and because the three use
cases differ in about six settings, which is small enough to list in an integration guide.

Requiring every safety-relevant setting explicitly, with no default. Rejected because it makes the
smallest integration, a static file provider routing a tenant identifier, carry a page of
configuration, and because a required setting that every integrator copies from an example is a
default with extra steps.

Floating-point settings for rates and shares. Rejected to keep one rule, that the library holds no
floating point outside a metric value, rather than a rule with a carve-out that a later author
widens.
