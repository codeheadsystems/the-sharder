#!/usr/bin/env python3
"""Compute requirement coverage and write `conformance/coverage.json`.

Every requirement identifier in `10-specification.md` is extracted, every identifier named by a
vector file, a property witness, or a scenario is collected, and the two sets are compared.  The
coverage table in `docs/design/30-conformance.md` is transcribed from this file, so a requirement
added to the specification shows up as uncovered rather than silently going untested.

    python3 coverage.py [--spec <path>] [--root <conformance root>] [--check]
"""

import argparse
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_SPEC = HERE.parent.parent / "docs/design/10-specification.md"

# A requirement is introduced as a backticked identifier followed by a full stop at the start of
# a line, which is how `10-specification.md` states every one of them.
REQUIREMENT = re.compile(r"^`([A-Z]+)-(\d{3})`\.", re.M)

SECTION_OF_PREFIX = {
    "CORE": "Core model",
    "HASH": "Core model",
    "KEY": "Routing keys and placement",
    "PLACE": "Routing keys and placement",
    "RING": "Routing keys and placement",
    "RV": "Routing keys and placement",
    "SLOT": "Routing keys and placement",
    "RANGE": "Routing keys and placement",
    "DIR": "Routing keys and placement",
    "OVR": "Routing keys and placement",
    "PROP": "Routing keys and placement",
    "REPL": "Replication and failover",
    "SPREAD": "Replication and failover",
    "HEALTH": "Replication and failover",
    "FAIL": "Replication and failover",
    "READ": "Replication and failover",
    "TOPO": "Topology change and rebalancing",
    "FENCE": "Topology change and rebalancing",
    "MOVE": "Topology change and rebalancing",
    "RATE": "Topology change and rebalancing",
    "SPLIT": "Topology change and rebalancing",
    "ERR": "Error taxonomy",
    "OBS": "Observability",
    "CFG": "Configuration surface",
    "SEC": "Security and multi-tenancy",
}


def specification_requirements(path: Path):
    text = path.read_text()
    return sorted({"%s-%s" % (prefix, number)
                   for prefix, number in REQUIREMENT.findall(text)})


def suite_requirements(root: Path):
    """Collect every identifier the suite names, with the artefacts that name it."""
    named = {}

    def record(identifier, source):
        named.setdefault(identifier, set()).add(source)

    manifest_path = root / "manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        for entry in manifest["vectorFiles"]:
            for identifier in entry["requirements"]:
                record(identifier, entry["file"])

    witnesses = root / "vectors/properties/witnesses.json"
    if witnesses.exists():
        payload = json.loads(witnesses.read_text())
        for case in payload["cases"]:
            for identifier in case.get("requirements", []):
                record(identifier, "vectors/properties/witnesses.json")

    properties = root / "properties/properties.json"
    if properties.exists():
        payload = json.loads(properties.read_text())
        for entry in payload["properties"]:
            for identifier in entry["requirements"]:
                record(identifier, "properties/properties.json")

    index = root / "scenarios/index.json"
    if index.exists():
        payload = json.loads(index.read_text())
        for entry in payload["scenarios"]:
            for identifier in entry["requirements"]:
                record(identifier, entry["file"])

    return {k: sorted(v) for k, v in named.items()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec", default=str(DEFAULT_SPEC))
    parser.add_argument("--root", default=str(HERE.parent))
    parser.add_argument("--check", action="store_true",
                        help="fail where the suite names an identifier the specification "
                             "does not state")
    args = parser.parse_args()

    root = Path(args.root)
    stated = specification_requirements(Path(args.spec))
    named = suite_requirements(root)

    unknown = sorted(set(named) - set(stated))
    by_prefix = {}
    for identifier in stated:
        prefix = identifier.split("-")[0]
        bucket = by_prefix.setdefault(prefix, {"section": SECTION_OF_PREFIX.get(prefix, ""),
                                               "stated": [], "covered": [], "uncovered": []})
        bucket["stated"].append(identifier)
        if identifier in named:
            bucket["covered"].append(identifier)
        else:
            bucket["uncovered"].append(identifier)

    rows = []
    for prefix in sorted(by_prefix, key=lambda p: (SECTION_OF_PREFIX.get(p, ""), p)):
        bucket = by_prefix[prefix]
        rows.append({
            "prefix": prefix,
            "section": bucket["section"],
            "stated": len(bucket["stated"]),
            "covered": len(bucket["covered"]),
            "uncovered": bucket["uncovered"],
            "percent": len(bucket["covered"]) * 100 // max(1, len(bucket["stated"])),
        })

    payload = {
        "specification": args.spec.split("sharder/")[-1],
        "statedRequirements": len(stated),
        "coveredRequirements": sum(r["covered"] for r in rows),
        "uncoveredRequirements": sum(r["stated"] - r["covered"] for r in rows),
        "coveragePercent": sum(r["covered"] for r in rows) * 100 // max(1, len(stated)),
        "identifiersNamedButNotStated": unknown,
        "byPrefix": rows,
        "coveredBy": named,
    }
    (root / "coverage.json").write_text(json.dumps(payload, indent=2) + "\n")

    print("specification states %d requirements; the suite names %d of them (%d%%)"
          % (len(stated), payload["coveredRequirements"], payload["coveragePercent"]))
    for row in rows:
        print("  %-7s %-32s %3d/%-3d  %3d%%"
              % (row["prefix"], row["section"], row["covered"], row["stated"], row["percent"]))
    if unknown:
        print("  identifiers named by the suite but absent from the specification: %s"
              % ", ".join(unknown))
    return 1 if (args.check and unknown) else 0


if __name__ == "__main__":
    sys.exit(main())
