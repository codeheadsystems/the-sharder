# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

## Repository contents

`sharder` is a language-agnostic sharding and routing library: given a key and a view of the world,
it answers which nodes handle that key, in what order, and what happens when those nodes are
unavailable. The design is complete and no implementation exists. What is here is the normative
specification, the topology document format and its JSON Schema, the decision records, the port
conventions and the Java binding design, and a conformance suite of language-neutral data files
computed by a Python reference implementation.

Three kinds of change are possible today: a change to a Markdown document, a change to the
conformance suite through its generator, and starting a port under `ports/`. No port has been
written, so there is no Gradle build, no `check` task, and no library source of any language;
[`docs/design/99-roadmap.md`](docs/design/99-roadmap.md) gives the staging.

## Commands

Regenerating the conformance suite is the only build in the repository. It needs `python3` and
`openssl` on the path; `jsonschema` is optional and its absence is reported rather than fatal.

```sh
cd conformance/generator
./run.sh                       # verify, generate, rebuild the manifest, run the driver
./run.sh --search              # the same, preceded by the collision search
```

`run.sh` verifies SipHash against the published paper vectors and against `openssl mac` before it
writes anything, then runs each generator in order, validates every topology against the schema,
rebuilds `manifest.json`, computes coverage, checks the withdrawal registers, and runs the reference
driver. A run that changed nothing produces a byte-identical tree, so the diff is the result.

Individual steps, run from `conformance/generator/`:

```sh
python3 verify_siphash.py                    # 64 paper vectors, plus OpenSSL
python3 generate.py                          # strategy, transform, digest, validation, determinism
python3 generate_properties.py --quick       # sample sizes below spec preconditions; checking only
python3 generate_scale.py                    # two 1000-node documents; tens of seconds
python3 build_manifest.py                    # manifest and suite revision
python3 coverage.py --check                  # coverage.json against the specification
python3 verify_withdrawals.py                # no withdrawn identifier restated, cited, or named
python3 verify_declarations.py               # each port's declaration against the manifest
```

Running the suite:

```sh
python3 conformance/driver/python/run_suite.py                        # everything
python3 conformance/driver/python/run_suite.py --level core           # core and what it requires
python3 conformance/driver/python/run_suite.py --strategy rendezvous,directory
python3 conformance/driver/python/run_suite.py --verbose
```

Timing: the property witnesses take a few minutes, `generate_scale.py` and the driver's scale level
take tens of seconds each, and the collision search takes tens of minutes per mode. The search
results under `conformance/generator/collisions/` are committed inputs and change only if the hash
construction changes.

A benchmark source builds standalone and depends on nothing in the repository:

```sh
cd bench && cc -O2 -o hash-short-input hash-short-input.c && ./hash-short-input
```

## Structure

| Path | Contents |
|---|---|
| `docs/design/10-specification.md` | the normative specification, one numbered requirement per rule |
| `docs/design/20-topology-format.md`, `topology-v1.schema.json` | the document format and its schema |
| `docs/design/30-conformance.md` | the suite design, the driver contract, the levels, and coverage |
| `docs/design/35-port-conventions.md` | what every port carries whatever the language |
| `docs/design/40-java-binding.md` | the Java rendering the first implementation follows |
| `docs/design/adr/` | the decision records, the only place argument is the content |
| `docs/maintain/style.md` | binding on every Markdown file in the repository |
| `conformance/generator/sharder_ref/` | the Python reference, one module per subject |
| `conformance/{topologies,vectors,properties,scenarios}/` | generated; never edited by hand |
| `conformance/manifest.json`, `coverage.json` | generated indices, counts, digests, suite revision |
| `conformance/declarations/` | one declaration per port, hand-written, checked against the manifest |
| `conformance/driver/python/run_suite.py` | the worked example driver a port copies |
| `ports/<port>/` | one implementation, rooted in the build its ecosystem expects |

