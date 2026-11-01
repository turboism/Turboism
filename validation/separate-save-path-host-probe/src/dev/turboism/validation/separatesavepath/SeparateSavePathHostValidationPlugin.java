package dev.turboism.validation.separatesavepath;

import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Task-local host probe for the separate export-save-directory capability on
 * an exact Cubism host. It only uses the public SDK
 * ({@link FileChooserHistoryService} via the plugin context) plus plain JDK
 * file reads; it never imports or reflects {@code com.live2d.*} types and
 * never opens a save/export dialog or touches the model.
 *
 * <p>Assertions:
 * <ol>
 *   <li>the service is available and reads both independently seeded history slots;</li>
 *   <li>both slots match the framework shell persistence fixture at
 *       {@code <home>/config/turboism.core/save-directory-history.properties};</li>
 *   <li>both setters and provider registration reject plugin writes, with history
 *       reads and persisted properties unchanged after all three attempts;</li>
 *   <li>the global {@code <home>/config.json} no longer carries the
 *       rolled-back {@code fileChooserHistory} section (v1 → v2 evidence);</li>
 *   <li>{@code exportSeparationEnabled()} matches
 *       {@code -Dturboism.validation.separateSavePath.expectEnabled}
 *       (default false for a fresh isolated home);</li>
 *   <li>the probe never writes the model and never triggers the export
 *       dialog; the save-dialog hook is covered indirectly by run evidence
 *       (probe PASS + host launch logs), not by a dialog automation.</li>
 * </ol>
 * Terminal status is written to
 * {@code state/dev.turboism.validation.separatesavepath/host-validation-result.properties}.
 */
public final class SeparateSavePathHostValidationPlugin implements TurboismPlugin {

    private static final String RESULT = "host-validation-result.properties";
    private static final String EXPECT_ENABLED_PROPERTY =
        "turboism.validation.separateSavePath.expectEnabled";
    private static final long READY_TIMEOUT_MILLIS = 240_000L;
    private static final long SETTLE_STEP_MILLIS = 2_000L;

