package dev.turboism.adapter.cubism.command;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.command.EditorCanvasSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorExternalAppSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorGridSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorModelingStatisticsRequest;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.cubism.command.EditorResizeModelRequest;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileHandleState;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedTypedEditorCommandAdapterTest {

    @TempDir Path temporary;

    @Test
    void executesCanvasSettingOnTheHostThreadWithTheVerifiedAuthoringSequence() {
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorCanvasSettingsRequest(1200, 1000));

        assertEquals(EditorCommandResult.Status.EXECUTED, result.status());
        assertTrue(host.onEdt.get(), "typed dispatch must run on the EDT");
        assertEquals(1200, host.canvas.width);
        assertEquals(1000, host.canvas.height);
        assertEquals("Change Canvas Size", host.editMode.lastEditLabel);
        assertEquals(1, host.group.undoCount, "one canvas undo snapshot must be registered");
        assertEquals(100.0f, host.handler.lastDeltaX, "center anchor translates by half the width delta");
        assertEquals(0.0f, host.handler.lastDeltaY, "height unchanged means zero translation");
        assertEquals(1.0f, host.handler.lastScale);
        assertTrue(host.document.dirty);
    }

    @Test
    void canvasSettingFailsClosedForNonModelingViewsAndNonMainEditModes() {
        FakeHost host = new FakeHost();
        host.viewKind = "other";
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));
        assertEquals(
            EditorCommandResult.Status.INVALID_STATE,
            adapter.execute(new EditorCanvasSettingsRequest(1200, 1000)).status()
        );
        assertEquals(1000, host.canvas.width, "no host mutation on invalid state");

        FakeHost nonMain = new FakeHost();
        nonMain.editModeKind = "other";
        VerifiedEditorCommandAdapter nonMainAdapter = new VerifiedEditorCommandAdapter(nonMain.resolver("5.3.02"));
        assertEquals(
            EditorCommandResult.Status.INVALID_STATE,
            nonMainAdapter.execute(new EditorCanvasSettingsRequest(1200, 1000)).status()
        );
    }

    @Test
    void canvasSettingFailsClosedWhenSelectorsAreAbsentFromTheVerifiedPlan() {
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(
            host.resolver("5.3.02", false)
        );
        assertEquals(
            EditorCommandResult.Status.FAILED,
            adapter.execute(new EditorCanvasSettingsRequest(1200, 1000)).status()
        );
        assertEquals(1000, host.canvas.width, "no host mutation without verified selectors");
    }

    @Test
    void canvasSettingIsUnsupportedForUnadmittedVersions() {
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("4.2.00"));
        assertEquals(
            EditorCommandResult.Status.UNSUPPORTED_VERSION,
            adapter.execute(new EditorCanvasSettingsRequest(1200, 1000)).status()
        );
    }

    @Test
    void executesGridSettingWithSpacingColorAndCanvasRefresh() {
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.2.03"));

        EditorCommandResult result = adapter.execute(new EditorGridSettingsRequest(
            80, new Color(0.25f, 0.5f, 0.75f, 1.0f)
        ));

        assertEquals(EditorCommandResult.Status.EXECUTED, result.status());
        assertEquals(80, host.grid.spacing);
        assertEquals(64, host.developerSetting.lastRed, "0.25 * 255 rounded");
        assertEquals(128, host.developerSetting.lastGreen);
        assertEquals(191, host.developerSetting.lastBlue);
        assertEquals(1, host.gridEntity.resetCount, "modeling grid entities are reset");
        assertEquals(1, host.updateManager.repaints);
    }

    @Test
    void executesExternalAppSettingAndPersistsRemoteFlag() {
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(
            new EditorExternalAppSettingsRequest(22033, true)
        );

        assertEquals(EditorCommandResult.Status.EXECUTED, result.status());
        assertEquals(22033, host.webSocket.instance.port);
        assertTrue(FakeHost.FakeWebSocketLCompanion.remoteFlag, "the companion remote flag must be set");
        assertTrue(host.webSocket.instance.started, "server starts when disconnected");
        assertEquals(true, host.config.values.get(
            VerifiedTypedEditorCommandOperations.EXTERNAL_APP_REMOTE_CONFIG_KEY
        ));
    }

    @Test
    void externalAppSettingFailsClosedWhileTheServerIsConnected() {
        FakeHost host = new FakeHost();
        host.webSocket.instance.connected = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        assertEquals(
            EditorCommandResult.Status.INVALID_STATE,
            adapter.execute(new EditorExternalAppSettingsRequest(22033, true)).status()
        );
        assertNull(host.config.values.get(
            VerifiedTypedEditorCommandOperations.EXTERNAL_APP_REMOTE_CONFIG_KEY
        ), "no persistence on invalid state");
    }

    @Test
    void executesResizeModelDocumentWithThePercentageScale() {
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorResizeModelRequest(200));

        assertEquals(EditorCommandResult.Status.EXECUTED, result.status());
        assertEquals(2.0f, host.handler.lastScale);
        assertTrue(host.document.dirty);
    }

    @Test
    void resizeModelDocumentFailsClosedWhileAResizeIsInProgress() {
        FakeHost host = new FakeHost();
        host.resizeGuard.active = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        assertEquals(
            EditorCommandResult.Status.INVALID_STATE,
            adapter.execute(new EditorResizeModelRequest(200)).status()
        );
        assertFalse(host.document.dirty);
    }

    @Test
    void statisticsAndUnimplementedParameterizedCommandsStayUnavailable() {
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        assertEquals(
            EditorCommandResult.Status.UNAVAILABLE,
            adapter.execute(new EditorModelingStatisticsRequest(true)).status()
        );
    }

    @Test
    void externalAppSettingRollsBackWhenARemoteMutationFails() {
        FakeHost host = new FakeHost();
        host.failOnSetRemote = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorExternalAppSettingsRequest(22034, true));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "sanitized failure");
        assertEquals(22033, host.webSocket.instance.port, "the port must be restored after the remote failure");
        assertFalse(FakeHost.FakeWebSocketLCompanion.remoteFlag, "the remote flag must be restored");
        assertEquals(false, host.config.values.get(
            VerifiedTypedEditorCommandOperations.EXTERNAL_APP_REMOTE_CONFIG_KEY),
            "the persisted config must be restored to the original value");
        assertFalse(host.webSocket.instance.started, "no leaked server");
    }

    @Test
    void externalAppSettingRollsBackWhenTheStartFails() {
        FakeHost host = new FakeHost();
        host.failOnStart = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorExternalAppSettingsRequest(22034, true));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "sanitized failure");
        assertEquals(22033, host.webSocket.instance.port, "the port must be restored");
        assertFalse(FakeHost.FakeWebSocketLCompanion.remoteFlag, "the remote flag must be restored");
        assertEquals(false, host.config.values.get(
            VerifiedTypedEditorCommandOperations.EXTERNAL_APP_REMOTE_CONFIG_KEY),
            "the persisted config must be restored to the original value");
        assertTrue(host.webSocket.instance.stopped, "the server must be stopped by the rollback");
    }

    @Test
    void gridSettingRollsBackWhenASpacingMutationFails() {
        FakeHost host = new FakeHost();
        host.failOnSetSpacing = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorGridSettingsRequest(
            80, new Color(0.25f, 0.5f, 0.75f, 1.0f)));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "sanitized failure");
        assertEquals(128, host.developerSetting.lastRed, "the color must be restored");
        assertEquals(50, host.grid.spacing, "the spacing must be restored");
        assertEquals(1, host.updateManager.repaints, "the rollback re-runs the refresh once");
    }

    @Test
    void gridSettingRollsBackWhenTheRepaintFails() {
        FakeHost host = new FakeHost();
        host.failOnRepaint = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorGridSettingsRequest(
            80, new Color(0.25f, 0.5f, 0.75f, 1.0f)));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "sanitized failure");
        assertEquals(50, host.grid.spacing, "the spacing must be restored after the repaint failure");
        assertEquals(128, host.developerSetting.lastRed, "the color must be restored");
    }

    @Test
    void canvasSettingRollsBackThroughTheUndoPathAfterWidthMutationFails() {
        FakeHost host = new FakeHost();
        host.failOnSetWidth = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorCanvasSettingsRequest(1200, 1100));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "sanitized failure");
        assertTrue(host.editMode.endEditCalled, "the transaction must be closed");
        assertEquals(1000, host.canvas.width, "the canvas must be restored through the Undo path");
        assertEquals(1000, host.canvas.height, "the canvas height must be restored");
        assertEquals(1, host.undoManager.undoCount, "the partial group must be undone");
        assertFalse(host.document.dirty, "dirty must not be marked after a rolled-back failure");
    }

    @Test
    void canvasSettingRollsBackThroughTheUndoPathWhenTheModelScaleStepFails() {
        FakeHost host = new FakeHost();
        host.failOnScale = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorCanvasSettingsRequest(1200, 1100));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "sanitized failure");
        assertTrue(host.editMode.endEditCalled, "the transaction must be closed");
        assertEquals(1000, host.canvas.width, "the canvas must be restored through the Undo path");
        assertEquals(1, host.undoManager.undoCount);
        assertFalse(host.document.dirty);
    }

    @Test
    void resizeModelDocumentRollsBackWhenTheNativeScaleFails() {
        FakeHost host = new FakeHost();
        host.failOnScale = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorResizeModelRequest(200));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "sanitized failure");
        assertTrue(host.editMode.endEditCalled, "the transaction must be closed");
        assertEquals(1, host.undoManager.undoCount, "the partial group must be undone");
        assertFalse(host.document.dirty);
    }

    @Test
    void externalAppSettingFailsClosedWhenTheRollbackStopFails() {
        FakeHost host = new FakeHost();
        host.failOnStart = true;
        host.failOnStop = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorExternalAppSettingsRequest(22034, true));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "rollback failure must fail closed");
        assertEquals(22033, host.webSocket.instance.port, "the port restore must still run");
    }

    @Test
    void externalAppSettingFailsClosedWhenThePortRestoreFails() {
        FakeHost host = new FakeHost();
        host.failOnStart = true;
        host.failOnRestorePort = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorExternalAppSettingsRequest(22034, true));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "unverified rollback must fail closed");
        assertEquals(22034, host.webSocket.instance.port, "the unverifiable port restore must not be claimed");
    }

    @Test
    void gridSettingFailsClosedWhenTheSpacingRestoreFails() {
        FakeHost host = new FakeHost();
        host.failOnRepaint = true;
        host.failOnRestoreSpacing = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorGridSettingsRequest(
            80, new Color(0.25f, 0.5f, 0.75f, 1.0f)));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "unverified grid rollback must fail closed");
        assertEquals(80, host.grid.spacing, "the unverifiable spacing restore must not be claimed");
    }

    @Test
    void canvasSettingFailsClosedWhenTheUndoRollbackFails() {
        FakeHost host = new FakeHost();
        host.failOnSetWidth = true;
        host.failOnUndo = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorCanvasSettingsRequest(1200, 1100));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "unverified undo rollback must fail closed");
        assertTrue(host.editMode.endEditCalled, "end-edit cleanup must still run");
    }

    @Test
    void canvasSettingFailsClosedWhenTheEndEditCleanupFails() {
        FakeHost host = new FakeHost();
        host.failOnEndEdit = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorCanvasSettingsRequest(1200, 1100));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "failed cleanup must fail closed");
        assertEquals(1000, host.canvas.width, "the undo path must still restore the canvas");
        assertEquals(1, host.undoManager.undoCount, "the partial group must still be undone");
    }

    @Test
    void canvasSettingRollsBackWhenTheDirtyMarkFails() {
        FakeHost host = new FakeHost();
        host.failOnMarkDirty = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorCanvasSettingsRequest(1200, 1100));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "sanitized failure");
        assertEquals(1000, host.canvas.width, "the committed write must be undone");
        assertEquals(1000, host.canvas.height, "the committed write must be undone");
        assertEquals(1, host.undoManager.undoCount, "the committed group must be undone");
        assertTrue(host.editMode.endEditCalled, "the transaction must be closed");
    }

    @Test
    void canvasSettingFailsBeforeMutationWhenARollbackSelectorIsAbsent() {
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(
            host.resolver("5.3.02", true, "cubism.editor-command.canvas.undo")
        );

        EditorCommandResult result = adapter.execute(new EditorCanvasSettingsRequest(1200, 1100));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "pre-flight failure must fail closed");
        assertEquals(1000, host.canvas.width, "no host mutation before the pre-flight resolution");
        assertEquals(1000, host.canvas.height, "no host mutation before the pre-flight resolution");
        assertNull(host.editMode.lastEditLabel, "no beginEdit before the pre-flight resolution");
        assertEquals(0, host.group.undoCount, "no Undo entry before the pre-flight resolution");
        assertFalse(host.document.dirty, "no dirty state before the pre-flight resolution");
    }

    @Test
    void externalAppSettingFailsBeforeMutationWhenAStopSelectorIsAbsent() {
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(
            host.resolver("5.3.02", true, "cubism.editor-command.external-app.stop")
        );

        EditorCommandResult result = adapter.execute(new EditorExternalAppSettingsRequest(22034, true));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "pre-flight failure must fail closed");
        assertEquals(22033, host.webSocket.instance.port, "no port mutation before the pre-flight resolution");
        assertFalse(host.webSocket.instance.started, "no server start before the pre-flight resolution");
    }

    @Test
    void gridAndResizeNoopRequireTheModelingDocumentAdmissionFirst() {
        FakeHost host = new FakeHost();
        host.viewKind = "other";
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        assertEquals(
            EditorCommandResult.Status.INVALID_STATE,
            adapter.execute(new EditorGridSettingsRequest(
                50, new Color(128f / 255f, 128f / 255f, 128f / 255f, 1.0f))).status(),
            "a missing modeling document must not receive a no-op EXECUTED"
        );
        assertEquals(
            EditorCommandResult.Status.INVALID_STATE,
            adapter.execute(new EditorResizeModelRequest(100)).status(),
            "a missing modeling document must not receive a no-op EXECUTED"
        );
    }

    @Test
    void openFailsClosedAsUnavailable() throws Exception {
        Path model = temporary.resolve("model.cmo3");
        Files.writeString(model, "fixture");
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        assertEquals(
            EditorCommandResult.Status.UNAVAILABLE,
            adapter.execute(new ResolvedEditorFileCommand(
                EditorFileCommand.OPEN, model, EditorOverwritePolicy.REJECT_EXISTING
            )).status(),
            "OPEN is not admitted: its first-open behavior is not fully verified on the exact host"
        );
        assertTrue(host.fileOps.opened.isEmpty(), "no host mutation for the unadmitted OPEN");
    }

    @Test
    void canvasSettingShortCircuitsIdenticalDimensionsWithoutSideEffects() {
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorCanvasSettingsRequest(1000, 1000));

        assertEquals(EditorCommandResult.Status.EXECUTED, result.status());
        assertNull(host.editMode.lastEditLabel, "no beginEdit for a no-op request");
        assertEquals(0, host.group.undoCount, "no synthetic Undo entry for a no-op request");
        assertFalse(host.document.dirty, "no synthetic dirty state for a no-op request");
    }

    @Test
    void resizeModelDocumentShortCircuitsHundredPercentWithoutSideEffects() {
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorResizeModelRequest(100));

        assertEquals(EditorCommandResult.Status.EXECUTED, result.status());
        assertEquals(0.0f, host.handler.lastScale, "no deformer wrapper for a 100% request");
        assertFalse(host.document.dirty, "no synthetic dirty state for a no-op request");
    }

    @Test
    void gridSettingShortCircuitsIdenticalValuesWithoutRefreshSideEffects() {
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorGridSettingsRequest(
            50, new Color(128f / 255f, 128f / 255f, 128f / 255f, 1.0f)
        ));

        assertEquals(EditorCommandResult.Status.EXECUTED, result.status());
        assertEquals(0, host.updateManager.repaints, "no repaint for a no-op request");
        assertEquals(0, host.gridEntity.resetCount, "no grid reset for a no-op request");
    }

    @Test
    void gridSettingFailsClosedOutsideTheHostSliderRange() {
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        assertEquals(
            EditorCommandResult.Status.INVALID_STATE,
            adapter.execute(new EditorGridSettingsRequest(3, new Color(0.5f, 0.5f, 0.5f, 1.0f))).status(),
            "spacing below the host slider minimum 5 is rejected before mutation"
        );
        assertEquals(50, host.grid.spacing, "no mutation for out-of-range spacing");

        assertEquals(
            EditorCommandResult.Status.INVALID_STATE,
            adapter.execute(new EditorGridSettingsRequest(900, new Color(0.5f, 0.5f, 0.5f, 1.0f))).status(),
            "spacing above min(documentWidth, documentHeight)=800 is rejected before mutation"
        );
        assertEquals(50, host.grid.spacing, "no mutation for out-of-range spacing");
    }

    @Test
    void canvasSettingFailsClosedWhileAnEditIsInProgress() {
        FakeHost host = new FakeHost();
        host.editing = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        assertEquals(
            EditorCommandResult.Status.INVALID_STATE,
            adapter.execute(new EditorCanvasSettingsRequest(1200, 1100)).status()
        );
        assertEquals(1000, host.canvas.width, "no mutation while an edit is in progress");
        assertEquals(0, host.group.undoCount);
    }

    @Test
    void canvasSettingCleansUpTheTransactionWhenAMidSequenceStepFails() {
        FakeHost host = new FakeHost();
        host.failOnSetWidth = true;
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        EditorCommandResult result = adapter.execute(new EditorCanvasSettingsRequest(1200, 1100));

        assertEquals(EditorCommandResult.Status.FAILED, result.status(), "sanitized failure");
        assertTrue(host.editMode.endEditCalled, "end-edit cleanup must run after a mid-sequence failure");
        assertEquals(1000, host.canvas.width, "the failing mutation step never applied its value");
        assertEquals(1, host.group.undoCount, "the opened transaction is closed so the editor never stays in an edit");
        assertFalse(host.document.dirty, "dirty marking happens only after the full sequence");
    }

    @Test
    void saveAsExecutesThroughTheModelingDocumentAndEnforcesOverwritePolicy() throws Exception {
        Path target = temporary.resolve("out.cmo3");
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));
        UserFileHandle handle = handle(target, UserFileMode.WRITE);

        EditorCommandResult result = adapter.execute(new ResolvedEditorFileCommand(
            EditorFileCommand.SAVE_AS, target, EditorOverwritePolicy.REJECT_EXISTING
            ));

        assertEquals(EditorCommandResult.Status.EXECUTED, result.status());
        assertEquals(target.toAbsolutePath().normalize().toFile(), host.document.lastSavedTo);

        Files.writeString(target, "existing");
        assertEquals(
            EditorCommandResult.Status.REJECTED,
            adapter.execute(new ResolvedEditorFileCommand(
                EditorFileCommand.SAVE_AS, target, EditorOverwritePolicy.REJECT_EXISTING
            )).status(),
            "existing target is rejected without an explicit replacement policy"
        );
        assertEquals(
            EditorCommandResult.Status.EXECUTED,
            adapter.execute(new ResolvedEditorFileCommand(
                EditorFileCommand.SAVE_AS, target, EditorOverwritePolicy.REPLACE_EXISTING
            )).status()
        );
    }

    @Test
    void saveAsFailsClosedForNonDocumentViewContexts() throws Exception {
        Path target = temporary.resolve("out.cmo3");
        FakeHost host = new FakeHost();
        host.viewKind = "scene";
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));
        UserFileHandle handle = handle(target, UserFileMode.WRITE);

        assertEquals(
            EditorCommandResult.Status.EXECUTED,
            adapter.execute(new ResolvedEditorFileCommand(
                EditorFileCommand.SAVE_AS, target, EditorOverwritePolicy.REJECT_EXISTING
            )).status(),
            "scene documents save through the animation content"
        );
        assertEquals(target.toAbsolutePath().normalize().toFile(), host.sceneDocument.lastSavedTo);

        host.viewKind = "other";
        assertEquals(
            EditorCommandResult.Status.INVALID_STATE,
            adapter.execute(new ResolvedEditorFileCommand(
                EditorFileCommand.SAVE_AS, target, EditorOverwritePolicy.REJECT_EXISTING
            )).status()
        );
    }

    @Test
    void unimplementedFileCommandsRemainUnavailable() throws Exception {
        Path target = temporary.resolve("out.csv");
        FakeHost host = new FakeHost();
        VerifiedEditorCommandAdapter adapter = new VerifiedEditorCommandAdapter(host.resolver("5.3.02"));

        assertEquals(
            EditorCommandResult.Status.UNAVAILABLE,
            adapter.execute(new ResolvedEditorFileCommand(
                EditorFileCommand.CSV_EXPORT_MODEL_IDS, target, EditorOverwritePolicy.REJECT_EXISTING
            )).status()
        );
        assertEquals(
            EditorCommandResult.Status.UNAVAILABLE,
            adapter.execute(new ResolvedEditorFileCommand(
                EditorFileCommand.OPEN, target, EditorOverwritePolicy.REJECT_EXISTING
            )).status()
        );
    }

    private UserFileHandle handle(final Path path, final UserFileMode mode) {
        return new UserFileHandle() {
            @Override
            public String id() { return "grant"; }

            @Override
            public String displayName() { return "grant"; }

            @Override
            public UserFileMode mode() { return mode; }

            @Override
            public UserFileLifetime lifetime() { return UserFileLifetime.UNTIL_DISABLE; }

            @Override
            public UserFileHandleState state() { return UserFileHandleState.ACTIVE; }

            @Override
            public void revoke() { }

            @Override
            public void close() { }
        };
    }

    /** Fake exact-version host mirroring every selector member the typed operations use. */
    static final class FakeHost {
        final FakeApp app = new FakeApp();
        final FakeModelingView view = new FakeModelingView();
        final FakeModelingDocument document = new FakeModelingDocument();
        final FakeEditModeMain editMode = new FakeEditModeMain();
        final FakeModelSource modelSource = new FakeModelSource();
        final FakeCanvas canvas = new FakeCanvas();
        final FakeModelHandler handler = new FakeModelHandler();
        final FakeGroupUndo group = new FakeGroupUndo();
        final FakeUndoManager undoManager = new FakeUndoManager();
        final FakeGridEntity grid = new FakeGridEntity();
        final FakeDeveloperSetting developerSetting = new FakeDeveloperSetting();
        final FakeGridPanelEntity gridEntity = new FakeGridPanelEntity();
        final FakeUpdateManager updateManager = new FakeUpdateManager();
        final FakeResizeGuard resizeGuard = new FakeResizeGuard();
        final FakeWebSocketX webSocket = new FakeWebSocketX();
        final FakeUUConfig config = new FakeUUConfig();
        final FakeFileOps fileOps = new FakeFileOps();
        final FakeSceneDocument sceneDocument = new FakeSceneDocument();
        final AtomicBoolean onEdt = new AtomicBoolean();
        String viewKind = "modeling";
        String editModeKind = "main";
        boolean editing;
        static boolean failOnSetWidth;
        static boolean failOnScale;
        static boolean failOnSetRemote;
        static boolean failOnStart;
        static boolean failOnSetSpacing;
        static boolean failOnRepaint;
        static boolean failOnStop;
        static boolean failOnRestorePort;
        static boolean failOnRestoreSpacing;
        static boolean failOnUndo;
        static boolean failOnEndEdit;
        static boolean failOnMarkDirty;

        FakeHost() {
            failOnSetWidth = false;
            failOnScale = false;
            failOnSetRemote = false;
            failOnStart = false;
            failOnSetSpacing = false;
            failOnRepaint = false;
            failOnStop = false;
            failOnRestorePort = false;
            failOnRestoreSpacing = false;
            failOnUndo = false;
            failOnEndEdit = false;
            failOnMarkDirty = false;
            FakeApp.INSTANCE.host = this;
            FakeGridEntity.a = grid;
            FakeDeveloperSetting.INSTANCE = developerSetting;
            FakeResizeGuard.a = resizeGuard;
            FakeWebSocketX.a = webSocket;
            FakeWebSocketLCompanion.remoteFlag = false;
            FakeUUConfig.a = config;
            FakeFileOps.a = fileOps;
        }

        FakeView currentView() {
            return switch (viewKind) {
                case "scene" -> sceneDocument;
                case "modeling" -> view;
                default -> new FakeView();
            };
        }

        FakeEditModeBase currentEditMode() {
            return switch (editModeKind) {
                case "main" -> editMode;
                default -> new FakeEditModeBase();
            };
        }

        VerifiedMemberResolver resolver(final String version) {
            return resolver(version, true);
        }

        VerifiedMemberResolver resolver(final String version, final boolean typed) {
            return resolver(version, typed, new String[0]);
        }

        VerifiedMemberResolver resolver(
            final String version,
            final boolean typed,
            final String... dropAliases
        ) {
            List<StaticSelector> selectors = new ArrayList<>();
            String host = internal(FakeApp.class);
            selectors.add(StaticSelector.staticMethod(
                "cubism.ui-top-menu.app-controller.instance", host, "instance", "()L" + host + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
            ));
            if (!typed) {
                return TestVerifiedResolvers.create(
                    version, "adapter.ui.top-menu", Set.of("cubism.ui-top-menu"), selectors,
                    FakeHost.class.getClassLoader()
                );
            }
            String view = internal(FakeModelingView.class);
            String doc = internal(FakeModelingDocument.class);
            String editModeBase = internal(FakeEditModeBase.class);
            String editModeMain = internal(FakeEditModeMain.class);
            String source = internal(FakeModelSource.class);
            String canvas = internal(FakeCanvas.class);
            String handler = internal(FakeModelHandler.class);
            String group = internal(FakeGroupUndo.class);
            String undo = internal(FakeSimpleUndo.class);
            String companion = internal(FakeCanvasCompanion.class);
            String vector = internal(FakeGVector2.class);
            String undoManager = internal(FakeUndoManager.class);
            String grid = internal(FakeGridEntity.class);
            String devSetting = internal(FakeDeveloperSetting.class);
            String color = internal(FakeCColor.class);
            String draw = internal(FakeDrawImpl.class);
            String gridEntity = internal(FakeGridPanelEntity.class);
            String updateManager = internal(FakeUpdateManager.class);
            String resizeGuard = internal(FakeResizeGuard.class);
            String wsX = internal(FakeWebSocketX.class);
            String wsL = internal(FakeWebSocketL.class);
            String config = internal(FakeUUConfig.class);
            String fileOps = internal(FakeFileOps.class);
            String sceneDoc = internal(FakeSceneDocument.class);
            String sceneContent = internal(FakeAnimationFileContent.class);
            String copyable = internal(FakeCopyable.class);
            String undoable = internal(FakeUndoable.class);
            String model = internal(FakeModel.class);
            String pack = internal(FakePack.class);
            String iEditMode = internal(FakeIEditMode.class);
            String editModeB = internal(FakeEditModeB.class);

            selectors.add(StaticSelector.method("cubism.editor-command.canvas.current-view-context", host, "getCurrentViewContext", "()L" + internal(FakeView.class) + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.doc-size", internal(FakeView.class), "getDocumentSize", "()L" + internal(FakeCSize.class) + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.size-width", internal(FakeCSize.class), "getWidth", "()I", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.size-height", internal(FakeCSize.class), "getHeight", "()I", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.is-editing", internal(FakeEditModeBase.class), "isEditing", "()Z", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.undo-manager", editModeMain, "getUndoManager", "()L" + undoManager + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.undo-pos", undoManager, "getCurrentPos", "()I", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.undo", undoManager, "undo", "()V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.complete-pack", host, "getCompletePack", "()L" + pack + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.classSelector("cubism.editor-command.canvas.modeling-view", view));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.modeling-doc", view, "getDoc", "()L" + doc + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.model", view, "getModel", "()L" + model + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.edit-mode", doc, "getCurrentEditMode", "()L" + editModeBase + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.model-source", doc, "getModelSource", "()L" + source + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.mark-dirty", doc, "updateLastModifiedTime", "()V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.file.save-model", doc, "saveDocument", "(Ljava/io/File;Z)Z", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.classSelector("cubism.editor-command.canvas.edit-mode-main", editModeMain));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.begin-edit", editModeMain, "beginEdit", "(Ljava/lang/String;)L" + group + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.canvas", source, "getCanvas", "()L" + canvas + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.handler", source, "getHandler", "()L" + handler + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.pixel-width", canvas, "getPixelWidth", "()I", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.pixel-height", canvas, "getPixelHeight", "()I", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.set-pixel-width", canvas, "setPixelWidth", "(I)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.set-pixel-height", canvas, "setPixelHeight", "(I)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.field("cubism.editor-command.canvas.companion", canvas, "Companion", "L" + companion + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.notify-size", companion, "a", "(II)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.constructor("cubism.editor-command.canvas.simple-undo", undo, "(Ljava/lang/String;L" + copyable + ";Ljava/lang/Object;)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.group-add", group, "plusAssign", "(L" + undoable + ";)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.constructor("cubism.editor-command.canvas.vector2", vector, "(FF)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.constructor("cubism.editor-command.canvas.vector2-zero", vector, "()V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.canvas.scale-with-anchor", handler, "a", "(L" + model + ";FL" + vector + ";L" + vector + ";L" + group + ";)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.staticMethod("cubism.editor-command.canvas.end-edit-default", editModeB, "a", "(L" + iEditMode + ";ZLjava/lang/Object;ILjava/lang/Object;)Z", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(StaticSelector.field("cubism.editor-command.grid.entity", grid, "a", "L" + grid + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(StaticSelector.method("cubism.editor-command.grid.get-spacing", grid, "a", "()I", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.grid.get-bold", grid, "b", "()I", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.grid.set-spacing", grid, "a", "(I)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.field("cubism.editor-command.grid.developer-setting", devSetting, "INSTANCE", "L" + devSetting + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(StaticSelector.method("cubism.editor-command.grid.get-color", devSetting, "getGridSelectColor", "()L" + color + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.grid.get-jcolor", color, "getJColor", "()Ljava/awt/Color;", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.grid.set-color", devSetting, "setGridSelectColor", "(L" + color + ";)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.constructor("cubism.editor-command.grid.color-create", color, "(III)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.grid.all-view-contexts", pack, "getAllViewContext", "()Ljava/util/List;", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.grid.update-manager", pack, "getUpdateManager", "()L" + updateManager + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.grid.modeling-draw", view, "getDrawImpl", "()L" + draw + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.grid.entity-from-draw", draw, "l", "()L" + gridEntity + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.grid.set-reset", gridEntity, "setResetFlg", "(Z)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.staticMethod("cubism.editor-command.grid.repaint-default", updateManager, "repaintCanvas$default", "(L" + updateManager + ";Ljava/lang/Object;ILjava/lang/Object;)V", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(StaticSelector.field("cubism.editor-command.resize.guard", resizeGuard, "a", "L" + resizeGuard + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(StaticSelector.method("cubism.editor-command.resize.guard-current", resizeGuard, "d", "()Ljava/lang/String;", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.resize.guard-active", resizeGuard, "a", "(Ljava/lang/String;)Z", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.staticMethod("cubism.editor-command.resize.scale-model", handler, "a", "(L" + handler + ";L" + pack + ";L" + editModeMain + ";L" + model + ";FL" + vector + ";L" + vector + ";ILjava/lang/Object;)V", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(StaticSelector.field("cubism.editor-command.external-app.manager", wsX, "a", "L" + wsX + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(StaticSelector.method("cubism.editor-command.external-app.instance", wsX, "a", "()L" + wsL + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.field("cubism.editor-command.external-app.companion", wsL, "a", "L" + internal(FakeWebSocketLCompanion.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(StaticSelector.method("cubism.editor-command.external-app.get-port", wsL, "a", "()I", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.external-app.set-port", wsL, "a", "(I)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.staticMethod("cubism.editor-command.external-app.get-remote", internal(FakeWebSocketLCompanion.class), "k", "()Z", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(StaticSelector.method("cubism.editor-command.external-app.set-remote", internal(FakeWebSocketLCompanion.class), "a", "(Z)V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.external-app.connected", wsL, "f", "()Z", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.external-app.start", wsL, "d", "()V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.external-app.stop", wsX, "d", "()V", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.field("cubism.editor-command.config.instance", config, "a", "L" + config + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(StaticSelector.method("cubism.editor-command.config.read", config, "a", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.config.write", config, "b", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.classSelector("cubism.editor-command.file.scene-document", sceneDoc));
            selectors.add(StaticSelector.method("cubism.editor-command.file.scene-content", sceneDoc, "getAnimationContent", "()L" + sceneContent + ";", StaticSelector.ACCESS_PUBLIC));
            selectors.add(StaticSelector.method("cubism.editor-command.file.save-scene", sceneContent, "saveDocument", "(Ljava/io/File;Z)Z", StaticSelector.ACCESS_PUBLIC));
            if (dropAliases.length > 0) {
                final Set<String> dropped = Set.of(dropAliases);
                selectors.removeIf(selector -> dropped.contains(selector.alias()));
            }
            return TestVerifiedResolvers.create(
                version, "adapter.ui.top-menu", Set.of("cubism.ui-top-menu"), selectors,
                FakeHost.class.getClassLoader()
            );
        }

        private static String internal(final Class<?> type) {
            return type.getName().replace('.', '/');
        }

        // ---- fake host types ----

        public static final class FakeApp {
            static final FakeApp INSTANCE = new FakeApp();
            FakeHost host;
            public static FakeApp instance() { return INSTANCE; }
            public FakeView getCurrentViewContext() {
                host.onEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
                return host.currentView();
            }
            public FakePack getCompletePack() { return new FakePack(host); }
        }

        static class FakeView {
            public FakeCSize getDocumentSize() { return new FakeCSize(800, 600); }
        }

        public static final class FakeCSize {
            private final int width;
            private final int height;
            public FakeCSize(int width, int height) { this.width = width; this.height = height; }
            public int getWidth() { return width; }
            public int getHeight() { return height; }
        }

        final class FakeModelingView extends FakeView {
            public FakeModelingDocument getDoc() { return FakeHost.this.document; }
            public FakeModel getModel() { return new FakeModel(); }
            public FakeDrawImpl getDrawImpl() { return new FakeDrawImpl(FakeHost.this); }
        }

        final class FakeSceneDocument extends FakeView {
            File lastSavedTo;
            public FakeAnimationFileContent getAnimationContent() {
                return new FakeAnimationFileContent(this);
            }
        }

        static final class FakeAnimationFileContent {
            private final FakeSceneDocument document;
            FakeAnimationFileContent(FakeSceneDocument document) { this.document = document; }
            public boolean saveDocument(File file, boolean saveAs) {
                document.lastSavedTo = file;
                return true;
            }
        }

        final class FakeModelingDocument {
            boolean dirty;
            File lastSavedTo;
            public FakeEditModeBase getCurrentEditMode() { return FakeHost.this.currentEditMode(); }
            public FakeModelSource getModelSource() { return FakeHost.this.modelSource; }
            public void updateLastModifiedTime() {
                dirty = true;
                if (FakeHost.failOnMarkDirty) {
                    FakeHost.failOnMarkDirty = false;
                    throw new IllegalStateException("injected host failure at the dirty-mark step");
                }
            }
            public boolean saveDocument(File file, boolean saveAs) {
                lastSavedTo = file;
                return true;
            }
            public File getFile() { return new File("model.cmo3"); }
        }

        class FakeEditModeBase {
            public boolean isEditing() { return FakeHost.this.editing; }
        }

        final class FakeEditModeMain extends FakeEditModeBase implements FakeIEditMode {
            String lastEditLabel;
            boolean endEditCalled;
            int preEditWidth;
            int preEditHeight;
            public FakeModelingDocument getDoc() { return FakeHost.this.document; }
            public FakeGroupUndo beginEdit(String label) {
                lastEditLabel = label;
                preEditWidth = FakeHost.this.canvas.width;
                preEditHeight = FakeHost.this.canvas.height;
                return FakeHost.this.group;
            }
            public FakeUndoManager getUndoManager() { return FakeHost.this.undoManager; }
            public void endEditCalled(boolean value) { endEditCalled = value; }
        }

        final class FakeModelSource {
            public FakeCanvas getCanvas() { return FakeHost.this.canvas; }
            public FakeModelHandler getHandler() { return FakeHost.this.handler; }
        }

        public final class FakeCanvas implements FakeCopyable {
            int width = 1000;
            int height = 1000;
            public static final FakeCanvasCompanion Companion = new FakeCanvasCompanion();
            public int getPixelWidth() { return width; }
            public int getPixelHeight() { return height; }
            public void setPixelWidth(int w) {
                width = w;
                                    if (FakeHost.failOnSetWidth) {
                FakeHost.failOnSetWidth = false;
                    throw new IllegalStateException("injected host failure after width mutation");
                }
            }
            public void setPixelHeight(int h) { height = h; }
        }

        static final class FakeCanvasCompanion {
            public void a(int w, int h) { }
        }

        final class FakeModelHandler {
            float lastScale;
            float lastDeltaX;
            float lastDeltaY;
            public void a(FakeModel model, float scale, FakeGVector2 t, FakeGVector2 zero, FakeGroupUndo group) {
                lastScale = scale;
                lastDeltaX = t.x;
                lastDeltaY = t.y;
                if (FakeHost.failOnScale) {
                    FakeHost.failOnScale = false;
                    throw new IllegalStateException("injected host failure at the model scale step");
                }
            }
            public static void a(FakeModelHandler handler, FakePack pack, FakeEditModeMain editMode,
                                 FakeModel model, float scale, FakeGVector2 t, FakeGVector2 zero,
                                 int mask, Object marker) {
                handler.lastScale = scale;
                // the native path begins an edit and registers the group before scaling
                editMode.lastEditLabel = "Resize Document";
                editMode.getUndoManager().entries.add(new Object());
                if (FakeHost.failOnScale) {
                    FakeHost.failOnScale = false;
                    throw new IllegalStateException("injected host failure in the native resize path");
                }
            }
        }

        final class FakeGroupUndo {
            int undoCount;
            public void plusAssign(FakeUndoable undo) {
                undoCount++;
                FakeHost.this.undoManager.entries.add(undo);
            }
        }

        public static final class FakeGVector2 {
            final float x;
            final float y;
            public FakeGVector2() { this(0f, 0f); }
            public FakeGVector2(float x, float y) { this.x = x; this.y = y; }
        }

        final class FakeUndoManager {
            final List<Object> entries = new ArrayList<>();
            int undoCount;
            public List<Object> getUndoList() { return entries; }
            public int getCurrentPos() { return entries.size(); }
            public boolean canUndo() { return !entries.isEmpty(); }
            public boolean canRedo() { return false; }
            public void undo() {
                undoCount++;
                if (FakeHost.failOnUndo) {
                    FakeHost.failOnUndo = false;
                    throw new IllegalStateException("injected host failure in the undo rollback");
                }
                if (!entries.isEmpty()) {
                    entries.remove(entries.size() - 1);
                }
                FakeHost.this.canvas.width = FakeHost.this.editMode.preEditWidth;
                FakeHost.this.canvas.height = FakeHost.this.editMode.preEditHeight;
            }
        }

        public static final class FakeGridEntity {
            public static FakeGridEntity a = new FakeGridEntity();
            int spacing = 50;
            int setSpacingCalls;
            public int a() { return spacing; }
            public void a(int value) {
                setSpacingCalls++;
                if (FakeHost.failOnSetSpacing) {
                    FakeHost.failOnSetSpacing = false;
                    throw new IllegalStateException("injected host failure after spacing mutation");
                }
                if (FakeHost.failOnRestoreSpacing && setSpacingCalls > 1) {
                    FakeHost.failOnRestoreSpacing = false;
                    throw new IllegalStateException("injected host failure restoring the spacing");
                }
                spacing = value;
            }
            public int b() { return 5; }
        }

        public static final class FakeDeveloperSetting {
            public static FakeDeveloperSetting INSTANCE = new FakeDeveloperSetting();
            int lastRed;
            int lastGreen;
            int lastBlue;
            public FakeCColor getGridSelectColor() { return new FakeCColor(128, 128, 128); }
            public void setGridSelectColor(FakeCColor color) {
                lastRed = color.r;
                lastGreen = color.g;
                lastBlue = color.b;
            }
        }

        public static final class FakeDrawImpl {
            private final FakeHost host;
            FakeDrawImpl(FakeHost host) { this.host = host; }
            public FakeGridPanelEntity l() { return host.gridEntity; }
        }

        static final class FakeGridPanelEntity {
            int resetCount;
            public void setResetFlg(boolean value) { if (value) resetCount++; }
        }

        static final class FakeUpdateManager {
            int repaints;
            public static void repaintCanvas$default(FakeUpdateManager self, Object b, int mask, Object marker) {
                self.repaints++;
                                    if (FakeHost.failOnRepaint) {
                FakeHost.failOnRepaint = false;
                    throw new IllegalStateException("injected host failure at the repaint step");
                }
            }
        }

        public static final class FakeResizeGuard {
            public static FakeResizeGuard a = new FakeResizeGuard();
            boolean active;
            public String d() { return "resize"; }
            public boolean a(String current) { return active; }
        }

        public static final class FakeWebSocketX {
            public static FakeWebSocketX a = new FakeWebSocketX();
            final FakeWebSocketL instance = new FakeWebSocketL();
            public FakeWebSocketL a() { return instance; }
            public void d() {
                if (FakeHost.failOnStop) {
                    FakeHost.failOnStop = false;
                    throw new IllegalStateException("injected host failure stopping the server");
                }
                instance.stopped = true;
                instance.started = false;
            }
        }

        public static final class FakeWebSocketLCompanion {
            public static boolean remoteFlag;
            public static boolean k() { return remoteFlag; }
            public void a(boolean value) {
                remoteFlag = value;
                if (FakeHost.failOnSetRemote) {
                    FakeHost.failOnSetRemote = false;
                    throw new IllegalStateException("injected host failure after the remote mutation");
                }
            }
        }

        public static final class FakeWebSocketL {
            public static final FakeWebSocketLCompanion a = new FakeWebSocketLCompanion();
            int port = 22033;
            boolean connected;
            boolean started;
            boolean stopped;
            int setPortCalls;
            public int a() { return port; }
            public void a(int value) {
                setPortCalls++;
                if (FakeHost.failOnRestorePort && setPortCalls > 1) {
                    FakeHost.failOnRestorePort = false;
                    throw new IllegalStateException("injected host failure restoring the port");
                }
                port = value;
            }
            public boolean f() { return connected; }
            public void d() {
                started = true;
                if (FakeHost.failOnStart) {
                    FakeHost.failOnStart = false;
                    throw new IllegalStateException("injected host failure at the server start");
                }
            }
        }

        public static final class FakeUUConfig {
            public static FakeUUConfig a = new FakeUUConfig();
            final java.util.Map<String, Object> values = new java.util.HashMap<>();
            public Object a(String key, Object fallback) { return values.getOrDefault(key, fallback); }
            public Object b(String key, Object value) { return values.put(key, value); }
        }

        public static final class FakeFileOps {
            public static FakeFileOps a = new FakeFileOps();
            final List<File> opened = new ArrayList<>();
            public void a(FakeApp app, List<File> files) { opened.addAll(files); }
        }

        // ---- support types referenced by selectors ----

        interface FakeCopyable {
            static FakeCopyable a(FakeCopyable target, Object context, int mask, Object marker) {
                return target;
            }
        }

        interface FakeUndoable {
        }

        interface FakeIEditMode {
        }

        public static final class FakeSimpleUndo implements FakeUndoable {
            public FakeSimpleUndo(String label, FakeCopyable target, Object context) { }
        }

        static final class FakeModel {
        }

        static final class FakePack {
            private final FakeHost host;
            FakePack(FakeHost host) { this.host = host; }
            public List<Object> getAllViewContext() {
                return host.viewKind.equals("modeling") ? List.of(host.view) : List.of();
            }
            public FakeUpdateManager getUpdateManager() { return host.updateManager; }
        }

        public static final class FakeCColor {
            final int r;
            final int g;
            final int b;
            public FakeCColor(int r, int g, int b) { this.r = r; this.g = g; this.b = b; }
            public java.awt.Color getJColor() { return new java.awt.Color(r, g, b); }
        }

        static final class FakeEditModeB {
            public static boolean a(FakeIEditMode mode, boolean refresh, Object callback,
                                    int mask, Object marker) {
                if (FakeHost.failOnEndEdit) {
                    FakeHost.failOnEndEdit = false;
                    throw new IllegalStateException("injected host failure closing the edit");
                }
                if (mode instanceof FakeEditModeMain main) {
                    main.endEditCalled = true;
                }
                return true;
            }
        }
    }
}
