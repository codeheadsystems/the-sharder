package com.codeheadsystems.sharder.core.internal.document;

/**
 * One reason a document is invalid, under {@code CORE-001} and {@code ERR-030}.
 *
 * <p>The path names the member the rule was broken at, the rule names which rule broke, and the
 * detail carries what broke it: an identifier, a level name, a token, or a count. A document that
 * breaks a rule is rejected whole, and every error is reported rather than the first.
 */
public record ValidationError(String path, String rule, String detail) {
}
