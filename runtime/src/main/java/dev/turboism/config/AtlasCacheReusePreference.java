package dev.turboism.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads the persisted atlas cache-reuse preference during premain.
 *
 * <p>The transformer must decide before the host class is defined, which is earlier than the
 * plugin runtime exists, so this deliberately reads only the one key it needs under the same
 * bounds as {@link RuntimeStartupConfig}: the common validated performance snapshot.</p>
 */
public final class AtlasCacheReusePreference {

    /** Config key holding the preference. */
    public static final String KEY = "atlasCacheReuse";
    /** Default when nothing is persisted: rendering optimizations require explicit opt-in. */
    public static final boolean DEFAULT_ENABLED = false;

    private AtlasCacheReusePreference() {}

    /**
     * @param turboismHome Turboism home containing {@code config.json}
     * @return the persisted preference, or {@link #DEFAULT_ENABLED} when absent; invalid files disable the optimization
     */
    public static boolean read(final Path turboismHome) {
        return NativeOptimizationConfig.load(turboismHome, ignored -> {})
                .choice(NativeOptimizationConfig.Option.ATLAS_CACHE_REUSE)
                .enabled();
    }

    /** @return the value to persist for a proposed toggle state */
    public static boolean normalize(final Object proposed) {
        Objects.requireNonNull(proposed, "proposed");
        return proposed instanceof Boolean value ? value : DEFAULT_ENABLED;
    }
}
