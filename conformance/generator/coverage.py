#!/usr/bin/env python3
"""Compute requirement coverage and write `conformance/coverage.json`.

Every requirement identifier in `10-specification.md` is extracted, every identifier named by a
vector file, a property witness, or a scenario is collected, and the two sets are compared.  The
identifiers are also collected per conformance level, so the report says what a declared level
proves rather than only what the whole suite proves.  This file is the one place a coverage figure
is stated: `docs/design/30-conformance.md` points a reader here rather than transcribing a count,
so a requirement added to the specification shows up as uncovered rather than silently going
untested.

    python3 coverage.py [--spec <path>] [--root <conformance root>] [--check]
"""

import argparse
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPOSITORY_ROOT = HERE.parent.parent
DEFAULT_SPEC = REPOSITORY_ROOT / "docs/design/10-specification.md"

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
    "ERR": "Error taxonomy",
    "OBS": "Observability",
    "CFG": "Configuration surface",
    "SEC": "Security and multi-tenancy",
}


def repository_path(path: Path):
    """Name a path as the repository names it, whatever the checkout directory is called."""
    resolved = Path(path).resolve()
    try:
        return resolved.relative_to(REPOSITORY_ROOT).as_posix()
    except ValueError:
        return resolved.as_posix()


def specification_requirements(path: Path):
    text = path.read_text()
    return sorted({"%s-%s" % (prefix, number)
                   for prefix, number in REQUIREMENT.findall(text)})


def suite_requirements(root: Path):
    """Collect every identifier the suite names, with the artefacts and levels that name it."""
    named = {}
    levelled = {}

    def record(identifier, source, level):
        named.setdefault(identifier, set()).add(source)
        levelled.setdefault(level, set()).add(identifier)

    manifest_path = root / "manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        for entry in manifest["vectorFiles"]:
            for identifier in entry["requirements"]:
                record(identifier, entry["file"], entry["level"])

    witnesses = root / "vectors/properties/witnesses.json"
    if witnesses.exists():
        payload = json.loads(witnesses.read_text())
        for case in payload["cases"]:
            for identifier in case.get("requirements", []):
                record(identifier, "vectors/properties/witnesses.json", payload["level"])

    properties = root / "properties/properties.json"
    if properties.exists():
        payload = json.loads(properties.read_text())
        for entry in payload["properties"]:
            for identifier in entry["requirements"]:
                record(identifier, "properties/properties.json", entry["level"])

    index = root / "scenarios/index.json"
    if index.exists():
        payload = json.loads(index.read_text())
        for entry in payload["scenarios"]:
            for identifier in entry["requirements"]:
                record(identifier, entry["file"], entry["level"])

    return ({k: sorted(v) for k, v in named.items()},
            {k: sorted(v) for k, v in levelled.items()})


def level_coverage(root: Path, levelled):
    """State, for each level, the requirement identifiers a port declaring it proves.

    A declaration names levels, so the coverage report answers what a declared level covers.  A
    level's own identifiers are those its artefacts name; its declared set adds the identifiers
    of the levels it requires, because a port reaching a level runs those too.
    """
    manifest_path = root / "manifest.json"
    if not manifest_path.exists():
        return []
    manifest = json.loads(manifest_path.read_text())
    requires = {row["level"]: row["requires"] for row in manifest["levels"]}

    def closure(name):
        seen = set()
        pending = [name]
        while pending:
            current = pending.pop()
            if current in seen:
                continue
            seen.add(current)
            pending.extend(requires[current])
        return seen

    rows = []
    for row in manifest["levels"]:
        name = row["level"]
        own = set(levelled.get(name, []))
        declared = set()
        for reached in closure(name):
            declared.update(levelled.get(reached, []))
        rows.append({
            "level": name,
            "requires": row["requires"],
            "requirements": sorted(own),
            "requirementsAtLevel": len(own),
            "requirementsWhenDeclared": len(declared),
        })
    return rows


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
    named, levelled = suite_requirements(root)
    levels = level_coverage(root, levelled)

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
        "specification": repository_path(args.spec),
        "statedRequirements": len(stated),
        "coveredRequirements": sum(r["covered"] for r in rows),
        "uncoveredRequirements": sum(r["stated"] - r["covered"] for r in rows),
        "coveragePercent": sum(r["covered"] for r in rows) * 100 // max(1, len(stated)),
        "identifiersNamedButNotStated": unknown,
        "byPrefix": rows,
        "byLevel": levels,
        "coveredBy": named,
    }
    (root / "coverage.json").write_text(json.dumps(payload, indent=2) + "\n")

    print("specification states %d requirements; the suite names %d of them (%d%%)"
          % (len(stated), payload["coveredRequirements"], payload["coveragePercent"]))
    for row in rows:
        print("  %-7s %-32s %3d/%-3d  %3d%%"
              % (row["prefix"], row["section"], row["covered"], row["stated"], row["percent"]))
    for level in levels:
        print("  %-13s %3d requirements at the level, %3d when declared"
              % (level["level"], level["requirementsAtLevel"],
                 level["requirementsWhenDeclared"]))
    if unknown:
        print("  identifiers named by the suite but absent from the specification: %s"
              % ", ".join(unknown))
    return 1 if (args.check and unknown) else 0


if __name__ == "__main__":
    sys.exit(main())
