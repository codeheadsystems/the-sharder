package com.codeheadsystems.sharder;

/**
 * The override entry a routing key matched, under {@code CORE-040}.
 *
 * <p>The index is the entry's place in the document's {@code overrides} array, which is the first
 * entry that matched under {@code OVR-002}, and the mode is which of its members applied.
 */
public record MatchedOverride(int index, OverrideMode mode) {
}
