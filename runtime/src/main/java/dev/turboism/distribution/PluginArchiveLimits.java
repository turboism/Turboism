package dev.turboism.distribution;

final class PluginArchiveLimits {
    static final long RAW_MAX = 1024L * 1024 * 1024;
    static final long TOTAL_MAX = 2L * 1024 * 1024 * 1024;
    static final long ENTRY_MAX = 512L * 1024 * 1024;
    static final int ENTRY_COUNT_MAX = 10_000;
    static final int PATH_BYTES_MAX = 1024;
    static final int PATH_DEPTH_MAX = 32;
    static final double RATIO_MAX = 100.0;
    static final int JSON_MAX = 1024 * 1024;

    private PluginArchiveLimits() {}
}
