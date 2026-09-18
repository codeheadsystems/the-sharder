#!/usr/bin/env python3
"""The observability contract as data: the metric and event inventories, and what a publication
emits.

`OBS-010` names every metric with its label set and `OBS-020` names every event with its severity
and its payload members.  Both are cross-language string contracts of exactly the shape a data file
carries well, and the register of `10-specification.md` makes each name permanent, so a port that
emits nothing, or a name spelled differently, or a payload member missing, is a port that has
broken a published contract.

The inventories are read out of `10-specification.md` rather than transcribed here.  A table row is
the specification's own statement of the contract, so parsing it makes a metric added, renamed, or
relabelled reach the suite at the next regeneration, and makes a rename fail a port rather than go
unnoticed.  The script refuses a table it cannot parse rather than generating a smaller inventory.

A metric and an event belong to the surface the first segment of its name gives, under the
Conformance surfaces section of the specification, so the inventory is split by that segment and
each part sits at the level that tests its surface.

    python3 generate_observability.py [--out <conformance root>]
"""

import argparse
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import topologies as T                                          # noqa: E402
from generate import write_json                                 # noqa: E402
from generate_scale import SCALE_RENDEZVOUS, SCALE_RING         # noqa: E402
from sharder_ref.observability import publication_events        # noqa: E402

SPECIFICATION = HERE.parent.parent / "docs/design/10-specification.md"

# Every metric and event name carries this prefix, which the specification's tables omit for width.
PREFIX = "sharder."

# The surface of a metric or an event, by the first segment of its name, as the Conformance
# surfaces section of `10-specification.md` states it.  Every other segment is `routing`.
SURFACE_OF_SEGMENT = {"health": "failover", "fencing": "fencing", "migration": "migration"}

# The level that tests each surface, which is where the inventory of that surface sits.  `core` is
# the lowest level at which a port has a snapshot lifecycle to report on.
LEVEL_OF_SURFACE = {"routing": "core", "failover": "failover", "fencing": "fencing",
                    "migration": "migration"}

IDENTIFIER = re.compile(r"`([^`]+)`")


def read_block(text, opening, closing):
    """The specification text between two requirement paragraphs, refusing an absent one."""
    start = text.index(opening)
    end = text.index(closing, start)
    return text[start:end]


def table_rows(block):
    """Every pipe-delimited row of a block, with the header and the rule row dropped."""
    rows = []
    for line in block.splitlines():
        line = line.strip()
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip("|").split("|")]
        if all(set(cell) <= {"-", ":"} and cell for cell in cells):
            continue
        rows.append(cells)
    return rows


def parse_metrics(text):
    """The three tables of `OBS-010`, each under the heading naming its instrument."""
    block = read_block(text, "`OBS-010`. An implementation MUST expose these metrics.",
                       "`OBS-011`.")
    instruments = {"Counters.": "counter", "Gauges.": "gauge", "Histograms.": "histogram"}
    current = None
    metrics = []
    header_seen = set()
    for line in block.splitlines():
        stripped = line.strip()
        if stripped in instruments:
            current = instruments[stripped]
            continue
        if not stripped.startswith("|"):
            continue
        cells = [cell.strip() for cell in stripped.strip("|").split("|")]
        if all(set(cell) <= {"-", ":"} and cell for cell in cells):
            continue
        if cells[0] == "Name":
            header_seen.add(current)
            continue
        name, labels, meaning = cells[0], cells[1], cells[2]
        metrics.append({
            "name": PREFIX + IDENTIFIER.match(name).group(1),
            "instrument": current,
            "labels": sorted(IDENTIFIER.findall(labels)),
            "meaning": meaning,
        })
    if header_seen != set(instruments.values()) or not metrics:
        raise SystemExit("OBS-010: the metric tables did not parse; the inventory would be short")
    return metrics


def parse_events(text):
    """The table of `OBS-020`, with its severity column and its payload member names."""
    block = read_block(text, "`OBS-020`. An implementation MUST emit every event of this table",
                       "`OBS-021`.")
    events = []
    for cells in table_rows(block):
        if cells[0] == "Name":
            continue
        name, when, severity, payload = cells[0], cells[1], cells[2], cells[3]
        events.append({
            "name": PREFIX + IDENTIFIER.match(name).group(1),
            "when": when,
            "severity": IDENTIFIER.match(severity).group(1),
            "payload": IDENTIFIER.findall(payload),
        })
    if not events:
        raise SystemExit("OBS-020: the event table did not parse; the inventory would be short")
    return events


