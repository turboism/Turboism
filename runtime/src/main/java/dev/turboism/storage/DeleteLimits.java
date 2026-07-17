package dev.turboism.storage;

/** Conservative internal recursive-delete ceilings; these are not SDK limits. */
record DeleteLimits(int maxDepth, long maxEntries, long maxWork) {

    static final int DEFAULT_MAX_DEPTH = 64;
    static final long DEFAULT_MAX_ENTRIES = 10_000L;
    static final long DEFAULT_MAX_WORK = 100_000L;

    DeleteLimits {
        requireTightened("maxDepth", maxDepth, DEFAULT_MAX_DEPTH);
        requireTightened("maxEntries", maxEntries, DEFAULT_MAX_ENTRIES);
        requireTightened("maxWork", maxWork, DEFAULT_MAX_WORK);
    }

    static DeleteLimits defaults() {
        return new DeleteLimits(
            DEFAULT_MAX_DEPTH,
            DEFAULT_MAX_ENTRIES,
            DEFAULT_MAX_WORK
        );
    }

    private static void requireTightened(
        final String name,
        final long value,
        final long defaultValue
    ) {
        if (value < 0L || value > defaultValue) {
            throw new IllegalArgumentException(
                name + " must be between 0 and " + defaultValue
            );
        }
    }
}
