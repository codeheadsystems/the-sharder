package com.codeheadsystems.sharder.core.internal.placement;

import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.Matcher;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Matcher evaluation and the precedence of {@code PLACE-060} through {@code PLACE-067}.
 *
 * <p>One table is a {@code directory}'s entries or a document's overrides, and both are evaluated
 * against the routing key by these rules. Matching is over octets: nothing is normalised, case
 * folded, or decoded as text.
 */
public final class Matchers {

    private Matchers() {
    }

    /** Whether the matcher matches the routing key, under {@code PLACE-061} onwards. */
    public static boolean matches(Matcher matcher, byte[] routingKey) {
        return switch (matcher.kind()) {
            case "exact" -> matcher.valueEquals(routingKey);
            // An empty prefix matches every routing key, under PLACE-063.
            case "prefix" -> matcher.valuePrefixes(routingKey);
            default -> throw new IllegalArgumentException(
                    "no matcher kind named " + matcher.kind());
        };
    }

    /**
     * The matched entry of {@code PLACE-065}: an {@code exact} matcher beats every {@code prefix},
     * the longest matching {@code prefix} wins among prefixes, and the lower array index wins among
     * entries still tied.
     *
     * <p>The third clause is unreachable in a valid document, because two entries surviving the
     * first two carry the same kind and the same octets, which is a load-time failure. It is
     * applied anyway, so that the precedence is total over a table this class has not validated.
     */
    public static <T> Optional<T> matched(List<T> entries, Function<T, Matcher> matcherOf,
                                          byte[] routingKey) {
        T best = null;
        Matcher bestMatcher = null;
        for (T entry : entries) {
            Matcher matcher = matcherOf.apply(entry);
            if (!matches(matcher, routingKey)) {
                continue;
            }
            if (best == null || beats(matcher, bestMatcher)) {
                best = entry;
                bestMatcher = matcher;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean beats(Matcher candidate, Matcher incumbent) {
        boolean candidateExact = "exact".equals(candidate.kind());
        boolean incumbentExact = "exact".equals(incumbent.kind());
        if (candidateExact != incumbentExact) {
            return candidateExact;
        }
        // Equal kinds: a longer prefix wins, and an earlier index keeps its place, which is what
        // refusing to replace on a tie amounts to.
        return candidate.valueLength() > incumbent.valueLength();
    }
}
