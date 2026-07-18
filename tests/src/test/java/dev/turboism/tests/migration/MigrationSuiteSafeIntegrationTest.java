package dev.turboism.tests.migration;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Safe pre-migration aggregate gate. The bundle is test-only and contains every official module
 * currently available; later waves can increase that discovery set without adding shell names here.
 */
class MigrationSuiteSafeIntegrationTest {

    private static final Set<String> LEGACY_PLUGIN_ROSTER = Set.of(
        "dev.turboism.plugin.bounding-box",
        "dev.turboism.plugin.clipmask",
        "dev.turboism.plugin.context-menu",
        "dev.turboism.plugin.logfilter",
        "dev.turboism.plugin.maintoolbar",
        "dev.turboism.plugin.mesh",
        "dev.turboism.plugin.parameter",
        "dev.turboism.plugin.perfopt",
        "dev.turboism.plugin.project-panel",
        "dev.turboism.plugin.psd-import",
        "dev.turboism.plugin.renderopt",
        "dev.turboism.plugin.texture-atlas",
        "dev.turboism.plugin.uitheme"
    );
    private static final Set<String> SAFE_NEIGHBORS = Set.of(
        "dev.turboism.plugin.demo",
        "dev.turboism.plugin.project-inspector"
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
        "cubism.render.restore"
    );

    @TempDir
    Path temporary;

    @Test
    void childJvmLoadsCurrentOfficialSafePluginsAndLeavesNoRuntimeHandles() throws Exception {
        final Path bundle = bundleDirectory();
        final List<Path> officialJars = officialPluginJars(bundle);
        assertChildJvmLifecycle(bundle, officialJars.size());

    }

    private void assertChildJvmLifecycle(final Path bundle, final int expectedPlugins) throws Exception {
        final Path home = temporary.resolve("child-jvm-home");
        final String childClasspath = childRuntimeClasspath();
        final Process process = new ProcessBuilder(
            javaBinary(),
            "-Djava.awt.headless=true",
            "-cp",
            childClasspath,
            ChildMain.class.getName(),
            bundle.toString(),
            home.toString(),
            Integer.toString(expectedPlugins)
        ).redirectErrorStream(true).start();
        final String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        final int exit = process.waitFor();
        assertEquals(0, exit, () -> "migration-suite-safe child JVM failed:\n" + output);
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 64, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private static Path bundleDirectory() {
        final String configured = System.getProperty("migrationSuiteSafeBundleDir");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("migrationSuiteSafeBundleDir is required");
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static List<Path> officialPluginJars(final Path bundle) throws IOException {
        final Path plugins = bundle.resolve("plugins");
        try (var entries = Files.list(plugins)) {
            return entries
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }
    }

    private static void copyOfficialPlugins(final List<Path> jars, final Path target) throws IOException {
        for (Path jar : jars) {
            Files.copy(jar, target.resolve(jar.getFileName()));
        }
    }

    private static void writeEmptyJar(final Path target) throws IOException {
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(target))) {
            // Deliberately invalid discovery neighbor: valid official jars must remain isolated.
        }
    }

    private static String childRuntimeClasspath() {
        final String separator = System.getProperty("path.separator");
        return java.util.Arrays.stream(System.getProperty("java.class.path").split(
                java.util.regex.Pattern.quote(separator)
            ))
            .filter(entry -> !isPluginJar(Path.of(entry)))
            .collect(java.util.stream.Collectors.joining(separator));
    }

