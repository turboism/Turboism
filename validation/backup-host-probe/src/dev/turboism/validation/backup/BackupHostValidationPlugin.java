package dev.turboism.validation.backup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.turboism.plugin.webdavbackup.webdav.WebDavConfig;
import dev.turboism.plugin.webdavbackup.webdav.WebDavSyncTarget;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.backup.BackupArtifactHandle;
import dev.turboism.sdk.cubism.backup.BackupRunResult;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupService;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupStatus;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Task-local exerciser for the native auto-backup manager takeover on an exact
 * Cubism host. It only uses the public SDK ({@code context.services().require(EditorAutoBackupService.class)},
 * {@code context.cubism()}) plus plain JDK file scanning and an in-JVM WebDAV
 * mock ({@code com.sun.net.httpserver}); it never imports or reflects
 * {@code com.live2d.*} types.
 *
 * <p>The WebDAV client under test is the production {@link WebDavSyncTarget}
 * from {@code plugins/webdav-backup} (compiled into this exerciser by build.sh), so
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
    private static final String REQUEST_LOG = "webdav-requests.jsonl";
    private static final String FIXTURE_PROPERTY = "turboism.validation.fixture";
    private static final String WEBDAV_PORT_PROPERTY = "turboism.validation.webdav.port";
    private static final String WEBDAV_EXPECT_PLUGIN_PROPERTY = "turboism.validation.webdav.expect-plugin";
    private static final String WEBDAV_PLUGIN_PATH_PROPERTY = "turboism.validation.webdav.plugin-path";
    private static final String HEAP_BYTES_PROPERTY = "turboism.validation.heap-bytes";
    private static final String WEBDAV_PLUGIN_ID = "dev.turboism.plugin.webdav";
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
        WebDavProbe webDav = null;
        try {
            final EditorAutoBackupService backup = context.services().find(EditorAutoBackupService.class).orElse(EditorAutoBackupService.unavailable());
            identityBanner(hostVersion, model, failures);
            settingsRead(backup, failures);
            final EditorAutoBackupSettings originalSettings = backup.settings();
            settingsWriteReadback(backup, failures);
            final Path fixture = resolveFixture(failures);
            final String fixtureHashBefore = fixture == null ? "missing" : sha256(fixture);
            webDav = webDavSyncFlow(backup, failures);
            backupNowFlow(backup, model, fixture, webDav, failures);
            final boolean pluginExpected =
                "1".equals(System.getProperty(WEBDAV_EXPECT_PLUGIN_PROPERTY, ""));
            if (pluginExpected && webDav != null) {
                pluginSaveUploadFlow(backup, model, fixture, webDav, failures);
            }
            final long heapBytes = parseLongProperty(HEAP_BYTES_PROPERTY, 0L);
            if (heapBytes > 0 && webDav != null) {
                heapUploadObservationFlow(backup, webDav, heapBytes, failures);
            }
            if (pluginExpected) {
                permissionAuditFlow(failures);
            }
            if (fixture != null) {
                final String fixtureHashAfter = sha256(fixture);
                // The save-triggered phase deliberately rewrites the copied
                // fixture; only the no-save matrix keeps the unchanged check.
                if (!pluginExpected && !fixtureHashBefore.equals(fixtureHashAfter)) {
                    failures.add("fixture hash changed: " + fixtureHashBefore + " -> " + fixtureHashAfter);
                } else {
                    logger.info("BACKUP_FIXTURE_HASH before=" + fixtureHashBefore
                        + " after=" + fixtureHashAfter
                        + " savePhase=" + pluginExpected);
                }
            }
            restoreSettings(backup, originalSettings, failures);
        } catch (RuntimeException | Error failure) {
            failures.add("matrix failed safely: " + failure.getClass().getName());
        } finally {
            if (webDav != null) {
                writeRequestLog(webDav);
                webDav.server.stop(0);
            }
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
            final EditorAutoBackupSettings settings = context.services().find(EditorAutoBackupService.class).orElse(EditorAutoBackupService.unavailable()).settings();
            logger.info("BACKUP_IDENTITY hostVersion=" + hostVersion
                + " modelId=" + safeModelId(model)
                + " drawables=" + onHostThread(() -> model.drawables().all().size())
                + " serviceAvailable=true backupDir=" + settings.backupDirDisplay().orElse(null)
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
            final String backupDir = settings.backupDirDisplay().orElse(null);
            if (backupDir != null) {
                // The display string is not an SDK access capability; the probe
                // resolves it itself because host probes are not permission-audited.
                final File dir = new File(backupDir);
                if (!dir.exists() && !dir.mkdirs()) {
                    failures.add("backup dir is not present and cannot be created: " + backupDir);
                }
            }
            logger.info("BACKUP_SETTINGS_READ enabled=" + settings.enabled()
                + " interval=" + settings.intervalMinutes() + " maxMB=" + settings.maxMB()
                + " backupDir=" + backupDir);
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
                new EditorAutoBackupSettings(true, 3, 128, Optional.empty())
            );
            if (updated.intervalMinutes() != 3 || updated.maxMB() != 128) {
                failures.add("settings write-readback mismatch: " + updated);
                return;
            }
            logger.info("BACKUP_SETTINGS_WRITE_READBACK interval=3 readback=" + updated.intervalMinutes()
                + " (manager roundtrip is the persistence evidence)");
            // On-disk evidence is BEST-EFFORT diagnostic only: this host build
            // never writes the FileSetting key to disk mid-session (no UUConfig
            // file, no host log line — prior 'log evidence' hits were the
            // probe's own failure messages mirrored by CubismLoggerBridge).
            // The key mapping itself rests on the reviewed decompile evidence
            // (a(int) writes UUConfig FileSetting.autoBackupIntervalMinute).
            logDiagnostics(updated.backupDirDisplay().orElse(null));
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
     * Best-effort on-disk diagnostics (INFO only, never failures): the derived
     * editor log path and the user-home whole-tree glob. The content search is
     * for the KEY only — a {@code =3} fragment is meaningless because the
     * probe's own mirrored messages and the restored value can contain it.
     */
    private void logDiagnostics(final String backupDir) {
        if (backupDir != null) {
            final Path log = Path.of(backupDir).getParent().resolve("logs").resolve("log.txt");
            boolean exists = Files.isRegularFile(log);
            boolean containsKey = false;
            if (exists) {
                try {
                    containsKey = Files.readString(log, StandardCharsets.ISO_8859_1)
                        .contains(UU_KEY);
                } catch (IOException | OutOfMemoryError unavailable) {
                    containsKey = false;
                }
            }
            logger.info("BACKUP_UUCONFIG_LOG_DIAGNOSTIC path=" + log
                + " exists=" + exists + " containsKey=" + containsKey);
        } else {
            logger.info("BACKUP_UUCONFIG_LOG_DIAGNOSTIC path=null exists=false containsKey=false");
        }
        final GlobDiagnostic glob = locateUuConfigFileDiagnostic(UU_KEY);
        logger.info("BACKUP_UUCONFIG_FILE_DIAGNOSTIC userHome=" + glob.userHome
            + " examined=" + glob.examined
            + " found=" + glob.found
            + (glob.path != null ? " path=" + glob.path : ""));
    }

    /** Result of the secondary user-home glob diagnostic. */
    record GlobDiagnostic(String userHome, int examined, boolean found, String path) {
    }

    /**
     * Bounded glob under the user profile for a text file containing the given
     * key. Bounded by depth, file count, and file size; never touches
     * non-text files. This is a DIAGNOSTIC ONLY: the user-tree walk is
     * unreliable under the Wine/Proton JVM and its result never fails the
     * matrix (and a {@code =value} fragment is meaningless — the probe's own
     * mirrored messages can contain it).
     */
    static GlobDiagnostic locateUuConfigFileDiagnostic(final String key) {
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
                        return content.contains(key);
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
            final BackupRunResult event;
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
            final List<BackupArtifactHandle> artifacts = event.artifacts();
            if (artifacts.isEmpty()) {
                failures.add("backupNow produced no artifacts");
                return;
            }
            for (BackupArtifactHandle artifact : artifacts) {
                if (artifact.sizeBytes() <= 0 || !artifactReadable(artifact)) {
                    failures.add("artifact is missing or empty: " + artifact.fileName());
                } else {
                    logger.info("BACKUP_ARTIFACT file=" + artifact.fileName()
                        + " bytes=" + artifact.sizeBytes());
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
                final String expected = artifacts.get(0).fileName();
                final List<RequestRecord> puts = webDav.putsUnder("/turboism-backup-probe/", expected);
                if (puts.isEmpty()) {
                    failures.add("webdav mock did not receive the matching PUT for " + expected
                        + "; received=" + webDav.receivedPuts);
                } else {
                    final RequestRecord success = puts.get(puts.size() - 1);
                    final String artifactSha = sha256Stream(artifacts.get(0));
                    if (success.status != 201 && success.status != 200) {
                        failures.add("webdav backupNow PUT did not succeed: status=" + success.status);
                    }
                    if (!success.sha256.equals(artifactSha)) {
                        failures.add("webdav backupNow PUT bytes differ from artifact: put="
                            + success.sha256 + " artifact=" + artifactSha);
                    }
                    if (success.contentLengthHeader != artifacts.get(0).sizeBytes()
                        || success.bodyBytes != artifacts.get(0).sizeBytes()) {
                        failures.add("webdav backupNow PUT length mismatch: contentLength="
                            + success.contentLengthHeader + " bodyBytes=" + success.bodyBytes
                            + " artifact=" + artifacts.get(0).sizeBytes());
                    }
                    logger.info("BACKUP_WEBDAV_PUT_MATCHED file=" + expected
                        + " attempts=" + puts.size()
                        + " sha256=" + success.sha256
                        + " contentLength=" + success.contentLengthHeader
                        + " bodyBytes=" + success.bodyBytes);
                    if (puts.size() < 2 || puts.get(0).status != 500) {
                        failures.add("webdav 500-injection retry was not exercised: puts=" + puts.size());
                    } else {
                        logger.info("BACKUP_WEBDAV_RETRY_OK puts=" + puts.size()
                            + " first=" + puts.get(0).status + " final=" + success.status
                            + " reopenedStreamBytes=" + success.bodyBytes);
                    }
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
     * Starts the in-JVM WebDAV recording mock (MKCOL/PROPFIND/PUT/DELETE on
     * 127.0.0.1 only; one injected 500 on the first PUT per collection so every
     * uploader's retry path is exercised), wires the production
     * {@link WebDavSyncTarget} against it through the sync-target registry, and
     * re-triggers a backup. The port comes from
     * {@code turboism.validation.webdav.port} when the production webdav-backup
     * plugin shares this server (fixed port so its seeded config resolves); 0
     * keeps the legacy ephemeral binding. Every request is recorded with its
     * method, path, Content-Length header, actual received byte count and body
     * SHA-256; the records land in {@code webdav-requests.jsonl} under the
     * plugin state directory.
     */
    private WebDavProbe webDavSyncFlow(final EditorAutoBackupService backup, final List<String> failures) {
        final HttpServer server;
        final List<String> receivedPuts = new CopyOnWriteArrayList<>();
        final List<RequestRecord> records = new CopyOnWriteArrayList<>();
        final Set<String> injectedCollections = ConcurrentHashMap.newKeySet();
        final AtomicLong sequence = new AtomicLong();
        try {
            final int port = (int) parseLongProperty(WEBDAV_PORT_PROPERTY, 0L);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", exchange -> handleWebDav(exchange, receivedPuts, records, injectedCollections, sequence));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            logger.info("BACKUP_WEBDAV_SERVER port=" + server.getAddress().getPort());
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
            return new WebDavProbe(target, receivedPuts, records, server);
        } catch (RuntimeException failure) {
            failures.add("webdav target construction failed: " + failure.getClass().getSimpleName());
            server.stop(0);
            return null;
        }
    }

    /** One observed HTTP request on the recording mock. */
    record RequestRecord(
        long sequence, String method, String path, long contentLengthHeader,
        long bodyBytes, String sha256, int status) {
    }

    private static final class WebDavProbe {
        final WebDavSyncTarget target;
        final List<String> receivedPuts;
        final List<RequestRecord> records;
        final HttpServer server;

        WebDavProbe(final WebDavSyncTarget target, final List<String> receivedPuts,
                    final List<RequestRecord> records, final HttpServer server) {
            this.target = target;
            this.receivedPuts = receivedPuts;
            this.records = records;
            this.server = server;
        }

        /** PUTs whose path starts with {@code prefix} and ends with {@code fileName}, in order. */
        List<RequestRecord> putsUnder(final String prefix, final String fileName) {
            final List<RequestRecord> matched = new ArrayList<>();
            for (RequestRecord record : records) {
                if ("PUT".equals(record.method) && record.path.startsWith(prefix)
                    && record.path.endsWith(fileName)) {
                    matched.add(record);
                }
            }
            return matched;
        }
    }

    /** First path segment — the remote collection — used for per-collection 500 injection. */
    private static String collectionOf(final String path) {
        final String trimmed = path.startsWith("/") ? path.substring(1) : path;
        final int slash = trimmed.indexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(0, slash);
    }

    private static void handleWebDav(
        final HttpExchange exchange,
        final List<String> receivedPuts,
        final List<RequestRecord> records,
        final Set<String> injectedCollections,
        final AtomicLong sequence
    ) throws IOException {
        final String method = exchange.getRequestMethod();
        final String path = exchange.getRequestURI().getPath();
        // Drain and hash the request body in bounded chunks so a large upload
        // never occupies the heap inside this JVM either.
        final MessageDigest digest = newSha256Digest();
        long bodyBytes = 0;
        try (InputStream body = exchange.getRequestBody()) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = body.read(buffer)) >= 0) {
                bodyBytes += read;
                digest.update(buffer, 0, read);
            }
        }
        final String sha256 = hex(digest.digest());
        final long contentLengthHeader = parseLongSafe(
            exchange.getRequestHeaders().getFirst("Content-Length"), -1L);
        final int status;
        switch (method) {
            case "MKCOL" -> status = 405; // collection already exists
            case "PROPFIND" -> status = 207;
            case "PUT" -> {
                if (injectedCollections.add(collectionOf(path))) {
                    // inject one 500 per collection to force the retry path
                    status = 500;
                } else {
                    receivedPuts.add(path);
                    status = 201;
                }
            }
            default -> status = 501;
        }
        records.add(new RequestRecord(
            sequence.incrementAndGet(), method, path, contentLengthHeader, bodyBytes, sha256, status));
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    /**
     * Save-triggered end-to-end phase against the production
     * {@code dev.turboism.plugin.webdav} plugin (seeded config points it at the
     * shared recording mock on the fixed port). Dirties the copied fixture,
     * executes the semantic {@link EditorCommand#SAVE}, then waits for the
     * plugin's save hook → {@code backupAfterSave} → streaming PUT → temp
     * discard chain. Asserts wire bytes equal the post-save fixture bytes, the
     * injected 500 forced a stream-reopening retry, the {@code turboism-backup-*}
     * temp directory is pruned, and the host backup directory artifacts (the
     * earlier {@code backupNow} product included) survive untouched.
     */
    private void pluginSaveUploadFlow(
        final EditorAutoBackupService backup,
        final CubismModel model,
        final Path fixture,
        final WebDavProbe webDav,
        final List<String> failures
    ) {
        // Captured before any save so the task-scoped copy can be restored in
        // the finally block regardless of where the phase fails.
        final byte[] fixtureBytes = fixture == null ? null : readAll(fixture);
        final String fixtureHashPreSave = fixture == null ? "missing" : sha256(fixture);
        if (fixture != null && fixtureBytes == null) {
            failures.add("could not capture fixture bytes for the post-save restore: " + fixture);
            return;
        }
        try {
            final String pluginCollection =
                System.getProperty(WEBDAV_PLUGIN_PATH_PROPERTY, "/turboism-backup") + "/";
            if (!awaitRuntimeLogLine(
                line -> line.contains(WEBDAV_PLUGIN_ID)
                    && line.contains("WEBDAV_TARGET_READY")
                    && line.contains("127.0.0.1:" + webDav.server.getAddress().getPort()),
                90_000L)) {
                failures.add("webdav plugin target never became ready (WEBDAV_TARGET_READY absent)");
                return;
            }
            logger.info("BACKUP_PLUGIN_TARGET_READY port=" + webDav.server.getAddress().getPort());
            final Map<String, String> artifactsBefore = artifactSnapshot(backup, failures);
            // The save deliberately rewrites the copied fixture; the runner
            // verifies the copy's hash, so the original bytes are restored
            // after every assertion has consumed the post-save content.
            dirtyModel(model, failures);
            final EditorCommandService commands = context.services()
                .find(EditorCommandService.class)
                .orElse(EditorCommandService.unavailable());
            final EditorCommandResult saveResult = commands.execute(EditorCommand.SAVE);
            logger.info("BACKUP_SAVE_COMMAND status=" + saveResult.status()
                + " command=" + saveResult.commandId());
            if (!saveResult.executed()) {
                failures.add("SAVE command was not executed: status=" + saveResult.status());
                return;
            }
            final List<RequestRecord> pluginPuts = awaitPluginPut(webDav, pluginCollection, 180_000L);
            if (pluginPuts == null) {
                failures.add("no successful plugin PUT under " + pluginCollection + " within timeout");
                return;
            }
            final RequestRecord first = pluginPuts.get(0);
            final RequestRecord success = pluginPuts.get(pluginPuts.size() - 1);
            if (pluginPuts.size() != 2 || first.status != 500) {
                failures.add("plugin PUT retry shape unexpected: puts="
                    + describePuts(pluginPuts));
            }
            final String fixtureHashPostSave = fixture == null ? "missing" : sha256(fixture);
            if (!success.sha256.equals(fixtureHashPostSave)) {
                failures.add("plugin PUT bytes differ from saved fixture: put=" + success.sha256
                    + " fixture=" + fixtureHashPostSave);
            }
            final long fixtureSize = fixture == null ? -1L : fileSize(fixture);
            if (success.contentLengthHeader != success.bodyBytes
                || (fixtureSize >= 0 && success.bodyBytes != fixtureSize)) {
                failures.add("plugin PUT length mismatch: contentLength=" + success.contentLengthHeader
                    + " bodyBytes=" + success.bodyBytes + " fixtureSize=" + fixtureSize);
            }
            logger.info("BACKUP_PLUGIN_PUT_OK path=" + success.path
                + " attempts=" + pluginPuts.size()
                + " sha256=" + success.sha256
                + " contentLength=" + success.contentLengthHeader
                + " bodyBytes=" + success.bodyBytes
                + " fixtureSha256=" + fixtureHashPostSave
                + " fixtureChanged=" + !fixtureHashPreSave.equals(fixtureHashPostSave));
            if (!awaitRuntimeLogLine(
                line -> line.contains(WEBDAV_PLUGIN_ID) && line.contains("WEBDAV_SYNC_COMPLETED"),
                120_000L)) {
                failures.add("webdav plugin never logged WEBDAV_SYNC_COMPLETED");
            }
            if (!awaitRuntimeLogLine(
                line -> line.contains(WEBDAV_PLUGIN_ID) && line.contains("WEBDAV_TEMP_CLEANUP"),
                120_000L)) {
                failures.add("webdav plugin never logged WEBDAV_TEMP_CLEANUP (temp artifact not discarded)");
            }
            final List<String> tempLeftovers = listTempBackupDirs();
            if (!tempLeftovers.isEmpty()) {
                failures.add("turboism-backup-* temp directories remain: " + tempLeftovers);
            } else {
                logger.info("BACKUP_TEMP_ARTIFACTS_CLEAN tmpdir="
                    + System.getProperty("java.io.tmpdir"));
            }
            final Map<String, String> artifactsAfter = artifactSnapshot(backup, failures);
            if (!artifactsBefore.equals(artifactsAfter)) {
                failures.add("host backup directory artifacts changed across the save-triggered flow: before="
                    + artifactsBefore.keySet() + " after=" + artifactsAfter.keySet());
            } else {
                logger.info("BACKUP_HOST_DIR_PRESERVED artifacts=" + artifactsAfter.keySet());
            }
        } catch (RuntimeException failure) {
            failures.add("save-triggered plugin flow failed: " + failure.getClass().getName());
        } finally {
            if (fixtureBytes != null) {
                restoreFixture(fixture, fixtureBytes, fixtureHashPreSave, failures);
            }
        }
    }

    /**
     * Writes the captured pre-save bytes back to the copied fixture and
     * verifies the restored hash, so the runner's copied-fixture integrity
     * check still holds after the save-triggered phase. The immutable source
     * fixture is never touched — only the task-scoped copy.
     */
    private void restoreFixture(
        final Path fixture, final byte[] original, final String expectedHash,
        final List<String> failures) {
        try {
            Files.write(fixture, original);
            final String restored = sha256(fixture);
            logger.info("BACKUP_FIXTURE_RESTORED sha256=" + restored
                + " matches=" + restored.equals(expectedHash));
            if (!restored.equals(expectedHash)) {
                failures.add("fixture restore produced a different hash: " + restored);
            }
        } catch (IOException | RuntimeException failure) {
            failures.add("fixture restore failed: " + failure.getClass().getSimpleName());
        }
    }

    private static byte[] readAll(final Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            return null;
        }
    }

    /** Waits for the first successful PUT under the plugin collection; returns all its PUT records. */
    private List<RequestRecord> awaitPluginPut(
        final WebDavProbe webDav, final String collectionPrefix, final long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final List<RequestRecord> puts = new ArrayList<>();
            for (RequestRecord record : webDav.records) {
                if ("PUT".equals(record.method) && record.path.startsWith(collectionPrefix)) {
                    puts.add(record);
                }
            }
            if (puts.stream().anyMatch(record -> record.status == 200 || record.status == 201)) {
                return puts;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static String describePuts(final List<RequestRecord> puts) {
        final StringBuilder text = new StringBuilder("[");
        for (RequestRecord put : puts) {
            if (text.length() > 1) {
                text.append(',');
            }
            text.append(put.status).append(':').append(put.path);
        }
        return text.append(']').toString();
    }

    /** name -> "size:sha256" content fingerprint of every listed backup-dir artifact. */
    private Map<String, String> artifactSnapshot(final EditorAutoBackupService backup, final List<String> failures) {
        final Map<String, String> snapshot = new LinkedHashMap<>();
        try {
            for (BackupArtifactHandle artifact : backup.artifacts()) {
                snapshot.put(artifact.fileName(),
                    artifact.sizeBytes() + ":" + sha256Stream(artifact));
            }
        } catch (RuntimeException failure) {
            failures.add("artifact snapshot failed: " + failure.getClass().getSimpleName());
        }
        return snapshot;
    }

    /**
     * Heap-observation phase: drops a large {@code _backup*.cmo3}-shaped file
     * into the host backup directory, uploads it through the production
     * streaming {@link WebDavSyncTarget} (probe-registered target) while a
     * sampler records the Editor JVM heap, then asserts the peak heap delta
     * stays below the file size — a buffered upload would have to grow the
     * heap by the full artifact size. Deletes its own file afterwards; host
     * artifacts are never touched.
     */
    private void heapUploadObservationFlow(
        final EditorAutoBackupService backup,
        final WebDavProbe webDav,
        final long bytes,
        final List<String> failures
    ) {
        Path heapFile = null;
        try {
            final String backupDirText = backup.settings().backupDirDisplay().orElse(null);
            if (backupDirText == null) {
                failures.add("heap phase: host backup directory is not exposed");
                return;
            }
            final File backupDir = new File(backupDirText);
            heapFile = new File(backupDir, "heap-probe_backup2099_0101_0000.cmo3").toPath();
            final String expectedSha = writeDeterministicFile(heapFile, bytes);
            settleHeap();
            final long baseline = heapUsed();
            final AtomicLong peak = new AtomicLong(baseline);
            final AtomicBoolean sampling = new AtomicBoolean(true);
            final Thread sampler = new Thread(() -> {
                while (sampling.get()) {
                    final long used = heapUsed();
                    peak.accumulateAndGet(used, Math::max);
                    try {
                        Thread.sleep(25L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "backup-heap-sampler");
            sampler.setDaemon(true);
            sampler.start();
            RuntimeException uploadFailure = null;
            BackupArtifactHandle handle = null;
            try {
                for (BackupArtifactHandle candidate : backup.artifacts()) {
                    if (candidate.fileName().equals(heapFile.getFileName().toString())) {
                        handle = candidate;
                        break;
                    }
                }
                if (handle == null) {
                    failures.add("heap phase: generated artifact not listed by artifacts()");
                } else {
                    webDav.target.upload(handle);
                }
            } catch (RuntimeException failure) {
                uploadFailure = failure;
            } finally {
                sampling.set(false);
                sampler.join(5_000L);
            }
            final long peakDelta = peak.get() - baseline;
            settleHeap();
            final long postGc = heapUsed();
            final List<RequestRecord> puts =
                webDav.putsUnder("/turboism-backup-probe/", heapFile.getFileName().toString());
            final String observedSha = puts.isEmpty() ? "<none>" : puts.get(puts.size() - 1).sha256;
            logger.info("BACKUP_HEAP_OBSERVATION bytes=" + bytes
                + " baselineHeap=" + baseline
                + " peakHeap=" + peak.get()
                + " peakDelta=" + peakDelta
                + " postGcHeap=" + postGc
                + " putRecords=" + puts.size()
                + " putSha256=" + observedSha);
            if (uploadFailure != null) {
                failures.add("heap phase upload failed: " + uploadFailure.getClass().getSimpleName());
            } else if (puts.isEmpty() || !expectedSha.equals(observedSha)) {
                failures.add("heap phase PUT bytes differ from generated artifact: expected="
                    + expectedSha + " observed=" + observedSha);
            } else if (peakDelta >= bytes) {
                failures.add("heap grew by the full artifact size during upload: peakDelta="
                    + peakDelta + " bytes=" + bytes);
            }
        } catch (IOException | RuntimeException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            failures.add("heap phase failed: " + failure.getClass().getSimpleName());
        } finally {
            if (heapFile != null) {
                try {
                    Files.deleteIfExists(heapFile);
                    logger.info("BACKUP_HEAP_ARTIFACT_REMOVED path=" + heapFile);
                } catch (IOException failure) {
                    failures.add("heap phase could not remove its generated artifact: " + heapFile);
                }
            }
        }
    }

    /**
     * Evidence for the no-{@code turboism.file.*} manifest requirement: the
     * production plugin must have loaded/enabled (plugin-load-report lists it
     * as ENABLED), produced WEBDAV_TARGET_READY + WEBDAV_SYNC_COMPLETED, and
     * the runtime log must carry no permission-denied or failed lines for the
     * plugin component.
     */
    private void permissionAuditFlow(final List<String> failures) {
        try {
            final Path report = stateDir.getParent().getParent()
                .resolve("state/runtime/plugin-load-report.json");
            if (Files.isRegularFile(report)) {
                String json;
                try {
                    json = Files.readString(report, StandardCharsets.UTF_8);
                } catch (IOException unreadable) {
                    json = null;
                }
                if (json == null || !json.contains(WEBDAV_PLUGIN_ID)) {
                    failures.add("plugin-load-report does not list " + WEBDAV_PLUGIN_ID);
                } else {
                    final int start = json.indexOf("\"pluginId\":\"" + WEBDAV_PLUGIN_ID + "\"");
                    final int end = json.indexOf("\"pluginId\"", start + 1);
                    final String entry = start < 0 ? ""
                        : json.substring(start, end < 0 ? json.length() : end);
                    if (!entry.contains("\"lifecycleState\":\"ENABLED\"")
                        || !entry.contains("\"failures\":[]")) {
                        failures.add("plugin-load-report entry for " + WEBDAV_PLUGIN_ID
                            + " is not cleanly ENABLED");
                    } else {
                        logger.info("BACKUP_PLUGIN_LOAD_REPORT contains=" + WEBDAV_PLUGIN_ID
                            + " lifecycleState=ENABLED failures=0");
                    }
                }
            } else {
                logger.info("BACKUP_PLUGIN_LOAD_REPORT absent=" + report);
            }
            final List<String> pluginLines = runtimeLogLines(line -> line.contains(WEBDAV_PLUGIN_ID));
            int denied = 0;
            int failed = 0;
            for (String line : pluginLines) {
                final String lowered = line.toLowerCase(java.util.Locale.ROOT);
                if (lowered.contains("permission") || lowered.contains("denied")
                    || lowered.contains("missing required")) {
                    denied++;
                }
                if (line.contains("WEBDAV_SYNC_FAILED") || line.contains("SAVE_BACKUP_FAILED")
                    || line.contains("WEBDAV_TEMP_CLEANUP_FAILED")
                    || line.contains("WEBDAV_TARGET_UNAVAILABLE")) {
                    failed++;
                }
            }
            logger.info("BACKUP_PLUGIN_AUDIT lines=" + pluginLines.size()
                + " denied=" + denied + " failed=" + failed);
            if (denied > 0 || failed > 0) {
                failures.add("webdav plugin produced " + denied + " permission-denied and "
                    + failed + " failed log lines");
            }
        } catch (RuntimeException failure) {
            failures.add("permission audit failed: " + failure.getClass().getSimpleName());
        }
    }

    /** {@code turboism-backup-*} directories currently under the JVM temp dir. */
    private static List<String> listTempBackupDirs() {
        final String tmp = System.getProperty("java.io.tmpdir");
        if (tmp == null) {
            return List.of("<no java.io.tmpdir>");
        }
        try (Stream<Path> stream = Files.list(Path.of(tmp))) {
            return stream
                .map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith("turboism-backup-"))
                .sorted()
                .toList();
        } catch (IOException failure) {
            return List.of("<unreadable:" + failure.getClass().getSimpleName() + ">");
        }
    }

    /** Latest runtime session log under the task home's logs/runtime/<date>/ tree, or null. */
    private Path latestRuntimeLog() {
        final Path logs = stateDir.getParent().getParent().resolve("logs/runtime");
        if (!Files.isDirectory(logs)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(logs, 2)) {
            return stream
                .filter(path -> path.getFileName().toString().endsWith(".log"))
                .filter(Files::isRegularFile)
                .max(java.util.Comparator.comparing(path -> {
                    try {
                        return Files.getLastModifiedTime(path);
                    } catch (IOException failure) {
                        return java.nio.file.attribute.FileTime.fromMillis(0L);
                    }
                }))
                .orElse(null);
        } catch (IOException failure) {
            return null;
        }
    }

    private List<String> runtimeLogLines(final java.util.function.Predicate<String> filter) {
        final Path log = latestRuntimeLog();
        if (log == null) {
            return List.of();
        }
        try {
            final List<String> matched = new ArrayList<>();
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                if (filter.test(line)) {
                    matched.add(line);
                }
            }
            return matched;
        } catch (IOException | RuntimeException failure) {
            return List.of();
        }
    }

    private boolean awaitRuntimeLogLine(
        final java.util.function.Predicate<String> predicate, final long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!runtimeLogLines(predicate).isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Writes one JSON object per observed request into the plugin state dir (archived with state/). */
    private void writeRequestLog(final WebDavProbe webDav) {
        final StringBuilder out = new StringBuilder();
        for (RequestRecord record : webDav.records) {
            out.append("{\"seq\":").append(record.sequence)
                .append(",\"method\":\"").append(record.method).append('"')
                .append(",\"path\":\"").append(jsonEscape(record.path)).append('"')
                .append(",\"contentLength\":").append(record.contentLengthHeader)
                .append(",\"bodyBytes\":").append(record.bodyBytes)
                .append(",\"sha256\":\"").append(record.sha256).append('"')
                .append(",\"status\":").append(record.status)
                .append("}\n");
        }
        try {
            Files.writeString(stateDir.resolve(REQUEST_LOG), out.toString(), StandardCharsets.UTF_8);
            logger.info("BACKUP_WEBDAV_REQUEST_LOG records=" + webDav.records.size()
                + " path=" + stateDir.resolve(REQUEST_LOG));
        } catch (IOException failure) {
            logger.warn("BACKUP_WEBDAV_REQUEST_LOG_WRITE_FAILED " + failure.getClass().getSimpleName());
        }
    }

    private static String jsonEscape(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static long parseLongProperty(final String name, final long fallback) {
        final String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static long parseLongSafe(final String value, final long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format("%02x", value & 0xFF));
        }
        return hex.toString();
    }

    /** Streams the artifact bytes through the permission-gated handle and hashes them. */
    private static String sha256Stream(final BackupArtifactHandle artifact) {
        final MessageDigest digest = newSha256Digest();
        try (InputStream in = artifact.openStream()) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (IOException | RuntimeException failure) {
            return "unreadable:" + failure.getClass().getSimpleName();
        }
    }

    private static long fileSize(final Path path) {
        try {
            return Files.size(path);
        } catch (IOException failure) {
            return -1L;
        }
    }

    private static long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void settleHeap() throws InterruptedException {
        System.gc();
        Thread.sleep(400L);
        System.gc();
    }

    /**
     * Writes {@code bytes} of deterministic content (position-derived pattern),
     * returning the content SHA-256. Chunked so the write itself never grows
     * the heap by the file size.
     */
    private static String writeDeterministicFile(final Path target, final long bytes) throws IOException {
        final MessageDigest digest = newSha256Digest();
        final byte[] chunk = new byte[1024 * 1024];
        try (var out = Files.newOutputStream(target)) {
            long written = 0;
            while (written < bytes) {
                final int length = (int) Math.min(chunk.length, bytes - written);
                for (int index = 0; index < length; index++) {
                    chunk[index] = (byte) ((written + index) * 31L + 7L);
                }
                out.write(chunk, 0, length);
                digest.update(chunk, 0, length);
                written += length;
            }
        }
        return hex(digest.digest());
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

    /** Streams the artifact through the runtime-issued handle; any read failure means unreadable. */
    private static boolean artifactReadable(final BackupArtifactHandle artifact) {
        try (var in = artifact.openStream()) {
            in.readAllBytes();
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
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
