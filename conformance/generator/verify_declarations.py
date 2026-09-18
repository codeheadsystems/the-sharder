#!/usr/bin/env python3
"""Check every conformance declaration against the manifest and the specification.

`docs/design/30-conformance.md` requires a port to publish seven things, and
`conformance/declarations/<port>.json` is the file that carries them.  The claims that file makes
are claims two generated artefacts can contradict: `manifest.json` holds the level table with the
requires relation, the strategy surfaces, and the artefact counts per level, and
`10-specification.md` states which requirements carry the RFC 2119 keyword a deviation is permitted
against.  This script checks a declaration against both.

What it checks, per declaration:

1.  Every member is present, and the file's name is the port identifier it carries.
2.  The port has a directory under `ports/`, and a driver report the declaration names by a
    repository path exists.
3.  Every level of the manifest's `levels` table is named exactly once, and no other level is.
4.  A reached level has every level it requires, transitively, reached.
5.  An excluded level names a conformance surface the specification states, and a reached level
    names none.
6.  The strategy surfaces are a non-empty subset of the manifest's, which `CORE-110` requires.
7.  The vector file and case counts do not exceed the manifest's totals for the reached levels, and
    equal them where the port exposes every strategy surface.
8.  The failure count is zero, and the `scale` figures are present where `scale` is reached.
9.  Every deviation names a requirement the specification states, a reason, and the cases it shows
    up in, and that requirement carries `SHOULD`, `SHOULD NOT`, or `MAY`.

A declaration naming an earlier revision than the manifest holds is reported as lagging and does not
fail the run, because regenerating the suite is what makes a declaration lag.  A directory under
`ports/` with no declaration is reported as undeclared, because a port reaches the four mandatory
levels before it declares anything.

    python3 verify_declarations.py [--root <repository root>]
"""

import argparse
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPOSITORY_ROOT = HERE.parent.parent

MEMBERS = ("port", "version", "revision", "strategySurfaces", "levels", "run", "scale",
           "deviations")
RUN_MEMBERS = ("command", "report", "vectorFiles", "vectorCases", "failures")
SCALE_MEMBERS = ("wallTimeMs", "peakResidentBytes", "machine", "runtime")
STATES = ("reached", "excluded")

# A requirement is introduced as a backticked identifier followed by a full stop at the start of a
# line, which is how `10-specification.md` states every one of them.
STATED = re.compile(r"^`([A-Z]+-\d{3})`\.", re.M)
# The Conformance surfaces section opens with a table whose first column is one surface name.
SURFACE_ROW = re.compile(r"^\|\s*`([a-z][A-Za-z]*)`\s*\|", re.M)
# The keywords a deviation is permitted against.  `SHOULD NOT` carries `SHOULD`.
PERMITTED = re.compile(r"\b(SHOULD|MAY)\b")


def section(text, heading):
    """The body of one heading, up to the next heading at the same depth or above."""
    depth = heading.split(" ", 1)[0]
    start = text.index(heading)
    body = text[start + len(heading):]
    end = len(body)
    for match in re.finditer(r"^#{1,%d} " % len(depth), body, re.M):
        end = match.start()
        break
    return body[:end]


def surfaces(spec):
    """The closed conformance surface set, from the table the specification opens with."""
    body = section(spec, "### Conformance surfaces")
    return set(SURFACE_ROW.findall(body))


def requirement_text(spec):
    """Each requirement identifier with the text that states it, up to the next requirement."""
    matches = list(STATED.finditer(spec))
    blocks = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(spec)
        blocks[match.group(1)] = spec[match.start():end]
    return blocks


def required_levels(levels, name):
    """One level with every level it requires, transitively."""
    seen = set()
    pending = [name]
    while pending:
        current = pending.pop()
        if current in seen or current not in levels:
            continue
        seen.add(current)
        pending.extend(levels[current]["requires"])
    return seen