    private static boolean isPluginJar(final Path entry) {
        if (!Files.isRegularFile(entry) || !entry.getFileName().toString().endsWith(".jar")) {
            return false;
        }
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(entry.toFile())) {
            return jar.getEntry("META-INF/turboism/plugin.json") != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Separate JVM entry point: do not depend on a Gradle test-worker lifecycle. */
    public static final class ChildMain {

        private ChildMain() {
        }

        public static void main(final String[] arguments) throws Exception {
            if (arguments.length != 3) {
                throw new IllegalArgumentException("Expected <bundle> <home> <official-plugin-count>");
            }
            final Path bundle = Path.of(arguments[0]).toAbsolutePath().normalize();
            final Path home = Path.of(arguments[1]).toAbsolutePath().normalize();
            final int expectedPlugins = Integer.parseInt(arguments[2]);
            Files.createDirectories(home.resolve("plugins"));
            copyOfficialPlugins(officialPluginJars(bundle), home.resolve("plugins"));
            writeEmptyJar(home.resolve("plugins/00-broken-neighbor.jar"));

            final PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"));
            final RuntimeScheduler scheduler = scheduler();
            final HostRuntimeIngress ingress = new HostRuntimeIngress();
            final LocalPluginRuntime plugins = new LocalPluginRuntime(
                home,
                scheduler,
                ingress.adapterAccess(),
                log
            );
            final LocalPluginRuntime.LoadReport loadReport = plugins.loadAll();
            final PreviewRuntime preview = MigrationSuitePreviewRuntimeSupport.compose(
                home,
                log,
                scheduler,
                ingress,
                plugins,
                loadReport
            );
            try {
                if (loadReport.loaded().size() != expectedPlugins) {
                    throw new IllegalStateException("Expected all official plugins to load; report=" + loadReport);
                }
                if (loadReport.failures().stream().noneMatch(failure ->
                    failure.code().equals("PLUGIN_DESCRIPTOR_MISSING")
                )) {
                    throw new IllegalStateException("Broken neighbor was not reported");
                }
                MigrationSuitePreviewRuntimeSupport.writeInitialReports(
                    preview,
                    dev.turboism.adapter.host.HostSession.State.SAFE_MODE
                );
            } finally {
                preview.close();
            }

            validatePreviewReports(home.resolve("state"));
            assertSafeReports(home.resolve("state"), home);
            assertExplicitLegacyRoster(loadReport);
            assertHighRiskCapabilitiesFailClosed(home.resolve("state"));
            assertNoWriteOrTransactionEvidence(home);
            assertDisableReenableUnloadEvidence(plugins.reportSummaries());
            System.out.println("migration-suite-safe child JVM: PASS plugins=" + expectedPlugins);
        }

        private static void assertExplicitLegacyRoster(
            final LocalPluginRuntime.LoadReport report
        ) {
            final Set<String> loadedIds = report.loaded().stream()
                .map(LocalPluginRuntime.LoadedPluginSummary::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            final Set<String> loadedLegacy = loadedIds.stream()
                .filter(LEGACY_PLUGIN_ROSTER::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!loadedLegacy.equals(LEGACY_PLUGIN_ROSTER)) {
                final Set<String> missing = new java.util.TreeSet<>(LEGACY_PLUGIN_ROSTER);
                missing.removeAll(loadedLegacy);
                throw new IllegalStateException(
                    "migration-suite-safe requires the explicit 13 legacy plugin roster; missing=" + missing
                        + ", loadedLegacy=" + loadedLegacy + ", neighbors=" + SAFE_NEIGHBORS
                );
            }
            final Set<String> unexpected = new java.util.TreeSet<>(loadedIds);
            unexpected.removeAll(LEGACY_PLUGIN_ROSTER);
            unexpected.removeAll(SAFE_NEIGHBORS);
            if (!unexpected.isEmpty()) {
                throw new IllegalStateException("Unexpected migration-suite plugin IDs: " + unexpected);
            }
        }

        private static void assertHighRiskCapabilitiesFailClosed(final Path state) throws IOException {
            final JsonNode capabilityReport = PreviewReportValidator.validate(
                Files.readAllBytes(state.resolve(PreviewReportType.CAPABILITY.fileName()))
            ).document();
            for (JsonNode capability : capabilityReport.path("payload").path("capabilities")) {
                final String capabilityId = capability.path("capabilityId").asText();
                if (!HIGH_RISK_CAPABILITIES.contains(capabilityId)) {
                    continue;
                }
                if (!capability.path("capabilityAvailability").asText().equals("UNKNOWN")
                    || !capability.path("registrationState").asText().equals("NONE")
                    || capability.path("registrationCounts").path("total").asInt(-1) != 0) {
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

        private static void assertNoWriteOrTransactionEvidence(final Path home) throws IOException {
            final String log = Files.readString(home.resolve("logs/turboism.log"));
            for (String operation : List.of(
                "transaction.open",
                "transaction.enqueue",
                "transaction.commit"
            )) {
                if (log.contains(operation)) {
                    throw new IllegalStateException("Safe suite executed a write operation: " + operation);
                }
            }
        }

        private static void assertDisableReenableUnloadEvidence(
            final List<LocalPluginRuntime.LoadedPluginSummary> summaries
        ) {
            for (LocalPluginRuntime.LoadedPluginSummary summary : summaries) {
                if (LEGACY_PLUGIN_ROSTER.contains(summary.id())
                    && (!summary.disableState().equals("SUCCEEDED")
                    || !summary.shutdownState().equals("SUCCEEDED")
                    || !summary.unloadState().equals("SUCCEEDED")
                    || !summary.scopeCleanupState().equals("SUCCEEDED")
                    || !summary.classloaderCleanupState().equals("SUCCEEDED")
                    || summary.cleanupEvidence().failures() != 0)) {
                    throw new IllegalStateException("Plugin cleanup evidence failed: " + summary.id());
                }
            }
            throw new IllegalStateException(
                "migration-suite-safe still lacks real aggregate disable -> re-enable -> unload evidence"
            );
        }

        private static void validatePreviewReports(final Path state) throws IOException {
            final Map<PreviewReportType, byte[]> reports = new java.util.EnumMap<>(PreviewReportType.class);
            for (PreviewReportType type : PreviewReportType.values()) {
                reports.put(type, Files.readAllBytes(state.resolve(type.fileName())));
            }
            PreviewReportValidator.validateSet(reports);
        }

        private static void assertSafeReports(final Path state, final Path home) throws IOException {
            final String absoluteHome = home.toAbsolutePath().normalize().toString().replace('\\', '/');
            for (PreviewReportType type : PreviewReportType.values()) {
                final JsonNode document = PreviewReportValidator.validate(
                    Files.readAllBytes(state.resolve(type.fileName()))
                ).document();
                final String serialized = document.toString().replace('\\', '/');
                if (serialized.contains(absoluteHome)
                    || serialized.matches("(?s).*[A-Za-z]:/.*")) {
                    throw new IllegalStateException("Preview report leaked an absolute path: " + type);
                }
            }
            final JsonNode pluginLoad = PreviewReportValidator.validate(
                Files.readAllBytes(state.resolve(PreviewReportType.PLUGIN_LOAD.fileName()))
            ).document();
            final JsonNode plugins = pluginLoad.path("payload").path("plugins");
            boolean descriptorFailure = false;
            for (JsonNode plugin : plugins) {
                final String relative = plugin.path("artifactRelativePath").asText("");
                if (!relative.isEmpty() && !PreviewReportValidator.isRelativePath(relative)) {
                    throw new IllegalStateException("Non-relative plugin artifact path: " + relative);
                }
                descriptorFailure |= plugin.path("failures").toString().contains("PLUGIN_DESCRIPTOR_MISSING");
                final JsonNode after = plugin.path("registrationsAfterCleanup");
                if (!after.isMissingNode() && after.path("total").asInt(-1) != 0) {
                    throw new IllegalStateException("Registration count did not return to baseline");
                }
            }
            if (!descriptorFailure) {
                throw new IllegalStateException("Broken neighbor was not isolated in preview report");
            }
        }
    }
}
