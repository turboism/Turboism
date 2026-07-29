package dev.turboism.plugin.textureatlas;

import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigRegistrationException;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.PluginConfigRegistry;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Typed persistence for the plugin-owned layout policy. */
final class TextureAtlasSettingsBinding {
    static final String CONFIG_ID = "texture-atlas.layout";
    static final String CONFIG_PATH = "texture-atlas/layout.cfg";
    private static final ConfigKey<TextureAtlasLayoutMode> MODE = new ConfigKey<>(
        CONFIG_ID,
        "layoutMode",
        TextureAtlasLayoutMode.PART_BUCKET,
        ConfigCodecs.enumValue(TextureAtlasLayoutMode.class)
    );
    private static final ConfigSchema SCHEMA = new ConfigSchema(
        CONFIG_ID,
        CONFIG_PATH,
        1,
        List.of(MODE)
    );

    private PluginConfigRegistry registry;
    private TextureAtlasSettings confirmed = TextureAtlasSettings.defaults();
    private long revision;
    private long epoch;
    private boolean initialized;
    private boolean enabled;

    CompletionStage<Boolean> init(final PluginConfigRegistry value) {
        registry = Objects.requireNonNull(value, "value");
        try {
            return registry.registerSchema(SCHEMA, List.of()).handle((ignored, failure) -> {
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
            return registry.read(MODE).handle((read, failure) -> {
                if (failure != null || !enabled || epoch != active || read.error().isPresent()) return false;
                confirmed = new TextureAtlasSettings(read.value().value());
                revision = read.value().revision();
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
            return registry.write(MODE, value.layoutMode(), revision).handle((write, failure) -> {
                if (failure != null || !enabled || epoch != active || !write.written()) return false;
                confirmed = value;
                revision = write.revision();
                return true;
            });
        } catch (UnsupportedOperationException failure) {
            return CompletableFuture.completedStage(false);
        }
    }

    TextureAtlasSettings confirmed() {
        return confirmed;
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
