# 0082. Continuous integration and dependency updates

Status: accepted. Date: 2026-09-18.

## Context

[`0079`](0079-repository-layout-for-multiple-ports.md) put every port in one repository so that a
change under `conformance/` reaches every port in the commit that makes it, and said that
continuous integration runs one job per port. Nothing ran anything: no workflow existed, and the
fan-out that the layout was chosen for was a statement about the future.

A second question arrives with the first port's build file. The port carries dependencies, all of
them test scope under [`0032`](0032-dependency-free-json-and-canonicalisation.md), and so do the
workflows, whose actions are dependencies of the build rather than of the library. Both go stale,
and a repository whose updates arrive in a heap once a year is a repository where each one is a
small investigation.

The ports do not share a build tool and will not. A Gradle invocation, a `go test`, a `cargo test`,
and a `rake` are four commands, and a workflow that knows all four is a workflow edited by every
port that lands. What the repository needs from a port is an answer to one question: build yourself,
and say whether you succeeded.

## Decision

Each port carries an executable `build.sh` in its own directory, which builds and tests that port
from that directory and exits non-zero on failure. `build.sh` at the repository root runs them: with
no argument it runs every port that carries one, with arguments it runs the ports named, and
`--list` names them. A port that lands carries its script and is built by the root script and by
continuous integration without either one changing.

Three workflows run under `.github/workflows/`.

`ports.yml` discovers the ports from `./build.sh --list`, builds each as one job of a matrix, and
sets up the toolchain each port's marker file calls for. A final job, `every port built`, succeeds
where every matrix job succeeded, so one check name stands for a matrix whose length changes.

`suite.yml` runs what the suite asserts about itself without regenerating it: the SipHash
verification, the schema validation, the coverage check, the withdrawal registers, the declaration
check, and the reference driver. Regeneration stays a maintainer's step under
`conformance/generator/run.sh`, because it takes minutes and a run that changed nothing produces a
byte-identical tree.

Neither workflow carries a path filter. A required check that a path filter skipped never reports,
and a pull request waiting on a check that never reports waits for ever. Both are a minute of
runner time, which is less than the arithmetic of deciding when to skip them.

Dependabot watches two ecosystems weekly on Wednesdays: the actions the workflows run, and the
Gradle dependencies of the Java port. Each ecosystem opens one grouped pull request rather than one
per dependency.

`dependabot-auto-merge.yml` approves a Dependabot pull request and queues it with `gh pr merge
--auto`, which merges it once every required check has passed and holds it where one has not.

Changes land through a pull request from here, and the branch protection of `main` is what requires
the two checks. Without a required check, a queued merge of an already mergeable pull request
happens at once, which is the whole of what stands between a dependency update and merging itself.

That protection is repository configuration rather than a file here, and it is set to require
`every port built` and `verify the suite against the specification`, to require no approving review,
and to leave an administrator free to push to `main` directly. A contributor who is not an
administrator, and every pull request including a Dependabot one, satisfies both checks to merge.

## Consequences

A regenerated suite runs against every port in the pull request that regenerates it, which is the
property [`0079`](0079-repository-layout-for-multiple-ports.md) chose the layout for and the first
thing about that layout to become mechanical rather than stated.

A port's build command stops being repository knowledge. What a contributor runs locally,
`./build.sh`, is what continuous integration runs, so a build that passes locally and fails in the
workflow is a difference in the environment rather than in the command.

The arrangement was demonstrated by the two updates that landed before the protection was set:
both merged the moment they opened, and both passed the builds afterwards. That is the failure mode
this section describes rather than a hypothetical one, and what changed is the protection rather
than the workflow.

An update merges with no human reading it. Every dependency here is test scope or an action, so an
update that passes the suite and every port's build has demonstrated what a reviewer would have
checked, and the library's published artifact carries none of them either way. What this accepts is
a compromised release of a test dependency or of an action, which passes the builds and reaches the
default branch without a person seeing it. The exposure is a contributor's machine and the runner,
not a consumer of the library, and the alternative is a queue of dependency pull requests nobody
reads and everybody approves.

Restricting auto-merge to patch and minor updates is a condition on one step, because
`dependabot/fetch-metadata` already reports the update type. It is not applied: a major version of a
test dependency that passes every build is the case the builds are for, and a major version that
does not pass stays open.

The toolchain setup in `ports.yml` names four ecosystems, of which one exists. A port in a fifth
ecosystem adds its setup step, which is the one place a new port touches a workflow.

## Alternatives

One workflow per port, written when the port lands. Rejected because the matrix is what makes the
fan-out automatic, and because four workflows that differ by a toolchain step and a directory name
drift apart.

A build tool at the root, driving Gradle, Cargo, and the rest beneath it. Rejected in
[`0079`](0079-repository-layout-for-multiple-ports.md) for the reason that stands here: a wrapper
adds a layer every contributor has to learn while removing none of the builds beneath it.

Regenerating the suite in continuous integration and failing on a tree that moved. Rejected for now
because the property witnesses take minutes on every pull request, and because the regeneration
diff is the maintainer's signal rather than a gate. A scheduled run of `run.sh` is the shape that
buys the check without the cost, and this record does not schedule one.

Dependabot on a daily interval. Rejected because the suite, the ports, and the specification move
in commits a person writes, and a dependency pull request a day is a repository whose history is
mostly dependencies.

Auto-merge without a required check, trusting the workflows to have run. Rejected on the mechanism:
a queued merge of a mergeable pull request is an immediate merge, so an update would land before a
build had started.