def surface_of(name):
    return SURFACE_OF_SEGMENT.get(name[len(PREFIX):].split(".", 1)[0], "routing")


# The common members of `OBS-021`, which every event carries whatever its surface, and the closed
# severity set the same requirement states.
COMMON_MEMBERS = ["name", "instant", "topologyId", "epoch", "severity"]
SEVERITIES = ["info", "warning", "error"]

# The closed label vocabularies of `OBS-011`.  Each value below is checked against the requirement
# paragraph before it is written, so a vocabulary that moves in the specification fails here.
LABEL_VALUES = {
    "sharder.routing.decisions": {"outcome": ["decided", "failed"]},
    "sharder.topology.documents": {"outcome": ["installed", "noop", "unchanged", "rejected"]},
    "sharder.routing.override_matched": {"mode": ["pin", "constrain", "both"]},
    "sharder.routing.shortfall": {"cause": ["nodes", "domains"]},
    "sharder.attempts.exhausted": {"cause": ["preferenceList", "attemptLimit", "retryBudget"]},
}

# The deduplication rule of `OBS-024`, which names four events and says nothing about the rest.  An
# event the requirement does not name carries no `deduplication` member, because the suite asserts
# what the specification states and not a default it does not.
DEDUPLICATION = {
    "sharder.routing.shortfall": "epochShardCause",
    "sharder.routing.spread_relaxed": "epochShardCause",
    "sharder.routing.exhausted": "perOccurrence",
    "sharder.fencing.refused": "perOccurrence",
}


def event_row(event):
    row = {"name": event["name"], "severity": event["severity"], "payload": event["payload"]}
    if event["name"] in DEDUPLICATION:
        row["deduplication"] = DEDUPLICATION[event["name"]]
    return row


def check_label_values(text):
    """Refuse a vocabulary the `OBS-011` paragraph no longer states."""
    block = read_block(text, "`OBS-011`. `routing.decisions` MUST carry", "`OBS-012`.")
    for metric, labels in sorted(LABEL_VALUES.items()):
        for label, values in sorted(labels.items()):
            for value in values:
                if "`%s`" % value not in block:
                    raise SystemExit("OBS-011: %s label %s no longer states `%s`"
                                     % (metric, label, value))


def inventory_cases(surface, metrics, events):
    """The cases of one surface's inventory, each naming what it actually carries.

    A case names `OBS-011` only where the surface owns a closed label vocabulary and `OBS-024` only
    where the surface owns an event that requirement deduplicates, so a case does not claim to
    exercise a rule it says nothing about.
    """
    metrics = [m for m in metrics if surface_of(m["name"]) == surface]
    events = [e for e in events if surface_of(e["name"]) == surface]
    vocabularies = {name: LABEL_VALUES[name] for name in sorted(LABEL_VALUES)
                    if surface_of(name) == surface}
    rows = [event_row(e) for e in events]
    metric_requirements = ["OBS-001", "OBS-003", "OBS-010"]
    if vocabularies:
        metric_requirements.append("OBS-011")
    event_requirements = ["OBS-020", "OBS-021"]
    if any("deduplication" in row for row in rows):
        event_requirements.append("OBS-024")
    cases = [{
        "name": "metric-inventory",
        "requirements": metric_requirements,
        "note": "`OBS-010` names each metric and its label set.  A port compares the names it "
                "registers and the labels it attaches against these, and a name it does not "
                "register is a metric it does not expose.",
        "expect": {
            "surface": surface,
            "metricCount": len(metrics),
            "names": sorted(m["name"] for m in metrics),
            "metrics": [{"name": m["name"], "instrument": m["instrument"], "labels": m["labels"]}
                        for m in metrics],
            "labelValues": vocabularies,
        },
    }, {
        "name": "event-inventory",
        "requirements": event_requirements,
        "note": "`OBS-020` names each event, the severity it carries, and the payload members it "
                "carries beyond the common members of `OBS-021`.  A port may carry further "
                "members and may not rename one of these.  A `deduplication` member appears on "
                "the four events `OBS-024` names and on no other.",
        "expect": {
            "surface": surface,
            "eventCount": len(events),
            "names": sorted(e["name"] for e in events),
            "events": rows,
        },
    }]
    if surface == "routing":
        cases.append({
            "name": "common-members",
            "requirements": ["OBS-021", "OBS-022", "OBS-025"],
            "note": "`OBS-021` states the members every event carries whatever its surface, and "
                    "`OBS-022` states the one thing none of them carries.",
            "expect": {
                "commonMembers": COMMON_MEMBERS,
                "severities": SEVERITIES,
                "carriesKey": False,
                "carriesRoutingKey": False,
                "countsByNameWithoutASink": True,
            },
        })
    return cases


