package dev.turboism.adapter.cubism.integration;

import dev.turboism.sdk.cubism.edit.CancelSource;
import dev.turboism.sdk.cubism.edit.DeformerOps;
import dev.turboism.sdk.cubism.edit.EditObjectNode;
import dev.turboism.sdk.cubism.edit.EditObjectSnapshot;
import dev.turboism.sdk.cubism.edit.EditParameterGroupNode;
import dev.turboism.sdk.cubism.edit.EditSession;
import dev.turboism.sdk.cubism.edit.EditSessionCloseOutcome;
import dev.turboism.sdk.cubism.edit.EditSessionCloseResult;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditSessionListener;
import dev.turboism.sdk.cubism.edit.EditSessionOpenResult;
import dev.turboism.sdk.cubism.edit.EditSessionOptions;
import dev.turboism.sdk.cubism.edit.EditSessionService;
import dev.turboism.sdk.cubism.edit.EditSessionState;
import dev.turboism.sdk.cubism.edit.ParameterKeyOps;
import dev.turboism.sdk.cubism.edit.ParameterStructureOps;
import dev.turboism.sdk.cubism.edit.PartObjectOps;
import dev.turboism.sdk.cubism.edit.SelectionOps;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.plugin.PluginContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Headless fake of the Phase-046 editing surface used by the protocol-matrix tests.
 *
 * <p>{@link FakeEditSession} records every delegated call (last request object per family,
 * log lines, progress values, close/cancel invocations) and returns canned results; {@link
 * FakeEditSessionService} controls admission ({@code approved}), open refusals, and the
 * session handed out. Nothing touches host classes — the bridge's environment seam is the
 * only thing under test.</p>
 */
final class FakeEditEngine {

    private FakeEditEngine() {
    }

    /** {@return an inspector reporting every socket as registered + authorized} */
    static EditConnectionInspector registered() {
        return socket -> Optional.of(new EditConnectionInfo(
            true, true, "key-" + Integer.toHexString(System.identityHashCode(socket)),
            "fake-plugin"));
    }

    /** {@return an inspector reporting every socket as present but unregistered} */
    static EditConnectionInspector unregistered() {
        return socket -> Optional.of(new EditConnectionInfo(
            false, false, "key", "fake-plugin"));
    }

    /** {@return a gate that grants approval without interaction} */
    static EditApprovalGate approveAll() {
        return new EditApprovalGate() {
            @Override
            public boolean isApproved(final EditConnectionInfo connection) {
                return true;
            }

            @Override
            public boolean requestApproval(final EditConnectionInfo connection) {
                return true;
            }
        };
    }

    /** {@return a gate that refuses approval without interaction} */
    static EditApprovalGate denyAll() {
        return EditApprovalGate.denyAll();
    }

    /** Assembles a test environment. */
    static EditBridgeEnvironment env(
        final EditConnectionInspector inspector,
        final EditSessionService sessions,
        final java.util.function.Supplier<Optional<DocumentId>> activeDocument,
        final EditApprovalGate gate
    ) {
        return new EditBridgeEnvironment(
            inspector, sessions, context(), activeDocument, gate);
    }

    /** {@return a bridge over a recording writer and the given environment} */
    static EditProtocolBridge bridge(
        final List<String> sent,
        final EditBridgeEnvironment env
    ) {
        return new EditProtocolBridge((socket, text) -> sent.add(text), env);
    }

