package dev.turboism.plugin.uitheme.service;

import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigRegistry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Typed persisted selection for the UI theme plugin. */
public final class ThemeSelectionConfig implements ThemeSelectionService.SelectionStore {

    public static final String CONFIG_ID = "ui-theme.selection";
    public static final String CONFIG_PATH = "ui-theme/selection.cfg";
    public static final String NATIVE_ID = "__native__";
    private static final String EMPTY = NATIVE_ID;
    private static final ConfigKey<List<String>> SELECTED_THEME = new ConfigKey<>(
        CONFIG_ID,
        "selected-theme",
        List.of(),
        ConfigCodecs.boundedStringList(1, 128)
    );
    private static final ConfigSchema SCHEMA = new ConfigSchema(
        CONFIG_ID,
        CONFIG_PATH,
        1,
        List.of(SELECTED_THEME)
    );

    private final PluginConfigRegistry registry;
    private Optional<String> selectedThemeId = Optional.empty();
    private long revision;
    private boolean initialized;

    public ThemeSelectionConfig(final PluginConfigRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Registers the selection schema and loads the persisted selection, making this store usable.
     *
     * <p>Must complete before any other method: reading or writing the selection beforehand throws
     * {@link IllegalStateException}. The sentinel {@value #NATIVE_ID} is read back as no selection.
     *
     * @return a stage completing once the selection has been loaded; it completes exceptionally with
     *     {@link IllegalStateException} if the config is unavailable or holds more than one value
     */
    public CompletionStage<Void> initialize() {
        return registry.registerSchema(SCHEMA, List.of())
            .thenCompose(ignored -> registry.read(SELECTED_THEME))
            .thenAccept(this::acceptRead);
    }

    @Override
    public Optional<String> selectedThemeId() {
        requireInitialized();
        return selectedThemeId;
    }

    @Override
    public void saveSelectedThemeId(final String themeId) {
        if (themeId == null || themeId.isBlank() || themeId.length() > 128) {
            throw new IllegalArgumentException("themeId must be bounded non-blank text");
        }
        write(List.of(themeId));
        selectedThemeId = Optional.of(themeId);
    }

    @Override
    public void clearSelectedThemeId() {
        write(List.of());
        selectedThemeId = Optional.empty();
    }

    private void acceptRead(final ConfigReadResult<List<String>> read) {
        if (read.error().isPresent()) {
            throw new IllegalStateException("theme selection config is unavailable: " + read.error().orElseThrow().code());
        }
        final List<String> value = read.value().value();
        if (value.size() > 1) {
            throw new IllegalStateException("theme selection config contains multiple values");
        }
        selectedThemeId = value.stream().findFirst().filter(candidate -> !NATIVE_ID.equals(candidate));
        revision = read.value().revision();
        initialized = true;
    }

    private void write(final List<String> value) {
        requireInitialized();
        final ConfigWriteResult result = registry.write(SELECTED_THEME, value, revision)
            .toCompletableFuture().join();
        if (!result.written()) {
            throw new IllegalStateException("could not persist theme selection: " + result.error().orElseThrow().code());
        }
        revision = result.revision();
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("theme selection config is not initialized");
        }
    }
}
