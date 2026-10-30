package dev.turboism.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.internal.core.CubismJvmSettingsService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Persists the managed launcher's Cubism JVM choice in the canonical config. */
public final class CubismJvmSettingsFileService implements CubismJvmSettingsService, AutoCloseable {

    /** Exact-host validation override: forces the opt-in on without config plumbing. */
    private static final String REDUCE_AUTO_BACKUP_ENV = "TURBOISM_REDUCE_AUTO_BACKUP";

    private final RuntimeConfigRepository config;
    private final Path turboismHome;
    private final Map<String, String> environment;
    private final dev.turboism.graal.ManagedGraalRuntimeService managedRuntime;

    public CubismJvmSettingsFileService(final Path turboismHome) {
        this(turboismHome, ignored -> {});
    }

    public CubismJvmSettingsFileService(final Path turboismHome, final Consumer<String> configDiagnostic) {
        this(new RuntimeConfigRepository(turboismHome, configDiagnostic), turboismHome, System.getenv());
    }

    CubismJvmSettingsFileService(final RuntimeConfigRepository config) {
        this(config, null, Map.of());
    }

    CubismJvmSettingsFileService(
            final RuntimeConfigRepository config, final Path turboismHome, final Map<String, String> environment) {
        this(
                config,
                turboismHome,
                environment,
                path -> new dev.turboism.graal.ManagedGraalRuntimeService(path, ignored -> {}));
    }

    CubismJvmSettingsFileService(
            final RuntimeConfigRepository config,
            final Path turboismHome,
            final Map<String, String> environment,
            final ManagedRuntimeFactory managedRuntimeFactory) {
        this.config = Objects.requireNonNull(config, "config");
        this.turboismHome =
                turboismHome == null ? null : turboismHome.toAbsolutePath().normalize();
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.managedRuntime = createManagedRuntime(
                this.turboismHome, Objects.requireNonNull(managedRuntimeFactory, "managedRuntimeFactory"));
    }

    private static dev.turboism.graal.ManagedGraalRuntimeService createManagedRuntime(
            final Path turboismHome, final ManagedRuntimeFactory factory) {
        if (turboismHome == null) return null;
        try {
            return factory.create(turboismHome);
        } catch (NoClassDefFoundError unavailable) {
            return null;
        }
    }

    @Override
    public CubismJvm read() {
        return CubismJvm.fromConfig(savedPerformance(PerformanceSettingsConfig.Field.CUBISM_JVM));
    }

    @Override
    public CubismJvm save(final CubismJvm value) {
        final CubismJvm requested = Objects.requireNonNull(value, "value");
        PerformanceSettingsConfig.save(config, PerformanceSettingsConfig.Field.CUBISM_JVM, requested.configValue());
        return requested;
    }

