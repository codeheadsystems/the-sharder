/**
 * The sharder library: given a key and a view of the world, which nodes handle that key, in what
 * order, and what happens when those nodes are unavailable.
 *
 * <p>The packages under {@code com.codeheadsystems.sharder.core.internal} are unexported,
 * unsupported, and free to change between any two versions.
 */
module com.codeheadsystems.sharder {
    exports com.codeheadsystems.sharder;
    exports com.codeheadsystems.sharder.error;
}
