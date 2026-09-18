# Contributing to sharder

The sharder library is designed and not implemented. Two kinds of change are possible today: a
change to a document, and a change to the conformance suite through its generator. A third kind, a
change to the Java implementation, is not possible yet, because there is no Java implementation.
[`docs/design/99-roadmap.md`](docs/design/99-roadmap.md) gives the staging.

[`docs/maintain/style.md`](docs/maintain/style.md) is binding on every Markdown file in this
repository, including this one. A change that adds prose is written to it.

## Constraints on every change

Three things are fixed, and a change that needs one of them is a different conversation from the
change that revealed it.

A requirement identifier names one requirement permanently. It is never reassigned, never renumbered
to close a gap, and never reused after withdrawal.
[`docs/design/10-specification.md`](docs/design/10-specification.md#requirement-identifiers) states
the scheme, and
[`docs/design/10-specification.md`](docs/design/10-specification.md#withdrawn-identifiers) states
what happens to an identifier whose behaviour the specification stops stating.
[`conformance/manifest.json`](conformance/manifest.json) joins the suite to the specification on
that identifier, and the generator fails where the suite names one the specification does not state.

The generated tree is not edited by hand. Every file under `conformance/topologies/`,
`conformance/vectors/`, `conformance/properties/`, and `conformance/scenarios/` is computed by the
reference implementation, and a hand edit to one is lost at the next regeneration. A change to what
the suite covers is a change to a generator script.

A heading is a link anchor. Renaming one is an interface change that breaks every cross-reference
into it, so a rename carries the repairs in the same commit.

## Changing a document

1. Read [`docs/maintain/style.md`](docs/maintain/style.md) first. Most of it is subtractive, and a
   draft written without it usually needs the emphasis and the justification taken back out.
2. Use each term as [`docs/design/05-glossary.md`](docs/design/05-glossary.md) defines it. A term
   that carries a meaning in [`docs/design/10-specification.md`](docs/design/10-specification.md)
   carries that meaning everywhere else.
3. Put a fact in one document. Where a second document needs it, cross-reference the first rather
   than restating it. The exceptions are marked where they occur.
4. State no figure that something in the repository computes. The Computed figures section of the
   style guide names the artefacts that hold them.
5. Keep the RFC 2119 capitals inside
   [`docs/design/10-specification.md`](docs/design/10-specification.md). Every other document says
   "refuses" rather than `MUST refuse`.
6. Check that every relative link and every heading anchor still resolves.

Justification belongs in a decision record and nowhere else. A change that turns on a judgement
somebody could reasonably make the other way carries a new record under
[`docs/design/adr/`](docs/design/adr/), numbered with the next free number, in the form the existing
records use: Context, Decision, Consequences, Alternatives.

## Changing the specification

A change to [`docs/design/10-specification.md`](docs/design/10-specification.md) reaches the
conformance suite, so it is made in that order.

1. Add the requirement with the next free number in its group, or withdraw an existing one under the
   withdrawal convention. Do not reuse a number.
2. Record the reasoning in a decision record. The specification carries no justification of its own.
3. Decide whether a language-neutral data file can carry the requirement's output. Where it can,
   extend the generator so that the suite names the identifier. Where it cannot, say why under the
   group it belongs to in
   [`30-conformance.md`](docs/design/30-conformance.md#requirements-without-an-executable-test).
4. Regenerate the suite, as below. The run fails where the suite names an identifier the
   specification no longer states, which is the signal that step 1 missed something. It also fails
   where a withdrawn identifier has been stated again, cited by a live document, or named by the
   suite, and where a requirement is stated under a prefix the prefix table does not name, which
   `conformance/generator/verify_withdrawals.py` checks against both registers.

A change to a requirement, a vector, a topology, a scenario, or the reference implementation changes
the suite revision, and a port that declared a level against the previous revision declared it
against a different suite. The revision is computed as
[`adr/0061`](docs/design/adr/0061-suite-revision-identifier.md) states.

## Changing the conformance suite

The suite is the output of the reference implementation under
[`conformance/generator/`](conformance/generator/).
[`conformance/generator/README.md`](conformance/generator/README.md) gives the layout: one module
per subject under `sharder_ref/`, one generator script per vector family, `build_manifest.py` for
the manifest, `coverage.py` for the coverage report, and `verify_withdrawals.py` for the withdrawal
registers.

Regenerating needs `python3` and `openssl` on the path. The run checks SipHash-2-4 against the
published paper vectors and against `openssl mac` before it generates anything, and stops where
`openssl` is absent, so no vector file is written by a hash that has not been checked against an
independent implementation.
[`adr/0064`](docs/design/adr/0064-hash-verification-before-generation.md) records that choice. The
`jsonschema` package is the one optional dependency: the schema check reports that it is absent and
the run continues.

1. Change the generator, not the generated file.
2. Run the generator from its own directory.

   ```sh
   cd conformance/generator
   ./run.sh
   ```

3. Read the diff. A byte-identical tree is the expected result of a run that changed nothing, so a
   file that moved and was not meant to move is the finding the script exists to produce.
4. Run the reference driver over the result, which `run.sh` already does at the end of a full run.

   ```sh
   python3 conformance/driver/python/run_suite.py
   ```

The property witnesses take a few minutes, because a balance witness evaluates a hundred thousand
keys against every node of a topology. `python3 generate_properties.py --quick` uses sample sizes
below the specification's preconditions and is for checking a script rather than for publishing.

`generate_scale.py` takes tens of seconds, because it builds a ring of over a million tokens in
Python, and the driver takes the same again running the file it wrote. Both print what the step
cost, and neither figure is asserted by anything.

The collision search behind the tie-break vectors is not part of an ordinary run. It takes tens of
minutes per mode, its results live under `conformance/generator/collisions/`, and they change only
if the hash construction changes.
[`conformance/generator/README.md`](conformance/generator/README.md#searching-for-collisions) gives
the procedure.

## Review and checks

Two checks are specified to run inside the Gradle `check` task, and neither exists yet, because
`check` arrives with the first Java implementation. Until then every rule is enforced at review.

| Check | On a finding |
|---|---|
| `verifyDocLinks` | fails `check`; a cross-reference that does not resolve is a defect with no honest reading |
| `verifyDocStyle` | reports a warning and leaves `check` green; three of its four rules are judgements |

[`adr/0062`](docs/design/adr/0062-documentation-style-check-as-a-warning.md) records why the two
differ. A `verifyDocStyle` warning is a worklist entry for a reviewer, and the rules it names bind
a document whether or not the check runs.

The reviewer is what enforces register, justification, and terminology, which no regular expression
reads. A change that reads as an argument for the design rather than a description of it is sent
back whatever the checks say.
