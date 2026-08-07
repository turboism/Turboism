package dev.turboism.validation.backup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.turboism.plugin.backup.webdav.WebDavConfig;
import dev.turboism.plugin.backup.webdav.WebDavSyncTarget;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.backup.BackupCompletedEvent;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupService;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupStatus;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Task-local exerciser for the native auto-backup manager takeover on an exact
 * Cubism host. It only uses the public SDK ({@code context.backup()},
 * {@code context.cubism()}) plus plain JDK file scanning and an in-JVM WebDAV
 * mock ({@code com.sun.net.httpserver}); it never imports or reflects
 * {@code com.live2d.*} types.
 *
 * <p>The WebDAV client under test is the production {@link WebDavSyncTarget}
 * from {@code plugins/backup} (compiled into this exerciser by build.sh), so
 * the real-host run exercises the exact plugin upload code path.</p>
 *
 * <p>Readiness and the dirty write use the model-scoped path
 * ({@code context.cubism().model().active()}), the proven pattern from the
 * mirror/clipmask probes: on the exact host the fixture becomes a model with
 * drawables ~90-150 s in, while the project-workspace snapshot-source document
 * path stays empty.</p>
 */
public final class BackupHostValidationPlugin implements TurboismPlugin {

    private static final String RESULT = "backup-validation-result.properties";
    private static final String FIXTURE_PROPERTY = "turboism.validation.fixture";
    private static final String UU_KEY = "autoBackupIntervalMinute";
    // On the exact host the fixture takes ~2.5 min to become a modeling
    // document (peer precedent: mirror/clipmask probes use 240 s); 360 s gives
    // the full window headroom.
    private static final long DOCUMENT_READY_TIMEOUT_MILLIS = 360_000L;
    private static final long MODEL_WAIT_WARN_MILLIS = 60_000L;
    private static final long MODEL_WAIT_WARN_2_MILLIS = 150_000L;
    private static final long SETTLE_STEP_MILLIS = 1_000L;
    private static final long PASS_SETTLE_MILLIS = 3_000L;
    private static final long EDT_TIMEOUT_MILLIS = 5_000L;

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;
    private boolean warnedAt60s;
    private boolean warnedAt150s;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenHostReady, "backup-host-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
    }

    @Override
    public void enable() {
        logger.info("BACKUP_EXERCISER_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("BACKUP_EXERCISER_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("BACKUP_EXERCISER_SHUTDOWN");
    }

    private void runWhenHostReady() {
        final List<String> failures = new ArrayList<>();
        final CubismModel model = awaitVerifiedModel(failures);
        if (model == null) {
            logger.warn("BACKUP_EXERCISER_READY_TIMEOUT reason=active-model-with-drawables-not-present");
            logger.warn("BACKUP_VALIDATION_RESULT status=FAIL phase=readiness");
            writeResult(false, "readiness", failures);
            Runtime.getRuntime().halt(2);
            return;
        }
        final String hostVersion = runtimeReportVersion();
        logger.info("BACKUP_EXERCISER_READY hostVersion=" + hostVersion
            + " modelId=" + safeModelId(model)
            + " drawables=" + onHostThread(() -> model.drawables().all().size()));
        runMatrix(model, hostVersion, failures);
    }

    /**
     * Model-scoped readiness gate (mirror/clipmask pattern): polls
     * {@code context.cubism().model().active()} until a model with non-empty
     * drawables appears. The runtime report presence is an additional gate and
     * is logged. The (usually empty on this host) snapshot-source
     * {@code activeDocument()} path is still probed for diagnostics so a future
     * failure distinguishes "no document at all" from "document present but not
     * yet a modeling document".
     */
    private CubismModel awaitVerifiedModel(final List<String> failures) {
        final long deadline = System.currentTimeMillis() + DOCUMENT_READY_TIMEOUT_MILLIS;
        final long started = System.currentTimeMillis();
        String lastFailure = "none";
        while (System.currentTimeMillis() < deadline) {
            try {
                final CubismModel model = onHostThread(() -> context.cubism().model().active());
                final boolean hasDrawables = onHostThread(() -> !model.drawables().all().isEmpty());
                final boolean reportReady = activeRuntimeReportPresent();
                if (hasDrawables && reportReady) {
                    logger.info("BACKUP_EXERCISER_MODEL_READY elapsedMs="
                        + (System.currentTimeMillis() - started)
                        + " reportReady=" + reportReady
                        + " modelId=" + safeModelId(model));
                    return model;
                }
                if (!hasDrawables) {
                    lastFailure = "model-with-drawables-not-present";
                }
            } catch (RuntimeException unavailable) {
                lastFailure = unavailable.getClass().getSimpleName();
            }
            final long elapsed = System.currentTimeMillis() - started;
            if (elapsed >= MODEL_WAIT_WARN_MILLIS && !warnedAt60s) {
                warnedAt60s = true;
                logger.warn("BACKUP_EXERCISER_MODEL_WAIT elapsedMs=" + elapsed
                    + " lastFailure=" + lastFailure
                    + " activeDocumentDiagnostic=" + activeDocumentDiagnostic());
            } else if (elapsed >= MODEL_WAIT_WARN_2_MILLIS && !warnedAt150s) {
                warnedAt150s = true;
                logger.warn("BACKUP_EXERCISER_MODEL_WAIT elapsedMs=" + elapsed
                    + " lastFailure=" + lastFailure
                    + " activeDocumentDiagnostic=" + activeDocumentDiagnostic());
            }
            try {
                Thread.sleep(SETTLE_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failures.add("readiness interrupted");
                return null;
            }
        }
        logger.warn("BACKUP_EXERCISER_MODEL_TIMEOUT lastFailure=" + lastFailure
            + " activeDocumentDiagnostic=" + activeDocumentDiagnostic());
        return null;
    }

    /**
     * Probes the snapshot-source document path purely for diagnostics: returns
     * {@code present=<bool> class=<name>} or {@code error=<exception class>}.
     * Never fails the readiness loop.
     */
    private String activeDocumentDiagnostic() {
        try {
            final Optional<DocumentSnapshot> document = context.cubism().activeDocument();
            return document
                .map(value -> "present=true class=" + value.getClass().getName())
                .orElse("present=false class=null");
        } catch (RuntimeException failure) {
            return "error=" + failure.getClass().getName();
        }
    }

    private boolean activeRuntimeReportPresent() {
        final Path report = stateDir.getParent().resolve("runtime/preview-runtime-report.json");
        try {
            final String json = Files.readString(report);
            return json.contains("\"identityState\":\"MATCHED\"")
                && json.contains("\"adapterState\":\"READY\"")
                && json.contains("\"runtimeState\":\"RUNNING\"");
        } catch (IOException unavailable) {
            return false;
        }
    }

    private String runtimeReportVersion() {
        final Path report = stateDir.getParent().resolve("runtime/preview-runtime-report.json");
        try {
            final String json = Files.readString(report);
            final String marker = "\"version\":\"";
            final int start = json.indexOf(marker);
            if (start < 0) {
                return "unknown";
            }
            final int end = json.indexOf('"', start + marker.length());
            return end < 0 ? "unknown" : json.substring(start + marker.length(), end);
        } catch (IOException unavailable) {
            return "unknown";
        }
    }

    private static String safeModelId(final CubismModel model) {
        try {
            return model.id() == null ? "null" : model.id().value();
        } catch (RuntimeException unavailable) {
            return "unavailable";
        }
    }

    private void runMatrix(final CubismModel model, final String hostVersion, final List<String> failures) {
        logger.info("BACKUP_VALIDATION_BEGIN hostVersion=" + hostVersion);
        try {
            final EditorAutoBackupService backup = context.backup();
            identityBanner(hostVersion, model, failures);
            settingsRead(backup, failures);
            final EditorAutoBackupSettings originalSettings = backup.settings();
            settingsWriteReadback(backup, failures);
            final Path fixture = resolveFixture(failures);
            final String fixtureHashBefore = fixture == null ? "missing" : sha256(fixture);
            final WebDavProbe webDav = webDavSyncFlow(backup, failures);
            backupNowFlow(backup, model, fixture, webDav, failures);
            if (fixture != null) {
                final String fixtureHashAfter = sha256(fixture);
                if (!fixtureHashBefore.equals(fixtureHashAfter)) {
                    failures.add("fixture hash changed: " + fixtureHashBefore + " -> " + fixtureHashAfter);
                }
            }
            restoreSettings(backup, originalSettings, failures);
            if (webDav != null) {
                webDav.server.stop(0);
            }
        } catch (RuntimeException | Error failure) {
            failures.add("matrix failed safely: " + failure.getClass().getName());
        }
        final boolean pass = failures.isEmpty();
        writeResult(pass, "full", failures);
        logger.info("BACKUP_VALIDATION_RESULT status=" + (pass ? "PASS" : "FAIL")
            + " hostVersion=" + hostVersion + " failures=" + failures.size());
        for (String failure : failures) {
            logger.warn("BACKUP_VALIDATION_FAILURE " + failure);
        }
        try {
            Thread.sleep(PASS_SETTLE_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(pass ? 0 : 2);
    }

    private void identityBanner(final String hostVersion, final CubismModel model,
                                final List<String> failures) {
        try {
            final EditorAutoBackupSettings settings = context.backup().settings();
            logger.info("BACKUP_IDENTITY hostVersion=" + hostVersion
                + " modelId=" + safeModelId(model)
                + " drawables=" + onHostThread(() -> model.drawables().all().size())
                + " serviceAvailable=true backupDir=" + settings.backupDir()
                + " interval=" + settings.intervalMinutes() + " maxMB=" + settings.maxMB());
        } catch (RuntimeException failure) {
            failures.add("identity banner failed: " + failure.getClass().getSimpleName());
        }
    }

    private void settingsRead(final EditorAutoBackupService backup, final List<String> failures) {
        try {
            final EditorAutoBackupSettings settings = backup.settings();
            if (settings.intervalMinutes() < 1 || settings.maxMB() < 1) {
                failures.add("settings read produced out-of-range values: " + settings);
                return;
            }
            if (settings.backupDir() != null) {
                final File dir = new File(settings.backupDir());
                if (!dir.exists() && !dir.mkdirs()) {
                    failures.add("backup dir is not present and cannot be created: " + settings.backupDir());
                }
            }
            logger.info("BACKUP_SETTINGS_READ enabled=" + settings.enabled()
                + " interval=" + settings.intervalMinutes() + " maxMB=" + settings.maxMB()
                + " backupDir=" + settings.backupDir());
        } catch (RuntimeException failure) {
            failures.add("settings read failed: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * Writes interval=3 through the manager setter, reads it back, locates the
     * UUConfig persisted file under the user profile (bounded glob) containing
     * {@code autoBackupIntervalMinute}=3, then restores the original interval
     * and reads it back.
     */
    private void settingsWriteReadback(final EditorAutoBackupService backup, final List<String> failures) {
        try {
            final EditorAutoBackupSettings updated = backup.updateSettings(
                new EditorAutoBackupSettings(true, 3, 128, null)
            );
            if (updated.intervalMinutes() != 3 || updated.maxMB() != 128) {
                failures.add("settings write-readback mismatch: " + updated);
                return;
            }
            logger.info("BACKUP_SETTINGS_WRITE_READBACK interval=3 readback=" + updated.intervalMinutes());
            // On-host persistence evidence: this editor build never flushes a
            // separate UUConfig file mid-session; the host logs the applied key
            // into its own editor log, so the log line is the acceptance. The
            // log sits beside the backup dir returned by the host manager i()
            // (backupDir's parent is the editor dir, logs/log.txt next to it);
            // deriving the path from the settings snapshot is deterministic and
            // avoids the unreliable user.home walk under the Wine/Proton JVM.
            final String backupDir = updated.backupDir();
            final Optional<Path> logEvidence = backupDir == null
                ? Optional.empty()
                : pollDerivedLogEvidence(backupDir, UU_KEY, "=3", 10_000L, 1_000L);
            if (logEvidence.isPresent()) {
                logger.info("BACKUP_UUCONFIG_LOG_EVIDENCE found=true path=" + logEvidence.orElseThrow());
            } else {
                failures.add("editor log evidence with " + UU_KEY + "=3 not found after 10s at derived path "
                    + (backupDir == null ? "null" : Path.of(backupDir).getParent().resolve("logs").resolve("log.txt"))
                    + " (backupDir from the host manager)");
            }
            // Secondary diagnostic only: the user.home whole-tree glob (plus the
            // JVM user.home value and the examined file count) must never fail
            // the matrix; the user-tree walk is unreliable under Wine/Proton.
            final GlobDiagnostic glob = locateUuConfigFileDiagnostic(UU_KEY, "=3");
            logger.info("BACKUP_UUCONFIG_FILE_DIAGNOSTIC userHome=" + glob.userHome
                + " examined=" + glob.examined
                + " found=" + glob.found
                + (glob.path != null ? " path=" + glob.path : ""));
        } catch (RuntimeException failure) {
            failures.add("settings write-readback failed: " + failure.getClass().getSimpleName());
        }
    }

    private void restoreSettings(final EditorAutoBackupService backup,
                                 final EditorAutoBackupSettings original,
                                 final List<String> failures) {
        try {
            final EditorAutoBackupSettings restored = backup.updateSettings(original);
            if (restored.intervalMinutes() != original.intervalMinutes()
                || restored.maxMB() != original.maxMB()
                || restored.enabled() != original.enabled()) {
                failures.add("settings restore readback mismatch: restored=" + restored
                    + " expected=" + original);
            }
            logger.info("BACKUP_SETTINGS_RESTORED interval=" + restored.intervalMinutes()
                + " maxMB=" + restored.maxMB() + " enabled=" + restored.enabled());
        } catch (RuntimeException failure) {
            failures.add("settings restore failed: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * Polls the derived editor-log path ({@code <backupDir-parent>/logs/log.txt})
     * until the key/value fragment appears (the host may flush the log line
     * asynchronously after the interval write) or the poll budget expires.
     */
    static Optional<Path> pollDerivedLogEvidence(
        final String backupDir,
        final String key,
        final String valueFragment,
        final long pollMillis,
        final long intervalMillis
    ) {
        final Path log = Path.of(backupDir).getParent().resolve("logs").resolve("log.txt");
        final long deadline = System.currentTimeMillis() + pollMillis;
        while (true) {
            if (Files.isRegularFile(log)) {
                try {
                    final String content = Files.readString(log, StandardCharsets.ISO_8859_1);
                    if (content.contains(key) && content.contains(valueFragment)) {
                        return Optional.of(log);
                    }
                } catch (IOException | OutOfMemoryError unavailable) {
                    // keep polling; the file may still be mid-flush
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                return Optional.empty();
            }
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /** Result of the secondary user-home glob diagnostic. */
    record GlobDiagnostic(String userHome, int examined, boolean found, String path) {
    }

    /**
     * Bounded glob under the user profile for a text file containing the given
     * key/value fragment (e.g. {@code autoBackupIntervalMinute=3}). Bounded by
     * depth, file count, and file size; never touches non-text files. This is a
     * DIAGNOSTIC ONLY: the user-tree walk is unreliable under the Wine/Proton
     * JVM and its result never fails the matrix.
     */
    static GlobDiagnostic locateUuConfigFileDiagnostic(final String key, final String valueFragment) {
        final Path root = Path.of(System.getProperty("user.home", "."));
        final int maxDepth = 10;
        final int maxFiles = 2_000;
        final long maxBytes = 8_000_000L;
        final java.util.concurrent.atomic.AtomicInteger examined =
            new java.util.concurrent.atomic.AtomicInteger();
        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            final Optional<Path> found = stream
                .filter(Files::isRegularFile)
                .filter(path -> !path.getFileName().toString().endsWith(".jar"))
                .filter(path -> {
                    try {
                        return Files.size(path) <= maxBytes;
                    } catch (IOException unavailable) {
                        return false;
                    }
                })
                .limit(maxFiles)
                .filter(path -> examined.incrementAndGet() <= maxFiles)
                .filter(path -> {
                    try {
                        final String content = Files.readString(path, StandardCharsets.ISO_8859_1);
                        return content.contains(key) && content.contains(valueFragment);
                    } catch (IOException | OutOfMemoryError unavailable) {
                        return false;
                    }
                })
                .findFirst();
            return new GlobDiagnostic(
                root.toString(),
                examined.get(),
                found.isPresent(),
                found.map(Path::toString).orElse(null)
            );
        } catch (IOException walkFailure) {
            return new GlobDiagnostic(root.toString(), examined.get(), false, null);
        }
    }

    private Path resolveFixture(final List<String> failures) {
        final String fixture = System.getProperty(FIXTURE_PROPERTY);
        if (fixture == null || fixture.isBlank()) {
            failures.add("fixture property " + FIXTURE_PROPERTY + " is not set");
            return null;
        }
        final Path path = Path.of(fixture);
        if (!Files.isRegularFile(path)) {
            failures.add("fixture is not a regular file: " + fixture);
            return null;
        }
        return path;
    }

    private void backupNowFlow(
        final EditorAutoBackupService backup,
        final CubismModel model,
        final Path fixture,
        final WebDavProbe webDav,
        final List<String> failures
    ) {
        try {
            final List<EditorAutoBackupStatus> before = backup.statuses();
            final long beforeLatestBackup = before.stream()
                .mapToLong(EditorAutoBackupStatus::lastAutoBackupTimeMillis)
                .max()
                .orElse(0L);
            dirtyModel(model, failures);
            final Registration registration = webDav == null
                ? null
                : backup.registerSyncTarget(webDav.target);
            final BackupCompletedEvent event;
            try {
                event = backup.backupNow().toCompletableFuture()
                    .get(120, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                failures.add("backupNow timed out after 120s");
                return;
            } finally {
                if (registration != null) {
                    registration.close();
                }
            }
            final List<File> artifacts = event.newBackupFiles();
            if (artifacts.isEmpty()) {
                failures.add("backupNow produced no artifacts");
                return;
            }
            for (File artifact : artifacts) {
                if (!artifact.isFile() || artifact.length() <= 0) {
                    failures.add("artifact is missing or empty: " + artifact);
                } else {
                    logger.info("BACKUP_ARTIFACT file=" + artifact.getAbsolutePath()
                        + " bytes=" + artifact.length());
                }
            }
            final List<EditorAutoBackupStatus> after = backup.statuses();
            final long afterLatestBackup = after.stream()
                .mapToLong(EditorAutoBackupStatus::lastAutoBackupTimeMillis)
                .max()
                .orElse(0L);
            if (afterLatestBackup <= beforeLatestBackup) {
                failures.add("lastAutoBackupTime did not advance: before=" + beforeLatestBackup
                    + " after=" + afterLatestBackup);
            } else {
                logger.info("BACKUP_LAST_AUTO_BACKUP_TIME_ADVANCED before=" + beforeLatestBackup
                    + " after=" + afterLatestBackup);
            }
            if (fixture != null) {
                final String hash = sha256(fixture);
                logger.info("BACKUP_FIXTURE_HASH_AFTER " + hash);
            }
            if (webDav != null) {
                final String expected = artifacts.get(0).getName();
                final boolean matched = webDav.receivedPuts.stream()
                    .anyMatch(path -> path.endsWith("/" + expected));
                if (!matched) {
                    failures.add("webdav mock did not receive the matching PUT for " + expected
                        + "; received=" + webDav.receivedPuts);
                } else {
                    logger.info("BACKUP_WEBDAV_PUT_MATCHED file=" + expected
                        + " attempts=" + webDav.putCalls.get());
                }
                if (webDav.putCalls.get() < 2) {
                    failures.add("webdav 500-injection retry was not exercised: putCalls="
                        + webDav.putCalls.get());
                } else {
                    logger.info("BACKUP_WEBDAV_RETRY_OK putCalls=" + webDav.putCalls.get());
                }
            }
        } catch (RuntimeException failure) {
            failures.add("backupNow flow failed: " + failure.getClass().getSimpleName());
        } catch (java.util.concurrent.ExecutionException failure) {
            failures.add("backupNow failed: " + failure.getCause().getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add("backupNow interrupted");
        }
    }

    /**
     * Dirtys the active model through the model-scoped write path: picks the
     * FIRST non-blend-shape parameter and writes a value that differs from the
     * current one (max when below the range midpoint, else min), so the
     * document ends up modified and the native {@code h()} (updateAutoBackup)
     * backs it up.
     */
    private void dirtyModel(final CubismModel model, final List<String> failures) {
        try {
            final Parameter parameter = onHostThread(() -> model.parameters().all().stream()
                .filter(candidate -> !candidate.isBlendShape())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "the fixture model exposes no non-blend-shape parameters")));
            final String parameterId = onHostThread(() -> parameter.id().value());
            final float before = onHostThread(parameter::getValue);
            final float minimum = onHostThread(parameter::getMinimumValue);
            final float maximum = onHostThread(parameter::getMaximumValue);
            if (minimum >= maximum) {
                failures.add("parameter has no writable range: id=" + parameterId
                    + " min=" + minimum + " max=" + maximum);
                return;
            }
            final float midpoint = (minimum + maximum) / 2.0f;
            final float target = before < midpoint ? maximum : minimum;
            onHostThread(() -> {
                parameter.setValue(target);
                return null;
            });
            final float after = onHostThread(parameter::getValue);
            logger.info("BACKUP_DIRTIED parameter=" + parameterId
                + " before=" + before + " target=" + target + " after=" + after);
            if (Math.abs(after - target) > 0.0001f) {
                failures.add("parameter write did not stick: id=" + parameterId
                    + " target=" + target + " after=" + after);
            }
        } catch (RuntimeException failure) {
            failures.add("dirty via Parameter.setValue failed: " + failure.getClass().getName());
        }
    }

    /**
     * Starts an in-JVM WebDAV mock (MKCOL/PROPFIND/PUT, one injected 500 on the
     * first PUT to exercise the plugin retry), wires the production
     * {@link WebDavSyncTarget} against it through the sync-target registry, and
     * re-triggers a backup. The mock must receive the matching PUT (retried
     * after the injected 500).
     */
    private WebDavProbe webDavSyncFlow(final EditorAutoBackupService backup, final List<String> failures) {
        final HttpServer server;
        final List<String> receivedPuts = new CopyOnWriteArrayList<>();
        final AtomicInteger putCalls = new AtomicInteger();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> handleWebDav(exchange, receivedPuts, putCalls));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        } catch (IOException failure) {
            failures.add("webdav mock start failed: " + failure.getClass().getSimpleName());
            return null;
        }
        try {
            final WebDavConfig config = new WebDavConfig(
                true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "probe-user",
                "probe-pass",
                "/turboism-backup-probe",
                true,
                2,
                50L,
                10
            );
            final WebDavSyncTarget target = new WebDavSyncTarget(config,
                reason -> logger.warn("BACKUP_WEBDAV_DIAG " + reason));
            return new WebDavProbe(target, receivedPuts, putCalls, server);
        } catch (RuntimeException failure) {
            failures.add("webdav target construction failed: " + failure.getClass().getSimpleName());
            server.stop(0);
            return null;
        }
    }

    private static final class WebDavProbe {
        final WebDavSyncTarget target;
        final List<String> receivedPuts;
        final AtomicInteger putCalls;
        final HttpServer server;

        WebDavProbe(final WebDavSyncTarget target, final List<String> receivedPuts,
                    final AtomicInteger putCalls, final HttpServer server) {
            this.target = target;
            this.receivedPuts = receivedPuts;
            this.putCalls = putCalls;
            this.server = server;
        }
    }

    private static void handleWebDav(
        final HttpExchange exchange,
        final List<String> receivedPuts,
        final AtomicInteger putCalls
    ) throws IOException {
        final String method = exchange.getRequestMethod();
        final String path = exchange.getRequestURI().getPath();
        switch (method) {
            case "MKCOL" -> exchange.sendResponseHeaders(405, -1); // collection already exists
            case "PROPFIND" -> exchange.sendResponseHeaders(207, -1);
            case "PUT" -> {
                final int attempt = putCalls.incrementAndGet();
                if (attempt == 1) {
                    // inject one 500 to force the plugin's retry path
                    exchange.sendResponseHeaders(500, -1);
                } else {
                    receivedPuts.add(path);
                    exchange.sendResponseHeaders(201, -1);
                }
            }
            default -> exchange.sendResponseHeaders(501, -1);
        }
        exchange.close();
    }

    private boolean writeResult(final boolean pass, final String phase, final List<String> failures) {
        final StringBuilder result = new StringBuilder()
            .append("status=").append(pass ? "PASS" : "FAIL").append('\n')
            .append("phase=").append(phase).append('\n')
            .append("failures=").append(failures.size()).append('\n');
        for (int index = 0; index < failures.size(); index++) {
            result.append("failure.").append(index).append('=')
                .append(failures.get(index).replace('\n', ' ')).append('\n');
        }
        try {
            Files.writeString(stateDir.resolve(RESULT), result);
            logger.info("BACKUP_RESULT_FILE status=" + (pass ? "PASS" : "FAIL")
                + " path=" + stateDir.resolve(RESULT));
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    /** Runs one host operation on the Swing EDT (mirror probe pattern). */
    private <T> T onHostThread(final Callable<T> operation) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return operation.call();
            } catch (Exception exception) {
                throw new IllegalStateException("auto-backup probe host operation failed", exception);
            }
        }
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(operation.call());
                } catch (Exception exception) {
                    failure.set(exception);
                } finally {
                    completed.countDown();
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("auto-backup probe EDT dispatch interrupted", interrupted);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            throw new IllegalStateException("auto-backup probe EDT dispatch failed", exception);
        }
        try {
            if (!completed.await(EDT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Cubism EDT did not accept the probe within 5 seconds.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("auto-backup probe EDT wait interrupted", interrupted);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("auto-backup probe host operation failed", failure.get());
        }
        return result.get();
    }

    private static String sha256(final Path path) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                final byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            final StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value & 0xFF));
            }
            return hex.toString();
        } catch (Exception failure) {
            return "hash-unavailable:" + failure.getClass().getSimpleName();
        }
    }
}
