package com.codeheadsystems.sharder.conformance;

import java.util.ArrayList;
import java.util.List;

/**
 * The deterministic key sample of {@code PROP-006}, which every sampled bound is evaluated over.
 *
 * <p>Keys are sixteen octets, drawn two {@code u64} values at a time from SplitMix64 seeded with
 * {@code 5348415244455201}, each value written most significant octet first. A suite cannot draw at
 * random and stay reproducible, so it fixes the sample and a port that disagrees with a witness has
 * a placement defect rather than an unlucky draw.
 *
 * <p>The generator reaches no placement decision. It chooses which keys a property is evaluated
 * over and nothing else.
 */
final class KeySample {

    private static final long GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    KeySample(long seed) {
        this.state = seed;
    }

    /** The next draw. */
    long next() {
        state += GAMMA;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The first {@code count} keys of the sample, each of sixteen octets. */
    static List<byte[]> keys(long seed, int count, int keyOctets) {
        KeySample sample = new KeySample(seed);
        List<byte[]> keys = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            byte[] key = new byte[keyOctets];
            for (int offset = 0; offset < keyOctets; offset += 8) {
                long draw = sample.next();
                for (int octet = 0; octet < 8 && offset + octet < keyOctets; octet++) {
                    key[offset + octet] = (byte) (draw >>> (56 - 8 * octet));
                }
            }
            keys.add(key);
        }
        return keys;
    }
}