    /** Minimal plugin context; the fakes never dereference it. */
    static PluginContext context() {
        return new PluginContext() {
            @Override
            public dev.turboism.sdk.plugin.PluginDescriptor descriptor() {
                return null;
            }

            @Override
            public dev.turboism.sdk.plugin.PluginLogger logger() {
                return null;
            }

            @Override
            public dev.turboism.sdk.plugin.PluginPaths paths() {
                return null;
            }

            @Override
            public dev.turboism.sdk.cubism.CubismFacade cubism() {
                return null;
            }

            @Override
            public List<dev.turboism.sdk.permission.PluginPermission> permissions() {
                return List.of();
            }

            @Override
            public dev.turboism.sdk.event.EventBus eventBus() {
                return null;
            }

            @Override
            public dev.turboism.sdk.action.ActionRegistry actions() {
                return null;
            }

            @Override
            public dev.turboism.sdk.menu.MenuRegistry menus() {
                return null;
            }

            @Override
            public dev.turboism.sdk.ui.UiScheduler uiScheduler() {
                return null;
            }

            @Override
            public dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
                return null;
            }

            @Override
            public dev.turboism.sdk.plugin.DisposableScope disposableScope() {
                return new dev.turboism.sdk.plugin.DisposableScope();
            }
        };
    }

    /** Fake {@link EditSessionService}: admission flag, canned sessions, open refusal. */
    static final class Service implements EditSessionService {
        boolean approved = true;
        int openCalls;
        DocumentId lastDocument;
        EditSessionOptions lastOptions;
        EditSessionException openFailure;
        final Function<EditSessionOptions, FakeSession> sessionFactory;

        Service() {
            this(options -> new FakeSession());
        }

        Service(final Function<EditSessionOptions, FakeSession> sessionFactory) {
            this.sessionFactory = sessionFactory;
        }

        final List<FakeSession> sessions = new ArrayList<>();

        @Override
        public boolean isEditApproved(final PluginContext context) {
            return approved;
        }

        @Override
        public EditSession open(
            final PluginContext context,
            final DocumentId document,
            final EditSessionOptions options
        ) throws EditSessionException {
            openCalls++;
            lastDocument = document;
            lastOptions = options;
            if (openFailure != null) {
                throw openFailure;
            }
            final FakeSession session = sessionFactory.apply(options);
            sessions.add(session);
            return session;
        }
    }

    /** Fake {@link EditSession}: open state, recording ops families, close/cancel outcomes. */
    static class FakeSession implements EditSession {
        boolean open = true;
        final List<String> logs = new ArrayList<>();
        final List<Double> progresses = new ArrayList<>();
        int closeCalls;
        int cancelCalls;
        EditSessionCloseOutcome closeOutcome = EditSessionCloseOutcome.COMMITTED;
        EditSessionCloseOutcome cancelOutcome = EditSessionCloseOutcome.CANCELLED;
        EditSessionException closeFailure;
        EditSessionListener undoListener;
        final Keys keys = new Keys();
        final Structure structure = new Structure();
        final Selection selection = new Selection();
        final Parts parts = new Parts();
        final Deformers deformers = new Deformers();

        FakeSession() {
        }

        FakeSession(final EditSessionOptions options) {
            options.undoCancelListener().ifPresent(listener -> undoListener = listener);
        }

        /** Simulates a host-side undo-cancel: fires the registered listener, closes state. */
        void fireUndoCancel(final CancelSource source) {
            open = false;
            if (undoListener != null) {
                undoListener.onUndoCancelled(this, source);
            }
        }

        @Override
        public DocumentId document() {
            return new DocumentId("doc-fake");
        }

        @Override
        public EditSessionState state() {
            return open ? EditSessionState.OPEN : EditSessionState.CLOSED;
        }

        @Override
        public EditSessionOpenResult openResult() {
            return new EditSessionOpenResult(
                document(), EditSessionOptions.defaults());
        }

        @Override
        public Optional<CancelSource> cancelledBy() {
            return Optional.empty();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void log(final String message) {
            logs.add(message);
        }

        @Override
        public void progress(final double value) {
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException("progress must be within [0, 1]");
            }
            progresses.add(value);
        }

        @Override
        public EditSessionCloseResult cancel() throws EditSessionException {
            cancelCalls++;
            open = false;
            if (closeFailure != null) {
                throw closeFailure;
            }
            return closeResult(cancelOutcome);
        }

        @Override
        public EditSessionCloseResult close() throws EditSessionException {
            closeCalls++;
            open = false;
            if (closeFailure != null) {
                throw closeFailure;
            }
            return closeResult(closeOutcome);
        }

        private EditSessionCloseResult closeResult(final EditSessionCloseOutcome outcome) {
            return switch (outcome) {
                case COMMITTED -> EditSessionCloseResult.committed();
                case CANCELLED -> EditSessionCloseResult.cancelled(
                    CancelSource.PLUGIN);
                default -> EditSessionCloseResult.failed("fake-failure");
            };
        }

        @Override
        public ParameterKeyOps parameterKeys() {
            return keys;
        }

        @Override
        public ParameterStructureOps parameterStructure() {
            return structure;
        }

        @Override
        public SelectionOps selection() {
            return selection;
        }

        @Override
        public PartObjectOps partObjects() {
            return parts;
        }

        @Override
        public DeformerOps deformers() {
            return deformers;
        }
    }

    /** Fake {@link ParameterKeyOps}. */
    static final class Keys implements ParameterKeyOps {
        Object lastRequest;
        boolean booleanResult = true;
        List<ParameterKeyValues> keysResult = List.of();
        List<ModelObjectId> objectsResult = List.of();

        @Override
        public boolean addParameterKey(final AddParameterKey request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean deleteParameterKey(final DeleteParameterKey request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean moveParameterKey(final MoveParameterKey request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public List<ParameterKeyValues> parameterKeys(final GetParameterKeys request) {
            lastRequest = request;
            return keysResult;
        }

        @Override
        public List<ModelObjectId> objectsByParameterKeys(
            final GetObjectsByParameterKeys request
        ) {
            lastRequest = request;
            return objectsResult;
        }
    }

    /** Fake {@link ParameterStructureOps}. */
    static final class Structure implements ParameterStructureOps {
        Object lastRequest;
        boolean booleanResult = true;
        EditParameterGroupNode treeResult;

        @Override
        public EditParameterGroupNode parameterStructure() {
            return Objects.requireNonNullElseGet(treeResult, () -> EditFixtures.parameterTree());
        }

        @Override
        public boolean addParameter(final AddParameter request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean addParameterGroup(final AddParameterGroup request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean editParameter(final EditParameter request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean editParameterGroup(final EditParameterGroup request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean deleteParameter(final DeleteParameter request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean deleteParameterGroup(final DeleteParameterGroup request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean moveParameter(final MoveParameter request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean moveParameterGroup(final MoveParameterGroup request) {
            lastRequest = request;
            return booleanResult;
        }
    }

    /** Fake {@link SelectionOps}. */
    static final class Selection implements SelectionOps {
        Object lastRequest;
        boolean booleanResult = true;
        List<ModelObjectId> selectedResult = List.of();

        @Override
        public List<ModelObjectId> selectedObjects() {
            return selectedResult;
        }

        @Override
        public boolean addSelectedObjects(final AddSelectedObjects request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean clearSelectedObjects() {
            lastRequest = "clear";
            return booleanResult;
        }
    }

    /** Fake {@link PartObjectOps}. */
    static final class Parts implements PartObjectOps {
        Object lastRequest;
        boolean booleanResult = true;
        EditObjectNode treeResult;
        EditObjectSnapshot objectResult;

        @Override
        public EditObjectNode partStructure() {
            return Objects.requireNonNullElseGet(treeResult, EditFixtures::objectTree);
        }

        @Override
        public EditObjectSnapshot object(final GetObject request) {
            lastRequest = request;
            return Objects.requireNonNullElseGet(objectResult, EditFixtures::snapshot);
        }

        @Override
        public boolean deleteObject(final DeleteObject request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean moveObjectOnPartsPalette(final MoveObjectOnPartsPalette request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean addPart(final AddPart request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean editPart(final EditPart request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean editArtMesh(final EditArtMesh request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean editGlue(final EditGlue request) {
            lastRequest = request;
            return booleanResult;
        }
    }

    /** Fake {@link DeformerOps}. */
    static final class Deformers implements DeformerOps {
        Object lastRequest;
        boolean booleanResult = true;
        EditObjectNode treeResult;

        @Override
        public EditObjectNode deformerStructure() {
            return Objects.requireNonNullElseGet(treeResult, EditFixtures::deformerTree);
        }

        @Override
        public boolean addRotationDeformer(final AddRotationDeformer request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean addWarpDeformer(final AddWarpDeformer request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean editRotationDeformer(final EditRotationDeformer request) {
            lastRequest = request;
            return booleanResult;
        }

        @Override
        public boolean editWarpDeformer(final EditWarpDeformer request) {
            lastRequest = request;
            return booleanResult;
        }
    }
}
