package dev.turboism.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.schema.runtimeconfig.RuntimeConfigValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** Early immutable subset of the global runtime configuration needed during premain. */
public record RuntimeStartupConfig(
    boolean safeMode,
    boolean requestedSkipStartupUpdateCheck,
    boolean requestedSkipStartupSplash,
    boolean requestedSkipStartupInformation,
    boolean skipStartupUpdateCheck,
    boolean skipStartupSplash,
    boolean skipStartupInformation
) {

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
            !safeMode && skipStartupInformation
        );
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RuntimeStartupConfig DISABLED =
        new RuntimeStartupConfig(false, false, false, false);
    private static final long MAX_CONFIG_BYTES = 64L * 1024L;

    public static RuntimeStartupConfig load(final Path turboismHome) {
        return load(turboismHome, ignored -> { });
    }

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
            if (!new RuntimeConfigValidator().validate(root, configPath.toString()).isEmpty()) {
                report(diagnostic, "RUNTIME_STARTUP_CONFIG_INVALID");
                return DISABLED;
            }
            final boolean safeMode = root.path("safeMode").asBoolean(false);
            final JsonNode startup = root.path("hooks").path("startup");
            final boolean requestedUpdate = startup.path("skipUpdateCheck").asBoolean(false);
            final boolean requestedSplash = startup.path("skipSplash").asBoolean(false);
            final boolean requestedInformation = startup.path("skipInformation").asBoolean(false);
            return new RuntimeStartupConfig(
                safeMode,
                requestedUpdate,
                requestedSplash,
                requestedInformation
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
