package dev.turboism.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.schema.runtimeconfig.RuntimeConfigValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Early immutable subset of the global runtime configuration needed during premain. */
public record RuntimeStartupConfig(
    boolean safeMode,
    boolean requestedSkipStartupUpdateCheck,
    boolean requestedSkipStartupSplash,
    boolean requestedSkipStartupInformation,
    boolean skipStartupUpdateCheck,
    boolean skipStartupSplash,
    boolean skipStartupInformation,
    Set<String> disabledHookIds
) {

    public RuntimeStartupConfig {
        disabledHookIds = Set.copyOf(Objects.requireNonNull(disabledHookIds, "disabledHookIds"));
    }

    public RuntimeStartupConfig(
        final boolean safeMode,
        final boolean skipStartupUpdateCheck,
        final boolean skipStartupSplash,
        final boolean skipStartupInformation
    ) {
        this(
            safeMode,
            skipStartupUpdateCheck,
            skipStartupSplash,
            skipStartupInformation,
            !safeMode && skipStartupUpdateCheck,
            !safeMode && skipStartupSplash,
            !safeMode && skipStartupInformation,
            Set.of()
        );
    }

    /**
     * @param hookId identifier of the startup hook to test
     * @return true when the hook may run: safe mode is off and the ID is not in the operator's
     *     disable list. Safe mode disables every hook regardless of the list.
     * @throws NullPointerException if {@code hookId} is null
     */
    public boolean hookEnabled(final String hookId) {
        return !safeMode && !disabledHookIds.contains(Objects.requireNonNull(hookId, "hookId"));
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RuntimeStartupConfig DISABLED =
        new RuntimeStartupConfig(false, false, false, false);
    private static final long MAX_CONFIG_BYTES = 64L * 1024L;

    /**
     * Loads the startup subset, discarding diagnostics.
     *
     * @param turboismHome Turboism home directory containing {@code config.json}
     * @return the parsed configuration, or the all-disabled configuration on any problem
     * @throws NullPointerException if {@code turboismHome} is null
     */
    public static RuntimeStartupConfig load(final Path turboismHome) {
        return load(turboismHome, ignored -> { });
    }

    /**
     * Loads the startup subset of {@code config.json} during premain, before the runtime proper
     * exists.
     *
     * <p>This never throws on a bad configuration: every rejection path returns the all-disabled
     * configuration and reports a code, because failing to read config must not stop the official
     * host from starting. Rejections are a path escaping the home directory, a non-regular file, a
     * file over 64 KiB, a schema-invalid document, and any read or parse error. An absent file is
     * normal and reports nothing.</p>
     *
     * @param turboismHome Turboism home directory containing {@code config.json}
     * @param diagnostic receives a {@code RUNTIME_STARTUP_CONFIG_*} code per rejection; exceptions
     *     it throws are swallowed so a broken diagnostic sink cannot block startup
     * @return the parsed configuration, or the all-disabled configuration when anything was wrong
     * @throws NullPointerException if either argument is null
     */
    public static RuntimeStartupConfig load(
        final Path turboismHome,
        final Consumer<String> diagnostic
    ) {
        Objects.requireNonNull(turboismHome, "turboismHome");
        Objects.requireNonNull(diagnostic, "diagnostic");
        final Path home = turboismHome.toAbsolutePath().normalize();
        final Path configPath = home.resolve("config.json").normalize();
        if (!configPath.startsWith(home)) {
            report(diagnostic, "RUNTIME_STARTUP_CONFIG_PATH_REJECTED");
            return DISABLED;
        }
        if (!Files.exists(configPath, LinkOption.NOFOLLOW_LINKS)) {
            return DISABLED;
        }
        try {
            if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)
                || Files.size(configPath) > MAX_CONFIG_BYTES) {
                report(diagnostic, "RUNTIME_STARTUP_CONFIG_FILE_REJECTED");
                return DISABLED;
            }
            final JsonNode root = JSON.readTree(Files.readAllBytes(configPath));
            final java.util.List<dev.turboism.core.schema.SchemaValidationError> configErrors =
                new RuntimeConfigValidator().validate(root, configPath.toString());
            if (!configErrors.isEmpty()) {
                report(diagnostic, "RUNTIME_STARTUP_CONFIG_INVALID");
                return DISABLED;
            }
            final boolean safeMode = root.path("safeMode").asBoolean(false);
            final JsonNode startup = root.path("hooks").path("startup");
            final boolean requestedUpdate = startup.path("skipUpdateCheck").asBoolean(false);
            final boolean requestedSplash = startup.path("skipSplash").asBoolean(false);
            final boolean requestedInformation = startup.path("skipInformation").asBoolean(false);
            final java.util.Set<String> disabledHookIds = new java.util.LinkedHashSet<>();
            root.path("hooks").path("disabledIds").forEach(value -> disabledHookIds.add(value.asText()));
            return new RuntimeStartupConfig(
                safeMode,
                requestedUpdate,
                requestedSplash,
                requestedInformation,
                !safeMode && requestedUpdate,
                !safeMode && requestedSplash,
                !safeMode && requestedInformation,
                disabledHookIds
            );
        } catch (IOException | RuntimeException failure) {
            report(diagnostic, "RUNTIME_STARTUP_CONFIG_UNREADABLE");
            return DISABLED;
        }
    }

    private static void report(final Consumer<String> diagnostic, final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
            // Diagnostics must never block the official host startup path.
        }
}

}
