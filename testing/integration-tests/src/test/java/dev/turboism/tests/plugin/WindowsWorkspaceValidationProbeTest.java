package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceInfo;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceService;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;
import dev.turboism.tests.plugin.WindowsWorkspaceValidationProbe.Command;
import dev.turboism.tests.plugin.WindowsWorkspaceValidationProbe.CommandResult;
import dev.turboism.tests.plugin.WindowsWorkspaceValidationProbe.ProtocolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsWorkspaceValidationProbeTest {

    private static final List<String> PERMISSIONS = List.of(
        "turboism.cubism.model.read", "turboism.cubism.project.read", "turboism.host.unsafe"
    );
    private static final CubismFacade NO_HOST = fakeCubism(false, Optional.empty(), Optional.empty());

    @Test
    void parserAcceptsEveryBoundedOperation() {
        assertEquals(new Command(1, "status", Optional.empty()),
            WindowsWorkspaceValidationProbe.parseCommandContent(1, "status", "status\n"));
        assertEquals(new Command(2, "current", Optional.empty()),
            WindowsWorkspaceValidationProbe.parseCommandContent(2, "current", "current"));
        assertEquals(new Command(3, "readiness", Optional.empty()),
            WindowsWorkspaceValidationProbe.parseCommandContent(3, "readiness", "readiness\n"));
        assertEquals(new Command(4, "update-default", Optional.empty()),
            WindowsWorkspaceValidationProbe.parseCommandContent(4, "update-default", "update-default\n"));
        assertEquals(new Command(5, "reset-default", Optional.empty()),
            WindowsWorkspaceValidationProbe.parseCommandContent(5, "reset-default", "reset-default"));
        assertEquals(new Command(6, "switch", Optional.of("modeling")),
            WindowsWorkspaceValidationProbe.parseCommandContent(6, "switch", "switch\nmodeling\n"));
    }

    @Test
    void parserRejectsUnknownMalformedAndOversizedCommands() {
        assertThrows(ProtocolException.class, () ->
            WindowsWorkspaceValidationProbe.parseCommandContent(1, "explode", "explode"));
        assertThrows(ProtocolException.class, () ->
            WindowsWorkspaceValidationProbe.parseCommandContent(1, "switch", "switch"));
        assertThrows(ProtocolException.class, () ->
            WindowsWorkspaceValidationProbe.parseCommandContent(1, "switch", "switch\n  \n"));
        assertThrows(ProtocolException.class, () ->
            WindowsWorkspaceValidationProbe.parseCommandContent(1, "status", "status\nunexpected"));
        assertThrows(ProtocolException.class, () ->
            WindowsWorkspaceValidationProbe.parseCommandContent(1, "current", "current\ncurrent\ncurrent"));
        assertThrows(ProtocolException.class, () ->
            WindowsWorkspaceValidationProbe.parseCommandContent(1, "switch",
                "switch\n" + "x".repeat(600)));
        final String oversized = "status\n" + "x".repeat(WindowsWorkspaceValidationProbe.MAX_COMMAND_BYTES);
        assertThrows(ProtocolException.class, () ->
            WindowsWorkspaceValidationProbe.parseCommandContent(1, "status", oversized));
    }

    @Test
    void commandFileNamePatternRejectsMalformedNames() {
        assertTrue(WindowsWorkspaceValidationProbe.COMMAND_FILE_NAME
            .matcher("1-status.cmd").matches());
        assertTrue(WindowsWorkspaceValidationProbe.COMMAND_FILE_NAME
            .matcher("7-switch-1.cmd").matches());
        assertFalse(WindowsWorkspaceValidationProbe.COMMAND_FILE_NAME.matcher("status.cmd").matches());
        assertFalse(WindowsWorkspaceValidationProbe.COMMAND_FILE_NAME.matcher("1-STATUS.cmd").matches());
        assertFalse(WindowsWorkspaceValidationProbe.COMMAND_FILE_NAME.matcher("1-status.txt").matches());
        assertFalse(WindowsWorkspaceValidationProbe.COMMAND_FILE_NAME.matcher("1-status.cmd.extra").matches());
        assertFalse(WindowsWorkspaceValidationProbe.COMMAND_FILE_NAME.matcher("1234567-status.cmd").matches());
        assertFalse(WindowsWorkspaceValidationProbe.COMMAND_FILE_NAME.matcher("0-status.cmd").matches());
    }

    @Test
    void executorRecordsTypedUnavailableFailClosedResults() {
        final CommandResult status = WindowsWorkspaceValidationProbe.executeCommand(
            WorkspaceService.unavailable(), NO_HOST,
            new Command(1, "status", Optional.empty()), PERMISSIONS);
        assertEquals("OK", status.status());
        assertLine(status, "status.availability=UNAVAILABLE");
        assertLine(status, "status.diagnosticCode=workspace.unavailable");
        assertLine(status, "status.availableCount=0");

        final CommandResult switched = WindowsWorkspaceValidationProbe.executeCommand(
            WorkspaceService.unavailable(), NO_HOST,
            new Command(2, "switch", Optional.of("modeling")), PERMISSIONS);
        assertEquals("OK", switched.status());
        assertLine(switched, "outcome=UNAVAILABLE");
        assertLine(switched, "result.diagnosticCode=workspace.unavailable");
    }

    @Test
    void executorRecordsPermissionDenialFailClosed() {
        final WorkspaceService denying = new WorkspaceService() {
            @Override
            public CompletionStage<WorkspaceStatus> current() {
                throw new CubismPermissionException("Missing required permission turboism.host.unsafe");
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> switchTo(final WorkspaceId workspaceId) {
                throw new CubismPermissionException("Missing required permission turboism.host.unsafe");
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> updateDefault() {
                throw new CubismPermissionException("Missing required permission turboism.host.unsafe");
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> resetToDefault() {
                throw new CubismPermissionException("Missing required permission turboism.host.unsafe");
            }
        };
        final CommandResult result = WindowsWorkspaceValidationProbe.executeCommand(
            denying, NO_HOST, new Command(1, "update-default", Optional.empty()), PERMISSIONS);
        assertEquals("DENIED", result.status());
        assertLine(result, "permissionDenied=true");
        assertTrue(result.lines().stream().anyMatch(line -> line.startsWith("denial=")));
    }

    @Test
    void executorRecordsTypedAvailableResultFieldsAndCallerThread() {
        final WorkspaceStatus available = new WorkspaceStatus(
            WorkspaceStatus.Availability.AVAILABLE,
            Optional.of(new WorkspaceInfo(new WorkspaceId("modeling"), "Modeling")),
            List.of(
                new WorkspaceInfo(new WorkspaceId("modeling"), "Modeling"),
                new WorkspaceInfo(new WorkspaceId("animation"), "Animation")
            ),
            Optional.empty()
        );
        final WorkspaceOperationResult changed = new WorkspaceOperationResult(
            WorkspaceOperationResult.Outcome.CHANGED,
            available,
            Optional.empty()
        );
        final WorkspaceService service = new WorkspaceService() {
            @Override
            public CompletionStage<WorkspaceStatus> current() {
                return CompletableFuture.completedFuture(available);
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> switchTo(final WorkspaceId workspaceId) {
                return CompletableFuture.completedFuture(changed);
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> updateDefault() {
                return CompletableFuture.completedFuture(changed);
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> resetToDefault() {
                return CompletableFuture.completedFuture(changed);
            }
        };

        final CommandResult current = WindowsWorkspaceValidationProbe.executeCommand(
            service, NO_HOST, new Command(1, "current", Optional.empty()), PERMISSIONS);
        assertEquals("OK", current.status());
        assertLine(current, "status.currentId=" + WindowsWorkspaceValidationProbe.encodeValue("modeling"));
        assertLine(current, "status.currentName=" + WindowsWorkspaceValidationProbe.encodeValue("Modeling"));
        assertLine(current, "status.availableCount=2");
        assertLine(current, "status.available.1.id=" + WindowsWorkspaceValidationProbe.encodeValue("animation"));
        assertLine(current, "status.available.1.name=" + WindowsWorkspaceValidationProbe.encodeValue("Animation"));
        assertLine(current, "edt=false");
        assertTrue(current.lines().stream().anyMatch(line -> line.startsWith("thread=")));

        final CommandResult switched = WindowsWorkspaceValidationProbe.executeCommand(
            service, NO_HOST, new Command(2, "switch", Optional.of("animation")), PERMISSIONS);
        assertEquals("OK", switched.status());
        assertLine(switched, "outcome=CHANGED");
        assertLine(switched, "argument=" + WindowsWorkspaceValidationProbe.encodeValue("animation"));
        assertLine(switched, "result.currentId=" + WindowsWorkspaceValidationProbe.encodeValue("modeling"));
    }

    @Test
    void executorRecordsUnexpectedFailuresWithoutHostAccess() {
        final WorkspaceService broken = new WorkspaceService() {
            @Override
            public CompletionStage<WorkspaceStatus> current() {
                throw new IllegalStateException("host gone");
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> switchTo(final WorkspaceId workspaceId) {
                return null;
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> updateDefault() {
                return null;
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> resetToDefault() {
                return null;
            }
        };
        final CommandResult result = WindowsWorkspaceValidationProbe.executeCommand(
            broken, NO_HOST, new Command(1, "status", Optional.empty()), PERMISSIONS);
        assertEquals("ERROR", result.status());
        assertTrue(result.lines().stream().anyMatch(line -> line.startsWith("error=")));
    }

    @Test
    void readinessRecordsEncodedDocumentAndModelIdentity() {
        final CubismFacade host = fakeCubism(
            true,
            Optional.of(new DocumentSnapshot(
                "doc-123",
                "测试 混合模式.cmo3",
                "fixture.cmo3",
                Optional.empty(),
                Optional.of(new ModelSnapshot("model-1", "Model One", List.of(),
                    List.of(), List.of(), List.of()))
            )),
            Optional.of(new ModelSnapshot("model-1", "Model One", List.of(),
                List.of(), List.of(), List.of()))
        );
        final CommandResult result = WindowsWorkspaceValidationProbe.executeCommand(
            WorkspaceService.unavailable(), host,
            new Command(1, "readiness", Optional.empty()), PERMISSIONS);
        assertEquals("OK", result.status());
        assertLine(result, "hostPresent=true");
        assertLine(result, "documentId=" + WindowsWorkspaceValidationProbe.encodeValue("doc-123"));
        assertLine(result, "documentName=" + WindowsWorkspaceValidationProbe.encodeValue("测试 混合模式.cmo3"));
        assertLine(result, "modelPresent=true");
        assertLine(result, "modelId=" + WindowsWorkspaceValidationProbe.encodeValue("model-1"));
        assertLine(result, "activeModelPresent=true");
        assertLine(result, "activeModelId=" + WindowsWorkspaceValidationProbe.encodeValue("model-1"));
        assertLine(result, "activeModelName=" + WindowsWorkspaceValidationProbe.encodeValue("Model One"));
        assertFalse(result.lines().stream().anyMatch(line -> line.contains("测试")),
            "host-derived names must be Base64-encoded, never raw");
    }

    @Test
    void readinessRecordsAbsentDocumentWithoutFailure() {
        final CommandResult result = WindowsWorkspaceValidationProbe.executeCommand(
            WorkspaceService.unavailable(), NO_HOST,
            new Command(1, "readiness", Optional.empty()), PERMISSIONS);
        assertEquals("OK", result.status());
        assertLine(result, "hostPresent=false");
        assertLine(result, "documentId=absent");
        assertLine(result, "activeModelPresent=false");
    }

    @Test
    void readinessSurfacesHostFailuresAsCommandErrorNotMaskedUnavailable() {
        final CubismFacade broken = new CubismFacade() {
            @Override
            public CubismRuntimeSnapshot runtime() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<ProjectSnapshot> activeProject() {
                return Optional.empty();
            }

            @Override
            public Optional<DocumentSnapshot> activeDocument() {
                throw new IllegalStateException("mapping failure");
            }

            @Override
            public Optional<ModelSnapshot> activeModel() {
                throw new IllegalStateException("mapping failure");
            }

            @Override
            public boolean isHostPresent() {
                return true;
            }

            @Override
            public TransactionManager transactionManager() {
                throw new UnsupportedOperationException();
            }
        };
        final CommandResult result = WindowsWorkspaceValidationProbe.executeCommand(
            WorkspaceService.unavailable(), broken,
            new Command(1, "readiness", Optional.empty()), PERMISSIONS);
        assertEquals("ERROR", result.status());
        assertTrue(result.lines().stream().anyMatch(line -> line.startsWith("error=")));
    }

    @Test
    void hostileNewlinesAndOversizeValuesAreEncodedOrRedacted() {
        final WorkspaceStatus hostile = new WorkspaceStatus(
            WorkspaceStatus.Availability.AVAILABLE,
            Optional.of(new WorkspaceInfo(
                new WorkspaceId("evil\nstatus=INJECTED\nid"), "name=forged\nline=1")),
            List.of(),
            Optional.of("diag\nnext=forged")
        );
        final WorkspaceService service = new WorkspaceService() {
            @Override
            public CompletionStage<WorkspaceStatus> current() {
                return CompletableFuture.completedFuture(hostile);
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> switchTo(final WorkspaceId workspaceId) {
                return null;
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> updateDefault() {
                return null;
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> resetToDefault() {
                return null;
            }
        };
        final CommandResult result = WindowsWorkspaceValidationProbe.executeCommand(
            service, NO_HOST, new Command(1, "status", Optional.empty()), PERMISSIONS);
        assertEquals("OK", result.status());
        assertLine(result, "status.currentId=" + WindowsWorkspaceValidationProbe.encodeValue(
            "evil\nstatus=INJECTED\nid"));
        assertFalse(result.lines().stream().anyMatch(line -> line.equals("status=INJECTED")),
            "host-derived values must never inject key=value lines");
        assertTrue(result.lines().stream().allMatch(line -> !line.contains("\n") && !line.contains("\r")),
            "evidence lines must never contain raw newlines");

        final String oversized = "x".repeat(WindowsWorkspaceValidationProbe.MAX_ENCODED_VALUE_BYTES + 1);
        assertEquals(WindowsWorkspaceValidationProbe.REDACTED_VALUE,
            WindowsWorkspaceValidationProbe.encodeValue(oversized));
        assertTrue(Base64.getDecoder().decode(WindowsWorkspaceValidationProbe.encodeValue("ok id")).length > 0,
            "encodeValue must remain reversibly decodable below the ceiling");
    }

    @Test
    void corruptWatermarkFailsClosedAndNeverResetsToZero(@TempDir final Path root) throws IOException {
        final Path commands = root.resolve(WindowsWorkspaceValidationProbe.COMMANDS_DIR);
        Files.createDirectories(commands);
        Files.writeString(commands.resolve("1-status.cmd"), "status", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(WindowsWorkspaceValidationProbe.PROTOCOL_STATE_FILE),
            "not-a-number", StandardCharsets.UTF_8);

        final long next = WindowsWorkspaceValidationProbe.processPending(
            root, 0L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS);
        assertEquals(-1L, next, "corrupt watermark must fail closed");
        assertTrue(Files.exists(commands.resolve("1-status.cmd")), "pending command must stay pending");
        assertTrue(Files.exists(root.resolve(WindowsWorkspaceValidationProbe.EVIDENCE_FILE)));
        final String evidence = Files.readString(root.resolve(WindowsWorkspaceValidationProbe.EVIDENCE_FILE));
        assertTrue(evidence.contains("WATERMARK_INVALID"), evidence);

        Files.writeString(root.resolve(WindowsWorkspaceValidationProbe.PROTOCOL_STATE_FILE),
            "0\n", StandardCharsets.UTF_8);
        final long recovered = WindowsWorkspaceValidationProbe.processPending(
            root, 0L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS);
        assertEquals(1L, recovered, "repairing the watermark must resume without resetting to zero");
    }

    @Test
    void duplicateNeverOverwritesAcceptedResults(@TempDir final Path root) throws IOException {
        final Path commands = root.resolve(WindowsWorkspaceValidationProbe.COMMANDS_DIR);
        Files.createDirectories(commands);
        Files.writeString(commands.resolve("1-status.cmd"), "status", StandardCharsets.UTF_8);
        final long first = WindowsWorkspaceValidationProbe.processPending(
            root, 0L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS);
        assertEquals(1L, first);

        final Path accepted = root.resolve(WindowsWorkspaceValidationProbe.RESULTS_DIR)
            .resolve("000001-status.txt");
        final String acceptedText = Files.readString(accepted);
        assertTrue(acceptedText.contains("status=OK"));

        // Replayed old sequence: recorded as DUPLICATE, accepted result untouched.
        Files.writeString(commands.resolve("1-status.cmd"), "status", StandardCharsets.UTF_8);
        final long afterDuplicate = WindowsWorkspaceValidationProbe.processPending(
            root, first, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS);
        assertEquals(1L, afterDuplicate, "duplicate must not advance the watermark");
        assertEquals(acceptedText, Files.readString(accepted), "accepted result must be preserved");
        final String duplicate = Files.readString(root.resolve(WindowsWorkspaceValidationProbe.RESULTS_DIR)
            .resolve("000001-status.duplicate.txt"));
        assertTrue(duplicate.contains("status=DUPLICATE"));
    }

    @Test
    void acceptedResultsAboveWatermarkFailClosed(@TempDir final Path root) throws IOException {
        final Path results = root.resolve(WindowsWorkspaceValidationProbe.RESULTS_DIR);
        Files.createDirectories(results);
        Files.writeString(results.resolve("000002-status.txt"), "accepted evidence",
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve(WindowsWorkspaceValidationProbe.PROTOCOL_STATE_FILE),
            "0\n", StandardCharsets.UTF_8);

        final long next = WindowsWorkspaceValidationProbe.processPending(
            root, 0L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS);
        assertEquals(-1L, next, "accepted results above the watermark must fail closed");
        assertEvidenceContains(root, "ACCEPTED_RESULTS_ABOVE_WATERMARK");
    }

    @Test
    void acceptedResultsWithoutWatermarkFailClosed(@TempDir final Path root) throws IOException {
        final Path results = root.resolve(WindowsWorkspaceValidationProbe.RESULTS_DIR);
        Files.createDirectories(results);
        Files.writeString(results.resolve("000001-status.txt"), "accepted evidence",
            StandardCharsets.UTF_8);

        final long next = WindowsWorkspaceValidationProbe.processPending(
            root, 0L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS);
        assertEquals(-1L, next, "accepted results without a watermark file must fail closed");
        assertEvidenceContains(root, "RESULTS_WITHOUT_WATERMARK");
    }

    @Test
    void watermarkRegressionFailsClosed(@TempDir final Path root) throws IOException {
        final Path commands = root.resolve(WindowsWorkspaceValidationProbe.COMMANDS_DIR);
        Files.createDirectories(commands);
        Files.writeString(commands.resolve("1-status.cmd"), "status", StandardCharsets.UTF_8);
        assertEquals(1L, WindowsWorkspaceValidationProbe.processPending(
            root, 0L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS));

        // An operator restores an older watermark while the plugin still remembers 1.
        Files.writeString(root.resolve(WindowsWorkspaceValidationProbe.PROTOCOL_STATE_FILE),
            "0\n", StandardCharsets.UTF_8);
        final long next = WindowsWorkspaceValidationProbe.processPending(
            root, 1L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS);
        assertEquals(-1L, next, "a disk watermark below the in-memory value must fail closed");
        assertEvidenceContains(root, "WATERMARK_REGRESSED");
    }

    @Test
    void unresolvedInflightClaimFailsClosedAndBlocksAllProcessing(@TempDir final Path root)
        throws IOException {
        final Path commands = root.resolve(WindowsWorkspaceValidationProbe.COMMANDS_DIR);
        final Path inflight = root.resolve(WindowsWorkspaceValidationProbe.INFLIGHT_DIR);
        Files.createDirectories(commands);
        Files.createDirectories(inflight);
        Files.writeString(commands.resolve("1-status.cmd"), "status", StandardCharsets.UTF_8);
        Files.writeString(inflight.resolve("1-status.cmd"), "switch\nsome-id",
            StandardCharsets.UTF_8);

        final long next = WindowsWorkspaceValidationProbe.processPending(
            root, 0L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS);
        assertEquals(-1L, next, "an unresolved claim must fail the whole scan closed");
        assertTrue(Files.exists(commands.resolve("1-status.cmd")),
            "pending commands must stay pending while a claim is unresolved");
        assertEvidenceContains(root, "INFLIGHT_UNRESOLVED");
    }

    @Test
    void claimIsRetainedWhenResultOrWatermarkPublicationFails(@TempDir final Path root)
        throws IOException {
        final Path commands = root.resolve(WindowsWorkspaceValidationProbe.COMMANDS_DIR);
        final Path results = root.resolve(WindowsWorkspaceValidationProbe.RESULTS_DIR);
        final Path inflight = root.resolve(WindowsWorkspaceValidationProbe.INFLIGHT_DIR);
        Files.createDirectories(commands);
        Files.createDirectories(results);
        Files.writeString(commands.resolve("1-status.cmd"), "status", StandardCharsets.UTF_8);

        // Result publication fails: the temp target name is occupied by a directory. The
        // directory does not match the accepted-result pattern, so the scan-start consistency
        // checks pass and the failure happens after the claim.
        Files.createDirectory(results.resolve("000001-status.txt.tmp"));
        assertThrows(IOException.class, () -> WindowsWorkspaceValidationProbe.processPending(
            root, 0L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS));
        assertTrue(Files.exists(inflight.resolve("1-status.cmd")),
            "the claim must survive a failed result publication");
        assertFalse(Files.exists(commands.resolve("1-status.cmd")),
            "the command must not remain re-executable");
        assertFalse(Files.exists(root.resolve(WindowsWorkspaceValidationProbe.PROTOCOL_STATE_FILE)),
            "the watermark must not advance when publication failed");

        // The next scan fails closed instead of re-executing the uncertain command.
        final long blocked = WindowsWorkspaceValidationProbe.processPending(
            root, 0L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS);
        assertEquals(-1L, blocked);
        assertEvidenceContains(root, "INFLIGHT_UNRESOLVED");

        // Watermark publication failure leaves the claim as well.
        Files.delete(inflight.resolve("1-status.cmd"));
        Files.delete(results.resolve("000001-status.txt.tmp"));
        Files.writeString(commands.resolve("2-status.cmd"), "status", StandardCharsets.UTF_8);
        Files.createDirectory(root.resolve(WindowsWorkspaceValidationProbe.PROTOCOL_STATE_FILE));
        assertThrows(IOException.class, () -> WindowsWorkspaceValidationProbe.processPending(
            root, 0L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS));
        assertTrue(Files.exists(inflight.resolve("2-status.cmd")),
            "the claim must survive a failed watermark publication");
    }

    @Test
    void partialPublicationAndForeignFilesAreIgnoredOrRejected(@TempDir final Path root)
        throws IOException {
        final Path commands = root.resolve(WindowsWorkspaceValidationProbe.COMMANDS_DIR);
        Files.createDirectories(commands);
        // A temp file from an interrupted helper publication must never be read as a command.
        Files.writeString(commands.resolve(".2-switch.abc123.tmp"), "switch\npartial",
            StandardCharsets.UTF_8);
        Files.writeString(commands.resolve("1-status.cmd"), "status", StandardCharsets.UTF_8);

        final long next = WindowsWorkspaceValidationProbe.processPending(
            root, 0L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS);
        assertEquals(1L, next);
        assertTrue(Files.exists(commands.resolve(".2-switch.abc123.tmp")),
            "non-.cmd temp files must be ignored and left in place");
        assertFalse(Files.exists(commands.resolve("1-status.cmd")));
        assertTrue(Files.exists(root.resolve(WindowsWorkspaceValidationProbe.RESULTS_DIR)
            .resolve("000001-status.txt")));
    }

    @Test
    void processPendingIsSequentialIdempotentAndRejectsMalformedOversized(@TempDir final Path root)
        throws IOException {
        final Path commands = root.resolve(WindowsWorkspaceValidationProbe.COMMANDS_DIR);
        Files.createDirectories(commands);

        Files.writeString(commands.resolve("1-status.cmd"), "status", StandardCharsets.UTF_8);
        Files.writeString(commands.resolve("3-switch.cmd"), "switch\nmodeling", StandardCharsets.UTF_8);
        Files.writeString(commands.resolve("not-a-command.cmd"), "status", StandardCharsets.UTF_8);
        Files.writeString(commands.resolve("2-current.cmd"), "current", StandardCharsets.UTF_8);
        final Path oversized = commands.resolve("4-current.cmd");
        Files.writeString(oversized,
            "status\n" + "x".repeat(WindowsWorkspaceValidationProbe.MAX_COMMAND_BYTES + 1),
            StandardCharsets.UTF_8);
        Files.writeString(commands.resolve("5-update-default.cmd"), "explode",
            StandardCharsets.UTF_8);

        final long next = WindowsWorkspaceValidationProbe.processPending(
            root, 0L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS);

        assertEquals(3L, next, "sequence must advance only past accepted commands");
        assertEquals(3L, WindowsWorkspaceValidationProbe.processPending(
            root, next, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS),
            "reprocessing the same root must stay idempotent");
        final Path results = root.resolve(WindowsWorkspaceValidationProbe.RESULTS_DIR);
        final List<String> resultNames;
        try (var stream = Files.list(results)) {
            resultNames = stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of(
            "000001-status.txt",
            "000002-current.txt",
            "000003-switch.txt"
        ), resultNames);
        assertTrue(Files.exists(root.resolve(WindowsWorkspaceValidationProbe.REJECTED_DIR)
            .resolve("not-a-command.cmd.malformed")));
        assertTrue(Files.exists(root.resolve(WindowsWorkspaceValidationProbe.REJECTED_DIR)
            .resolve("4-current.cmd.oversized")));
        assertTrue(Files.exists(root.resolve(WindowsWorkspaceValidationProbe.REJECTED_DIR)
            .resolve("5-update-default.cmd.malformed")));
        assertTrue(Files.exists(root.resolve(WindowsWorkspaceValidationProbe.EVIDENCE_FILE)));
        assertTrue(Files.exists(root.resolve(WindowsWorkspaceValidationProbe.PROTOCOL_STATE_FILE)));
    }

    @Test
    void processPendingRecordsDuplicatesWhenSequenceRegresses(@TempDir final Path root)
        throws IOException {
        final Path commands = root.resolve(WindowsWorkspaceValidationProbe.COMMANDS_DIR);
        Files.createDirectories(commands);
        Files.writeString(commands.resolve("7-status.cmd"), "status", StandardCharsets.UTF_8);

        final long next = WindowsWorkspaceValidationProbe.processPending(
            root, 0L, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS);
        assertEquals(7L, next);

        Files.writeString(commands.resolve("6-status.cmd"), "status", StandardCharsets.UTF_8);
        Files.writeString(commands.resolve("8-status.cmd"), "status", StandardCharsets.UTF_8);
        final long advanced = WindowsWorkspaceValidationProbe.processPending(
            root, next, WorkspaceService.unavailable(), NO_HOST, PERMISSIONS);
        assertEquals(8L, advanced, "a regressed sequence must be skipped without moving the watermark");
        final String duplicate = Files.readString(
            root.resolve(WindowsWorkspaceValidationProbe.RESULTS_DIR).resolve("000006-status.duplicate.txt"));
        assertTrue(duplicate.contains("status=DUPLICATE"));
        final String accepted = Files.readString(
            root.resolve(WindowsWorkspaceValidationProbe.RESULTS_DIR).resolve("000008-status.txt"));
        assertTrue(accepted.contains("status=OK"));
        assertFalse(Files.exists(commands.resolve("6-status.cmd")));
        assertFalse(Files.exists(commands.resolve("8-status.cmd")));
    }

    private static void assertEvidenceContains(final Path root, final String expected)
        throws IOException {
        final String evidence = Files.readString(root.resolve(WindowsWorkspaceValidationProbe.EVIDENCE_FILE));
        assertTrue(evidence.contains(expected), "missing " + expected + " in " + evidence);
    }

    private static CubismFacade fakeCubism(
        final boolean hostPresent,
        final Optional<DocumentSnapshot> document,
        final Optional<ModelSnapshot> model
    ) {
        return new CubismFacade() {
            @Override
            public CubismRuntimeSnapshot runtime() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<ProjectSnapshot> activeProject() {
                return Optional.empty();
            }

            @Override
            public Optional<DocumentSnapshot> activeDocument() {
                return document;
            }

            @Override
            public Optional<ModelSnapshot> activeModel() {
                return model;
            }

            @Override
            public boolean isHostPresent() {
                return hostPresent;
            }

            @Override
            public TransactionManager transactionManager() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static void assertLine(final CommandResult result, final String expected) {
        assertTrue(result.lines().contains(expected), "missing line " + expected + " in " + result.lines());
    }
}
