package dev.turboism.plugin.atlasmaxrectsbssf;

import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigDocument;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigRegistrationException;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.PluginConfigRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Typed persistence for the plugin-owned layout-mode policy. */
final class TextureAtlasSettingsBinding {
    static final String CONFIG_ID = "texture-atlas.layout";
    static final String CONFIG_PATH = "texture-atlas/layout.cfg";
    private static final ConfigKey<TextureAtlasLayoutMode> MODE = new ConfigKey<>(
        CONFIG_ID,
        "layout-mode",
        TextureAtlasLayoutMode.PART_BUCKET,
        ConfigCodecs.enumValue(TextureAtlasLayoutMode.class)
    );
    // Migrations: every intermediate document is validated against the final v4 schema,
    // so each step emits only the surviving layout-mode key. The legacy algorithm and
    // parallel values are captured into pendingLegacySelection at whichever step first
    // sees them, so the plugin can hand the user's preference to the runtime-owned
    // selection once instead of silently dropping it.
    private final ConfigMigration V1_TO_V2 = new ConfigMigration() {
        @Override
        public int fromVersion() {
            return 1;
        }

        @Override
        public int toVersion() {
            return 2;
        }

        @Override
        public ConfigDocument migrate(final ConfigDocument input) {
            // v1 predates the algorithm keys; the effective choice was the v2 default.
            pendingLegacySelection = new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection(
                TextureAtlasPlugin.ALGORITHM_MAXRECTS,
                false
            );
            return new ConfigDocument(2, retained(input));
        }
    };
    private final ConfigMigration V2_TO_V3 = new ConfigMigration() {
        @Override
        public int fromVersion() {
            return 2;
        }

        @Override
        public int toVersion() {
            return 3;
        }

        @Override
        public ConfigDocument migrate(final ConfigDocument input) {
            captureLegacySelection(input, true);
            return new ConfigDocument(3, retained(input));
        }
    };
    private final ConfigMigration V3_TO_V4 = new ConfigMigration() {
        @Override
        public int fromVersion() {
            return 3;
        }

        @Override
        public int toVersion() {
            return 4;
        }

        @Override
        public ConfigDocument migrate(final ConfigDocument input) {
            captureLegacySelection(input, false);
            return new ConfigDocument(4, retained(input));
        }
    };

    /** @return only the keys that survive into the v4 schema. */
    private Map<String, String> retained(final ConfigDocument input) {
        final Map<String, String> values = new LinkedHashMap<>();
        final Map<String, String> encoded =
            input.encodedValues() == null ? Map.of() : input.encodedValues();
        final String mode = encoded.get(MODE.name());
        if (mode != null) {
            values.put(MODE.name(), mode);
        }
        return values;
    }

    /**
     * Captures the persisted algorithm/parallel preference for the one-time hand-off to
     * the runtime-owned selection. {@code enumNames} is true for v2 documents, which
     * stored the algorithm enum name rather than the algorithm id.
     */
    private void captureLegacySelection(
        final ConfigDocument input,
        final boolean enumNames
    ) {
        final Map<String, String> encoded =
            input.encodedValues() == null ? Map.of() : input.encodedValues();
        final String stored = encoded.get("algorithm");
        if (stored == null) {
            return;
        }
        final String algorithmId = !enumNames ? stored
            : "NATIVE".equals(stored) ? TextureAtlasPlugin.ALGORITHM_NATIVE
            : "MAXRECTS".equals(stored) ? TextureAtlasPlugin.ALGORITHM_MAXRECTS
            : stored;
        pendingLegacySelection = new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection(
            algorithmId,
            "true".equals(encoded.get("parallel"))
        );
    }
    private static final ConfigSchema SCHEMA = new ConfigSchema(
        CONFIG_ID,
        CONFIG_PATH,
        4,
        List.of(MODE)
    );

    private PluginConfigRegistry registry;
    private volatile TextureAtlasSettings confirmed = TextureAtlasSettings.defaults();
    private volatile dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection
        pendingLegacySelection;
    private long revision;
    private long epoch;
    private boolean initialized;
    private boolean enabled;

    CompletionStage<Boolean> init(final PluginConfigRegistry value) {
        registry = Objects.requireNonNull(value, "value");
        try {
            return registry.registerSchema(SCHEMA, List.of(V1_TO_V2, V2_TO_V3, V3_TO_V4))
                .handle((ignored, failure) -> {
                    initialized = failure == null;
                    return initialized;
                });
        } catch (ConfigRegistrationException | UnsupportedOperationException failure) {
            return CompletableFuture.completedStage(false);
        }
    }

    CompletionStage<Boolean> enable() {
        if (!initialized || registry == null) return CompletableFuture.completedStage(false);
        enabled = true;
        final long active = ++epoch;
        try {
            return registry.read(MODE).handle((modeRead, failure) -> {
                if (failure != null || !enabled || epoch != active) return false;
                if (modeRead.error().isPresent()) return false;
                confirmed = new TextureAtlasSettings(modeRead.value().value());
                revision = modeRead.value().revision();
                return true;
            });
        } catch (UnsupportedOperationException failure) {
            return CompletableFuture.completedStage(false);
        }
    }

    CompletionStage<Boolean> update(final TextureAtlasSettings value) {
        Objects.requireNonNull(value, "value");
        if (!enabled || registry == null) return CompletableFuture.completedStage(false);
        if (value.equals(confirmed)) return CompletableFuture.completedStage(true);
        final long active = epoch;
        try {
            return registry.write(MODE, value.layoutMode(), revision).handle((modeWrite, failure) -> {
                if (failure != null || !enabled || epoch != active) return false;
                if (!modeWrite.written()) return false;
                confirmed = value;
                revision = modeWrite.revision();
                return true;
            });
        } catch (UnsupportedOperationException failure) {
            return CompletableFuture.completedStage(false);
        }
    }

    TextureAtlasSettings confirmed() {
        return confirmed;
    }

    /**
     * Returns the selection captured by the v3→v4 migration exactly once, so the plugin
     * can hand a legacy user preference to the runtime-owned selection state; {@code
     * null} when there is nothing to migrate or it was already consumed.
     */
    dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection consumeLegacySelection() {
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection captured =
            pendingLegacySelection;
        pendingLegacySelection = null;
        return captured;
    }

    void disable() {
        enabled = false;
        epoch++;
    }

    void shutdown() {
        disable();
        initialized = false;
        registry = null;
    }
}
