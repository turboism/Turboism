package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.CancellationToken;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceInfo;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceService;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Manual-test-only SDK plugin with an opt-in automatic exact-host Workspace matrix.
 *
 * <p>The probe calls only {@link PluginContext#workspace()}, {@link PluginContext#cubism()},
 * {@link PluginContext#tasks()}, {@link PluginContext#paths()}, and the SDK permission surface;
 * it never touches runtime, reflection, or host objects. The protocol root is
 * {@code PluginContext.paths().stateDir()}. One scheduled low-frequency task scans bounded
 * command files under a scan lock; disable/shutdown set the enabled flag under the lock, cancel
 * the handle, and wait for any in-flight scan to leave the lock before returning, so no probe
 * polling survives disable. Each accepted command is claimed by an atomic move into
 * {@code inflight/} before any SDK call, executed at most once per claim, and the claim is
 * removed only after the result and the durable watermark are published; an unresolved claim
 * fails the protocol closed. Host-derived values are Base64-encoded; free text is sanitized.</p>
 */
public final class WindowsWorkspaceValidationProbe implements CubismPlugin {

    static final String COMMANDS_DIR = "commands";
    static final String RESULTS_DIR = "results";
    static final String REJECTED_DIR = "rejected";
    static final String INFLIGHT_DIR = "inflight";
    static final String EVIDENCE_FILE = "evidence.txt";
    static final String PROTOCOL_STATE_FILE = "protocol-state.txt";
    static final int MAX_COMMAND_BYTES = 4096;
    static final int MAX_PENDING_PER_SCAN = 64;
    static final int MAX_ENCODED_VALUE_BYTES = 512;
    static final int MAX_TEXT_LINE_BYTES = 512;
    static final String REDACTED_VALUE = "__REDACTED__";
    private static final String AUTOMATIC_MODE_PROPERTY = "turboism.workspaceValidation.mode";
    private static final String AUTOMATIC_MODE = "matrix";
    private static final String EXIT_ON_COMPLETE_PROPERTY = "turboism.validation.exitOnComplete";
    private static final String WORKSPACE_RESULT_FILE = "workspace-host-validation.properties";
    private static final String VALIDATION_AGENT_FILE = "validation-agent.txt";
    private static final String CONNECT_MARKER = "validation-agent.connect";
    private static final String DISCONNECT_MARKER = "validation-agent.disconnect";
    private static final Duration AUTOMATIC_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration SDK_CALL_TIMEOUT = Duration.ofSeconds(30);
    static final Pattern COMMAND_FILE_NAME = Pattern.compile("([1-9][0-9]{0,5})-([a-z0-9-]{1,64})\\.cmd");
    static final Pattern ACCEPTED_RESULT_FILE = Pattern.compile("([0-9]{6})-([a-z0-9-]{1,64})\\.txt");

    /** One parsed command file: strictly increasing sequence plus a bounded operation name. */
    public record Command(long sequence, String name, Optional<String> argument) {
        public Command {
            if (sequence < 1) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            name = Objects.requireNonNull(name, "name");
            argument = Objects.requireNonNull(argument, "argument");
        }
    }

    /** Result of executing one command: {@code status} plus typed key=value evidence lines. */
    public record CommandResult(String status, List<String> lines) {
        public CommandResult {
            status = Objects.requireNonNull(status, "status");
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        }
    }

    /** Raised when the durable protocol-state watermark is unreadable; the scan fails closed. */
    static final class ProtocolStateException extends RuntimeException {
        ProtocolStateException(final String message) {
            super(message);
        }
    }

    private final Object scanLock = new Object();
    private PluginContext context;
    private boolean scanEnabled;
    private volatile TaskHandle scanHandle;
    private volatile long lastProcessed;
    private List<String> declaredPermissions = List.of();
    private Thread automaticMatrixThread;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        // Sole cleanup registration: cancelScan owns the handle lifecycle and the fence.
        context.disposableScope().register(this::cancelScan);
        context.logger().info("Windows workspace validation probe initialized");
    }

    @Override
    public void enable() {
        if (scanHandle != null) {
            return;
        }
        declaredPermissions = context.permissions().stream()
            .map(PluginPermission::id)
            .sorted()
            .toList();
        synchronized (scanLock) {
            scanEnabled = true;
        }
        final TaskSubmission submission = context.tasks().scheduleWithFixedDelay(
            new FixedDelayTaskRequest(
                new TaskId("workspace-validation-scan"),
                PluginTaskKind.LOW_FREQUENCY_REFRESH,
                PluginTaskPriority.LOW,
                Duration.ofSeconds(1),
                Duration.ofMillis(500),
                this::scanOnce
            )
        );
        if (!submission.accepted()) {
            synchronized (scanLock) {
                scanEnabled = false;
            }
            context.logger().warn(
                "Windows workspace validation scan rejected: "
                    + submission.rejectionReason().map(Object::toString).orElse("unknown")
            );
            return;
        }
        scanHandle = submission.handle();
        if (automaticMatrixEnabled()) {
            automaticMatrixThread = new Thread(
                this::runAutomaticMatrix, "turboism-workspace-validation-matrix"
            );
            automaticMatrixThread.setDaemon(true);
            automaticMatrixThread.start();
        }
        context.logger().info(
            "Windows workspace validation probe listening under "
                + context.paths().stateDir()
        );
    }

    @Override
    public void disable() {
        cancelScan();
    }

    @Override
    public void shutdown() {
        cancelScan();
    }

    /**
     * Disables scanning and returns only after any in-flight scan has finished: the enabled flag
     * is cleared under the scan lock (so every subsequent scan observes it before any service or
     * I/O), the handle is cancelled, and acquiring the lock again synchronously waits for a scan
     * already inside the lock to leave it. No probe polling survives disable/shutdown.
     */
    private void cancelScan() {
        final TaskHandle handle;
        final Thread matrixThread;
        synchronized (scanLock) {
            scanEnabled = false;
            handle = scanHandle;
            scanHandle = null;
            matrixThread = automaticMatrixThread;
            automaticMatrixThread = null;
        }
        if (matrixThread != null) {
            matrixThread.interrupt();
        }
        if (handle != null) {
            handle.cancel();
            handle.close();
        }
    }

    private void scanOnce(final CancellationToken token) {
        synchronized (scanLock) {
            if (!scanEnabled || token.isCancellationRequested()) {
                return;
            }
            try {
                final long next = processPending(
                    context.paths().stateDir(),
                    lastProcessed,
                    context.workspace(),
                    context.cubism(),
                    declaredPermissions
                );
                if (next >= 0) {
                    lastProcessed = next;
                }
            } catch (Exception failure) {
                // The scheduled task survives transient I/O failures; evidence records them.
                try {
                    appendEvidence(
                        context.paths().stateDir(),
                        "scan",
                        "error=" + sanitizeText(failure.getClass().getName() + ": " + failure.getMessage())
                    );
                } catch (IOException ignored) {
                    // No state directory writable at all; stay quiet and retry next scan.
                }
            }
        }
    }

    private boolean automaticMatrixEnabled() {
        return AUTOMATIC_MODE.equals(System.getProperty(AUTOMATIC_MODE_PROPERTY));
    }

    private void runAutomaticMatrix() {
        final long startedNanos = System.nanoTime();
        final List<String> report = new ArrayList<>();
        report.add("schemaVersion=1");
        report.add("runId=" + sanitizeText(System.getProperty("turboism.validation.runId", "unknown")));
        report.add("mode=" + AUTOMATIC_MODE);
        boolean passed = false;
        try {
            runWorkspaceMatrix(report);
            passed = true;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            report.add("error=" + encodeValue(
                failure.getClass().getName() + ": " + String.valueOf(failure.getMessage())
            ));
        }
        report.add("modelMutation=NONE");
        report.add("fixtureUnchanged=runner-enforced");
        report.add("cleanup=runner-enforced");
        report.add("durationMillis=" + ((System.nanoTime() - startedNanos) / 1_000_000L));
        report.add("status=" + (passed ? "PASS" : "FAIL"));

        final Path result = validationStateDir().resolve(WORKSPACE_RESULT_FILE);
        boolean published = false;
        try {
            writeAtomic(result, String.join("\n", report) + "\n");
            published = true;
            context.logger().info(
                "WORKSPACE_HOST_VALIDATION_RESULT status=" + (passed ? "PASS" : "FAIL")
                    + " result=" + result
            );
        } catch (Exception failure) {
            context.logger().error("WORKSPACE_HOST_VALIDATION_RESULT status=FAIL", failure);
        }
        if (published && Boolean.getBoolean(EXIT_ON_COMPLETE_PROPERTY)) {
            requestAutomatedHostClose();
        }
    }

    private void runWorkspaceMatrix(final List<String> report) throws Exception {
        final Path stateDir = validationStateDir();
        final WorkspaceService workspace = context.workspace();
        final CubismFacade cubism = context.cubism();
        String originalId = null;
        boolean connected = false;
        try {
            final String baselineAgent = awaitAgentState(stateDir, "DISCONNECTED", AUTOMATIC_TIMEOUT);
            require(report, "agent.baselineDisconnected", hasLine(baselineAgent, "status=DISCONNECTED"),
                "agent status was not DISCONNECTED");
            require(report, "host.active", hasLine(baselineAgent, "hostState=ACTIVE"),
                "validation agent did not admit host=ACTIVE");
            final ProviderCounts baselineCounts = awaitProviderCounts(
                stateDir, counts -> counts.allZero(), AUTOMATIC_TIMEOUT
            );
            reportCounts(report, "agent.baselineCounts", baselineCounts);
            awaitFacadeReadiness(cubism, AUTOMATIC_TIMEOUT);

            final CommandResult baselineStatus = executeCommand(
                workspace, cubism, new Command(1, "status", Optional.empty()), declaredPermissions
            );
            final CommandResult baselineCurrent = executeCommand(
                workspace, cubism, new Command(2, "current", Optional.empty()), declaredPermissions
            );
            final CommandResult baselineReadiness = executeCommand(
                workspace, cubism, new Command(3, "readiness", Optional.empty()), declaredPermissions
            );
            recordCommand(report, "baseline.status", baselineStatus);
            recordCommand(report, "baseline.current", baselineCurrent);
            recordCommand(report, "baseline.readiness", baselineReadiness);
            requireCommandLine(report, "baseline.status", baselineStatus,
                "status.availability=UNAVAILABLE");
            requireCommandLine(report, "baseline.status", baselineStatus,
                "status.diagnosticCode=workspace.provider.unavailable");
            requireCommandLine(report, "baseline.current", baselineCurrent,
                "status.availability=UNAVAILABLE");
            requireCommandLine(report, "baseline.current", baselineCurrent,
                "status.diagnosticCode=workspace.provider.unavailable");
            requireCommandLine(report, "baseline.readiness", baselineReadiness, "hostPresent=true");
            require(report, "baseline.typedUnavailable", "OK".equals(baselineStatus.status())
                    && "OK".equals(baselineCurrent.status()),
                baselineStatus.status() + "/" + baselineCurrent.status());
            require(report, "baseline.countsFrozen", baselineCounts.allZero(), baselineCounts.toString());

            writeMarker(stateDir, CONNECT_MARKER);
            final String connectedAgent = awaitAgentState(stateDir, "CONNECTED", AUTOMATIC_TIMEOUT);
            connected = true;
            require(report, "agent.connected", hasLine(connectedAgent, "status=CONNECTED"),
                "agent did not reach CONNECTED");

            final WorkspaceStatus initial = awaitCurrent(workspace);
            recordWorkspaceStatus(report, "active.workspace", initial);
            require(report, "workspace.available",
                initial.availability() == WorkspaceStatus.Availability.AVAILABLE,
                initial.availability().toString());
            final WorkspaceInfo original = initial.current().orElse(null);
            require(report, "workspace.current", original != null, "current workspace is absent");
            originalId = original.id().value();
            final String expectedOriginalId = originalId;
            final Optional<WorkspaceInfo> alternateValue = initial.available().stream()
                .filter(info -> !info.id().value().equals(expectedOriginalId))
                .findFirst();
            require(report, "workspace.alternate", alternateValue.isPresent(),
                "no existing alternate workspace was returned");
            final WorkspaceInfo alternate = alternateValue.orElseThrow();
            final String alternateId = alternate.id().value();
            require(report, "workspace.availableList", initial.available().size() > 1,
                "available workspace list has fewer than two entries");

            recordActiveSnapshot(report, cubism, initial);

            final WorkspaceOperationResult changed = switchTo(workspace, alternate.id());
            recordOperation(report, "switch.alternate", changed);
            requireOperation(report, "switch.alternate", changed,
                WorkspaceOperationResult.Outcome.CHANGED, alternateId);
            final WorkspaceOperationResult restored = switchTo(workspace, original.id());
            recordOperation(report, "switch.restore", restored);
            requireOperation(report, "switch.restore", restored,
                WorkspaceOperationResult.Outcome.CHANGED, originalId);
            final WorkspaceOperationResult self = switchTo(workspace, original.id());
            recordOperation(report, "switch.self", self);
            requireOperation(report, "switch.self", self,
                WorkspaceOperationResult.Outcome.NO_CHANGE, originalId);
            final WorkspaceId absentId = new WorkspaceId("__turboism-validation-absent__");
            require(report, "switch.absentId", initial.available().stream()
                    .noneMatch(info -> info.id().equals(absentId)), absentId.value());
            final WorkspaceOperationResult absent = switchTo(workspace, absentId);
            recordOperation(report, "switch.absent", absent);
            requireOperation(report, "switch.absent", absent,
                WorkspaceOperationResult.Outcome.NOT_FOUND, originalId);

            final WorkspaceOperationResult defaultAlternate = switchTo(workspace, alternate.id());
            requireOperation(report, "updateDefault.prepare", defaultAlternate,
                WorkspaceOperationResult.Outcome.CHANGED, alternateId);
            final WorkspaceOperationResult updatedAlternate = updateDefault(workspace);
            recordOperation(report, "updateDefault.alternate", updatedAlternate);
            requireOperation(report, "updateDefault.alternate", updatedAlternate,
                WorkspaceOperationResult.Outcome.CHANGED, alternateId);
            final WorkspaceOperationResult awayFromAlternate = switchTo(workspace, original.id());
            requireOperation(report, "updateDefault.away", awayFromAlternate,
                WorkspaceOperationResult.Outcome.CHANGED, originalId);
            final WorkspaceOperationResult backToAlternate = switchTo(workspace, alternate.id());
            requireOperation(report, "updateDefault.restore", backToAlternate,
                WorkspaceOperationResult.Outcome.CHANGED, alternateId);
            report.add("updateDefault.restoredId=" + encodeValue(alternateId));

            final WorkspaceOperationResult resetAway = switchTo(workspace, original.id());
            requireOperation(report, "resetDefault.away", resetAway,
                WorkspaceOperationResult.Outcome.CHANGED, originalId);
            final WorkspaceOperationResult resetBack = switchTo(workspace, alternate.id());
            requireOperation(report, "resetDefault.prepare", resetBack,
                WorkspaceOperationResult.Outcome.CHANGED, alternateId);
            final WorkspaceOperationResult resetAlternate = resetDefault(workspace);
            recordOperation(report, "resetDefault.restore", resetAlternate);
            requireOperation(report, "resetDefault.restore", resetAlternate,
                WorkspaceOperationResult.Outcome.CHANGED, alternateId);
            report.add("resetDefault.restoredId=" + encodeValue(alternateId));

            final WorkspaceOperationResult finalOriginal = switchTo(workspace, original.id());
            recordOperation(report, "cleanup.prepare", finalOriginal);
            requireOperation(report, "cleanup.prepare", finalOriginal,
                WorkspaceOperationResult.Outcome.CHANGED, originalId);

            final ProviderCounts connectedCounts = awaitProviderCounts(
                stateDir,
                counts -> counts.read() > baselineCounts.read()
                    && counts.switchCalls() >= 10
                    && counts.updateCalls() >= 1
                    && counts.resetCalls() >= 1,
                AUTOMATIC_TIMEOUT
            );
            reportCounts(report, "agent.connectedCounts", connectedCounts);
            require(report, "agent.edt", connectedCounts.onEdtCalls() > 0
                    && connectedCounts.offEdtCalls() == 0
                    && connectedCounts.lastOnEdt(), connectedCounts.toString());

            writeMarker(stateDir, DISCONNECT_MARKER);
            connected = false;
            final String disconnectedAgent = awaitAgentState(
                stateDir, "DISCONNECTED", AUTOMATIC_TIMEOUT
            );
            require(report, "agent.disconnected", hasLine(disconnectedAgent, "status=DISCONNECTED"),
                "agent did not reach DISCONNECTED");
            final ProviderCounts fenceBefore = awaitProviderCounts(
                stateDir, counts -> counts.sameCalls(connectedCounts), AUTOMATIC_TIMEOUT
            );
            final CommandResult staleStatus = executeCommand(
                workspace, cubism, new Command(101, "status", Optional.empty()), declaredPermissions
            );
            final CommandResult staleSwitch = executeCommand(
                workspace, cubism, new Command(102, "switch", Optional.of(originalId)), declaredPermissions
            );
            final CommandResult staleUpdate = executeCommand(
                workspace, cubism, new Command(103, "update-default", Optional.empty()), declaredPermissions
            );
            final CommandResult staleReset = executeCommand(
                workspace, cubism, new Command(104, "reset-default", Optional.empty()), declaredPermissions
            );
            recordCommand(report, "disconnect.status", staleStatus);
            recordCommand(report, "disconnect.switch", staleSwitch);
            recordCommand(report, "disconnect.updateDefault", staleUpdate);
            recordCommand(report, "disconnect.resetDefault", staleReset);
            requireCommandLine(report, "disconnect.status", staleStatus,
                "status.availability=UNAVAILABLE");
            requireCommandLine(report, "disconnect.status", staleStatus,
                "status.diagnosticCode=workspace.provider.unavailable");
            requireCommandLine(report, "disconnect.switch", staleSwitch, "outcome=UNAVAILABLE");
            requireCommandLine(report, "disconnect.switch", staleSwitch,
                "result.diagnosticCode=workspace.provider.unavailable");
            requireCommandLine(report, "disconnect.updateDefault", staleUpdate, "outcome=UNAVAILABLE");
            requireCommandLine(report, "disconnect.updateDefault", staleUpdate,
                "result.diagnosticCode=workspace.provider.unavailable");
            requireCommandLine(report, "disconnect.resetDefault", staleReset, "outcome=UNAVAILABLE");
            requireCommandLine(report, "disconnect.resetDefault", staleReset,
                "result.diagnosticCode=workspace.provider.unavailable");
            require(report, "disconnect.typedUnavailable", "OK".equals(staleStatus.status())
                    && "OK".equals(staleSwitch.status()) && "OK".equals(staleUpdate.status())
                    && "OK".equals(staleReset.status()),
                staleStatus.status() + "/" + staleSwitch.status() + "/"
                    + staleUpdate.status() + "/" + staleReset.status());
            final ProviderCounts fenceAfter = awaitProviderCounts(
                stateDir, counts -> counts.sameCalls(fenceBefore), AUTOMATIC_TIMEOUT
            );
            reportCounts(report, "agent.disconnectFence", fenceAfter);
            require(report, "disconnect.countsFrozen", fenceAfter.sameCalls(fenceBefore),
                fenceAfter.toString());

            writeMarker(stateDir, CONNECT_MARKER);
            final String reconnectedAgent = awaitAgentState(
                stateDir, "CONNECTED", AUTOMATIC_TIMEOUT
            );
            connected = true;
            final WorkspaceStatus resumed = awaitCurrent(workspace);
            recordWorkspaceStatus(report, "reconnect.workspace", resumed);
            require(report, "reconnect.available",
                resumed.availability() == WorkspaceStatus.Availability.AVAILABLE
                    && resumed.current().map(info -> info.id().value().equals(expectedOriginalId)).orElse(false),
                resumed.toString());
            final ProviderCounts resumedCounts = awaitProviderCounts(
                stateDir, counts -> counts.read() > fenceAfter.read(), AUTOMATIC_TIMEOUT
            );
            reportCounts(report, "agent.reconnectedCounts", resumedCounts);
            require(report, "reconnect.countsResume", resumedCounts.read() > fenceAfter.read(),
                resumedCounts.toString());
            require(report, "agent.reconnected", hasLine(reconnectedAgent, "status=CONNECTED"),
                "agent did not reconnect");
            require(report, "model.noMutation", true,
                "workspace SDK operations do not write model state");
        } finally {
            if (originalId != null) {
                try {
                    if (!connected) {
                        writeMarker(stateDir, CONNECT_MARKER);
                        awaitAgentState(stateDir, "CONNECTED", AUTOMATIC_TIMEOUT);
                        connected = true;
                    }
                    final WorkspaceOperationResult cleanup = switchTo(
                        workspace, new WorkspaceId(originalId)
                    );
                    final String cleanupId = cleanup.status().current()
                        .map(WorkspaceInfo::id)
                        .map(WorkspaceId::value)
                        .orElse("absent");
                    report.add("cleanup.restoreOutcome=" + cleanup.outcome());
                    report.add("cleanup.restoredId=" + encodeValue(cleanupId));
                    require(report, "cleanup.restore", cleanupId.equals(originalId)
                            && cleanup.outcome() != WorkspaceOperationResult.Outcome.FAILED
                            && cleanup.outcome() != WorkspaceOperationResult.Outcome.UNAVAILABLE,
                        cleanup.outcome() + "/" + cleanupId);
                } catch (Exception cleanupFailure) {
                    report.add("assertion.cleanup.restore.status=FAIL");
                    report.add("cleanup.error=" + encodeValue(
                        cleanupFailure.getClass().getName() + ": " + cleanupFailure.getMessage()
                    ));
                    throw new IllegalStateException("workspace cleanup failed", cleanupFailure);
                }
            }
        }
    }

    private static WorkspaceStatus awaitCurrent(final WorkspaceService workspace) throws Exception {
        return workspace.current().toCompletableFuture().get(
            SDK_CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS
        );
    }

    private static void awaitFacadeReadiness(
        final CubismFacade cubism,
        final Duration timeout
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cubism.isHostPresent()
                    && cubism.activeProject().isPresent()
                    && cubism.activeDocument().isPresent()
                    && cubism.activeModel().isPresent()) {
                return;
            }
            Thread.sleep(250L);
        }
        throw new IllegalStateException("Cubism facade did not become ready before timeout");
    }

    private static WorkspaceOperationResult switchTo(
        final WorkspaceService workspace,
        final WorkspaceId id
    ) throws Exception {
        return workspace.switchTo(id).toCompletableFuture().get(
            SDK_CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS
        );
    }

    private static WorkspaceOperationResult updateDefault(final WorkspaceService workspace)
        throws Exception {
        return workspace.updateDefault().toCompletableFuture().get(
            SDK_CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS
        );
    }

    private static WorkspaceOperationResult resetDefault(final WorkspaceService workspace)
        throws Exception {
        return workspace.resetToDefault().toCompletableFuture().get(
            SDK_CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS
        );
    }

    private static void recordActiveSnapshot(
        final List<String> report,
        final CubismFacade cubism,
        final WorkspaceStatus workspace
    ) {
        final boolean hostPresent = cubism.isHostPresent();
        final Optional<ProjectSnapshot> project = cubism.activeProject();
        final Optional<DocumentSnapshot> document = cubism.activeDocument();
        final Optional<ModelSnapshot> model = cubism.activeModel();
        report.add("active.hostPresent=" + hostPresent);
        report.add("active.projectPresent=" + project.isPresent());
        report.add("active.documentPresent=" + document.isPresent());
        report.add("active.modelPresent=" + model.isPresent());
        project.ifPresent(value -> {
            report.add("active.projectId=" + encodeValue(value.projectId()));
            report.add("active.projectName=" + encodeValue(value.name()));
            report.add("active.projectDocumentCount=" + value.documents().size());
        });
        document.ifPresent(value -> {
            report.add("active.documentId=" + encodeValue(value.documentId()));
            report.add("active.documentName=" + encodeValue(value.name()));
            report.add("active.documentRelativePath=" + encodeValue(value.relativePath()));
        });
        model.ifPresent(value -> {
            report.add("active.modelId=" + encodeValue(value.modelId()));
            report.add("active.modelName=" + encodeValue(value.name()));
        });
        require(report, "active.host", hostPresent, "hostPresent=false");
        require(report, "active.project", project.isPresent(), "active project is absent");
        require(report, "active.document", document.isPresent(), "active document is absent");
        require(report, "active.model", model.isPresent(), "active model is absent");
        require(report, "active.workspace", workspace.availability()
                == WorkspaceStatus.Availability.AVAILABLE && workspace.current().isPresent(),
            workspace.availability().toString());
    }

    private static void recordWorkspaceStatus(
        final List<String> report,
        final String prefix,
        final WorkspaceStatus status
    ) {
        report.add(prefix + ".availability=" + status.availability());
        status.current().ifPresent(current -> {
            report.add(prefix + ".currentId=" + encodeValue(current.id().value()));
            report.add(prefix + ".currentName=" + encodeValue(current.displayName()));
        });
        report.add(prefix + ".availableCount=" + status.available().size());
        for (int index = 0; index < status.available().size(); index++) {
            final WorkspaceInfo info = status.available().get(index);
            report.add(prefix + ".available." + index + ".id=" + encodeValue(info.id().value()));
            report.add(prefix + ".available." + index + ".name=" + encodeValue(info.displayName()));
        }
        status.diagnosticCode().ifPresent(code -> report.add(prefix + ".diagnosticCode="
            + sanitizeText(code)));
    }

    private static void recordOperation(
        final List<String> report,
        final String prefix,
        final WorkspaceOperationResult result
    ) {
        report.add(prefix + ".outcome=" + result.outcome());
        result.diagnosticCode().ifPresent(code -> report.add(prefix + ".diagnosticCode="
            + sanitizeText(code)));
        result.status().current().ifPresent(current -> report.add(prefix + ".currentId="
            + encodeValue(current.id().value())));
        report.add(prefix + ".availability=" + result.status().availability());
    }

    private static void requireOperation(
        final List<String> report,
        final String name,
        final WorkspaceOperationResult result,
        final WorkspaceOperationResult.Outcome expectedOutcome,
        final String expectedCurrentId
    ) {
        final String actualCurrentId = result.status().current()
            .map(WorkspaceInfo::id)
            .map(WorkspaceId::value)
            .orElse("absent");
        require(report, name + ".outcome", result.outcome() == expectedOutcome,
            result.outcome().toString());
        require(report, name + ".current", actualCurrentId.equals(expectedCurrentId),
            actualCurrentId);
    }

    private static void recordCommand(
        final List<String> report,
        final String prefix,
        final CommandResult result
    ) {
        report.add(prefix + ".status=" + result.status());
        for (int index = 0; index < result.lines().size(); index++) {
            report.add(prefix + ".line." + index + "=" + result.lines().get(index));
        }
    }

    private static void requireCommandLine(
        final List<String> report,
        final String name,
        final CommandResult result,
        final String expected
    ) {
        require(report, name + ".command", "OK".equals(result.status()), result.status());
        require(report, name + ".evidence", result.lines().contains(expected),
            expected + " not found");
    }

    private static void require(
        final List<String> report,
        final String name,
        final boolean condition,
        final String detail
    ) {
        report.add("assertion." + name + ".status=" + (condition ? "PASS" : "FAIL"));
        report.add("assertion." + name + ".detail=" + encodeValue(detail));
        if (!condition) {
            throw new IllegalStateException(name + ": " + sanitizeText(detail));
        }
    }

    private static String awaitAgentState(
        final Path stateDir,
        final String expected,
        final Duration timeout
    ) throws Exception {
        final Path evidence = stateDir.resolve(VALIDATION_AGENT_FILE);
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(evidence)) {
                final String text = Files.readString(evidence, StandardCharsets.UTF_8);
                if (hasLine(text, "status=FAILED")) {
                    throw new IllegalStateException("validation agent failed");
                }
                if (hasLine(text, "status=" + expected)) {
                    return text;
                }
            }
            Thread.sleep(250L);
        }
        throw new IllegalStateException("validation agent did not reach " + expected);
    }

    private static ProviderCounts awaitProviderCounts(
        final Path stateDir,
        final Predicate<ProviderCounts> expected,
        final Duration timeout
    ) throws Exception {
        final Path evidence = stateDir.resolve(VALIDATION_AGENT_FILE);
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(evidence)) {
                final String text = Files.readString(evidence, StandardCharsets.UTF_8);
                if (hasLine(text, "status=FAILED")) {
                    throw new IllegalStateException("validation agent failed");
                }
                try {
                    final ProviderCounts counts = parseProviderCounts(text);
                    if (expected.test(counts)) {
                        return counts;
                    }
                } catch (IllegalArgumentException ignored) {
                    // The agent publishes its state atomically; wait for its first counts detail.
                }
            }
            Thread.sleep(250L);
        }
        throw new IllegalStateException("validation agent call-count evidence timed out");
    }

    private static ProviderCounts parseProviderCounts(final String evidence) {
        final String line = evidence.lines()
            .filter(value -> value.contains("counts="))
            .reduce((left, right) -> right)
            .orElseThrow(() -> new IllegalArgumentException("counts are absent"));
        final String counts = line.substring(line.lastIndexOf("counts=") + "counts=".length());
        long read = -1L;
        long switchCalls = -1L;
        long updateCalls = -1L;
        long resetCalls = -1L;
        long onEdtCalls = -1L;
        long offEdtCalls = -1L;
        String lastThread = "";
        boolean lastOnEdt = false;
        for (final String token : counts.strip().split("\\s+")) {
            final int separator = token.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            final String key = token.substring(0, separator);
            final String value = token.substring(separator + 1);
            switch (key) {
                case "read" -> read = Long.parseLong(value);
                case "switch" -> switchCalls = Long.parseLong(value);
                case "update" -> updateCalls = Long.parseLong(value);
                case "reset" -> resetCalls = Long.parseLong(value);
                case "onEdt" -> onEdtCalls = Long.parseLong(value);
                case "offEdt" -> offEdtCalls = Long.parseLong(value);
                case "lastThread" -> lastThread = value;
                case "lastOnEdt" -> lastOnEdt = Boolean.parseBoolean(value);
                default -> { }
            }
        }
        if (read < 0 || switchCalls < 0 || updateCalls < 0 || resetCalls < 0
            || onEdtCalls < 0 || offEdtCalls < 0) {
            throw new IllegalArgumentException("incomplete provider counts");
        }
        return new ProviderCounts(
            read, switchCalls, updateCalls, resetCalls, onEdtCalls, offEdtCalls,
            lastThread, lastOnEdt
        );
    }

    private static boolean hasLine(final String text, final String expected) {
        return text.lines().anyMatch(expected::equals);
    }

    private static void reportCounts(
        final List<String> report,
        final String prefix,
        final ProviderCounts counts
    ) {
        report.add(prefix + ".read=" + counts.read());
        report.add(prefix + ".switch=" + counts.switchCalls());
        report.add(prefix + ".update=" + counts.updateCalls());
        report.add(prefix + ".reset=" + counts.resetCalls());
        report.add(prefix + ".onEdt=" + counts.onEdtCalls());
        report.add(prefix + ".offEdt=" + counts.offEdtCalls());
        report.add(prefix + ".lastThread=" + sanitizeText(counts.lastThread()));
        report.add(prefix + ".lastOnEdt=" + counts.lastOnEdt());
    }

    private static void writeMarker(final Path stateDir, final String marker) throws IOException {
        writeAtomic(stateDir.resolve(marker), "matrix\n");
    }

    private Path validationStateDir() {
        final Path pluginState = context.paths().stateDir();
        final Path rootState = pluginState.getParent();
        if (rootState == null) {
            throw new IllegalStateException("plugin state directory has no Turboism state root");
        }
        return rootState;
    }

    private void requestAutomatedHostClose() {
        try {
            final java.awt.Robot robot = new java.awt.Robot();
            robot.keyPress(java.awt.event.KeyEvent.VK_ALT);
            robot.keyPress(java.awt.event.KeyEvent.VK_F4);
            robot.keyRelease(java.awt.event.KeyEvent.VK_F4);
            robot.keyRelease(java.awt.event.KeyEvent.VK_ALT);
        } catch (Exception failure) {
            context.logger().error("Automated workspace host close shortcut failed", failure);
        }
    }

    private record ProviderCounts(
        long read,
        long switchCalls,
        long updateCalls,
        long resetCalls,
        long onEdtCalls,
        long offEdtCalls,
        String lastThread,
        boolean lastOnEdt
    ) {
        boolean allZero() {
            return read == 0 && switchCalls == 0 && updateCalls == 0 && resetCalls == 0
                && onEdtCalls == 0 && offEdtCalls == 0;
        }

        boolean sameCalls(final ProviderCounts other) {
            return read == other.read && switchCalls == other.switchCalls
                && updateCalls == other.updateCalls && resetCalls == other.resetCalls
                && onEdtCalls == other.onEdtCalls && offEdtCalls == other.offEdtCalls;
        }
    }

    /**
     * Executes every pending command file under a strict, crash-safe protocol:
     *
     * <ul>
     *   <li>An unresolved {@code inflight/} claim fails the whole scan closed
     *       (INFLIGHT_UNRESOLVED) — an uncertain mutation is never auto-retried.</li>
     *   <li>The durable watermark must be readable, must not regress below the in-memory value,
     *       and must not be lower than any accepted result sequence; accepted results without a
     *       watermark file also fail closed.</li>
     *   <li>Each accepted command is parsed, then claimed by an atomic move into
     *       {@code inflight/} before any SDK call. The result and the watermark are published
     *       (same-directory temp + atomic move) and only then is the claim removed. A crash
     *       between these steps leaves the claim, blocking the protocol until human repair.</li>
     * </ul>
     *
     * Returns the new durable watermark, or {@code -1} when the scan failed closed.
     */
    static long processPending(
        final Path root,
        final long lastProcessed,
        final WorkspaceService workspace,
        final CubismFacade cubism,
        final List<String> declaredPermissions
    ) throws IOException {
        final Path commands = root.resolve(COMMANDS_DIR);
        final Path results = root.resolve(RESULTS_DIR);
        final Path rejected = root.resolve(REJECTED_DIR);
        final Path inflight = root.resolve(INFLIGHT_DIR);
        Files.createDirectories(commands);
        Files.createDirectories(results);
        Files.createDirectories(rejected);
        Files.createDirectories(inflight);

        final List<Path> unresolvedClaims = listFiles(inflight, ".cmd");
        if (!unresolvedClaims.isEmpty()) {
            appendEvidence(root, "inflight", "status=INFLIGHT_UNRESOLVED claims="
                + joinNames(unresolvedClaims));
            return -1L;
        }

        final Watermark watermark;
        try {
            watermark = readWatermark(root, lastProcessed);
        } catch (ProtocolStateException failure) {
            appendEvidence(root, "watermark", "status=WATERMARK_INVALID "
                + sanitizeText(failure.getMessage()));
            return -1L;
        }
        if (watermark.value() < lastProcessed) {
            appendEvidence(root, "watermark",
                "status=WATERMARK_REGRESSED disk=" + watermark.value()
                    + " memory=" + lastProcessed);
            return -1L;
        }
        final List<Matcher> acceptedResultMatchers = listFiles(results, ".txt").stream()
            .map(path -> ACCEPTED_RESULT_FILE.matcher(path.getFileName().toString()))
            .filter(Matcher::matches)
            .toList();
        if (!watermark.filePresent() && !acceptedResultMatchers.isEmpty()) {
            appendEvidence(root, "watermark",
                "status=RESULTS_WITHOUT_WATERMARK acceptedResults=" + acceptedResultMatchers.size());
            return -1L;
        }
        final long maxAcceptedResultSequence = acceptedResultMatchers.stream()
            .mapToLong(matcher -> Long.parseLong(matcher.group(1)))
            .max()
            .orElse(0L);
        if (maxAcceptedResultSequence > watermark.value()) {
            appendEvidence(root, "watermark",
                "status=ACCEPTED_RESULTS_ABOVE_WATERMARK watermark=" + watermark.value()
                    + " maxResult=" + maxAcceptedResultSequence);
            return -1L;
        }

        final List<PendingFile> pending = new ArrayList<>();
        final List<Path> malformed = new ArrayList<>();
        for (final Path path : listFiles(commands, ".cmd")) {
            try {
                pending.add(PendingFile.parse(path));
            } catch (IllegalArgumentException ignored) {
                malformed.add(path);
            }
        }
        for (final Path path : malformed) {
            final Path target = rejected.resolve(path.getFileName() + ".malformed");
            Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
            appendEvidence(root, path.getFileName().toString(), "status=REJECTED reason=malformed-file-name");
        }
        pending.sort(Comparator.comparingLong(PendingFile::sequence));

        long next = watermark.value();
        int processed = 0;
        for (final PendingFile file : pending) {
            if (processed >= MAX_PENDING_PER_SCAN) {
                appendEvidence(root, "scan", "error=max-pending-per-scan-reached");
                break;
            }
            if (file.sequence() <= next) {
                final CommandResult duplicate = new CommandResult(
                    "DUPLICATE",
                    List.of(
                        "sequence=" + formatSequence(file.sequence()),
                        "command=" + file.name(),
                        "reason=sequence-not-greater-than-durable-watermark"
                    )
                );
                writeResultVariant(results, file, duplicate, ".duplicate");
                appendEvidence(root, file.name(), "status=DUPLICATE");
                Files.deleteIfExists(file.path());
                processed++;
                continue;
            }
            if (file.path().toFile().length() > MAX_COMMAND_BYTES) {
                moveToRejected(root, file, "oversized");
                appendEvidence(root, file.name(), "status=REJECTED reason=oversized");
                processed++;
                continue;
            }
            final Command command;
            try {
                final String content = Files.readString(file.path(), StandardCharsets.UTF_8);
                command = parseCommandContent(file.sequence(), file.name(), content);
            } catch (ProtocolException rejection) {
                appendEvidence(root, file.name(), "status=REJECTED reason="
                    + sanitizeText(rejection.getMessage()));
                moveToRejected(root, file, "malformed");
                processed++;
                continue;
            }
            // Claim before any SDK call: the parsed command moves out of commands/ atomically.
            // The claim is removed only after result and watermark publication, so a crash at
            // any point leaves an INFLIGHT_UNRESOLVED claim instead of re-executing the command.
            final Path claimed = inflight.resolve(file.path().getFileName());
            try {
                Files.move(file.path(), claimed, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Plain rename without replace: never clobber an existing claim.
                Files.move(file.path(), claimed);
            }
            final CommandResult result = executeCommand(workspace, cubism, command, declaredPermissions);
            next = command.sequence();
            writeResultAtomic(results, file, result);
            writeWatermarkAtomic(root, next);
            Files.deleteIfExists(claimed);
            appendEvidence(root, file.name(), "status=" + result.status());
            processed++;
        }
        return next;
    }

    /** Parses command file content: one operation line, plus one argument line for {@code switch}. */
    static Command parseCommandContent(
        final long sequence,
        final String name,
        final String content
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(content, "content");
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_COMMAND_BYTES) {
            throw new ProtocolException("command content exceeds " + MAX_COMMAND_BYTES + " bytes");
        }
        final String[] lines = content.split("\\r?\\n");
        if (lines.length == 0 || lines.length > 2) {
            throw new ProtocolException("command content must be one or two lines");
        }
        final String operation = lines[0].strip();
        if (operation.isBlank()) {
            throw new ProtocolException("command operation is blank");
        }
        final Optional<String> argument;
        switch (operation) {
            case "status", "current", "readiness", "update-default", "reset-default" -> {
                if (lines.length == 2 && !lines[1].isBlank()) {
                    throw new ProtocolException(operation + " does not accept an argument");
                }
                argument = Optional.empty();
            }
            case "switch" -> {
                if (lines.length != 2) {
                    throw new ProtocolException("switch requires an opaque workspace id argument");
                }
                final String id = lines[1].strip();
                if (id.isBlank()) {
                    throw new ProtocolException("switch argument must not be blank");
                }
                if (id.getBytes(StandardCharsets.UTF_8).length > MAX_ENCODED_VALUE_BYTES) {
                    throw new ProtocolException(
                        "switch argument exceeds " + MAX_ENCODED_VALUE_BYTES + " UTF-8 bytes"
                    );
                }
                argument = Optional.of(id);
            }
            default -> throw new ProtocolException("unknown operation: " + operation);
        }
        return new Command(sequence, operation, argument);
    }

    /**
     * Executes one command against the SDK {@link WorkspaceService} and formats typed,
     * machine-readable evidence. Host-derived ids/names are Base64-encoded with a byte ceiling;
     * free text is sanitized. Permission denial and unexpected failures are recorded without
     * host access; typed UNAVAILABLE results are recorded as OK evidence, not errors.
     */
    static CommandResult executeCommand(
        final WorkspaceService workspace,
        final CubismFacade cubism,
        final Command command,
        final List<String> declaredPermissions
    ) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(cubism, "cubism");
        Objects.requireNonNull(command, "command");
        final String thread = Thread.currentThread().getName();
        final boolean edt = SwingUtilities.isEventDispatchThread();
        final String permissions = String.join(",", declaredPermissions);
        final List<String> lines = new ArrayList<>();
        lines.add("sequence=" + formatSequence(command.sequence()));
        lines.add("command=" + command.name());
        command.argument().ifPresent(id -> lines.add("argument=" + encodeValue(id)));
        lines.add("thread=" + sanitizeText(thread));
        lines.add("edt=" + edt);
        lines.add("declaredPermissions=" + permissions);
        try {
            final WorkspaceStatus status = currentOrStatus(workspace, command);
            if (status != null) {
                statusLines("status", status, lines);
                return new CommandResult("OK", lines);
            }
            if (command.name().equals("readiness")) {
                readinessLines(cubism, lines);
                return new CommandResult("OK", lines);
            }
            final WorkspaceOperationResult result = switch (command.name()) {
                case "switch" -> workspace.switchTo(new WorkspaceId(command.argument().orElseThrow()))
                    .toCompletableFuture().join();
                case "update-default" -> workspace.updateDefault().toCompletableFuture().join();
                case "reset-default" -> workspace.resetToDefault().toCompletableFuture().join();
                default -> throw new ProtocolException("unknown operation: " + command.name());
            };
            lines.add("outcome=" + result.outcome());
            result.diagnosticCode().ifPresent(code -> lines.add("result.diagnosticCode="
                + sanitizeText(code)));
            statusLines("result", result.status(), lines);
            return new CommandResult("OK", lines);
        } catch (CubismPermissionException denial) {
            lines.add("status=DENIED");
            lines.add("permissionDenied=true");
            lines.add("denial=" + sanitizeText(denial.getMessage()));
            return new CommandResult("DENIED", lines);
        } catch (ProtocolException | IllegalArgumentException rejection) {
            lines.add("status=REJECTED");
            lines.add("reason=" + sanitizeText(rejection.getMessage()));
            return new CommandResult("REJECTED", lines);
        } catch (RuntimeException failure) {
            lines.add("status=ERROR");
            lines.add("error=" + sanitizeText(
                failure.getClass().getName() + ": " + failure.getMessage()));
            return new CommandResult("ERROR", lines);
        }
    }

    private static WorkspaceStatus currentOrStatus(
        final WorkspaceService workspace,
        final Command command
    ) {
        return switch (command.name()) {
            case "status", "current" -> workspace.current().toCompletableFuture().join();
            default -> null;
        };
    }

    /**
     * Bounded SDK-only readiness snapshot through the facade snapshots: host present, active
     * document identity, and active model identity. Unexpected host failures surface as command
     * ERROR evidence; they are never masked as "unavailable".
     */
    private static void readinessLines(final CubismFacade cubism, final List<String> lines) {
        lines.add("hostPresent=" + cubism.isHostPresent());
        final Optional<DocumentSnapshot> document = cubism.activeDocument();
        if (document.isPresent()) {
            final DocumentSnapshot snapshot = document.orElseThrow();
            lines.add("documentId=" + encodeValue(snapshot.documentId()));
            lines.add("documentName=" + encodeValue(snapshot.name()));
            lines.add("modelPresent=" + snapshot.model().isPresent());
            snapshot.model().ifPresent(model ->
                lines.add("modelId=" + encodeValue(model.modelId())));
        } else {
            lines.add("documentId=absent");
        }
        final Optional<ModelSnapshot> model = cubism.activeModel();
        lines.add("activeModelPresent=" + model.isPresent());
        model.ifPresent(snapshot -> {
            lines.add("activeModelId=" + encodeValue(snapshot.modelId()));
            lines.add("activeModelName=" + encodeValue(snapshot.name()));
        });
    }

    private static void statusLines(
        final String prefix,
        final WorkspaceStatus status,
        final List<String> lines
    ) {
        lines.add(prefix + ".availability=" + status.availability());
        status.current().ifPresent(current -> {
            lines.add(prefix + ".currentId=" + encodeValue(current.id().value()));
            lines.add(prefix + ".currentName=" + encodeValue(current.displayName()));
        });
        lines.add(prefix + ".availableCount=" + status.available().size());
        for (int index = 0; index < status.available().size(); index++) {
            final WorkspaceInfo info = status.available().get(index);
            lines.add(prefix + ".available." + index + ".id=" + encodeValue(info.id().value()));
            lines.add(prefix + ".available." + index + ".name=" + encodeValue(info.displayName()));
        }
        status.diagnosticCode().ifPresent(code -> lines.add(prefix + ".diagnosticCode="
            + sanitizeText(code)));
    }

    /** Reversible Base64 (UTF-8) encoding of host-derived identity values, with a byte ceiling. */
    static String encodeValue(final String value) {
        Objects.requireNonNull(value, "value");
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_ENCODED_VALUE_BYTES) {
            return REDACTED_VALUE;
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Single-line printable sanitization for free text; never emits newlines or raw controls. */
    static String sanitizeText(final String value) {
        final String normalized = (value == null ? "" : value)
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replaceAll("[\\p{Cntrl}]", " ")
            .replaceAll(" {2,}", " ")
            .strip();
        if (normalized.getBytes(StandardCharsets.UTF_8).length <= MAX_TEXT_LINE_BYTES) {
            return normalized;
        }
        return REDACTED_VALUE;
    }

    private static void writeResultAtomic(
        final Path results,
        final PendingFile file,
        final CommandResult result
    ) throws IOException {
        final Path target = results.resolve(
            formatSequence(file.sequence()) + "-" + file.name() + ".txt"
        );
        writeAtomic(target, resultText(result));
    }

    private static void writeResultVariant(
        final Path results,
        final PendingFile file,
        final CommandResult result,
        final String suffix
    ) throws IOException {
        final Path target = results.resolve(
            formatSequence(file.sequence()) + "-" + file.name() + suffix + ".txt"
        );
        writeAtomic(target, resultText(result));
    }

    private static String resultText(final CommandResult result) {
        final StringBuilder text = new StringBuilder();
        text.append("status=").append(result.status()).append('\n');
        for (final String line : result.lines()) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    private static void moveToRejected(
        final Path root,
        final PendingFile file,
        final String reason
    ) throws IOException {
        final Path target = root.resolve(REJECTED_DIR).resolve(
            file.path().getFileName() + "." + reason
        );
        Files.move(file.path(), target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void appendEvidence(final Path root, final String command, final String detail)
        throws IOException {
        final Path evidence = root.resolve(EVIDENCE_FILE);
        Files.createDirectories(evidence.getParent());
        Files.writeString(
            evidence,
            "time=" + Instant.now() + " command=" + sanitizeText(command) + " "
                + sanitizeText(detail) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    private static List<Path> listFiles(final Path directory, final String suffix) throws IOException {
        final List<Path> files = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(suffix))
                .forEach(files::add);
        }
        return files;
    }

    private static String joinNames(final List<Path> paths) {
        return paths.stream()
            .map(path -> path.getFileName().toString())
            .reduce((left, right) -> left + "," + right)
            .orElse("");
    }

    private record Watermark(long value, boolean filePresent) {
    }

    /**
     * Durable watermark: a missing file falls back to {@code lastProcessed}; a present but
     * unreadable, negative, or non-numeric file fails closed instead of resetting to zero.
     */
    private static Watermark readWatermark(final Path root, final long lastProcessed) throws IOException {
        final Path state = root.resolve(PROTOCOL_STATE_FILE);
        if (!Files.isRegularFile(state)) {
            return new Watermark(lastProcessed, false);
        }
        final String text = Files.readString(state, StandardCharsets.UTF_8).strip();
        final long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException failure) {
            throw new ProtocolStateException("protocol-state is not a number: " + text);
        }
        if (value < 0) {
            throw new ProtocolStateException("protocol-state is negative: " + value);
        }
        return new Watermark(value, true);
    }

    private static void writeWatermarkAtomic(final Path root, final long sequence) throws IOException {
        writeAtomic(
            root.resolve(PROTOCOL_STATE_FILE),
            Long.toString(sequence) + "\n"
        );
    }

    /**
     * Same-directory temp file then atomic move; requests replace semantics and falls back to
     * a plain replace move when the filesystem does not support atomic moves.
     */
    private static void writeAtomic(final Path target, final String text) throws IOException {
        Files.createDirectories(target.getParent());
        final Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporary, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String formatSequence(final long sequence) {
        return String.format(Locale.ROOT, "%06d", sequence);
    }

    private record PendingFile(long sequence, String name, Path path) {

        static PendingFile parse(final Path path) {
            final Matcher matcher = COMMAND_FILE_NAME.matcher(path.getFileName().toString());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("malformed command file name");
            }
            return new PendingFile(Long.parseLong(matcher.group(1)), matcher.group(2), path);
        }
    }

    static final class ProtocolException extends RuntimeException {
        ProtocolException(final String message) {
            super(message);
        }
    }
}
