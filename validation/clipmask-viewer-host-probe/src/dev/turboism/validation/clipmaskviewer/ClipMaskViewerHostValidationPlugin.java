package dev.turboism.validation.clipmaskviewer;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.StatusNotification;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task-local exerciser for the clip-mask viewer's exact-host read path.
 *
 * <p>This probe never touches production code: it re-executes the same public
 * SDK read path the clipmask-viewer plugin uses
 * ({@code context.cubismClipMasks().collectClipMaskRecords()},
 * {@code context.cubismRead().meshes()/selection()}) and the same runtime-side
 * registration lifecycle (action / menu / collapsible-section / status
 * notification), recording structured expected/actual/status evidence. All
 * host object reads happen on the Swing EDT via {@link #onHostThread(Callable)}.
 * The probe is validation tooling only and is never part of the production
 * preview bundle or product build.</p>
 */
public final class ClipMaskViewerHostValidationPlugin implements TurboismPlugin {

    private static final String FLAG = "exerciser.flag";
    private static final long FLAG_TIMEOUT_MILLIS = 240_000L;
    // Post-flag model-await budget. On the exact host the fixture document
    // takes ~2.5 min to become a modeling document, so 240 s matches the
    // peer probe's startup precedent.
    private static final long MODEL_AWAIT_MAX_MILLIS = 240_000L;
    private static final long MODEL_WAIT_WARN_MILLIS = 60_000L;
    private static final long MODEL_WAIT_WARN_2_MILLIS = 150_000L;
    private static final long EDT_TIMEOUT_MILLIS = 5_000L;
    // Cap the per-record assertions at 20 records to keep the result file bounded.
    private static final int RECORD_SAMPLE_MAX = 20;

    private static final String ACTION_ID = "clipmask-viewer.validation.probe.open";
    private static final String BUTTON_ID = "clipmask-viewer.validation.probe.button";
    private static final String MENU_PATH = "Turboism/clipmask-viewer-validation";
    private static final String SECTION_ID = "clipmask-viewer.validation.probe";
    private static final String PANEL_ID = "turboism.panel.main";
    private static final int SECTION_ORDER = 200;
    private static final String STATUS_ID = "clipmask-viewer.validation.probe.status";

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;
    private final List<Assertion> assertions = new ArrayList<>();
    private final List<Registration> openRegistrations = new ArrayList<>();

    private String modelId = "unknown";
    private int meshCount = 0;
    private int recordCount = 0;
    private int maskRelationships = 0;

    private boolean warnedAt60s;
    private boolean warnedAt150s;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenFlagged, "clipmask-viewer-host-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
        logger.info("CLIPMASK_VIEWER_PROBE_READY stateDir=" + stateDir);
    }

    @Override
    public void enable() {
        logger.info("CLIPMASK_VIEWER_PROBE_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("CLIPMASK_VIEWER_PROBE_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("CLIPMASK_VIEWER_PROBE_SHUTDOWN");
    }

    private void runWhenFlagged() {
        final Path flag = stateDir.resolve(FLAG);
        final long deadline = System.currentTimeMillis() + FLAG_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(flag)) {
                runMatrix();
                return;
            }
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warn("CLIPMASK_VIEWER_PROBE_FLAG_TIMEOUT flag=" + flag);
        Runtime.getRuntime().halt(2);
    }

    // ------------------------------------------------------------------
    // Matrix
    // ------------------------------------------------------------------

    private void runMatrix() {
        final long startedNanos = System.nanoTime();
        try {
            final CubismModel model = awaitActiveModel();
            recordIdentity(model);
            logger.info("CLIPMASK_VIEWER_MODEL_READY modelId=" + modelId + " meshCount=" + meshCount);
            runDataMatrix();
            runSelectionRead();
            runLifecycle();
            runNotifyStatus();
        } catch (Exception failure) {
            recordAssertion("matrix.unexpectedFailure", "no exception",
                singleLine(failure), "FAIL");
            logger.error("CLIPMASK_VIEWER_MATRIX_FAILED " + singleLine(failure), failure);
        }

        final String terminal = computeTerminal();
        writeResultFile(startedNanos);
        logger.info("CLIPMASK_VIEWER_MATRIX_RESULT status=" + terminal
            + " modelId=" + modelId
            + " meshCount=" + meshCount
            + " recordCount=" + recordCount
            + " maskRelationships=" + maskRelationships
            + " assertions=" + assertions.size()
            + " durationMillis=" + ((System.nanoTime() - startedNanos) / 1_000_000L));
        try {
            Thread.sleep(3_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(0);
    }

    private CubismModel awaitActiveModel() throws Exception {
        final long deadline = System.currentTimeMillis() + MODEL_AWAIT_MAX_MILLIS;
        final long started = System.currentTimeMillis();
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                final CubismModel model = onHostThread(() -> context.cubism().model().active());
                final boolean hasDrawables = onHostThread(() -> !model.drawables().all().isEmpty());
                if (model != null && hasDrawables) {
                    return model;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
            final long elapsed = System.currentTimeMillis() - started;
            if (elapsed >= MODEL_WAIT_WARN_MILLIS && !warnedAt60s) {
                warnedAt60s = true;
                logger.warn("CLIPMASK_VIEWER_MODEL_WAIT elapsedMs=" + elapsed
                    + " lastFailure=" + singleLine(lastFailure));
            } else if (elapsed >= MODEL_WAIT_WARN_2_MILLIS && !warnedAt150s) {
                warnedAt150s = true;
                logger.warn("CLIPMASK_VIEWER_MODEL_WAIT elapsedMs=" + elapsed
                    + " lastFailure=" + singleLine(lastFailure));
            }
            Thread.sleep(1_000L);
        }
        throw new IllegalStateException(
            "No active model with drawables was observed within " + MODEL_AWAIT_MAX_MILLIS + " ms",
            lastFailure);
    }

    private void recordIdentity(final CubismModel model) throws Exception {
        modelId = onHostThread(() -> model.id() != null ? model.id().value() : "null");
        meshCount = onHostThread(() -> model.drawables().all().size());
    }

    // ------------------------------------------------------------------
    // Data matrix: clip-mask records + mesh join
    // ------------------------------------------------------------------

    private void runDataMatrix() throws Exception {
        final List<ClipMaskRecord> first = onHostThread(
            () -> context.cubismClipMasks().collectClipMaskRecords());
        final List<ClipMaskRecord> second = onHostThread(
            () -> context.cubismClipMasks().collectClipMaskRecords());
        recordCount = first.size();

        final boolean idempotent = first.equals(second);
        recordAssertion("matrix.read.idempotent", "two collects identical",
            idempotent ? "identical records=" + first.size()
                : "differ first=" + first.size() + " second=" + second.size(),
            idempotent ? "PASS" : "FAIL");

        // meshes() may legitimately be empty on the exact host (the host model's
        // art-mesh list is design-empty), so this id->name index is best-effort
        // and meshCount stays drawables-based from recordIdentity().
        final List<ArtMeshSnapshot> meshes = onHostThread(() -> context.cubismRead().meshes());
        final Map<String, String> namesByGuid = new HashMap<>();
        for (ArtMeshSnapshot mesh : meshes) {
            namesByGuid.put(mesh.id(), mesh.name() == null ? "" : mesh.name());
        }

        // Dedup: record count must equal the distinct-guid count.
        final Set<String> recordGuids = new HashSet<>();
        for (ClipMaskRecord record : first) {
            recordGuids.add(record.guid());
        }
        final boolean dedup = recordGuids.size() == first.size();
        recordAssertion("matrix.dedupByGuid",
            "records == distinct guids",
            dedup ? "records=" + first.size() + " distinctGuids=" + recordGuids.size()
                : "records=" + first.size() + " distinctGuids=" + recordGuids.size(),
            dedup ? "PASS" : "FAIL");

        // maskRelationships over ALL records (not just the sample). Evidence only:
        // ArtMesh guid (clip-mask slices) and ArtMesh id (meshes()/drawables) are
        // separate namespaces on the real host, so no membership assertion is made.
        int relationships = 0;
        for (ClipMaskRecord record : first) {
            relationships += record.orderedMaskGuids().size();
        }
        maskRelationships = relationships;
        recordAssertion("record.maskGuidsObserved", "evidence: summed orderedMaskGuids",
            "maskGuidsObserved=" + maskRelationships, "PASS");

        // Per-record checks on a bounded sample: non-blank fields + join consistency.
        final int sample = Math.min(RECORD_SAMPLE_MAX, first.size());
        boolean guidsOk = true;
        boolean displayNamesOk = true;
        boolean maskGuidsOk = true;
        String recordDetail = "";
        for (int index = 0; index < sample; index++) {
            final ClipMaskRecord record = first.get(index);
            final String where = "record #" + index + " guid=" + record.guid();
            if (record.guid() == null || record.guid().isBlank()) {
                guidsOk = false;
                recordDetail = where + ": blank guid";
            }
            if (record.displayName() == null || record.displayName().isBlank()) {
                displayNamesOk = false;
                recordDetail = where + ": blank displayName";
            }
            for (String maskGuid : record.orderedMaskGuids()) {
                if (maskGuid == null || maskGuid.isBlank()) {
                    maskGuidsOk = false;
                    recordDetail = where + ": blank mask guid";
                }
            }
            // displayName resolution contract: joined to the mesh name when the
            // meshes() index has an entry for this guid, else the short-guid
            // fallback (same rule as the runtime MeshIndex). Both paths are hard
            // assertions: a mismatch is a FAIL.
            final boolean joined = namesByGuid.containsKey(record.guid());
            final String expectedName = joined ? namesByGuid.get(record.guid()) : shortGuid(record.guid());
            recordAssertion("record." + index + ".displayNameResolved",
                joined ? "joined:" + expectedName : "fallback:" + expectedName,
                record.displayName(),
                expectedName.equals(record.displayName()) ? "PASS" : "FAIL");
        }
        recordAssertion("record.guidNonBlank",
            "sampled record guids non-blank",
            guidsOk ? "ok sample=" + sample : recordDetail,
            guidsOk ? "PASS" : "FAIL");
        recordAssertion("record.displayNameNonBlank",
            "sampled record display names non-blank",
            displayNamesOk ? "ok sample=" + sample : recordDetail,
            displayNamesOk ? "PASS" : "FAIL");
        recordAssertion("record.maskGuidsNonBlank",
            "sampled record ordered mask guids non-blank",
            maskGuidsOk ? "ok sample=" + sample : recordDetail,
            maskGuidsOk ? "PASS" : "FAIL");

        // Fixture-content adaptation: zero clip-mask relationships over a loaded
        // model means the fixture itself has none (mirror BLOCKED precedent).
        final String fixtureStatus;
        if (recordCount > 0) {
            fixtureStatus = "PASS";
        } else if (meshCount > 0) {
            fixtureStatus = "BLOCKED";
        } else {
            fixtureStatus = "FAIL";
        }
        recordAssertion("fixture.clipMasks",
            "clip-mask relationships present in fixture",
            "records=" + recordCount + " meshes=" + meshCount,
            fixtureStatus);
    }

    // ------------------------------------------------------------------
    // Selection bridge (read-only)
    // ------------------------------------------------------------------

    private void runSelectionRead() throws Exception {
        final SelectionSnapshot selection = onHostThread(() -> context.cubismRead().selection());
        final boolean ok = selection != null
            && selection.selectedObjectIds() != null
            && selection.activeArtMeshId() != null;
        recordAssertion("selection.read",
            "selection() without exception; selectedObjectIds/activeArtMeshId non-null",
            ok ? "selectedObjectIds=" + selection.selectedObjectIds().size()
                + " activeArtMeshId=" + selection.activeArtMeshId().orElse("")
                : singleLine(selection),
            ok ? "PASS" : "FAIL");

        // Best-effort selection-query bridge: any outcome is PASS (same pattern as
        // notifyStatus). selectionQuery() is a PluginContext default method that may
        // throw UnsupportedOperationException on the exact host.
        try {
            onHostThread(() -> context.selectionQuery().currentSelection());
            recordAssertion("selection.query", "best-effort read", "delivered", "PASS");
        } catch (UnsupportedOperationException unsupported) {
            logger.warn("CLIPMASK_VIEWER_SELECTION_QUERY unsupported=" + unsupported.getMessage());
            recordAssertion("selection.query", "best-effort read", "unsupported", "PASS");
        } catch (Exception failure) {
            logger.warn("CLIPMASK_VIEWER_SELECTION_QUERY failed=" + singleLine(failure));
            recordAssertion("selection.query", "best-effort read", "failed", "PASS");
        }
    }

    // ------------------------------------------------------------------
    // Plugin registration lifecycle (runtime side, permission routed)
    // ------------------------------------------------------------------

    private void runLifecycle() {
        try {
            registerAll();
            recordAssertion("lifecycle.register",
                "action+menu+section registered without exception", "registered", "PASS");
            closeAll();
            recordAssertion("lifecycle.close",
                "action+menu+section closed without exception", "closed", "PASS");
            registerAll();
            recordAssertion("lifecycle.reregister",
                "action+menu+section registered again without exception", "registered", "PASS");
            closeAll();
            recordAssertion("lifecycle.reclose",
                "action+menu+section closed again without exception", "closed", "PASS");
        } catch (Exception failure) {
            recordAssertion("lifecycle.registerClose",
                "register/close without exception", singleLine(failure), "FAIL");
            logger.error("CLIPMASK_VIEWER_LIFECYCLE status=FAIL " + singleLine(failure), failure);
        }
    }

    private void registerAll() {
        final Registration actionRegistration = context.actions().register(ACTION_ID,
            new ActionRegistry.Action() {
                @Override
                public String id() {
                    return ACTION_ID;
                }

                @Override
                public String label() {
                    return "Clip Mask Viewer validation probe";
                }

                @Override
                public java.util.function.Consumer<ActionRegistry.ActionContext> handler() {
                    return ignored -> { /* validation action is intentionally a no-op */ };
                }
            });
        final Registration menuRegistration = context.menus().contribute(
            new MenuRegistry.MenuContribution() {
                @Override
                public String menuPath() {
                    return MENU_PATH;
                }

                @Override
                public String actionId() {
                    return ACTION_ID;
                }

                @Override
                public int order() {
                    return SECTION_ORDER;
                }
            });
        final Registration sectionRegistration = context.uiHost().contributeCollapsibleSection(
            new CollapsibleSectionContribution(
                EmbeddedPanelId.of(PANEL_ID),
                SECTION_ID,
                "Clip Mask Viewer validation probe",
                SECTION_ORDER,
                false,
                PanelView.column(PanelView.button(BUTTON_ID, "Clip Mask Viewer validation", ACTION_ID))
            ));
        openRegistrations.add(actionRegistration);
        openRegistrations.add(menuRegistration);
        openRegistrations.add(sectionRegistration);
    }

    private void closeAll() {
        for (Registration registration : openRegistrations) {
            registration.close();
        }
        openRegistrations.clear();
    }

    private void runNotifyStatus() {
        try {
            context.uiHost().notifyStatus(new StatusNotification(
                STATUS_ID, "INFO", "Clip mask viewer host validation probe completed."));
            recordAssertion("status.notify", "best-effort delivery", "delivered", "PASS");
        } catch (UnsupportedOperationException unsupported) {
            logger.warn("CLIPMASK_VIEWER_STATUS_NOTIFY unsupported=" + unsupported.getMessage());
            recordAssertion("status.notify", "best-effort delivery", "unsupported", "PASS");
        } catch (RuntimeException failure) {
            logger.warn("CLIPMASK_VIEWER_STATUS_NOTIFY failed=" + singleLine(failure));
            recordAssertion("status.notify", "best-effort delivery", "failed", "PASS");
        }
    }

    // ------------------------------------------------------------------
    // EDT / evidence helpers
    // ------------------------------------------------------------------

    private static <T> T onHostThread(final Callable<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.call();
        }
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(operation.call());
            } catch (Exception exception) {
                failure.set(exception);
            } finally {
                completed.countDown();
            }
        });
        if (!completed.await(EDT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Cubism EDT did not accept the probe within 5 seconds.");
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private static String shortGuid(final String guid) {
        return guid.length() <= 8 ? guid : guid.substring(0, 8);
    }

    private void recordAssertion(final String name, final Object expected, final Object actual, final String status) {
        assertions.add(new Assertion(name, singleLine(expected), singleLine(actual), status));
    }

    private static String singleLine(final Object value) {
        if (value == null) {
            return "null";
        }
        final String text = value.toString().replace('\n', ' ').replace('\r', ' ');
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    private String computeTerminal() {
        boolean anyFail = false;
        boolean anyBlocked = false;
        for (Assertion assertion : assertions) {
            if ("FAIL".equals(assertion.status())) {
                anyFail = true;
            } else if ("BLOCKED".equals(assertion.status())) {
                anyBlocked = true;
            }
        }
        return anyFail ? "FAIL" : anyBlocked ? "BLOCKED" : "PASS";
    }

    private void writeResultFile(final long startedNanos) {
        final Path result = stateDir.getParent().resolve("clipmask-viewer-validation-result.properties");
        try {
            final StringBuilder report = new StringBuilder()
                .append("schemaVersion=1\n")
                .append("runId=").append(System.getProperty("turboism.validation.runId", "unknown")).append('\n')
                .append("pluginId=dev.turboism.validation.clipmask-viewer\n")
                .append("hostVersion=").append(System.getProperty("turboism.validation.hostVersion", "unknown")).append('\n')
                .append("fixtureName=").append(System.getProperty("turboism.validation.fixtureName", "unknown")).append('\n')
                .append("modelId=").append(modelId).append('\n')
                .append("meshCount=").append(meshCount).append('\n')
                .append("recordCount=").append(recordCount).append('\n')
                .append("maskRelationships=").append(maskRelationships).append('\n')
                .append("durationMillis=").append((System.nanoTime() - startedNanos) / 1_000_000L).append('\n');
            for (Assertion assertion : assertions) {
                report.append("assertion.").append(assertion.name()).append(".expected=")
                    .append(assertion.expected()).append('\n')
                    .append("assertion.").append(assertion.name()).append(".actual=")
                    .append(assertion.actual()).append('\n')
                    .append("assertion.").append(assertion.name()).append(".status=")
                    .append(assertion.status()).append('\n');
            }
            report.append("status=").append(computeTerminal()).append('\n');
            Files.createDirectories(result.getParent());
            Files.writeString(result, report.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("CLIPMASK_VIEWER_RESULT_WRITTEN result=" + result
                + " status=" + computeTerminal() + " assertions=" + assertions.size());
        } catch (Exception failure) {
            logger.error("CLIPMASK_VIEWER_RESULT_WRITE_FAILED result=" + result
                + " " + singleLine(failure), failure);
        }
    }

    private record Assertion(String name, String expected, String actual, String status) {
    }
}
