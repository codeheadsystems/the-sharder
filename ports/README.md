# Ports

An implementation of the sharder library in one language, with its build rooted in its own
directory. [`../docs/design/35-port-conventions.md`](../docs/design/35-port-conventions.md) states
what every port carries whatever the language, and
[`adr/0079`](../docs/design/adr/0079-repository-layout-for-multiple-ports.md) records the layout.

| Port | Directory | Status |
|---|---|---|
| Java | [`java/`](java/) | one module, declaring `hash`, `place`, `core`, `scale`, and `failover` with all four strategy surfaces, designed in [`../docs/design/40-java-binding.md`](../docs/design/40-java-binding.md) |

A directory appears here when work on a port starts.
[`../docs/design/99-roadmap.md`](../docs/design/99-roadmap.md#later-releases) names the ports the
roadmap schedules, and [`../conformance/declarations/`](../conformance/declarations/) holds what
each port declares once it reaches the four mandatory levels.

Every port carries an executable `build.sh` that builds and tests it, and
[`../build.sh`](../build.sh) at the repository root runs them all. Continuous integration runs the
root script over a matrix it discovers from this directory, so a port that lands is built without a
workflow changing.

The suite every port runs is [`../conformance/`](../conformance/), read at a relative path from the
port's own root. No port copies a vector file into its source tree by hand, and no port shares
source with the reference implementation that computed the suite's expected values.
