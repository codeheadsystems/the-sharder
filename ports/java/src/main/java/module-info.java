/**
 * The sharder library: given a key and a view of the world, which nodes handle that key, in what
 * order, and what happens when those nodes are unavailable.
 *
 * <p>The packages under {@code com.codeheadsystems.sharder.core.internal} and
 * {@code com.codeheadsystems.sharder.migrate.internal} are unexported, unsupported, and free to
 * change between any two versions.
 */
module com.codeheadsystems.sharder {
    exports com.codeheadsystems.sharder;
    exports com.codeheadsystems.sharder.config;
    exports com.codeheadsystems.sharder.core;
    exports com.codeheadsystems.sharder.error;
    exports com.codeheadsystems.sharder.fence;
    exports com.codeheadsystems.sharder.health;
    exports com.codeheadsystems.sharder.migrate;
    exports com.codeheadsystems.sharder.observe;
    exports com.codeheadsystems.sharder.placement;
    exports com.codeheadsystems.sharder.provider.file;
    exports com.codeheadsystems.sharder.topology;
}