    private PluginContext context;
    private PluginLogger logger;
    private Path stateDir;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenHostReady, "separate-save-path-host-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
    }

    @Override
    public void enable() {
        logger.info("SEPARATE_SAVE_PATH_PROBE_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("SEPARATE_SAVE_PATH_PROBE_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("SEPARATE_SAVE_PATH_PROBE_SHUTDOWN");
    }

    private void runWhenHostReady() {
        if (dev.turboism.validation.SessionGate.enabled()
                && dev.turboism.validation.SessionGate.awaitTurn(stateDir, logger::info) == null) {
            return;
        }
        final List<String> failures = new ArrayList<>();
        if (!awaitHostReady(failures)) {
            logger.warn("SEPARATE_SAVE_PATH_RESULT status=FAIL phase=readiness");
            writeResult(false, failures);
            if (dev.turboism.validation.SessionGate.enabled()) {
                dev.turboism.validation.SessionGate.complete(stateDir, "FAIL");
                return;
            }
            Runtime.getRuntime().halt(2);
            return;
        }
        logger.info("SEPARATE_SAVE_PATH_EXERCISER_READY hostState=ACTIVE");
        runAssertions(failures);
        final boolean pass = failures.isEmpty();
        writeResult(pass, failures);
        logger.info("SEPARATE_SAVE_PATH_RESULT status=" + (pass ? "PASS" : "FAIL")
            + " failures=" + failures.size());
        for (String failure : failures) {
            logger.warn("SEPARATE_SAVE_PATH_FAILURE " + failure);
        }
        System.out.flush();
        System.err.flush();
        if (dev.turboism.validation.SessionGate.enabled()) {
            dev.turboism.validation.SessionGate.complete(stateDir, pass ? "PASS" : "FAIL");
            return;
        }
        // halt (not exit): skips shutdown hooks that can hang under Wine once
        // the probe has written its terminal result.
        Runtime.getRuntime().halt(pass ? 0 : 2);
    }

    private boolean awaitHostReady(final List<String> failures) {
        final long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        String lastFailure = "none";
        while (System.currentTimeMillis() < deadline) {
            try {
                final Path report = stateDir.getParent().resolve("runtime/preview-runtime-report.json");
                final String json = Files.readString(report);
                if (json.contains("\"identityState\":\"MATCHED\"")
                    && json.contains("\"adapterState\":\"READY\"")
                    && json.contains("\"runtimeState\":\"RUNNING\"")) {
                    return true;
                }
                lastFailure = "runtime-report-not-ready";
            } catch (IOException | RuntimeException unavailable) {
                lastFailure = unavailable.getClass().getSimpleName();
            }
            try {
                Thread.sleep(SETTLE_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        failures.add("readiness timeout: " + lastFailure);
        return false;
    }

    private void runAssertions(final List<String> failures) {
        // The runner seeds the shell-owned store before startup. The ordinary plugin
        // reads it through the SDK; it never writes shell config to set up its own proof.
        final FileChooserHistoryService service = context.services().find(FileChooserHistoryService.class)
            .orElse(FileChooserHistoryService.unavailable());
        if (!service.isAvailable()) {
            failures.add("file chooser history service unavailable");
            return;
        }
        final Path projectDir = Path.of("separate-save-path/project-saves");
        final Path exportDir = Path.of("separate-save-path/export-saves");
        final Optional<Path> projectReadBack = readSafely(
            service::projectRecentDirectory, "projectRecentDirectory", failures);
        final Optional<Path> exportReadBack = readSafely(
            service::exportRecentDirectory, "exportRecentDirectory", failures);
        checkDirectory(projectReadBack, projectDir, "projectRecentDirectory fixture", failures);
        checkDirectory(exportReadBack, exportDir, "exportRecentDirectory fixture", failures);

        final Path home = stateDir.getParent().getParent();
        final Path pluginConfig = home.resolve("config/turboism.core/save-directory-history.properties");
        final Properties persisted;
        if (!Files.isRegularFile(pluginConfig)) {
            failures.add("save-directory-history.properties missing at " + pluginConfig);
            persisted = null;
        } else {
            persisted = readProperties(pluginConfig, failures);
            if (persisted != null) {
                checkPersistedSlot(persisted, "projectRecentDirectory", projectDir, failures);
                checkPersistedSlot(persisted, "exportRecentDirectory", exportDir, failures);
            }
        }

        // Each entry point must reject writes. A no-op writable service is also a failure.
        expectReadOnly(() -> service.setProjectRecentDirectory(stateDir.resolve("forbidden-project")),
            "setProjectRecentDirectory", failures);
        expectReadOnly(() -> service.setExportRecentDirectory(stateDir.resolve("forbidden-export")),
            "setExportRecentDirectory", failures);
        expectReadOnly(() -> {
            try (FileChooserHistoryService.Registration ignored = service.registerProvider(
                    new FileChooserHistoryService.Provider() {
                        public Optional<Path> loadProjectDirectory() { return Optional.empty(); }
                        public Optional<Path> loadExportDirectory() { return Optional.empty(); }
                        public void saveProjectDirectory(Path dir) { throw new AssertionError("unexpected provider write"); }
                        public void saveExportDirectory(Path dir) { throw new AssertionError("unexpected provider write"); }
                    })) {
                // Close an erroneously accepted registration without leaving it installed.
            }
        }, "registerProvider", failures);
        if (!projectReadBack.equals(readSafely(service::projectRecentDirectory, "project after rejection", failures))) {
            failures.add("project history changed after rejected writes");
        }
        if (!exportReadBack.equals(readSafely(service::exportRecentDirectory, "export after rejection", failures))) {
            failures.add("export history changed after rejected writes");
        }
        if (persisted != null && !persisted.equals(readProperties(pluginConfig, failures))) {
            failures.add("persisted history changed after rejected writes");
        }

        // 4. Rollback evidence: config.json (if present) must no longer carry
        //    the fileChooserHistory section. A fresh isolated home may have no
        //    config.json at all (v2 writes nothing global), which is expected.
        final Path config = home.resolve("config.json");
        if (!Files.isRegularFile(config)) {
            logger.info("SEPARATE_SAVE_PATH_CONFIG_JSON absent (expected for fresh home)");
        } else {
            final String json = readConfig(config, failures);
            if (json != null && json.contains("fileChooserHistory")) {
                failures.add("config.json still contains the rolled-back fileChooserHistory section");
            } else {
                logger.info("SEPARATE_SAVE_PATH_CONFIG_JSON noRollbackSection=true");
            }
        }

        // 5. exportSeparationEnabled matches the expectEnabled property
        //    (fresh isolated home: false unless overridden).
        final boolean expectEnabled = Boolean.parseBoolean(
            System.getProperty(EXPECT_ENABLED_PROPERTY, "false"));
        final boolean actualEnabled = service.exportSeparationEnabled();
        if (actualEnabled != expectEnabled) {
            failures.add("exportSeparationEnabled mismatch: expected "
                + expectEnabled + " actual " + actualEnabled);
        } else {
            logger.info("SEPARATE_SAVE_PATH_ENABLED_FLAG expected=" + expectEnabled
                + " actual=" + actualEnabled);
        }
    }

    private static void expectReadOnly(final Runnable write, final String name, final List<String> failures) {
        try {
            write.run();
            failures.add(name + " accepted a plugin write");
        } catch (UnsupportedOperationException expected) {
            // The ordinary plugin's SDK contract requires this exact refusal.
        } catch (RuntimeException unexpected) {
            failures.add(name + " threw " + unexpected.getClass().getSimpleName() + " instead of read-only refusal");
        }
    }

    private static void checkDirectory(final Optional<Path> actual, final Path expected,
            final String name, final List<String> failures) {
        if (actual.isEmpty() || !actual.get().toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize())) {
            failures.add(name + " mismatch: expected " + expected + " actual " + actual);
        }
    }

    private interface DirectoryRead {
        Optional<Path> read();
    }

    private static Optional<Path> readSafely(
        final DirectoryRead read,
        final String name,
        final List<String> failures
    ) {
        try {
            final Optional<Path> value = read.read();
            if (value.isEmpty()) {
                failures.add(name + " read back empty");
            }
            return value;
        } catch (RuntimeException failure) {
            failures.add(name + " read failed: " + failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String readConfig(final Path config, final List<String> failures) {
        try {
            return Files.readString(config);
        } catch (IOException failure) {
            failures.add("config.json read failed: " + failure.getClass().getSimpleName());
            return null;
        }
    }

    private static Properties readProperties(final Path file, final List<String> failures) {
        final Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException failure) {
            failures.add("save-directory-history.properties read failed: "
                + failure.getClass().getSimpleName());
            return null;
        }
        return properties;
    }

    private void checkPersistedSlot(
        final Properties properties,
        final String key,
        final Path expected,
        final List<String> failures
    ) {
        final String expectedText = expected.toAbsolutePath().normalize().toString();
        final String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            failures.add("save-directory-history.properties missing " + key);
        } else if (!expectedText.equals(Path.of(value).toAbsolutePath().normalize().toString())) {
            failures.add("save-directory-history.properties " + key + " mismatch: expected \""
                + expectedText + " actual " + value);
        } else {
            logger.info("SEPARATE_SAVE_PATH_PERSISTED " + key + "=" + value);
        }
    }

    private void writeResult(final boolean pass, final List<String> failures) {
        final StringBuilder result = new StringBuilder()
            .append("status=").append(pass ? "PASS" : "FAIL").append('\n')
            .append("phase=matrix\n")
            .append("schemaVersion=2\n")
            .append("contract=plugin-read-only-seeded-history\n")
            .append("failures=").append(failures.size()).append('\n');
        for (int index = 0; index < failures.size(); index++) {
            result.append("failure.").append(index).append('=')
                .append(failures.get(index).replace('\n', ' ')).append('\n');
        }
        try {
            Files.writeString(stateDir.resolve(RESULT), result);
        } catch (IOException failure) {
            logger.error("SEPARATE_SAVE_PATH_RESULT_WRITE_FAILED", failure);
        }
    }
}
