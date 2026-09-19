package com.codeheadsystems.sharder.topology;

/**
 * One reason a topology document is refused, under {@code TOPO-002} and {@code TOPO-010}.
 *
 * <p>A document is refused whole, and every error it carries is reported rather than the first, so
 * an operator repairs a document in one pass. {@code path} is the JSON pointer of the member the
 * rule read, {@code rule} names the rule, and {@code detail} states what was found.
 */
public record ValidationError(String path, String rule, String detail) {
}
