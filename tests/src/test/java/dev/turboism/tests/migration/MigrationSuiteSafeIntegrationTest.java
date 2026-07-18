package dev.turboism.tests.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.preview.LocalPluginRuntime;
import dev.turboism.preview.MigrationSuitePreviewRuntimeSupport;
import dev.turboism.preview.PreviewLog;
import dev.turboism.preview.PreviewRuntime;
import dev.turboism.preview.report.PreviewReportType;
import dev.turboism.preview.report.PreviewReportValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Child-JVM fail-closed gate for the explicit legacy migration target roster. */
class MigrationSuiteSafeIntegrationTest {

    private static final String DESCRIPTOR_PATH = "META-INF/turboism/plugin.json";
    private static final String TARGET = "target";
    private static final String NEIGHBOR = "neighbor";
    private static final List<RosterEntry> ROSTER = List.of(
        target("ui-theme", "dev.turboism.plugin.uitheme"),
        target("log-filter", "dev.turboism.plugin.logfilter"),
        target("main-toolbar", "dev.turboism.plugin.maintoolbar"),
        target("context-menu", "dev.turboism.plugin.context-menu"),
        target("project-panel", "dev.turboism.plugin.project-panel"),
        target("texture-atlas", "dev.turboism.plugin.texture-atlas"),
        target("clip-mask", "dev.turboism.plugin.clipmask"),
        target("bounding-box", "dev.turboism.plugin.bounding-box"),
        target("perf-opt", "dev.turboism.plugin.perfopt"),
        target("render-opt", "dev.turboism.plugin.renderopt"),
        target("parameter", "dev.turboism.plugin.parameter"),
        target("mesh", "dev.turboism.plugin.mesh"),
        target("psd-import", "dev.turboism.plugin.psd-import"),
        neighbor("demo", "dev.turboism.plugin.demo"),
        neighbor("project-inspector", "dev.turboism.plugin.project-inspector")
    );
    private static final Set<String> HIGH_RISK_CAPABILITIES = Set.of(
        "cubism.parameter.write",
        "cubism.model-tree.write",
        "cubism.mesh.write",
        "cubism.deformer.write",
        "cubism.mirror.writeback",
        "cubism.psd.binding.write",
        "cubism.clipmask.write",
        "cubism.canvas.write",
        "cubism.bounding-box.action.write",
        "cubism.render.modify",
        "cubism.render.restore",
        "cubism.transaction.real-write-undo"
    );
    private static final Set<String> KNOWN_HIGH_RISK_WRITE_OPERATIONS = Set.of(
        "transaction.open",
        "transaction.enqueue",
        "transaction.commit",
        "transaction.rollback"
    );
    private static final List<String> TURBOISM_THREAD_PREFIXES = List.of(
        "turboism-plugin-callback-",
        "turboism-runtime-scheduler-timer",
        "turboism-host-read-shared",
        "turboism-host-read-deadline",
        "turboism-config-",
        "turboism-storage-",
        "turboism-user-file-",
        "turboism-ui-timer-",
        "turboism-sidecar-dispatch-"
    );
    private static final Duration CHILD_JVM_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration THREAD_QUIESCENCE_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        final long targets = ROSTER.stream().filter(entry -> entry.role().equals(TARGET)).count();
        if (targets != 13
            || ROSTER.stream().map(RosterEntry::module).distinct().count() != ROSTER.size()
            || ROSTER.stream().map(RosterEntry::pluginId).distinct().count() != ROSTER.size()) {
            throw new ExceptionInInitializerError("migration-suite-safe requires 13 distinct target bindings");
        }
    }

    @TempDir
    Path temporary;

    @Test
    void childJvmRunsTwoRealSafeLifecycleCyclesWithoutLeaks() throws Exception {
        final Path bundle = bundleDirectory();
        assertBundleRoster(bundle);
        final Process process = new ProcessBuilder(
            javaBinary(),
            "-Djava.awt.headless=true",
            "-cp",
            childRuntimeClasspath(),
            ChildMain.class.getName(),
            bundle.toString(),
            temporary.resolve("child-jvm-home").toString()
        ).redirectErrorStream(true).start();
        final String output;
        try {
            if (!process.waitFor(CHILD_JVM_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                throw new IllegalStateException(
                    "migration-suite-safe child JVM timed out after " + CHILD_JVM_TIMEOUT
                );
            }
            try (InputStream input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        assertEquals(0, process.exitValue(), () -> "migration-suite-safe child JVM failed:\n" + output);
    }

    private static void assertBundleRoster(final Path bundle) throws IOException {
        final List<RosterEntry> manifest = readManifest(bundle.resolve("roster.tsv"));
        if (!manifest.equals(ROSTER)) {
            throw new IllegalStateException("Migration-suite roster manifest mismatch: " + manifest);
        }
        final Set<String> targetIds = new LinkedHashSet<>();
        final Set<String> neighborIds = new LinkedHashSet<>();
        for (RosterEntry entry : manifest) {
            final Path jar = bundle.resolve(entry.role().equals(TARGET) ? "targets" : "neighbors")
                .resolve(entry.module() + ".jar");
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException("Missing bundled plugin JAR: " + jar);
            }
            final String descriptorId = descriptorId(jar);
            if (!descriptorId.equals(entry.pluginId())) {
                throw new IllegalStateException(
                    "Bundle descriptor mismatch for module " + entry.module()
                        + ": expected=" + entry.pluginId() + ", actual=" + descriptorId
                );
            }
            (entry.role().equals(TARGET) ? targetIds : neighborIds).add(descriptorId);
        }
        final Set<String> targetJarNames = jarNames(bundle.resolve("targets"));
        final Set<String> neighborJarNames = jarNames(bundle.resolve("neighbors"));
        final Set<String> expectedTargetJars = ROSTER.stream().filter(entry -> entry.role().equals(TARGET))
            .map(entry -> entry.module() + ".jar").collect(Collectors.toUnmodifiableSet());
        final Set<String> expectedNeighborJars = ROSTER.stream().filter(entry -> entry.role().equals(NEIGHBOR))
            .map(entry -> entry.module() + ".jar").collect(Collectors.toUnmodifiableSet());
        if (!targetJarNames.equals(expectedTargetJars) || !neighborJarNames.equals(expectedNeighborJars)) {
            throw new IllegalStateException(
                "Unexpected migration-suite bundle layout: targets=" + targetJarNames
                    + ", neighbors=" + neighborJarNames
            );
        }
        if (targetIds.size() != 13 || !targetIds.equals(targetPluginIds())
            || !neighborIds.equals(neighborPluginIds()) || targetIds.containsAll(neighborPluginIds())) {
            throw new IllegalStateException(
                "Roster roles are not one-to-one: targets=" + targetIds + ", neighbors=" + neighborIds
            );
        }
    }

    private static List<RosterEntry> readManifest(final Path manifest) throws IOException {
        final List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals("role\tmodule\tpluginId")) {
            throw new IllegalStateException("Invalid migration-suite roster manifest header");
        }
        final List<RosterEntry> entries = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            final String[] columns = line.split("\\t", -1);
            if (columns.length != 3 || (!columns[0].equals(TARGET) && !columns[0].equals(NEIGHBOR))
                || columns[1].isBlank() || columns[2].isBlank()) {
                throw new IllegalStateException("Invalid migration-suite roster row: " + line);
            }
            entries.add(new RosterEntry(columns[0], columns[1], columns[2]));
        }
        return List.copyOf(entries);
    }

    private static String descriptorId(final Path jar) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            final var entry = archive.getJarEntry(DESCRIPTOR_PATH);
            if (entry == null || entry.isDirectory()) {
                throw new IllegalStateException("Plugin descriptor is missing from " + jar.getFileName());
            }
            try (InputStream source = archive.getInputStream(entry)) {
                final JsonNode descriptor = JSON.readTree(source);
                final String id = descriptor.path("id").asText();
                if (id.isBlank()) {
                    throw new IllegalStateException("Plugin descriptor has no ID: " + jar.getFileName());
                }
                return id;
            }
        }
    }

    private static Set<String> jarNames(final Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".jar"))
                .collect(Collectors.toUnmodifiableSet());
        }
    }

    private static Path bundleDirectory() {
        final String configured = System.getProperty("migrationSuiteSafeBundleDir");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("migrationSuiteSafeBundleDir is required");
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static String childRuntimeClasspath() {
        final String separator = System.getProperty("path.separator");
        return java.util.Arrays.stream(System.getProperty("java.class.path").split(
                Pattern.quote(separator)
            ))
            .filter(entry -> !isPluginJar(Path.of(entry)))
            .collect(Collectors.joining(separator));
    }

    private static boolean isPluginJar(final Path entry) {
        if (!Files.isRegularFile(entry) || !entry.getFileName().toString().endsWith(".jar")) {
            return false;
        }
        try (JarFile jar = new JarFile(entry.toFile())) {
            return jar.getEntry(DESCRIPTOR_PATH) != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static RosterEntry target(final String module, final String pluginId) {
        return new RosterEntry(TARGET, module, pluginId);
    }

    private static RosterEntry neighbor(final String module, final String pluginId) {
        return new RosterEntry(NEIGHBOR, module, pluginId);
    }

    private static Set<String> targetPluginIds() {
        return ROSTER.stream().filter(entry -> entry.role().equals(TARGET))
            .map(RosterEntry::pluginId).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> neighborPluginIds() {
        return ROSTER.stream().filter(entry -> entry.role().equals(NEIGHBOR))
            .map(RosterEntry::pluginId).collect(Collectors.toUnmodifiableSet());
    }

    /** Separate JVM entry point: plugin class loaders never inherit official plugin JARs. */
    public static final class ChildMain {

        private ChildMain() {
        }

        public static void main(final String[] arguments) throws Exception {
            if (arguments.length != 2) {
                throw new IllegalArgumentException("Expected <bundle> <home>");
            }
            final Path bundle = Path.of(arguments[0]).toAbsolutePath().normalize();
            final Path home = Path.of(arguments[1]).toAbsolutePath().normalize();
            assertBundleRoster(bundle);
            copyBundlePlugins(bundle, home.resolve("plugins"));
            writeEmptyJar(home.resolve("plugins/00-broken-neighbor.jar"));

            final Set<Long> baselineThreads = liveTurboismThreadIds();
            CycleResult first = null;
            CycleResult second = null;
            try {
                first = runCycle(home, 1);
                assertThreadCleanup(baselineThreads, "cycle-1");
                second = runCycle(home, 2);
                assertThreadCleanup(baselineThreads, "cycle-2");
                if (!first.loadedIds().equals(second.loadedIds())) {
                    throw new IllegalStateException(
                        "Second runtime did not re-load/re-enable the same plugins: first="
                            + first.loadedIds() + ", second=" + second.loadedIds()
                    );
                }
                assertNoWriteOrTransactionEvidence(home);
                System.out.println(
                    "migration-suite-safe child JVM: PASS targets=" + targetPluginIds().size()
                        + " neighbors=" + neighborPluginIds().size() + " cycles=2"
                );
            } finally {
                assertThreadCleanup(baselineThreads, "child-finally");
            }
        }

        private static CycleResult runCycle(final Path home, final int cycle) throws Exception {
            final Path cycleHome = home.resolve("cycle-" + cycle);
            Files.createDirectories(cycleHome.resolve("plugins"));
            copyDirectory(home.resolve("plugins"), cycleHome.resolve("plugins"));
            final PreviewLog log = new PreviewLog(cycleHome.resolve("logs/turboism.log"));
            final RuntimeScheduler scheduler = scheduler();
            final HostRuntimeIngress ingress = new HostRuntimeIngress();
            final LocalPluginRuntime plugins = new LocalPluginRuntime(
                cycleHome,
                scheduler,
                ingress.adapterAccess(),
                log
            );
            final LocalPluginRuntime.LoadReport loadReport = plugins.loadAll();
            assertLoadedRoster(loadReport);
            final PreviewRuntime preview = MigrationSuitePreviewRuntimeSupport.compose(
                cycleHome,
                log,
                scheduler,
                ingress,
                plugins,
                loadReport
            );
            try {
                MigrationSuitePreviewRuntimeSupport.writeInitialReports(
                    preview,
                    dev.turboism.adapter.host.HostSession.State.SAFE_MODE
                );
            } finally {
                preview.close();
            }
            if (!preview.shutdownFailures().isEmpty()) {
                throw new IllegalStateException(
                    "Preview shutdown failed during cycle " + cycle + ": " + preview.shutdownFailures()
                );
            }
            final List<LocalPluginRuntime.LoadedPluginSummary> closed = plugins.reportSummaries();
            assertCleanupEvidence(closed, cycle);
            validatePreviewReports(cycleHome.resolve("state"));
            assertSafeReports(cycleHome.resolve("state"), cycleHome, cycle);
            return new CycleResult(loadReport.loaded().stream()
                .map(LocalPluginRuntime.LoadedPluginSummary::id)
                .collect(Collectors.toUnmodifiableSet()));
        }

        private static RuntimeScheduler scheduler() {
            return new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()),
                SidecarDispatcher.noop(),
                ignored -> { }
            );
        }

        private static void assertLoadedRoster(final LocalPluginRuntime.LoadReport report) {
            if (report.failures().stream().noneMatch(failure ->
                failure.code().equals("PLUGIN_DESCRIPTOR_MISSING")
            )) {
                throw new IllegalStateException("Broken neighbor was not reported");
            }
            final Set<String> loadedIds = report.loaded().stream()
                .map(LocalPluginRuntime.LoadedPluginSummary::id)
                .collect(Collectors.toUnmodifiableSet());
            final Set<String> expected = new TreeSet<>(targetPluginIds());
            expected.addAll(neighborPluginIds());
            if (!loadedIds.equals(expected)) {
                throw new IllegalStateException(
                    "Expected explicit 13 targets plus demo/Project Inspector only; loaded=" + loadedIds
                );
            }
        }

        private static void assertCleanupEvidence(
            final List<LocalPluginRuntime.LoadedPluginSummary> summaries,
            final int cycle
        ) {
            final Set<String> ids = summaries.stream()
                .map(LocalPluginRuntime.LoadedPluginSummary::id)
                .collect(Collectors.toUnmodifiableSet());
            final Set<String> expected = new TreeSet<>(targetPluginIds());
            expected.addAll(neighborPluginIds());
            if (!ids.equals(expected)) {
                throw new IllegalStateException("Closed runtime summary lost plugins in cycle " + cycle);
            }
            for (LocalPluginRuntime.LoadedPluginSummary summary : summaries) {
                if (!summary.disableState().equals("SUCCEEDED")
                    || !summary.shutdownState().equals("SUCCEEDED")
                    || !summary.unloadState().equals("SUCCEEDED")
                    || !summary.scopeCleanupState().equals("SUCCEEDED")
                    || !summary.classloaderCleanupState().equals("SUCCEEDED")
                    || summary.cleanupEvidence().failures() != 0) {
                    throw new IllegalStateException(
                        "Lifecycle/scope/classloader cleanup failed in cycle " + cycle + ": " + summary
                    );
                }
            }
        }

        private static void validatePreviewReports(final Path state) throws IOException {
            final Map<PreviewReportType, byte[]> reports = new EnumMap<>(PreviewReportType.class);
            for (PreviewReportType type : PreviewReportType.values()) {
                reports.put(type, Files.readAllBytes(state.resolve(type.fileName())));
            }
            PreviewReportValidator.validateSet(reports);
        }

        private static void assertSafeReports(
            final Path state,
            final Path cycleHome,
            final int cycle
        ) throws IOException {
            final String absoluteHome = cycleHome.toAbsolutePath().normalize().toString().replace('\\', '/');
            for (PreviewReportType type : PreviewReportType.values()) {
                final JsonNode document = PreviewReportValidator.validate(
                    Files.readAllBytes(state.resolve(type.fileName()))
                ).document();
                final String serialized = document.toString().replace('\\', '/');
                if (serialized.contains(absoluteHome) || serialized.matches("(?s).*[A-Za-z]:/.*")) {
                    throw new IllegalStateException("Preview report leaked an absolute path: " + type);
                }
            }
            final JsonNode pluginLoad = report(state, PreviewReportType.PLUGIN_LOAD);
            boolean descriptorFailure = false;
            final Set<String> reportedLoaded = new TreeSet<>();
            for (JsonNode plugin : pluginLoad.path("payload").path("plugins")) {
                final String relative = plugin.path("artifactRelativePath").asText("");
                if (!relative.isEmpty() && !PreviewReportValidator.isRelativePath(relative)) {
                    throw new IllegalStateException("Non-relative plugin artifact path: " + relative);
                }
                descriptorFailure |= plugin.path("failures").toString().contains("PLUGIN_DESCRIPTOR_MISSING");
                final String pluginId = plugin.path("pluginId").asText();
                if (targetPluginIds().contains(pluginId) || neighborPluginIds().contains(pluginId)) {
                    reportedLoaded.add(pluginId);
                    if (!plugin.path("disableState").asText().equals("SUCCEEDED")
                        || !plugin.path("shutdownState").asText().equals("SUCCEEDED")
                        || !plugin.path("unloadState").asText().equals("SUCCEEDED")
                        || !plugin.path("scopeCleanupState").asText().equals("SUCCEEDED")
                        || !plugin.path("classloaderCleanupState").asText().equals("SUCCEEDED")) {
                        throw new IllegalStateException(
                            "Preview lifecycle evidence failed in cycle " + cycle + ": " + plugin
                        );
                    }
                }
            }
            final Set<String> expected = new TreeSet<>(targetPluginIds());
            expected.addAll(neighborPluginIds());
            if (!reportedLoaded.equals(expected)) {
                throw new IllegalStateException(
                    "Preview report lost explicit roster entries: reported=" + reportedLoaded
                );
            }
            if (!descriptorFailure) {
                throw new IllegalStateException("Broken neighbor was not isolated in preview report");
            }

            final JsonNode runtime = report(state, PreviewReportType.PREVIEW_RUNTIME).path("payload");
            final JsonNode cleanup = runtime.path("cleanupCounts");
            if (!runtime.path("runtimeState").asText().equals("STOPPED")
                || !runtime.path("adapterState").asText().equals("UNAVAILABLE")
                || cleanup.path("failures").asLong(-1) != 0
                || cleanup.path("scopesClosed").asLong(-1) != 15
                || cleanup.path("classloadersClosed").asLong(-1) != 15
                || runtime.path("shutdownCounts").path("attempted").asLong(-1) != 15
                || runtime.path("shutdownCounts").path("succeeded").asLong(-1) != 15) {
                throw new IllegalStateException(
                    "Runtime cleanup counts failed in cycle " + cycle + ": " + runtime
                );
            }

            final Set<String> declaredHighRisk = declaredHighRiskCapabilities(cycleHome);
            if (declaredHighRisk.isEmpty()) {
                throw new IllegalStateException("Roster descriptors declared no high-risk capabilities");
            }
            final Map<String, List<JsonNode>> reportedHighRisk = new LinkedHashMap<>();
            for (JsonNode capability : report(state, PreviewReportType.CAPABILITY)
                .path("payload").path("capabilities")) {
                final String capabilityId = capability.path("capabilityId").asText();
                if (declaredHighRisk.contains(capabilityId)) {
                    reportedHighRisk.computeIfAbsent(capabilityId, ignored -> new ArrayList<>()).add(capability);
                }
            }
            if (!reportedHighRisk.keySet().equals(declaredHighRisk)) {
                throw new IllegalStateException(
                    "High-risk report coverage mismatch: declared=" + declaredHighRisk
                        + ", reported=" + reportedHighRisk.keySet()
                );
            }
            for (Map.Entry<String, List<JsonNode>> entry : reportedHighRisk.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    throw new IllegalStateException("Missing high-risk capability report: " + entry.getKey());
                }
                for (JsonNode capability : entry.getValue()) {
                    if (!capability.path("capabilityAvailability").asText().equals("UNKNOWN")
                        || !capability.path("registrationState").asText().equals("NONE")
                        || capability.path("registrationCounts").path("total").asInt(-1) != 0
                        || capability.path("evidence").isEmpty()) {
                        throw new IllegalStateException(
                            "High-risk capability was not fail-closed in safe mode: " + capability
                        );
                    }
                    for (JsonNode evidence : capability.path("evidence")) {
                        if (!evidence.path("state").asText().equals("UNKNOWN")) {
                            throw new IllegalStateException(
                                "High-risk capability gained elevated evidence: " + capability
                            );
                        }
                    }
                }
            }
        }

        private static Set<String> declaredHighRiskCapabilities(final Path cycleHome) {
            final Set<String> declared = new TreeSet<>();
            for (RosterEntry entry : ROSTER) {
                final Path jar = cycleHome.resolve("plugins").resolve(entry.module() + ".jar");
                try (JarFile archive = new JarFile(jar.toFile())) {
                    final var descriptorEntry = archive.getJarEntry(DESCRIPTOR_PATH);
                    if (descriptorEntry == null) {
                        throw new IllegalStateException("Plugin descriptor is missing from " + jar);
                    }
                    try (InputStream source = archive.getInputStream(descriptorEntry)) {
                        final JsonNode descriptor = JSON.readTree(source);
                        for (JsonNode capability : descriptor.path("capabilities")) {
                            final String capabilityId = capability.asText();
                            if (HIGH_RISK_CAPABILITIES.contains(capabilityId)) {
                                declared.add(capabilityId);
                            }
                        }
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException(
                        "Could not derive high-risk capabilities from " + jar,
                        exception
                    );
                }
            }
            return declared;
        }

        private static JsonNode report(final Path state, final PreviewReportType type) throws IOException {
            return PreviewReportValidator.validate(Files.readAllBytes(state.resolve(type.fileName())))
                .document();
        }

        private static void assertNoWriteOrTransactionEvidence(final Path home) throws IOException {
            final List<Path> expectedLogs = List.of(
                home.resolve("cycle-1/logs/turboism.log").toAbsolutePath().normalize(),
                home.resolve("cycle-2/logs/turboism.log").toAbsolutePath().normalize()
            );
            for (Path log : expectedLogs) {
                if (!Files.isRegularFile(log)) {
                    throw new IllegalStateException("Expected exactly one cycle log is missing: " + log);
                }
            }
            try (var files = Files.walk(home)) {
                final List<Path> actualLogs = files
                    .filter(path -> path.getFileName().toString().equals("turboism.log"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted()
                    .toList();
                if (!actualLogs.equals(expectedLogs)) {
                    throw new IllegalStateException("Unexpected safe-suite log set: " + actualLogs);
                }
            }
            for (Path log : expectedLogs) {
                final String contents = Files.readString(log, StandardCharsets.UTF_8);
                for (String operation : extractOperationIds(contents)) {
                    if (operation.startsWith("transaction.")) {
                        throw new IllegalStateException(
                            "Safe suite executed a transaction operation in " + log + ": " + operation
                        );
                    }
                    if (KNOWN_HIGH_RISK_WRITE_OPERATIONS.contains(operation)) {
                        throw new IllegalStateException(
                            "Safe suite logged high-risk write evidence in " + log + ": " + operation
                        );
                    }
                }
            }
        }

        private static Set<String> extractOperationIds(final String contents) {
            final Set<String> operations = new TreeSet<>();
            final var matcher = Pattern.compile("(?<![A-Za-z0-9_.-])([A-Za-z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)+)")
                .matcher(contents);
            while (matcher.find()) {
                operations.add(matcher.group(1));
            }
            return operations;
        }

        private static void copyBundlePlugins(final Path bundle, final Path target) throws IOException {
            Files.createDirectories(target);
            copyDirectory(bundle.resolve("targets"), target);
            copyDirectory(bundle.resolve("neighbors"), target);
        }

        private static void copyDirectory(final Path source, final Path target) throws IOException {
            Files.createDirectories(target);
            try (var entries = Files.list(source)) {
                for (Path entry : entries.filter(Files::isRegularFile).toList()) {
                    Files.copy(entry, target.resolve(entry.getFileName()));
                }
            }
        }

        private static void writeEmptyJar(final Path target) throws IOException {
            try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(target))) {
                // Deliberately invalid discovery neighbor: valid plugins must remain isolated.
            }
        }

        private static Set<Long> liveTurboismThreadIds() {
            return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> isTurboismRuntimeThread(thread.getName()))
                .map(Thread::getId)
                .collect(Collectors.toUnmodifiableSet());
        }

        private static boolean isTurboismRuntimeThread(final String name) {
            return TURBOISM_THREAD_PREFIXES.stream().anyMatch(name::startsWith)
                || name.toLowerCase(java.util.Locale.ROOT).contains("bulkhead");
        }

        private static void assertThreadCleanup(final Set<Long> baseline, final String phase)
            throws InterruptedException {
            final long deadline = System.nanoTime() + THREAD_QUIESCENCE_TIMEOUT.toNanos();
            Set<Thread> leaked;
            do {
                leaked = Thread.getAllStackTraces().keySet().stream()
                    .filter(Thread::isAlive)
                    .filter(thread -> isTurboismRuntimeThread(thread.getName()))
                    .filter(thread -> !baseline.contains(thread.getId()))
                    .collect(Collectors.toUnmodifiableSet());
                if (leaked.isEmpty()) {
                    return;
                }
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            throw new IllegalStateException(
                "Turboism scheduler/callback/bulkhead threads remained after " + phase + ": "
                    + leaked.stream().map(Thread::getName).sorted().toList()
            );
        }

        private record CycleResult(Set<String> loadedIds) {
        }
    }

    private record RosterEntry(String role, String module, String pluginId) {
    }

}
