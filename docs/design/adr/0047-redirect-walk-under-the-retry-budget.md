# 0047. Redirect walk under the retry budget

Status: accepted. Date: 2026-09-17.

## Context

The library budgets failover retries. `FAIL-030` accounts first attempts against retries over a
sliding window, `FAIL-031` gives the comparison, `FAIL-033` holds the budget per router across every
key so that a storm cannot be assembled from many keys, and `CFG-022` records the reasoning behind
the defaults.

The redirect walk of `FENCE-171` had no budget. `FENCE-181` bounds one request at `maxRedirects`,
defaulting to 2, and nothing bounded the aggregate. The two walks fail differently. A failover retry
fires when one node fails, which is largely uncorrelated across callers. A redirect fires when a
caller and a recipient disagree about the epoch, which is entirely correlated: a configuration push
that reaches half the fleet, a control plane outage that freezes one availability zone's callers, or
a rollout that installs a new epoch on callers before recipients puts every affected caller into the
walk at once. At the default bound that is up to threefold request amplification arriving across the
fleet at the moment the control plane is already unhealthy.

Nothing in the existing surface refused it. A redirect is not a `next` on an `AttemptSequence`, so
`FAIL-030` did not see it, and `FAIL-032` makes a first attempt always permitted, so the request
that starts a walk is never refused either. `sharder.fencing.redirects` counted the walk and
governed nothing.

The shape has to be right now. `ERR-010` is closed at sixteen conditions under `ERR-001`, `OBS-011`
fixes the value set of `attempts.exhausted`'s `cause` label, and `OBS-001` requires a metric to
carry exactly the labels given. A cause or a label added after publication is a change to a
taxonomy that `99-roadmap.md` lists as a one-way door.

## Decision

A followed redirect is a retry. `FAIL-030` accounts it in the window it already keeps, `FENCE-231`
states that it consumes no attempt limit, and no new setting appears in `CFG-040`.

`FENCE-221` puts the walk on the attempt sequence as `followRedirect(owner, at)`. That is the object
that already holds the identities attempted for the request, which `FENCE-191` needs, the bound of
`CFG-040`, which `FENCE-181` needs, and the retry budget, which `FENCE-231` now needs. A caller that
followed a redirect without telling the library could not be governed, and `FENCE-191` was a caller
obligation the library could not check; both are answered by the same call.

The refusals are evaluated in a stated order: the bound, an already attempted identity, an identity
absent from the snapshot, and last the budget. The first three end the walk whatever the budget
says, so evaluating them first keeps a walk that was going to end from spending budget that another
request could use.

`ERR-043` gains a `cause`, closed at `boundReached`, `revisitedNode`, `unknownNode`, and
`retryBudget`. The first two are the causes the shipped scenario already emitted with no requirement
behind them. `unknownNode` is the refusal `FENCE-171` already required and reported under no
condition. `retryBudget` is the new one. The set is total over the ways the walk ends without a
node, which is what makes it safe to close.

No metric changes its name or its labels. A budget-refused redirect is a budget-refused retry, so it
counts in `sharder.attempts.retries_refused`, and `sharder.routing.errors` separates it from a
failover exhaustion by the code 304 it already carries.

## Consequences

One number bounds the load the library adds. An operator who sets `retryBudgetPercent` gets that
share of first attempts as extra requests in total, whether they come from a node failing or from
half the fleet holding a stale epoch. Two budgets would have bounded each walk and left the product
unbounded, which is the quantity the cluster feels.

A stale caller under a spent budget gets `redirectExhausted` with a cause of `retryBudget` rather
than a served request. That is the load shedding `CFG-022` describes, applied to the case it was
written for: the cluster stops multiplying its own load while the control plane is unhealthy. The
caller's response is the refresh `FENCE-201` already asks for, which removes the staleness rather
than paying for it on every request.

The two walks compete. A failover storm can spend the window and leave a redirect refused, and the
reverse holds. That is the intended behaviour of a shared budget: the resource being protected is
requests the cluster serves, and it does not care which walk produced them. An integrator who finds
the interaction too tight raises `retryBudgetPercent`, which raises both.

An integrator has work to do. A caller that follows redirects itself, outside the attempt sequence,
now calls `followRedirect` to stay conforming. The call answers the node or the condition, so the
integration is a replacement for the caller's own bound check and its own attempted set, not an
addition to them.

`FENCE-191` and `FENCE-171`'s absent-identity rule become checkable. The scenario
`redirect-walk-depth-limit.json` gains cases for an unknown identity and for a refusing budget, and
the causes it already emitted now have a requirement behind them.

## Alternatives

A redirect budget of its own, with its own window, percentage, and minimum. Rejected because the
worst case is then the product of two budgets rather than the sum under one, and because an operator
tuning amplification would have two numbers whose interaction is not stated anywhere. It would also
have added three settings to `CFG-040` and a metric or a label value for refusals, and `OBS-001`
fixes a metric's labels, so the label would have been a published surface added for a second
mechanism.

Leaving the walk ungoverned and relying on `maxRedirects` alone. Rejected because `maxRedirects`
bounds one request and the failure mode is fleet-wide and correlated. An operator's only lever would
be to set the bound to 0 across the fleet, which turns every stale request into a failure rather
than shedding the excess.

Counting a redirect against the attempt limit instead of the budget. Rejected because the attempt
limit is per request and per preference list, so it bounds the same thing `maxRedirects` already
bounds and still leaves the aggregate free. It would also make the two bounds interfere in a way
neither name suggests: a factor 1 call with a limit of 3 would have its failover depth eaten by a
redirect.

Refusing the walk at the recipient rather than at the caller. Rejected because a recipient refusing
`notOwner` without naming `currentOwner` leaves the caller with no route to the shard at all, and
because the amplification is measured at the caller, which is where the requests are made.

Treating the first redirect as a first attempt, exempt under `FAIL-032`. Rejected because the first
attempt of the request has already been made and refused; the exemption exists so that the budget
cannot make a key unroutable, and a key whose owner is reachable at the epoch the caller holds stays
routable without it.
