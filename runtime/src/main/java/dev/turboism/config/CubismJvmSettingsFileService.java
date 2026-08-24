package dev.turboism.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.plugin.core.CubismJvmSettingsService;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Persists the managed launcher's Cubism JVM choice in the canonical config. */
public final class CubismJvmSettingsFileService implements CubismJvmSettingsService {

    private static final CubismJvm DEFAULT = CubismJvm.GRAALVM;

    private final RuntimeConfigRepository config;
    private final Path turboismHome;
    private final Map<String, String> environment;

    public CubismJvmSettingsFileService(final Path turboismHome) {
        this(
            new RuntimeConfigRepository(turboismHome, ignored -> { }),
            turboismHome,
            System.getenv()
        );
    }

    CubismJvmSettingsFileService(final RuntimeConfigRepository config) {
        this(config, null, Map.of());
    }

    CubismJvmSettingsFileService(
        final RuntimeConfigRepository config,
        final Path turboismHome,
        final Map<String, String> environment
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.turboismHome = turboismHome == null
            ? null
            : turboismHome.toAbsolutePath().normalize();
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    @Override
    public CubismJvm read() {
        final JsonNode value = config.read().path("launcher").path("cubismJvm");
        return value.isMissingNode() ? DEFAULT : CubismJvm.fromConfig(value.asText());
    }

    @Override
    public CubismJvm save(final CubismJvm value) {
        final CubismJvm requested = Objects.requireNonNull(value, "value");
        config.update(root -> {
            root.withObject("launcher").put("cubismJvm", requested.configValue());
            return root;
        });
        return requested;
    }

    @Override
    public Optional<Path> graalVmJava() {
        if (turboismHome == null) return Optional.empty();
        final List<Path> candidates = new ArrayList<>();
        addExecutable(candidates, environment.get("TURBOISM_CUBISM_JAVA"));
        candidates.add(turboismHome.resolve("graalvm/bin/java.exe"));
        candidates.add(turboismHome.resolve("graal/runtime/bin/java.exe"));
        addHome(candidates, environment.get("TURBOISM_GRAALVM_HOME"));
        addHome(candidates, environment.get("GRAALVM_HOME"));
        return candidates.stream()
            .map(path -> path.toAbsolutePath().normalize())
            .filter(CubismJvmSettingsFileService::compatibleGraalVmExecutable)
            .findFirst();
    }

    private static void addExecutable(final List<Path> candidates, final String raw) {
        if (raw == null || raw.isBlank()) return;
        try {
            final Path path = Paths.get(raw.trim());
            candidates.add(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                ? path.resolve("bin/java.exe")
                : path);
        } catch (RuntimeException ignored) {
            // An invalid environment path is unavailable, not an alternate authority.
        }
    }

    private static void addHome(final List<Path> candidates, final String raw) {
        if (raw == null || raw.isBlank()) return;
        try {
            candidates.add(Paths.get(raw.trim()).resolve("bin/java.exe"));
        } catch (RuntimeException ignored) {
            // An invalid environment path is unavailable, not an alternate authority.
        }
    }

    private static boolean compatibleGraalVmExecutable(final Path path) {
        if (Files.isSymbolicLink(path)
            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        final Path bin = path.getParent();
        final Path home = bin == null ? null : bin.getParent();
        final Path release = home == null ? null : home.resolve("release");
        if (release == null || Files.isSymbolicLink(release)
            || !Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            if (Files.size(release) > 64 * 1024) return false;
            final String metadata = Files.readString(release, StandardCharsets.UTF_8);
            return releaseValue(metadata, "IMPLEMENTOR").orElse("").startsWith("GraalVM")
                && releaseValue(metadata, "GRAALVM_VERSION").orElse("").startsWith("25.2.");
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static Optional<String> releaseValue(final String metadata, final String key) {
        final String prefix = key + "=";
        return metadata.lines()
            .map(String::trim)
            .filter(line -> line.startsWith(prefix))
            .map(line -> line.substring(prefix.length()).trim())
            .map(value -> value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value)
            .findFirst();
    }
}
