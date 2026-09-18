"""RFC 8785 JSON Canonicalization Scheme, and the SHA-256 topology digest over it.

A topology document carries integers only, never a fractional or exponential number, so the
number serialisation here covers the integer case and refuses anything else rather than
implementing the ECMAScript double formatting that would never be exercised.
"""

import hashlib

_ESCAPES = {
    0x08: "\\b",
    0x09: "\\t",
    0x0A: "\\n",
    0x0C: "\\f",
    0x0D: "\\r",
    0x22: '\\"',
    0x5C: "\\\\",
}


def _utf16_units(text: str):
    """The UTF-16 code unit sequence of `text`, which is the order RFC 8785 sorts member names in."""
    return tuple(text.encode("utf-16-be")[i] << 8 | text.encode("utf-16-be")[i + 1]
                 for i in range(0, len(text.encode("utf-16-be")), 2))


def _serialise_string(text: str) -> str:
    out = ['"']
    for char in text:
        code = ord(char)
        if code in _ESCAPES:
            out.append(_ESCAPES[code])
        elif code < 0x20:
            out.append("\\u%04x" % code)
        else:
            out.append(char)
    out.append('"')
    return "".join(out)


def _serialise_number(value) -> str:
    if isinstance(value, bool):
        raise TypeError("booleans are not numbers")
    if not isinstance(value, int):
        raise TypeError("a topology document carries integers only, got %r" % (value,))
    if value < -(2 ** 53 - 1) or value > 2 ** 53 - 1:
        raise ValueError("integer outside the exactly representable range: %r" % (value,))
    return str(value)


def canonicalise(value) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return _serialise_string(value)
    if isinstance(value, int):
        return _serialise_number(value)
    if isinstance(value, list):
        return "[" + ",".join(canonicalise(item) for item in value) + "]"
    if isinstance(value, dict):
        members = sorted(value.items(), key=lambda pair: _utf16_units(pair[0]))
        return "{" + ",".join(
            _serialise_string(name) + ":" + canonicalise(member) for name, member in members
        ) + "}"
    raise TypeError("not a JSON value: %r" % (value,))


def canonical_bytes(document) -> bytes:
    return canonicalise(document).encode("utf-8")


def digest(document) -> str:
    """The topology digest: SHA-256 of the canonical form, in lowercase hexadecimal."""
    return hashlib.sha256(canonical_bytes(document)).hexdigest()
