"""Key transforms: `none`, `braceTag`, and `prefixFields`, per `KEY-020` through `KEY-044`."""


def transform_none(key: bytes) -> bytes:
    return key


def brace_tag(key: bytes, open_octet: int, close_octet: int) -> bytes:
    o = -1
    for i in range(len(key)):
        if key[i] == open_octet:
            o = i
            break
    if o < 0:
        return key
    c = -1
    for j in range(o + 1, len(key)):
        if key[j] == close_octet:
            c = j
            break
    if c < 0:
        return key
    if c - o == 1:
        return key
    return key[o + 1:c]


def prefix_fields(key: bytes, separator: int, count: int) -> bytes:
    seen = 0
    for i in range(len(key)):
        if key[i] == separator:
            seen += 1
            if seen == count:
                return key[0:i]
    return key


def apply_transform(config: dict, key: bytes) -> bytes:
    kind = config["kind"]
    if kind == "none":
        return transform_none(key)
    if kind == "braceTag":
        return brace_tag(key,
                         bytes.fromhex(config.get("open", "7b"))[0],
                         bytes.fromhex(config.get("close", "7d"))[0])
    if kind == "prefixFields":
        return prefix_fields(key,
                             bytes.fromhex(config["separator"])[0],
                             config.get("count", 1))
    raise ValueError("unknown key transform kind: %r" % (kind,))
