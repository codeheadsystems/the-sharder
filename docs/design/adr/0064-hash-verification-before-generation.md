# 0064. Hash verification before generation

Status: accepted. Date: 2026-09-17.

## Context

Every expected value in the conformance suite descends from one function. A routing key, a ring
token, a rendezvous score, a shard identifier, a candidate ordering, and every preference list built
over one are computed from SipHash-2-4 as `conformance/generator/sharder_ref/siphash.py` implements
it. A defect in that file does not produce a failing suite. It produces a consistent suite of wrong
numbers, which the reference driver passes, because the driver runs the implementation that computed
the expectations.

Two independent checks stand between that file and the generated tree.
`conformance/generator/verify_siphash.py` runs the 64 test vectors the Aumasson and Bernstein paper
publishes, and runs the same 64 messages through `openssl mac -macopt digest:SIPHASH`. The paper
vectors are data in the repository. `openssl` is a second implementation, written by other people
from the same specification, and it is the only thing in the tree that is not downstream of
`sharder_ref`.

`run.sh` runs the check first, under `set -e`, so a failure stops the run before any generator
writes a file.

`CONTRIBUTING.md` described this differently. It said that `openssl` and the `jsonschema` package
are each used where they are present and that the run reports and continues where they are not, so
that a machine without them verifies less. That is true of `jsonschema`, which `verify_schema.py`
skips, and false of `openssl`, which fails the run.

## Decision

The code is right and the document was wrong. `openssl` is a requirement for regenerating the suite,
not an optional check, and the document now says so.

`verify_siphash.py` keeps returning a failure where `openssl` is absent, so `run.sh` stops. Its
output names the absent tool on its own line rather than reporting it as a test case of length
`openssl`, and says that the run stops rather than generating vectors from an unchecked hash.

`jsonschema` remains optional. The schema check validates generated topology documents against a
schema that lives in this repository, so both sides of that comparison are the design's own work and
skipping it loses a consistency check rather than an independent one.

## Consequences

A contributor on a machine without `openssl` cannot regenerate the suite and learns that from the
first line of output. Installing it is the fix, and it is present by default on every platform the
generator is run on.

The property that an expected value in the suite descends from a hash function two implementations
agree on holds for every regeneration rather than for the regenerations that happened to run
somewhere with `openssl` installed. Vendoring the suite into a port carries that property with it,
because the vectors were generated under it.

A run in a minimal container needs one package more than `python3`. That cost is paid once per
image, and the alternative is paid once per suite revision by every port that trusts the numbers.

## Alternatives

Making the check report and continue, which is what `CONTRIBUTING.md` described. Rejected because
the check has no value where it can be skipped by the absence of a tool: the run that most needs it
is a run on an unfamiliar machine, which is exactly the run most likely to lack `openssl`.

Vendoring a second SipHash implementation into the repository, in Python, so that the check needs
nothing on the path. Rejected because a second implementation transcribed by the same author from
the same paper is not independent evidence, and because the paper vectors already cover what a
transcription would catch.

Generating first and verifying afterwards, so that a machine without `openssl` still produces a
tree. Rejected because the tree is the artefact a port declares against, and a tree that exists is
a tree somebody commits.
