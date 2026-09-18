#!/usr/bin/env python3
"""Verify the reference SipHash-2-4 against published vectors and against OpenSSL.

Two independent checks run:

1.  The 64 reference vectors of the SipHash paper (Aumasson and Bernstein, "SipHash: a fast
    short-input PRF", appendix A, and the `vectors_sip64` table of the reference C
    implementation).  Key `000102...0f`, message `0001...(i-1)` for i in 0..63.  The table below
    holds each expected output as the paper prints it: eight octets, least significant first.

2.  OpenSSL 3's `SIPHASH` MAC at `size:8`, over the same 64 inputs.  This is an
    implementation nobody in this repository wrote.

Run it before generating any vector.  Exit status 0 means both checks passed.
"""

import os
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharder_ref.siphash import siphash24  # noqa: E402

# Published `vectors_sip64`, one row per message length 0 through 63, octets least
# significant first, exactly as the reference implementation prints them.
PAPER_VECTORS_LE = [
    "310e0edd47db6f72", "fd67dc93c539f874", "5a4fa9d909806c0d", "2d7efbd796666785",
    "b7877127e09427cf", "8da699cd64557618", "cee3fe586e46c9cb", "37d1018bf50002ab",
    "6224939a79f5f593", "b0e4a90bdf82009e", "f3b9dd94c5bb5d7a", "a7ad6b22462fb3f4",
    "fbe50e86bc8f1e75", "903d84c02756ea14", "eef27a8e90ca23f7", "e545be4961ca29a1",
    "db9bc2577fcc2a3f", "9447be2cf5e99a69", "9cd38d96f0b3c14b", "bd6179a71dc96dbb",
    "98eea21af25cd6be", "c7673b2eb0cbf2d0", "883ea3e395675393", "c8ce5ccd8c030ca8",
    "94af49f6c650adb8", "eab8858ade92e1bc", "f315bb5bb835d817", "adcf6b0763612e2f",
    "a5c91da7acaa4dde", "716595876650a2a6", "28ef495c53a387ad", "42c341d8fa92d832",
    "ce7cf2722f512771", "e37859f94623f3a7", "381205bb1ab0e012", "ae97a10fd434e015",
    "b4a31508beff4d31", "81396229f0907902", "4d0cf49ee5d4dcca", "5c73336a76d8bf9a",
    "d0a704536ba93e0e", "925958fcd6420cad", "a915c29bc8067318", "952b79f3bc0aa6d4",
    "f21df2e41d4535f9", "87577519048f53a9", "10a56cf5dfcd9adb",
    "eb75095ccd986cd0", "51a9cb9ecba312e6", "96afadfc2ce666c7", "72fe52975a4364ee",
    "5a1645b276d592a1", "b274cb8ebf87870a", "6f9bb4203de7b381", "eaecb2a30b22a87f",
    "9924a43cc1315724", "bd838d3aafbf8db7", "0b1a2a3265d51aea", "135079a3231ce660",
    "932b2846e4d70666", "e1915f5cb1eca46c", "f325965ca16d629f", "575ff28e60381be5",
    "724506eb4c328a95",
]

KEY = bytes(range(16))


def messages():
    for i in range(64):
        yield bytes(range(i))


def check_paper():
    failures = []
    for i, message in enumerate(messages()):
        expected = int.from_bytes(bytes.fromhex(PAPER_VECTORS_LE[i]), "little")
        actual = siphash24(KEY, message)
        if actual != expected:
            failures.append((i, "%016x" % expected, "%016x" % actual))
    return failures


def openssl_siphash(key: bytes, message: bytes) -> int:
    """Hash `message` through OpenSSL's SIPHASH MAC at an 8-octet output size.

    OpenSSL prints the eight output octets least significant first, the order the SipHash paper
    prints them in, so the result is read little-endian.
    """
    with tempfile.NamedTemporaryFile(delete=False) as handle:
        handle.write(message)
        path = handle.name
    try:
        result = subprocess.run(
            ["openssl", "mac", "-macopt", "hexkey:" + key.hex(), "-macopt", "size:8",
             "-in", path, "SIPHASH"],
            capture_output=True, check=True,
        )
    finally:
        os.unlink(path)
    return int.from_bytes(bytes.fromhex(result.stdout.decode().strip()), "little")


def check_openssl():
    failures = []
    for i, message in enumerate(messages()):
        try:
            expected = openssl_siphash(KEY, message)
        except (subprocess.CalledProcessError, FileNotFoundError) as exc:
            return [("openssl", "unavailable", str(exc))]
        actual = siphash24(KEY, message)
        if actual != expected:
            failures.append((i, "%016x" % expected, "%016x" % actual))
    return failures


def main():
    paper = check_paper()
    openssl = check_openssl()
    unavailable = bool(openssl) and openssl[0][0] == "openssl"

    print("SipHash-2-4 reference verification")
    print("  paper vectors   : %d cases, %d failures" % (64, len(paper)))
    for row in paper:
        print("    length %-3s expected %s got %s" % row)
    if unavailable:
        print("  openssl 3 mac   : not run, %s" % openssl[0][2])
        print("    install openssl; the regeneration stops here rather than generating vectors")
        print("    from a hash no independent implementation has checked")
    else:
        print("  openssl 3 mac   : %d cases, %d failures" % (64, len(openssl)))
        for row in openssl:
            print("    length %-3s expected %s got %s" % row)

    if paper or openssl:
        print("VERIFICATION FAILED")
        return 1
    print("VERIFICATION PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
