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
public final class CubismJvmSettingsFileService implements CubismJvmSettingsService, AutoCloseable {

    private static final CubismJvm DEFAULT = CubismJvm.GRAALVM;

    private final RuntimeConfigRepository config;
    private final Path turboismHome;
    private final Map<String, String> environment;
    private final dev.turboism.graal.ManagedGraalRuntimeService managedRuntime;

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
        this(
            config,
            turboismHome,
            environment,
            path -> new dev.turboism.graal.ManagedGraalRuntimeService(path, ignored -> { })
        );
    }

    CubismJvmSettingsFileService(
        final RuntimeConfigRepository config,
        final Path turboismHome,
        final Map<String, String> environment,
        final ManagedRuntimeFactory managedRuntimeFactory
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.turboismHome = turboismHome == null
            ? null
            : turboismHome.toAbsolutePath().normalize();
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.managedRuntime = createManagedRuntime(
            this.turboismHome,
            Objects.requireNonNull(managedRuntimeFactory, "managedRuntimeFactory")
        );
    }

    private static dev.turboism.graal.ManagedGraalRuntimeService createManagedRuntime(
        final Path turboismHome,
        final ManagedRuntimeFactory factory
    ) {
        if (turboismHome == null) return null;
        try {
            return factory.create(turboismHome);
        } catch (NoClassDefFoundError unavailable) {
            return null;
        }
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
    public String graalVmPath() {
        final JsonNode value = config.read().path("launcher").path("graalVmPath");
        return value.isTextual() ? value.asText() : "";
    }

    @Override
    public String saveGraalVmPath(final String value) {
        final String requested = Objects.requireNonNullElse(value, "").trim();
        final String persisted;
        if (requested.isEmpty()) {
            persisted = "";
        } else {
            final Optional<Path> compatible = compatibleGraalVmPath(requested);
            if (compatible.isEmpty()) {
                throw new IllegalArgumentException(
                    "GraalVM path must identify a GraalVM installation"
                );
            }
            persisted = Paths.get(requested).toAbsolutePath().normalize().toString();
        }
        config.update(root -> {
            if (persisted.isEmpty()) {
                root.withObject("launcher").remove("graalVmPath");
            } else {
                root.withObject("launcher").put("graalVmPath", persisted);
            }
            return root;
        });
        return persisted;
    }

    @Override
    public boolean graalVmPathCompatible(final String value) {
        return value == null || value.isBlank() || compatibleGraalVmPath(value).isPresent();
    }

    @Override
    public Optional<Path> graalVmJava() {
        if (turboismHome == null) return Optional.empty();
        final Optional<Path> explicit = compatibleGraalVmPath(
            environment.get("TURBOISM_CUBISM_JAVA")
        );
        if (explicit.isPresent()) return explicit;
        if (managedRuntime != null) {
            final Optional<Path> managed = managedRuntime.managedJavaExecutableIfReady();
            if (managed.isPresent()) return managed;
        }
        final List<Path> candidates = new ArrayList<>();
        candidates.add(turboismHome.resolve("graalvm/bin/java.exe"));
        addHome(candidates, environment.get("TURBOISM_GRAALVM_HOME"));
        addHome(candidates, environment.get("GRAALVM_HOME"));
        final Optional<Path> discovered = candidates.stream()
            .map(path -> path.toAbsolutePath().normalize())
            .filter(CubismJvmSettingsFileService::compatibleGraalVmExecutable)
            .findFirst();
        return discovered.isPresent()
            ? discovered
            : compatibleGraalVmPath(graalVmPath());
    }

    private static Optional<Path> compatibleGraalVmPath(final String raw) {
        final List<Path> candidates = new ArrayList<>();
        addExecutable(candidates, raw);
        return candidates.stream()
            .map(path -> path.toAbsolutePath().normalize())
            .filter(CubismJvmSettingsFileService::compatibleGraalVmExecutable)
            .findFirst();
    }

    private static void addExecutable(final List<Path> candidates, final String raw) {
        if (raw == null || raw.isBlank()) return;
        try {
            final Path path = Paths.get(raw.trim());
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                candidates.add(path);
            } else if (path.getFileName() != null
                && path.getFileName().toString().equalsIgnoreCase("bin")) {
                candidates.add(path.resolve("java.exe"));
            } else {
                candidates.add(path.resolve("bin/java.exe"));
            }
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
            return !releaseValue(metadata, "GRAALVM_VERSION").orElse("").isBlank()
                && !releaseValue(metadata, "JAVA_VERSION").orElse("").isBlank();
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public ManagedRuntimeStatus managedRuntimeStatus() {
        return managedRuntime == null
            ? CubismJvmSettingsService.super.managedRuntimeStatus()
            : managedStatus(managedRuntime.status());
    }

    @Override
    public ManagedRuntimeOperation installManagedRuntime() {
        if (managedRuntime == null) return CubismJvmSettingsService.super.installManagedRuntime();
        final dev.turboism.graal.ManagedGraalRuntimeService.Operation operation = managedRuntime.install();
        return new ManagedRuntimeOperation() {
            @Override public ManagedRuntimeStatus status() {
                return managedStatus(operation.status());
            }
            @Override public java.util.concurrent.CompletionStage<ManagedRuntimeStatus> completion() {
                return operation.completion().thenApply(CubismJvmSettingsFileService::managedStatus);
            }
            @Override public boolean cancel() { return operation.cancel(); }
        };
    }

    @Override
    public ManagedRuntimeStatus verifyManagedRuntime() {
        return managedRuntime == null
            ? CubismJvmSettingsService.super.verifyManagedRuntime()
            : managedStatus(managedRuntime.verify());
    }

    @Override
    public ManagedRuntimeStatus removeManagedRuntime() {
        return managedRuntime == null
            ? CubismJvmSettingsService.super.removeManagedRuntime()
            : managedStatus(managedRuntime.remove());
    }

    @Override
    public void close() {
        if (managedRuntime != null) managedRuntime.close();
    }

    @FunctionalInterface
    interface ManagedRuntimeFactory {
        dev.turboism.graal.ManagedGraalRuntimeService create(Path turboismHome);
    }

    private static ManagedRuntimeStatus managedStatus(
        final dev.turboism.graal.ManagedGraalRuntimeService.Status status
    ) {
        final ManagedRuntimeState state = switch (status.state()) {
            case ABSENT -> ManagedRuntimeState.ABSENT;
            case DOWNLOADING, EXTRACTING, VERIFYING -> ManagedRuntimeState.INSTALLING;
            case READY -> ManagedRuntimeState.READY;
            case FAILED -> ManagedRuntimeState.FAILED;
            case CANCELLED -> ManagedRuntimeState.CANCELLED;
            case UNSUPPORTED -> ManagedRuntimeState.UNSUPPORTED;
        };
        return new ManagedRuntimeStatus(
            state,
            status.version(),
            status.javaVersion(),
            status.javaExecutable(),
            status.completedBytes(),
            status.totalBytes(),
            status.code(),
            status.message()
        );
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