    @Override
    public String graalVmPath() {
        return savedPerformance(PerformanceSettingsConfig.Field.GRAAL_VM_PATH);
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
                throw new IllegalArgumentException("GraalVM path must identify a GraalVM installation");
            }
            persisted = Paths.get(requested).toAbsolutePath().normalize().toString();
        }
        PerformanceSettingsConfig.save(config, PerformanceSettingsConfig.Field.GRAAL_VM_PATH, persisted);
        return persisted;
    }

    @Override
    public boolean graalVmPathCompatible(final String value) {
        return value == null || value.isBlank() || compatibleGraalVmPath(value).isPresent();
    }

    @Override
    public boolean reduceAutoBackup() {
        final String override = environment.get(REDUCE_AUTO_BACKUP_ENV);
        if (override != null && !override.isBlank()) {
            final String normalized = override.trim();
            return "1".equals(normalized)
                    || "true".equalsIgnoreCase(normalized)
                    || "yes".equalsIgnoreCase(normalized)
                    || "on".equalsIgnoreCase(normalized);
        }
        return Boolean.parseBoolean(savedPerformance(PerformanceSettingsConfig.Field.REDUCE_AUTO_BACKUP));
    }

    @Override
    public boolean startupReduceAutoBackup() {
        var startup = NativeOptimizationDiagnostics.startup();
        return startup == null
                ? reduceAutoBackup()
                : Boolean.parseBoolean(startup.value(PerformanceSettingsConfig.Field.REDUCE_AUTO_BACKUP)
                        .value());
    }

    @Override
    public boolean saveReduceAutoBackup(final boolean value) {
        PerformanceSettingsConfig.save(
                config, PerformanceSettingsConfig.Field.REDUCE_AUTO_BACKUP, Boolean.toString(value));
        return value;
    }

    @Override
    public boolean zgc() {
        return Boolean.parseBoolean(savedPerformance(PerformanceSettingsConfig.Field.ZGC));
    }

    @Override
    public boolean saveZgc(final boolean value) {
        PerformanceSettingsConfig.save(config, PerformanceSettingsConfig.Field.ZGC, Boolean.toString(value));
        return value;
    }

    @Override
    public MemoryProfile memoryProfile() {
        // The repository already drops an unsupported persisted value on read
        // (RUNTIME_CONFIG_BAD_MEMORY_PROFILE diagnostic); the lenient parse is
        // the second fail-closed layer for non-validating config sources.
        return MemoryProfile.fromConfigOrSystem(savedPerformance(PerformanceSettingsConfig.Field.MEMORY_PROFILE));
    }

    @Override
    public MemoryProfile saveMemoryProfile(final MemoryProfile value) {
        final MemoryProfile requested = Objects.requireNonNull(value, "value");
        PerformanceSettingsConfig.save(config, PerformanceSettingsConfig.Field.MEMORY_PROFILE, requested.configValue());
        return requested;
    }

    @Override
    public boolean modelUpdateSkip() {
        return optimization("modelUpdateSkip");
    }

    @Override
    public boolean saveModelUpdateSkip(final boolean value) {
        return saveOptimization("modelUpdateSkip", value);
    }

    @Override
    public boolean incrementalUpdate() {
        return optimization("incrementalUpdate");
    }

    @Override
    public boolean saveIncrementalUpdate(final boolean value) {
        return saveOptimization("incrementalUpdate", value);
    }

    @Override
    public boolean uniformLocationCache() {
        return optimization("uniformLocationCache");
    }

    @Override
    public boolean saveUniformLocationCache(final boolean value) {
        return saveOptimization("uniformLocationCache", value);
    }

    @Override
    public boolean uploadElision() {
        return optimization("uploadElision");
    }

    @Override
    public boolean saveUploadElision(final boolean value) {
        return saveOptimization("uploadElision", value);
    }

    @Override
    public boolean inputPathElision() {
        return optimization("inputPathElision");
    }

    @Override
    public boolean saveInputPathElision(final boolean value) {
        return saveOptimization("inputPathElision", value);
    }

    @Override
    public boolean mesaGlThread() {
        return optimization("mesaGlThread");
    }

    @Override
    public boolean saveMesaGlThread(final boolean value) {
        return saveOptimization("mesaGlThread", value);
    }

    @Override
    public boolean meshInterpolation() {
        return optimization("meshInterpolation");
    }

    @Override
    public boolean saveMeshInterpolation(final boolean value) {
        return saveOptimization("meshInterpolation", value);
    }

    @Override
    public boolean shaderUniform() {
        return optimization("shaderUniform");
    }

    @Override
    public boolean saveShaderUniform(final boolean value) {
        return saveOptimization("shaderUniform", value);
    }

    @Override
    public boolean matrixScratch() {
        return optimization("matrixScratch");
    }

    @Override
    public boolean saveMatrixScratch(final boolean value) {
        return saveOptimization("matrixScratch", value);
    }

    @Override
    public boolean imageArchiveReuse() {
        return optimization("imageArchiveReuse");
    }

    @Override
    public boolean saveImageArchiveReuse(final boolean value) {
        return saveOptimization("imageArchiveReuse", value);
    }

    @Override
    public boolean floatArrayParseCache() {
        return optimization("floatArrayParseCache");
    }

    @Override
    public boolean saveFloatArrayParseCache(final boolean value) {
        return saveOptimization("floatArrayParseCache", value);
    }

    @Override
    public boolean textureUploadPreparation() {
        return optimization("textureUploadPreparation");
    }

    @Override
    public boolean saveTextureUploadPreparation(final boolean value) {
        return saveOptimization("textureUploadPreparation", value);
    }

    @Override
    public boolean warpPositionProjection() {
        return optimization("warpPositionProjection");
    }

    @Override
    public boolean saveWarpPositionProjection(final boolean value) {
        return saveOptimization("warpPositionProjection", value);
    }

    @Override
    public String globalConfigPath() {
        return turboismHome == null ? "" : turboismHome.resolve("config.json").toString();
    }

    @Override
    public List<PerformanceStatus> performanceStatuses() {
        JsonNode root;
        try {
            root = config.read();
        } catch (RuntimeException invalid) {
            root = null;
        }
        final boolean proton = dev.turboism.runtime.env.ProtonEnvironment.underWineOrProton(environment);
        final var startup = NativeOptimizationDiagnostics.startup();
        final var hooks = NativeOptimizationDiagnostics.snapshot();
        final List<PerformanceStatus> result = new ArrayList<>();
        for (var field : PerformanceSettingsConfig.Field.values()) {
            String saved = root == null ? "invalid-config" : PerformanceSettingsConfig.saved(root, field, proton);
            var request = startup == null ? null : startup.value(field);
            String actual;
            if (field.nativeOptimization()) {
                var status = hooks.get(NativeOptimizationConfig.Option.forKey(field.key()));
                actual = status == null ? "not-observed" : status.state() + ": " + status.reason();
            } else {
                actual = switch (field) {
                    case CUBISM_JVM -> System.getProperty("java.vm.name") + " / " + System.getProperty("java.vendor");
                    case GRAAL_VM_PATH -> System.getProperty("java.home");
                    case ZGC ->
                        java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream()
                                .map(java.lang.management.GarbageCollectorMXBean::getName)
                                .collect(java.util.stream.Collectors.joining(", "));
                    case MEMORY_PROFILE ->
                        "maxHeapBytes=" + Runtime.getRuntime().maxMemory() + "; JVM="
                                + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                                        .filter(argument -> argument.startsWith("-Xmx")
                                                || argument.startsWith("-XX:MaxRAM")
                                                || argument.startsWith("-XX:SoftRefLRUPolicyMSPerMB"))
                                        .collect(java.util.stream.Collectors.joining(" "));
                    default -> NativeOptimizationDiagnostics.observed(field);
                };
            }
            result.add(new PerformanceStatus(
                    field.key(),
                    saved,
                    request == null ? "unknown" : request.value(),
                    request == null ? "not-observed" : request.source(),
                    actual,
                    root != null && request != null && !saved.equals(request.value())));
        }
        return List.copyOf(result);
    }

    @Override
    public List<OptimizationStatus> optimizationStatuses() {
        final JsonNode root = config.read();
        final boolean proton = dev.turboism.runtime.env.ProtonEnvironment.underWineOrProton(environment);
        final var diagnostics = NativeOptimizationDiagnostics.snapshot();
        final List<OptimizationStatus> result = new ArrayList<>();
        for (var option : NativeOptimizationConfig.Option.values()) {
            final var status = diagnostics.get(option);
            final var choice = status == null ? null : status.choice();
            result.add(new OptimizationStatus(
                    option.key(),
                    Boolean.parseBoolean(PerformanceSettingsConfig.saved(root, option.field(), proton)),
                    choice == null ? Optional.empty() : Optional.of(choice.enabled()),
                    choice == null ? "unknown" : choice.source(),
                    status == null ? "unknown" : status.state(),
                    status == null ? "not-observed" : status.reason()));
        }
        return List.copyOf(result);
    }

    private String savedPerformance(PerformanceSettingsConfig.Field field) {
        return PerformanceSettingsConfig.saved(
                config.read(), field, dev.turboism.runtime.env.ProtonEnvironment.underWineOrProton(environment));
    }

    private boolean optimization(final String name) {
        return Boolean.parseBoolean(savedPerformance(PerformanceSettingsConfig.Field.forKey(name)));
    }

    private boolean saveOptimization(final String name, final boolean value) {
        PerformanceSettingsConfig.save(config, PerformanceSettingsConfig.Field.forKey(name), Boolean.toString(value));
        return value;
    }

    @Override
    public Optional<Path> graalVmJava() {
        if (turboismHome == null) return Optional.empty();
        final Optional<Path> explicit = compatibleGraalVmPath(environment.get("TURBOISM_CUBISM_JAVA"));
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
        return discovered.isPresent() ? discovered : compatibleGraalVmPath(graalVmPath());
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
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        final Path bin = path.getParent();
        final Path home = bin == null ? null : bin.getParent();
        final Path release = home == null ? null : home.resolve("release");
        if (release == null
                || Files.isSymbolicLink(release)
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
            @Override
            public ManagedRuntimeStatus status() {
                return managedStatus(operation.status());
            }

            @Override
            public java.util.concurrent.CompletionStage<ManagedRuntimeStatus> completion() {
                return operation.completion().thenApply(CubismJvmSettingsFileService::managedStatus);
            }

            @Override
            public boolean cancel() {
                return operation.cancel();
            }
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
            final dev.turboism.graal.ManagedGraalRuntimeService.Status status) {
        final ManagedRuntimeState state =
                switch (status.state()) {
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
                status.message());
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