def check(declaration, path, manifest, levels, surface_set, requirements, root):
    """Every failure one declaration carries, as a list of sentences naming the file."""
    failures = []
    name = path.name

    for member in MEMBERS:
        if member not in declaration:
            failures.append("%s names no %s" % (name, member))
    if failures:
        return failures

    shapes = (("strategySurfaces", list), ("levels", dict), ("run", dict), ("scale", dict),
              ("deviations", list))
    for member, shape in shapes:
        if not isinstance(declaration[member], shape):
            failures.append("%s carries a %s that is not %s"
                            % (name, member, "an object" if shape is dict else "an array"))
    if failures:
        return failures

    port = declaration["port"]
    if port != path.stem:
        failures.append("%s declares the port %s" % (name, port))
    if not (root / "ports" / port).is_dir():
        failures.append("%s declares %s, which has no directory under ports/" % (name, port))

    run = declaration["run"]
    for member in RUN_MEMBERS:
        if member not in run:
            failures.append("%s names no run.%s" % (name, member))
    report = run.get("report", "")
    if report and not report.startswith(("http://", "https://")) and not (root / report).exists():
        failures.append("%s names the driver report %s, which does not exist" % (name, report))
    if run.get("failures", 0) != 0:
        failures.append("%s reports %s failures, so it declares no level"
                        % (name, run.get("failures")))

    declared = declaration["levels"]
    for level in sorted(set(levels) - set(declared)):
        failures.append("%s names no level %s, which the manifest carries" % (name, level))
    for level in sorted(set(declared) - set(levels)):
        failures.append("%s names the level %s, which the manifest does not carry" % (name, level))

    reached = set()
    for level, entry in sorted(declared.items()):
        if not isinstance(entry, dict):
            failures.append("%s carries a level %s that is not an object" % (name, level))
            continue
        state = entry.get("state")
        if state not in STATES:
            failures.append("%s marks %s %s, which is neither reached nor excluded"
                            % (name, level, state))
            continue
        if state == "reached":
            reached.add(level)
            if "surface" in entry:
                failures.append("%s marks %s reached and names a surface" % (name, level))
        else:
            surface = entry.get("surface")
            if surface is None:
                failures.append("%s excludes %s and names no surface" % (name, level))
            elif surface not in surface_set:
                failures.append("%s excludes %s for %s, which is no conformance surface"
                                % (name, level, surface))

    for level in sorted(reached):
        for required in sorted(required_levels(levels, level) - reached):
            failures.append("%s reaches %s and not %s, which it requires"
                            % (name, level, required))

    exposed = declaration["strategySurfaces"]
    catalogue = set(manifest["strategySurfaces"])
    if not exposed:
        failures.append("%s exposes no strategy surface, which CORE-110 refuses" % name)
    for surface in sorted(set(exposed) - catalogue):
        failures.append("%s exposes %s, which the manifest does not list" % (name, surface))

    counted = reached & set(levels)
    files = sum(levels[level]["vectorFiles"] for level in counted)
    cases = sum(levels[level]["vectorCases"] for level in counted)
    whole = set(exposed) >= catalogue
    for member, total in (("vectorFiles", files), ("vectorCases", cases)):
        ran = run.get(member, 0)
        if ran > total:
            failures.append("%s reports %s of %d against %d at its reached levels"
                            % (name, member, ran, total))
        elif whole and ran != total:
            failures.append("%s exposes every strategy surface and reports %s of %d against %d"
                            % (name, member, ran, total))

    if "scale" in reached:
        for member in SCALE_MEMBERS:
            if not declaration["scale"].get(member):
                failures.append("%s reaches scale and names no scale.%s" % (name, member))

    for deviation in declaration["deviations"]:
        if not isinstance(deviation, dict):
            failures.append("%s carries a deviation that is not an object" % name)
            continue
        identifier = deviation.get("requirement")
        if not deviation.get("reason"):
            failures.append("%s deviates from %s and gives no reason" % (name, identifier))
        if not isinstance(deviation.get("cases"), list):
            failures.append("%s deviates from %s and names no cases, which may be an empty array"
                            % (name, identifier))
        if identifier not in requirements:
            failures.append("%s deviates from %s, which the specification does not state"
                            % (name, identifier))
        elif not PERMITTED.search(requirements[identifier]):
            failures.append("%s deviates from %s, which carries no SHOULD, SHOULD NOT, or MAY"
                            % (name, identifier))

    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=REPOSITORY_ROOT)
    arguments = parser.parse_args()
    root = arguments.root.resolve()

    manifest = json.loads((root / "conformance/manifest.json").read_text())
    spec = (root / "docs/design/10-specification.md").read_text()
    levels = {row["level"]: row for row in manifest["levels"]}
    surface_set = surfaces(spec)
    if "routing" not in surface_set:
        print("  declarations: the Conformance surfaces table did not parse")
        print("VERIFICATION FAILED")
        return 1
    requirements = requirement_text(spec)

    directory = root / "conformance/declarations"
    paths = sorted(directory.glob("*.json")) if directory.is_dir() else []

    failures = []
    lagging = []
    for path in paths:
        try:
            declaration = json.loads(path.read_text())
        except json.JSONDecodeError as error:
            failures.append("%s does not parse: %s" % (path.name, error))
            continue
        failures.extend(check(declaration, path, manifest, levels, surface_set, requirements, root))
        if declaration.get("revision") != manifest["revision"]["id"]:
            lagging.append(path.name)

    ports = root / "ports"
    declared = {path.stem for path in paths}
    undeclared = sorted(entry.name for entry in ports.iterdir()
                        if entry.is_dir() and entry.name not in declared) if ports.is_dir() else []

    print("  declarations: %d checked, %d lagging, %d undeclared, %d failures"
          % (len(paths), len(lagging), len(undeclared), len(failures)))
    for name in lagging:
        print("    %s names an earlier revision than the manifest holds" % name)
    for name in undeclared:
        print("    ports/%s carries no declaration" % name)
    for failure in failures:
        print("    " + failure)
    if failures:
        print("VERIFICATION FAILED")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
