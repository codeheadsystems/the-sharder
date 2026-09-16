# 0008. JSON canonical serialisation

Status: accepted. Date: 2026-09-16.

## Context

The topology document is the interchange format between implementations in different languages and
the input format for every conformance vector. A vector is a file that a Java test, a Go test, and a
Rust test all read, so the format has to parse identically in all of them. Operators also edit these
documents and read them in code review, and a control plane generates them.

The document is also digested, so that two views of epoch 42 can be compared for equality. A digest
over a serialisation is only meaningful when the serialisation is canonical, and most formats offer
several byte sequences for the same logical content.

JSON has one hazard that matters here. Its numbers have no specified precision, and many parsers
represent them as IEEE-754 doubles, which cannot hold every 64-bit integer. A 64-bit token written
as a JSON number would be read back changed in some languages and not in others.

## Decision

The topology document is JSON. The canonical form is its RFC 8785 JSON Canonicalization Scheme
encoding in UTF-8, and the topology digest is the SHA-256 of that encoding, written as lowercase
hexadecimal.

JSON numbers appear only as integers in the range 0 to 9007199254740991, which every conforming
parser represents exactly. Any value that needs the full 64-bit range, such as a ring token or a
range bound, is carried as lowercase hexadecimal text.

Unknown members are a validation failure rather than an ignorable extension, at every level of the
document. Extension without a schema change is available through `metadata` at the document level
and `tags` on a node, neither of which placement reads.

A provider may accept another surface syntax, such as YAML or a control plane's own representation,
and converts it to JSON before the sharder library validates or digests it.

The media type is `application/vnd.sharder.topology+json` and the conventional file extension is
`.topology.json`.

## Consequences

Every port needs an RFC 8785 canonicaliser, which for this document shape is a recursive sort of
object members by their UTF-16 code unit sequence plus the scheme's number and string rules. The
restriction of numbers to safe integers removes the hardest part of the scheme, its floating-point
formatting rule, from the code paths this format exercises.

Refusing unknown members means an old implementation cannot read a document written for a newer
minor version. That is the intended behaviour: an implementation that silently ignored a member
another implementation honoured would route differently from the same document, and a clear
validation failure is a better outcome than a quiet divergence.

Hexadecimal tokens are less readable than decimal ones and are unambiguous in every parser. A
document is still hand-editable, which a binary format would not be.

A digest over the whole document means that a change to `metadata`, which placement never reads,
changes the digest. Two documents that route identically can therefore have different digests, and
the digest answers the question "is this the same document" rather than "does this route the same
way".

## Alternatives

Protocol Buffers. Compact, schema-first, with generated types in every target language. Rejected
because its evolution model is built on ignoring unknown fields, which is precisely the behaviour
this format refuses; because canonical serialisation of a protobuf message is not well defined
across implementations, so a digest would be unreliable; and because an operator cannot read or edit
one in a pull request.

CBOR with the canonical encoding rules. Solves the canonical form and the integer range. Rejected
because it is not hand-editable and because the tooling around conformance vectors, which people
read and write by hand, would need a converter on every path.

YAML as the canonical form. Better to edit. Rejected because it has many spellings of the same
value, no canonical form to digest, and implementation differences that are worse than JSON's.
Accepting YAML at the provider boundary keeps the editing benefit without the digest problem.

JSON with unknown members ignored, in the usual forward-compatible style. Rejected because two
implementations at different versions would then produce different routes from the same bytes with
no error, which is the failure this design exists to prevent.

JSON with unrestricted numeric ranges. Rejected because a 64-bit token would be corrupted by a
double-based parser in some languages and not in others, which is a silent cross-language divergence
in exactly the values that decide placement.

A digest over a placement-relevant projection of the document, ignoring `metadata` and `tags`.
Rejected because the projection rule would itself need specifying and versioning, and because "is
this the same document" is the question the authority and the operator actually ask.