def build_inventories(root, metrics, events):
    for surface, level in sorted(LEVEL_OF_SURFACE.items()):
        cases = inventory_cases(surface, metrics, events)
        requirements = sorted({r for case in cases for r in case["requirements"]})
        write_json(root / ("vectors/observability/inventory-%s.json" % surface), {
            "vectorSet": "observability-inventory-%s" % surface,
            "kind": "observabilityInventory",
            "level": level,
            "description": "The metric and event names, label sets, severities, and payload "
                           "members of the `%s` surface, as `OBS-010` and `OBS-020` state them."
                           % surface,
            "requirements": requirements,
            "cases": cases,
        })


# The documents whose publication reports something.  Each is a shape an operator chose and each
# reaches a different rule: the two scale documents cross the totals of `PLACE-073` and the ring one
# clamps four nodes under `PLACE-052`; `one-domain-relaxed` cannot reach its factor at any level;
# `directory-tenants` carries a `directory` strategy on the default seed.
PUBLICATION_DOCUMENTS = [
    ("scale-ring-1000", SCALE_RING,
     ["PLACE-050", "PLACE-052", "PLACE-073", "PLACE-074", "PLACE-077", "OBS-020", "CFG-014"]),
    ("scale-rendezvous-1000", SCALE_RENDEZVOUS,
     ["PLACE-073", "PLACE-077", "OBS-020", "CFG-014"]),
    ("one-domain-relaxed", T.ALL_ONE_DOMAIN_RELAXED,
     ["SPREAD-022", "SPREAD-023", "SPREAD-024", "OBS-020"]),
    ("spread-ladder", T.SPREAD_LADDER,
     ["SPREAD-022", "SPREAD-023", "SPREAD-024", "OBS-020"]),
    ("directory-tenants", T.DIRECTORY_TENANTS, ["SEC-011", "OBS-020"]),
    ("ring-plain", T.RING_DERIVED_PLAIN, ["PLACE-073", "SPREAD-024", "SEC-011", "OBS-020"]),
]


def build_publication_events(root):
    """What one publication of each document emits, in the order the reference emits it.

    Every event here is decided before stage 6 of `TOPO-001` under `PLACE-077`, or from a scan of
    the accepted document, so a case asserts a large topology's events without preparing its
    placement.  A document that reports nothing carries an empty list, which is the assertion that
    a port emitting a warning on an ordinary topology fails.
    """
    cases = []
    for name, document, requirements in PUBLICATION_DOCUMENTS:
        cases.append({
            "name": name,
            "requirements": sorted(set(requirements)),
            "topology": "topologies/%s.topology.json" % name,
            "expect": {"events": publication_events(document)},
        })
    write_json(root / "vectors/observability/publication-events.json", {
        "vectorSet": "observability-publication-events",
        "kind": "publicationEvents",
        "level": "core",
        "description": "The events one publication of a document emits, from the totals "
                       "`PLACE-077` computes before preparation and from the scans of "
                       "`SPREAD-024` and `SEC-011`.",
        "requirements": sorted({r for case in cases for r in case["requirements"]}),
        "cases": cases,
    })


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(HERE.parent))
    args = parser.parse_args()
    root = Path(args.out)

    text = SPECIFICATION.read_text()
    metrics = parse_metrics(text)
    events = parse_events(text)
    check_label_values(text)

    build_inventories(root, metrics, events)
    build_publication_events(root)

    print("  %d metrics and %d events read from %s"
          % (len(metrics), len(events), SPECIFICATION.name))
    return 0


if __name__ == "__main__":
    sys.exit(main())
