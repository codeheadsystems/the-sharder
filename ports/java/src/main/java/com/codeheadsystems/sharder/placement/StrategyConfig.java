package com.codeheadsystems.sharder.placement;

import java.util.Map;
import java.util.Optional;

/**
 * The strategy object of a topology document, as a strategy implementation reads it.
 *
 * <p>The members are those the document carries under its own kind, already decoded: a strategy
 * reads what it declared and nothing about the rest of the document, which is what keeps
 * {@code PLACE-012} a function of the snapshot alone.
 */
public record StrategyConfig(String kind, Map<String, String> members) {

    /** The members are copied. */
    public StrategyConfig {
        members = Map.copyOf(members);
    }

    /** One member, absent where the document carries none of that name. */
    public Optional<String> member(String name) {
        return Optional.ofNullable(members.get(name));
    }
}
