package com.codeheadsystems.sharder.core.internal.document;

import com.codeheadsystems.sharder.Digest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The topology digest of {@code TOPO-030}: the SHA-256 of a document's canonical form.
 *
 * <p>The digest identifies document content. Two documents carrying one {@code topologyId} and one
 * epoch are the same topology exactly when their digests match, and a mismatch is an authority
 * defect the library refuses rather than resolves.
 */
public final class Digests {

    private Digests() {
    }

    /** The digest of the canonical octets. */
    public static Digest of(byte[] canonical) {
        try {
            return Digest.ofBytes(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException cause) {
            throw new IllegalStateException("SHA-256 is absent from this runtime", cause);
        }
    }
}
