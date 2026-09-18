/* Pollard rho search for 64-bit collisions in the sharder hash construction.
 *
 * The determinism vectors need cases that reach a tie-break: two nodes that derive the same ring
 * token, and two nodes that score identically under rendezvous placement.  Both
 * are 64-bit collisions, so they are found rather than chosen.  A birthday table would need
 * roughly 2^32 stored values; Brent's cycle detection finds the same collision in constant
 * memory.
 *
 * The iterated function maps a 64-bit state to a node identity, hashes it at virtual node index
 * zero, and takes the resulting token or score as the next state.  A node identity is therefore
 * the sixteen lowercase hexadecimal digits of the state, which the topology schema accepts.
 *
 * The output is two node identities whose hash values agree.  `generate.py` recomputes both
 * through the Python reference before writing any vector, so an error here cannot reach a vector
 * file undetected.
 *
 * Build:  cc -O2 -o rho_search rho_search.c
 * Usage:  ./rho_search <mode> [argument]
 *         keyHash
 *
 * RHO_START overrides the start state, so several instances of one mode can race.
 *         ring
 *         rendezvous <routing-key-hex>
 */

#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#define ROTL(x, b) (uint64_t)(((x) << (b)) | ((x) >> (64 - (b))))

#define SIPROUND                                                                                  \
    do {                                                                                          \
        v0 += v1; v1 = ROTL(v1, 13); v1 ^= v0; v0 = ROTL(v0, 32);                                 \
        v2 += v3; v3 = ROTL(v3, 16); v3 ^= v2;                                                    \
        v0 += v3; v3 = ROTL(v3, 21); v3 ^= v0;                                                    \
        v2 += v1; v1 = ROTL(v1, 17); v1 ^= v2; v2 = ROTL(v2, 32);                                 \
    } while (0)

/* SipHash-2-4, 64-bit output, zero key.  The conformance seed default is sixteen zero octets. */
static uint64_t siphash24_zero_key(const uint8_t *in, size_t inlen)
{
    uint64_t v0 = 0x736f6d6570736575ULL;
    uint64_t v1 = 0x646f72616e646f6dULL;
    uint64_t v2 = 0x6c7967656e657261ULL;
    uint64_t v3 = 0x7465646279746573ULL;
    uint64_t m;
    size_t i;
    const size_t whole = inlen - (inlen % 8);

    for (i = 0; i < whole; i += 8) {
        m = (uint64_t)in[i] | ((uint64_t)in[i + 1] << 8) | ((uint64_t)in[i + 2] << 16) |
            ((uint64_t)in[i + 3] << 24) | ((uint64_t)in[i + 4] << 32) |
            ((uint64_t)in[i + 5] << 40) | ((uint64_t)in[i + 6] << 48) |
            ((uint64_t)in[i + 7] << 56);
        v3 ^= m;
        SIPROUND; SIPROUND;
        v0 ^= m;
    }

    m = ((uint64_t)(inlen & 0xff)) << 56;
    for (i = whole; i < inlen; i++)
        m |= ((uint64_t)in[i]) << (8 * (i - whole));

    v3 ^= m;
    SIPROUND; SIPROUND;
    v0 ^= m;

    v2 ^= 0xff;
    SIPROUND; SIPROUND; SIPROUND; SIPROUND;
    return v0 ^ v1 ^ v2 ^ v3;
}

static const char HEX[] = "0123456789abcdef";

static void write_u32be(uint8_t *out, uint32_t value)
{
    out[0] = (uint8_t)(value >> 24);
    out[1] = (uint8_t)(value >> 16);
    out[2] = (uint8_t)(value >> 8);
    out[3] = (uint8_t)(value);
}

/* The framed message layout is fixed per mode, so it is built once and the node identity is
 * overwritten in place at each step. */
typedef struct {
    uint8_t buffer[512];
    size_t length;
    size_t id_offset;   /* where the sixteen identity digits sit */
} framed;

static void frame_append(framed *f, const uint8_t *field, uint32_t len)
{
    write_u32be(f->buffer + f->length, len);
    f->length += 4;
    memcpy(f->buffer + f->length, field, len);
    f->length += len;
}

static void frame_append_u32(framed *f, uint32_t value)
{
    uint8_t tmp[4];
    write_u32be(tmp, value);
    frame_append(f, tmp, 4);
}

static void set_identity(framed *f, uint64_t state)
{
    int i;
    for (i = 0; i < 16; i++)
        f->buffer[f->id_offset + i] = (uint8_t)HEX[(state >> (60 - 4 * i)) & 0xf];
}

static framed TEMPLATE;

static uint64_t step(uint64_t state)
{
    set_identity(&TEMPLATE, state);
    return siphash24_zero_key(TEMPLATE.buffer, TEMPLATE.length);
}