[`docs/README.md`](docs/README.md) routes by reader and names sections rather than whole documents;
use it to find where a fact lives before searching.

## The model the documents share

Three structures join every document, the suite, and any future port.

A requirement identifier, `PREFIX-NNN`, names one requirement permanently. It is never reassigned,
never renumbered to close a gap, and never reused after withdrawal. `conformance/manifest.json`
joins the suite to the specification on that identifier, and the generator fails where the suite
names one the specification does not state, or where a withdrawn one is restated or cited. The
prefix table in [`10-specification.md`](docs/design/10-specification.md#requirement-prefixes) is the
closed set of prefixes.

A conformance surface is a named part of the behaviour an implementation either exposes whole or
not at all: `routing`, the four strategy surfaces, `failover`, `readAffinity`, `fencing`, and
`migration`. Every requirement belongs to exactly one, through its prefix.

A port is an implementation in one language, under `ports/<port>/`, where `<port>` is the lower
case name of that language. It reads the suite from `conformance/`, carries a driver of its own,
and publishes `conformance/declarations/<port>.json` once it reaches the four mandatory levels.
`conformance/generator/verify_declarations.py` checks that file against the manifest and the
specification, and [`docs/design/35-port-conventions.md`](docs/design/35-port-conventions.md) states
what every port carries.

A conformance level is a set of vector files, properties, and scenarios a port runs in full, each
testing one surface: `hash`, `place`, `core`, `scale`, `failover`, `readAffinity`, `fencing`, and
`migration`, with the requires relation the `levels` table of the manifest carries. A port declares
levels, not a percentage, against the suite revision in `manifest.json`.

The planned Java build is Gradle with the Kotlin DSL rooted at `ports/java/`, group
`com.codeheadsystems`, artifacts `sharder-api`, `sharder-core`, `sharder-migrate`,
`sharder-provider-file`, `sharder-conformance`, `sharder-conformance-vectors`, `sharder-bom`, and
`sharder-bench`, with `buildSrc` carrying `verifyDocLinks`, `verifyDocStyle`, and
`verifyUnsignedComparisons`. None of it exists yet, so every rule those tasks would enforce is
enforced at review.

## Rules a change obeys

[`docs/maintain/style.md`](docs/maintain/style.md) binds every Markdown file, including this one.
Read it before writing prose. Its load-bearing rules: third person and present tense about the
library, bold only for a defined term's first occurrence, no em dashes, serial commas, British
spelling in prose with source spelling for identifiers, prose wrapped at 100 columns, and
noun-phrase headings of eight words or fewer with no verb of judgement.

- Justification belongs in a decision record under `docs/design/adr/` and nowhere else. A change
  turning on a judgement somebody could reasonably make the other way carries a new record, numbered
  with the next free number, in the form Context, Decision, Consequences, Alternatives.
- The generated tree is not edited by hand. A change to what the suite covers is a change to
  `conformance/generator/topologies.py` or to a generator script.
- A heading is a link anchor. A rename carries the repairs to every cross-reference in the same
  commit.
- State no figure that something in the repository computes. Name the artefact and the field
  instead; counts live in `manifest.json` and `coverage.json`.
- Put a fact in one document and cross-reference it from the second.
- Keep the RFC 2119 capitals inside `10-specification.md`. Every other document says "refuses".
- Use each term as [`docs/design/05-glossary.md`](docs/design/05-glossary.md) defines it.

A change to the specification reaches the suite, so it is made in that order: add or withdraw the
requirement, record the reasoning in a decision record, extend the generator where a data file can
carry the requirement's output or say why it cannot under
[`30-conformance.md`](docs/design/30-conformance.md#requirements-without-an-executable-test), then
regenerate and read the diff. [`CONTRIBUTING.md`](CONTRIBUTING.md) carries the whole procedure.

Commit subjects are imperative sentences with no type prefix, and the body explains what the change
repairs and why, at length where the change is a specification repair.
