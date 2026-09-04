package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.plugin.PluginContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** Verifies that a chooser-staged direct JAR is applied and loaded after a real host restart. */
public final class PluginManagementRestartValidationProbe implements CubismPlugin {

    private static final String RESULT_RELATIVE = "state/plugin-management-restart-result.properties";
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private PluginContext context;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        context.logger().info("PLUGIN_RESTART_PROBE_READY");
    }

    @Override
    public void enable() {
        final Thread worker = new Thread(this::verify, "turboism-plugin-restart-validation");
        worker.setDaemon(true);
        worker.start();
    }

    private void verify() {
        final long startedNanos = System.nanoTime();
        final Path home = Path.of(requireProperty("turboism.home"));
        final String targetId = requireProperty("turboism.validation.expectedPluginId");
        final Path installed = home.resolve("plugins/" + targetId + ".jar");
        final Path pending = home.resolve("state/runtime/plugin-management/pending.json");
        final Path report = home.resolve("state/runtime/plugin-load-report.json");
        final Path result = home.resolve(RESULT_RELATIVE);
        boolean installedJar = false;
        boolean pendingCleared = false;
        boolean digestMatches = false;
        boolean loaded = false;
        try {
            final String expectedSha256 = requireProperty("turboism.validation.expectedJarSha256");
            final long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                installedJar = Files.isRegularFile(installed);
                pendingCleared = !Files.exists(pending);
                digestMatches = installedJar && sha256(installed).equals(expectedSha256);
                loaded = loadReportShowsEnabled(report, targetId);
                if (installedJar && pendingCleared && digestMatches && loaded) break;
                Thread.sleep(500L);
            }
            final boolean passed = installedJar && pendingCleared && digestMatches && loaded;
            writeResult(result, installedJar, pendingCleared, digestMatches, loaded, passed,
                (System.nanoTime() - startedNanos) / 1_000_000L);
            context.logger().info("PLUGIN_RESTART_RESULT status=" + (passed ? "PASS" : "FAIL")
                + " installed=" + installedJar + " pendingCleared=" + pendingCleared
                + " digestMatches=" + digestMatches + " loaded=" + loaded);
        } catch (Exception failure) {
            context.logger().error("PLUGIN_RESTART_RESULT status=FAIL", failure);
            try {
                writeResult(result, installedJar, pendingCleared, digestMatches, loaded, false,
                    (System.nanoTime() - startedNanos) / 1_000_000L);
            } catch (Exception writeFailure) {
                context.logger().error("Plugin restart result file could not be written", writeFailure);
            }
        } finally {
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            Runtime.getRuntime().exit(0);
        }
    }

    static boolean loadReportShowsEnabled(final Path report, final String targetId) {
        try {
            if (!Files.isRegularFile(report)) return false;
            final String content = Files.readString(report);
            final int target = content.indexOf("\"pluginId\":\"" + targetId + "\"");
            if (target < 0) return false;
            final int next = content.indexOf("\"pluginId\":", target + 1);
            final String entry = next < 0 ? content.substring(target) : content.substring(target, next);
            return entry.contains("\"discoveryState\":\"DISCOVERED\"")
                && entry.contains("\"dependencyState\":\"RESOLVED\"")
                && entry.contains("\"lifecycleState\":\"ENABLED\"");
        } catch (Exception ignored) {
            return false;
        }
    }

    static void writeResult(
        final Path result,
        final boolean installed,
        final boolean pendingCleared,
        final boolean digestMatches,
        final boolean loaded,
        final boolean passed,
        final long durationMillis
    ) throws Exception {
        Files.createDirectories(result.getParent());
        Files.writeString(
            result,
            "schemaVersion=1\n"
                + "installed=" + installed + "\n"
                + "pendingCleared=" + pendingCleared + "\n"
                + "digestMatches=" + digestMatches + "\n"
                + "loaded=" + loaded + "\n"
                + "durationMillis=" + durationMillis + "\n"
                + "status=" + (passed ? "PASS" : "FAIL") + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private static String sha256(final Path path) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            final byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String requireProperty(final String name) {
        final String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set");
        return value;
    }
}
