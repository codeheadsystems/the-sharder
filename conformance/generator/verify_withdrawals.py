#!/usr/bin/env python3
"""Enforce the permanence of a withdrawn identifier.

The Conventions section of `10-specification.md` states that a withdrawn requirement identifier is
entered in a register, is never reused by a later requirement, and is cited by no live document.
`90-open-questions.md` states the same convention for a withdrawn `OQ-*` identifier.  Both rules
are absolute, and neither is visible in a diff that adds a requirement paragraph three thousand
lines away from the register, so this script checks them.

Four properties are checked, and each failure names the file and the identifier.

1.  No register row names an identifier the same document states as a live requirement, which is
    what reuse looks like.
2.  No live document cites a withdrawn identifier outside the register row that resolves it.
3.  No artefact of the conformance suite names a withdrawn identifier.
4.  Every register row names a decision record that exists.

    python3 verify_withdrawals.py [--root <repository root>]
"""

import argparse
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPOSITORY_ROOT = HERE.parent.parent
DESIGN = REPOSITORY_ROOT / "docs/design"

# A requirement is introduced as a backticked identifier followed by a full stop at the start of a
# line, which is how `10-specification.md` states every one of them.
STATED = re.compile(r"^`([A-Z]+-\d{3})`\.", re.M)
# A question is introduced as a level-three heading in `90-open-questions.md`.
STATED_QUESTION = re.compile(r"^### (OQ-\d{2})\.", re.M)

REGISTER_ROW = re.compile(r"^\|\s*`([A-Z]+-\d{3})`\s*\|\s*`(\d{4})`\s*\|", re.M)
QUESTION_ROW = re.compile(r"^\|\s*`(OQ-\d{2})`\s*\|[^|]*\|\s*\[`adr/(\d{4})`\]", re.M)

# The live documents, which the Withdrawn identifiers section of the specification names.  A
# decision record is excluded by that section, because it keeps the identifier it argued about.
LIVE_DOCUMENTS = [
    "00-overview.md",
    "05-glossary.md",
    "10-specification.md",
    "20-topology-format.md",
    "30-conformance.md",
    "40-java-binding.md",
    "90-open-questions.md",
    "99-roadmap.md",
]


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


def registers():
    """The withdrawn identifiers of both registers, each with the record that resolves it."""
    spec = (DESIGN / "10-specification.md").read_text()
    questions = (DESIGN / "90-open-questions.md").read_text()
    withdrawn = dict(REGISTER_ROW.findall(section(spec, "### Withdrawn identifiers")))
    withdrawn.update(QUESTION_ROW.findall(section(questions, "## Withdrawn questions")))
    return withdrawn


def suite_identifiers(root):
    """Every identifier the generated suite names, with the artefact that names it."""
    named = {}

    def record(identifier, source):
        named.setdefault(identifier, set()).add(source)

    manifest = root / "manifest.json"
    if manifest.exists():
        payload = json.loads(manifest.read_text())
        if "vectorFiles" not in payload:
            raise SystemExit("%s carries no `vectorFiles`; run `build_manifest.py` first"
                             % manifest)
        for entry in payload["vectorFiles"]:
            for identifier in entry["requirements"]:
                record(identifier, entry["file"])
    for relative, key in (("properties/properties.json", "properties"),
                          ("scenarios/index.json", "scenarios")):
        path = root / relative
        if path.exists():
            for entry in json.loads(path.read_text())[key]:
                for identifier in entry["requirements"]:
                    record(identifier, entry.get("file", relative))
    witnesses = root / "vectors/properties/witnesses.json"
    if witnesses.exists():
        for case in json.loads(witnesses.read_text())["cases"]:
            for identifier in case.get("requirements", []):
                record(identifier, "vectors/properties/witnesses.json")
    return named


def citations(text, identifier):
    """The one-based line numbers on which a document cites the identifier."""
    pattern = re.compile(r"`%s`" % re.escape(identifier))
    return [n for n, line in enumerate(text.splitlines(), 1) if pattern.search(line)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=str(REPOSITORY_ROOT / "conformance"))
    args = parser.parse_args()
    root = Path(args.root)

    withdrawn = registers()
    failures = []

    spec = (DESIGN / "10-specification.md").read_text()
    questions = (DESIGN / "90-open-questions.md").read_text()
    stated = set(STATED.findall(spec)) | set(STATED_QUESTION.findall(questions))
    for identifier in sorted(set(withdrawn) & stated):
        failures.append("%s is in the register and is stated as a live identifier" % identifier)

    register_lines = {}
    for name, text in (("docs/design/10-specification.md", spec),
                       ("docs/design/90-open-questions.md", questions)):
        heading = ("### Withdrawn identifiers" if name.endswith("10-specification.md")
                   else "## Withdrawn questions")
        # The heading sits on line `offset + 1`, and the section body continues from there.
        offset = text[:text.index(heading)].count("\n")
        register_lines[name] = {offset + n
                                for n, _ in enumerate(section(text, heading).splitlines(), 1)}

    for document in LIVE_DOCUMENTS:
        path = DESIGN / document
        name = "docs/design/%s" % document
        text = path.read_text()
        exempt = register_lines.get(name, set())
        for identifier in sorted(withdrawn):
            lines = [n for n in citations(text, identifier) if n not in exempt]
            if lines:
                failures.append("%s cites the withdrawn %s at line %s"
                                % (name, identifier,
                                   ", ".join(str(n) for n in lines)))

    named = suite_identifiers(root)
    for identifier in sorted(set(withdrawn) & set(named)):
        failures.append("the suite names the withdrawn %s in %s"
                        % (identifier, ", ".join(sorted(named[identifier]))))

    for identifier, number in sorted(withdrawn.items()):
        if not list((DESIGN / "adr").glob("%s-*.md" % number)):
            failures.append("%s names record %s, which does not exist" % (identifier, number))

    print("  withdrawal register: %d identifiers, %d failures"
          % (len(withdrawn), len(failures)))
    for failure in failures:
        print("    " + failure)
    if failures:
        print("VERIFICATION FAILED")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
