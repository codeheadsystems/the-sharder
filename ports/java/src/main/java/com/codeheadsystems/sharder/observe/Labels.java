package com.codeheadsystems.sharder.observe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The labels one metric carries, under {@code OBS-011}.
 *
 * <p>It is a small fixed-arity value type rather than a {@code Map}, because a metric is recorded
 * on the routing path and a map allocation per decision is not. The four arities below cover every
 * metric the specification states.
 */
public final class Labels {

    private static final Labels NONE = new Labels(null, null, null, null, null, null);

    private final String firstName;
    private final String firstValue;
    private final String secondName;
    private final String secondValue;
    private final String thirdName;
    private final String thirdValue;

    private Labels(String firstName, String firstValue, String secondName, String secondValue,
                   String thirdName, String thirdValue) {
        this.firstName = firstName;
        this.firstValue = firstValue;
        this.secondName = secondName;
        this.secondValue = secondValue;
        this.thirdName = thirdName;
        this.thirdValue = thirdValue;
    }

    /** No labels. */
    public static Labels none() {
        return NONE;
    }

    /** One label. */
    public static Labels of(String name, String value) {
        return new Labels(require(name), require(value), null, null, null, null);
    }

    /** Two labels, in the order given. */
    public static Labels of(String firstName, String firstValue,
                            String secondName, String secondValue) {
        return new Labels(require(firstName), require(firstValue), require(secondName),
                require(secondValue), null, null);
    }

    /** Three labels, in the order given. */
    public static Labels of(String firstName, String firstValue, String secondName,
                            String secondValue, String thirdName, String thirdValue) {
        return new Labels(require(firstName), require(firstValue), require(secondName),
                require(secondValue), require(thirdName), require(thirdValue));
    }

    /** The number of labels. */
    public int size() {
        if (firstName == null) {
            return 0;
        }
        return secondName == null ? 1 : thirdName == null ? 2 : 3;
    }

    /** The name of one label, by position. */
    public String name(int index) {
        return switch (index) {
            case 0 -> firstName;
            case 1 -> secondName;
            case 2 -> thirdName;
            default -> throw new IndexOutOfBoundsException(index);
        };
    }

    /** The value of one label, by position. */
    public String value(int index) {
        return switch (index) {
            case 0 -> firstValue;
            case 1 -> secondValue;
            case 2 -> thirdValue;
            default -> throw new IndexOutOfBoundsException(index);
        };
    }

    /** The labels as a map, for a registry whose own surface takes one. */
    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < size(); index++) {
            map.put(name(index), value(index));
        }
        return Map.copyOf(map);
    }

    private static String require(String value) {
        return Objects.requireNonNull(value, "a label name and value are never null");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Labels labels
                && Objects.equals(firstName, labels.firstName)
                && Objects.equals(firstValue, labels.firstValue)
                && Objects.equals(secondName, labels.secondName)
                && Objects.equals(secondValue, labels.secondValue)
                && Objects.equals(thirdName, labels.thirdName)
                && Objects.equals(thirdValue, labels.thirdValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(firstName, firstValue, secondName, secondValue, thirdName, thirdValue);
    }

    @Override
    public String toString() {
        return asMap().toString();
    }
}
