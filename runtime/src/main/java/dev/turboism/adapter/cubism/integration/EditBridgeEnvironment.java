package dev.turboism.adapter.cubism.integration;

import dev.turboism.sdk.cubism.edit.EditSessionService;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.plugin.PluginContext;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Everything the protocol bridge needs from the runtime, behind one injectable seam.
 *
 * <p>The production wiring assembles this from the verified member resolver (connection
 * inspection), the {@code RuntimeEditSessionProvider} view of the model access (edit
 * sessions), the project workspace adapter (active document), and the approval dialog. Every
 * member may degrade: an absent inspector reports connections unregistered, an unavailable
 * session service fails engine calls closed, and a denying approval gate refuses editing —
 * the bridge still answers claimed messages with official-shaped errors.</p>
 */
public final class EditBridgeEnvironment {

    private final EditConnectionInspector inspector;
    private final EditSessionService editSessions;
    private final PluginContext pluginContext;
    private final Supplier<Optional<DocumentId>> activeDocument;
    private final EditApprovalGate approvalGate;

    public EditBridgeEnvironment(
        final EditConnectionInspector inspector,
        final EditSessionService editSessions,
        final PluginContext pluginContext,
        final Supplier<Optional<DocumentId>> activeDocument,
        final EditApprovalGate approvalGate
    ) {
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.editSessions = Objects.requireNonNull(editSessions, "editSessions");
        this.pluginContext = Objects.requireNonNull(pluginContext, "pluginContext");
        this.activeDocument = Objects.requireNonNull(activeDocument, "activeDocument");
        this.approvalGate = Objects.requireNonNull(approvalGate, "approvalGate");
    }

    /** {@return the host connection-record reader} */
    public EditConnectionInspector inspector() {
        return inspector;
    }

    /** {@return the 046 editing surface the handlers delegate to} */
    public EditSessionService editSessions() {
        return editSessions;
    }

    /** {@return the bridge-scoped plugin identity handed to the engine} */
    public PluginContext pluginContext() {
        return pluginContext;
    }

    /** {@return the active modeling document identity, or empty when none is active} */
    public Optional<DocumentId> activeDocument() {
        return activeDocument.get();
    }

    /** {@return the Turboism edit-approval gate} */
    public EditApprovalGate approvalGate() {
        return approvalGate;
    }

    /**
     * {@return the fail-closed environment: no host records, no engine, approval denied}
     *
     * <p>Used when the verified dispatch bindings are absent — the bridge still intercepts and
     * answers recognized editing requests with official-shaped errors instead of falling
     * through to a host path that cannot serve them.</p>
     */
    public static EditBridgeEnvironment unavailable(final PluginContext pluginContext) {
        return new EditBridgeEnvironment(
            EditConnectionInspector.unavailable(),
            EditSessionService.unavailable(),
            Objects.requireNonNull(pluginContext, "pluginContext"),
            Optional::empty,
            EditApprovalGate.denyAll());
    }

    /**
     * {@return the fail-closed environment with an unwired plugin identity}
     *
     * <p>The stub context is never dereferenced: {@link EditSessionService#unavailable()}
     * fails closed before consulting it. Used by the default bridge constructor.</p>
     */
    public static EditBridgeEnvironment unavailable() {
        return unavailable(UNWIRED_CONTEXT);
    }

    /**
     * {@return the production environment composition}
     *
     * <p>Assembles the verified-selector connection inspector, the supplied engine and
     * document surfaces, and the Swing approval dialog. This is the composition the host
     * bootstrap wires once the resolver and plugin services exist.</p>
     */
    public static EditBridgeEnvironment production(
        final dev.turboism.mapping.verification.VerifiedMemberResolver resolver,
        final EditSessionService editSessions,
        final PluginContext pluginContext,
        final Supplier<Optional<DocumentId>> activeDocument,
        final Supplier<Optional<Object>> mainWindow
    ) {
        return production(
            resolver, editSessions, pluginContext, activeDocument,
            new SwingEditApprovalGate(mainWindow));
    }

    /**
     * {@return the production environment composition with an explicit approval gate}
     *
     * <p>Same composition as the Swing-dialog variant, but the caller picks the
     * {@link EditApprovalGate}: {@link EditToggleApprovalGate} when the native edit checkbox
     * is admitted and installed (051 approval source — the connection-time prompt degrades
     * to nothing because the checkbox IS the grant), {@link SwingEditApprovalGate} on the
     * 050 fallback path.</p>
     */
    public static EditBridgeEnvironment production(
        final dev.turboism.mapping.verification.VerifiedMemberResolver resolver,
        final EditSessionService editSessions,
        final PluginContext pluginContext,
        final Supplier<Optional<DocumentId>> activeDocument,
        final EditApprovalGate approvalGate
    ) {
        return new EditBridgeEnvironment(
            new VerifiedEditConnectionInspector(
                Objects.requireNonNull(resolver, "resolver")),
            editSessions, pluginContext, activeDocument,
            Objects.requireNonNull(approvalGate, "approvalGate"));
    }

    /**
     * {@return the production composition under the bridge's own plugin identity}
     *
     * <p>The engine only requires a non-null context (admission is binding-scoped, not
     * context-scoped), so the bridge runs under its unwired identity until the plugin runtime
     * hands it a real one.</p>
     */
    public static EditBridgeEnvironment production(
        final dev.turboism.mapping.verification.VerifiedMemberResolver resolver,
        final EditSessionService editSessions,
        final Supplier<Optional<DocumentId>> activeDocument,
        final Supplier<Optional<Object>> mainWindow
    ) {
        return production(resolver, editSessions, UNWIRED_CONTEXT, activeDocument, mainWindow);
    }

    /**
     * {@return the production composition under the bridge's own plugin identity with an
     *          explicit approval gate}
     *
     * <p>The 051 wiring path: the bootstrap passes {@link EditToggleApprovalGate} when the
     * native edit checkbox is admitted, otherwise the Swing prompt.</p>
     */
    public static EditBridgeEnvironment production(
        final dev.turboism.mapping.verification.VerifiedMemberResolver resolver,
        final EditSessionService editSessions,
        final Supplier<Optional<DocumentId>> activeDocument,
        final EditApprovalGate approvalGate
    ) {
        return production(resolver, editSessions, UNWIRED_CONTEXT, activeDocument, approvalGate);
    }

    /** Minimal context for the fail-closed environment; no method is ever invoked. */
    private static final PluginContext UNWIRED_CONTEXT = new PluginContext() {
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
        public java.util.List<dev.turboism.sdk.permission.PluginPermission> permissions() {
            return java.util.List.of();
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
