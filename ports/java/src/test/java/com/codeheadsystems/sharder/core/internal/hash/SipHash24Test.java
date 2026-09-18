package com.codeheadsystems.sharder.core.internal.hash;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import java.util.stream.IntStream;
import org.bouncycastle.crypto.macs.SipHash;
import org.bouncycastle.crypto.params.KeyParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code HASH-003}: SipHash-2-4 against the vectors published with the algorithm, before any
 * conformance vector runs.
 *
 * <p>The reference set is the sixty-four outputs for the key {@code
 * 000102030405060708090a0b0c0d0e0f} and, for {@code i} from 0 to 63, the message of {@code i}
 * octets holding the values 0 through {@code i - 1}. The paper prints each output as eight octets,
 * least significant first; the table below carries the same values in the rendering of {@code
 * HASH-044}, sixteen hexadecimal digits most significant first, which is the octets reversed.
 *
 * <p>Bouncy Castle is the second oracle, under {@code adr/0040}. It is a test dependency, reaches
 * no consumer, and answers the same sixty-four messages independently, so a transcription defect
 * in the table below fails against it rather than passing quietly.
 */
class SipHash24Test {

    private static final byte[] KEY = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f");

    private static final String[] PAPER_VECTORS = {
            "726fdb47dd0e0e31", "74f839c593dc67fd", "0d6c8009d9a94f5a", "85676696d7fb7e2d",
            "cf2794e0277187b7", "18765564cd99a68d", "cbc9466e58fee3ce", "ab0200f58b01d137",
            "93f5f5799a932462", "9e0082df0ba9e4b0", "7a5dbbc594ddb9f3", "f4b32f46226bada7",
            "751e8fbc860ee5fb", "14ea5627c0843d90", "f723ca908e7af2ee", "a129ca6149be45e5",
            "3f2acc7f57c29bdb", "699ae9f52cbe4794", "4bc1b3f0968dd39c", "bb6dc91da77961bd",
            "bed65cf21aa2ee98", "d0f2cbb02e3b67c7", "93536795e3a33e88", "a80c038ccd5ccec8",
            "b8ad50c6f649af94", "bce192de8a85b8ea", "17d835b85bbb15f3", "2f2e6163076bcfad",
            "de4daaaca71dc9a5", "a6a2506687956571", "ad87a3535c49ef28", "32d892fad841c342",
            "7127512f72f27cce", "a7f32346f95978e3", "12e0b01abb051238", "15e034d40fa197ae",
            "314dffbe0815a3b4", "027990f029623981", "cadcd4e59ef40c4d", "9abfd8766a33735c",
            "0e3ea96b5304a7d0", "ad0c42d6fc585992", "187306c89bc215a9", "d4a60abcf3792b95",
            "f935451de4f21df2", "a9538f0419755787", "db9acddff56ca510", "d06c98cd5c0975eb",
            "e612a3cb9ecba951", "c766e62cfcadaf96", "ee64435a9752fe72", "a192d576b245165a",
            "0a8787bf8ecb74b2", "81b3e73d20b49b6f", "7fa8220ba3b2ecea", "245731c13ca42499",
            "b78dbfaf3a8d83bd", "ea1ad565322a1a0b", "60e61c23a3795013", "6606d7e446282b93",
            "6ca4ecb15c5f91e1", "9f626da15c9625f3", "e51b38608ef25f57", "958a324ceb064572"
    };

    static IntStream messageLengths() {
        return IntStream.range(0, PAPER_VECTORS.length);
    }

    private static byte[] message(int length) {
        byte[] octets = new byte[length];
        for (int index = 0; index < length; index++) {
            octets[index] = (byte) index;
        }
        return octets;
    }

    private static long sipHash(byte[] key, byte[] message) {
        return SipHash24.hash(SipHash24.keyLow(key), SipHash24.keyHigh(key), message, 0,
                message.length);
    }

    @ParameterizedTest(name = "the published vector for a message of {0} octets")
    @MethodSource("messageLengths")
    @DisplayName("HASH-003: the sixty-four vectors published with the algorithm")
    void paperVectors(int length) {
        assertThat(U64.toHex(sipHash(KEY, message(length)))).isEqualTo(PAPER_VECTORS[length]);
    }

    @ParameterizedTest(name = "an independent implementation agrees at {0} octets")
    @MethodSource("messageLengths")
    @DisplayName("HASH-003: a second implementation answers the same sixty-four messages")
    void independentOracleAgrees(int length) {
        byte[] message = message(length);
        SipHash oracle = new SipHash(2, 4);
        oracle.init(new KeyParameter(KEY));
        oracle.update(message, 0, message.length);
        assertThat(U64.toHex(sipHash(KEY, message))).isEqualTo(U64.toHex(oracle.doFinal()));
    }

    @Test
    @DisplayName("HASH-012: the key is used as it stands, so a different key answers differently")
    void keyReachesTheOutput() {
        byte[] other = HexFormat.of().parseHex("0f0e0d0c0b0a09080706050403020100");
        assertThat(sipHash(KEY, message(8))).isNotEqualTo(sipHash(other, message(8)));
    }

    @Test
    @DisplayName("HASH-002: the output is read as an unsigned sixty-four bit value")
    void outputIsUnsigned() {
        // The published vector for a message of four octets is cf2794e0277187b7, which sits above
        // 2^63 and is therefore a negative long. A port that reads the output as a signed quantity
        // renders it wrongly here and orders it wrongly wherever two values are compared.
        long value = sipHash(KEY, message(4));
        assertThat(value).isNegative();
        assertThat(U64.toHex(value)).isEqualTo(PAPER_VECTORS[4]).isEqualTo("cf2794e0277187b7");
        assertThat(U64.compare(value, 0L)).isPositive();
        assertThat(U64.compare(value, sipHash(KEY, message(0)))).isPositive();
    }
}