static size_t parse_hex(const char *text, uint8_t *out)
{
    size_t n = strlen(text) / 2, i;
    for (i = 0; i < n; i++)
        sscanf(text + 2 * i, "%2hhx", out + i);
    return n;
}

static void build_template(int argc, char **argv)
{
    uint8_t scratch[256];
    const char *mode = argv[1];
    uint8_t placeholder[16];
    memset(placeholder, '0', 16);
    TEMPLATE.length = 0;

    if (strcmp(mode, "keyHash") == 0) {
        frame_append(&TEMPLATE, (const uint8_t *)"sharder/key/v1", 14);
        TEMPLATE.id_offset = TEMPLATE.length + 4;
        frame_append(&TEMPLATE, placeholder, 16);
    } else if (strcmp(mode, "ring") == 0) {
        frame_append(&TEMPLATE, (const uint8_t *)"sharder/ring-token/v1", 21);
        TEMPLATE.id_offset = TEMPLATE.length + 4;
        frame_append(&TEMPLATE, placeholder, 16);
        frame_append_u32(&TEMPLATE, 0);
    } else if (strcmp(mode, "rendezvous") == 0) {
        size_t n = parse_hex(argv[2], scratch);
        frame_append(&TEMPLATE, (const uint8_t *)"sharder/rendezvous/v1", 21);
        frame_append(&TEMPLATE, scratch, (uint32_t)n);
        TEMPLATE.id_offset = TEMPLATE.length + 4;
        frame_append(&TEMPLATE, placeholder, 16);
        frame_append_u32(&TEMPLATE, 0);
    } else {
        fprintf(stderr, "unknown mode %s\n", mode);
        exit(2);
    }
    (void)argc;
}

static void print_identity(const char *label, uint64_t state)
{
    char text[17];
    int i;
    for (i = 0; i < 16; i++)
        text[i] = HEX[(state >> (60 - 4 * i)) & 0xf];
    text[16] = 0;
    printf("%s %s %016llx\n", label, text, (unsigned long long)step(state));
}

int main(int argc, char **argv)
{
    uint64_t tortoise, hare, power, lam, mu, x, y;
    uint64_t start = 0x0123456789abcdefULL;
    const char *start_env = getenv("RHO_START");

    /* A different start state draws an independent rho, so several instances of one mode can
     * race and the first to finish wins.  Any collision is equally valid whatever the start. */
    if (start_env != NULL)
        start = strtoull(start_env, NULL, 0);

    if (argc < 2) {
        fprintf(stderr, "usage: rho_search <keyHash|ring|rendezvous> [argument] [--probe]\n");
        return 2;
    }
    build_template(argc, argv);

    /* `--probe` prints step() over a fixed ladder of states so that `verify_rho.py` can compare
     * this hash construction against the Python reference before a search is trusted. */
    if (argc > 1 && strcmp(argv[argc - 1], "--probe") == 0) {
        for (uint64_t i = 0; i < 8; i++) {
            uint64_t state = start ^ (i * 0x9e3779b97f4a7c15ULL);
            char text[17];
            for (int j = 0; j < 16; j++)
                text[j] = HEX[(state >> (60 - 4 * j)) & 0xf];
            text[16] = 0;
            printf("%s %016llx\n", text, (unsigned long long)step(state));
        }
        return 0;
    }

    /* Brent's cycle detection: find the cycle length, then the two distinct states that both
     * map onto the first repeated value. */
    power = lam = 1;
    tortoise = start;
    hare = step(start);
    while (tortoise != hare) {
        if (power == lam) {
            tortoise = hare;
            power <<= 1;
            lam = 0;
        }
        hare = step(hare);
        lam++;
    }

    tortoise = hare = start;
    for (uint64_t i = 0; i < lam; i++)
        hare = step(hare);

    mu = 0;
    while (tortoise != hare) {
        tortoise = step(tortoise);
        hare = step(hare);
        mu++;
    }

    if (mu == 0) {
        fprintf(stderr, "rho entered the cycle at the start; rerun with a different seed state\n");
        return 1;
    }

    /* The collision is between the state one step before the cycle entry on each path. */
    x = start;
    for (uint64_t i = 0; i + 1 < mu; i++)
        x = step(x);
    y = start;
    for (uint64_t i = 0; i + 1 < mu + lam; i++)
        y = step(y);

    if (x == y || step(x) != step(y)) {
        fprintf(stderr, "no collision recovered (x=%016llx y=%016llx)\n",
                (unsigned long long)x, (unsigned long long)y);
        return 1;
    }

    printf("mode %s\n", argv[1]);
    printf("lambda %llu mu %llu\n", (unsigned long long)lam, (unsigned long long)mu);
    print_identity("a", x);
    print_identity("b", y);
    return 0;
}
