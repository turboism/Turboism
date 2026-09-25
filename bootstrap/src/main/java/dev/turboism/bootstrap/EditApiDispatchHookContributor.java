package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.integration.EditApprovalGate;
import dev.turboism.adapter.cubism.integration.EditBridgeEnvironment;
import dev.turboism.adapter.cubism.integration.EditProtocolBridge;
import dev.turboism.adapter.cubism.integration.EditSocketWriter;
import dev.turboism.adapter.cubism.integration.EditToggleApprovalGate;
import dev.turboism.adapter.cubism.integration.EditToggleConfigStore;
import dev.turboism.adapter.cubism.integration.EditToggleState;
import dev.turboism.adapter.cubism.integration.NativeEditToggleInjector;
import dev.turboism.adapter.cubism.integration.SwingEditApprovalGate;
import dev.turboism.adapter.cubism.integration.VerifiedEditApiDispatchInstaller;
import dev.turboism.adapter.cubism.integration.VerifiedEditToggleHookInstaller;
import dev.turboism.preview.PreviewRuntime;

import java.util.Optional;

/**
 * Declarative contributor for the edit-protocol dispatch interception point on
 * the host dispatcher. It needs the runtime's verified editor-model resolver
 * and host session access, so it installs in {@link Phase#RUNTIME_STARTED} and
 * closes on the process-exit path with the {@code cleanup=COMPLETE} protocol
 * line.
 *
 * <p>The bridge answers the official-1.1.0-compatible editing methods through
 * the verified-selector environment (connection records, Phase-046 edit
 * sessions, the approval gate) while every other message keeps flowing
 * through the native path — including when the reviewed selectors are absent,
 * the bridge is disabled, or the receiver throws.</p>
 *
 * <p>When the verified settings-dialog surface is admitted, the native
 * 「编辑」 checkbox (spec 051) replaces the 050 connection-time approval
 * prompt as the live grant source; every failure on that path falls back to
 * the Swing prompt gate.</p>
 */
final class EditApiDispatchHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_EDIT_API_DISPATCH";
    }

    @Override public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override public boolean closesOnProcessExit() {
        return true;
    }

    /**
     * Same ordinary reviewed admission as the other host hooks; the deeper
     * gate is inside {@code VerifiedEditApiDispatchInstaller.fromVerifiedResolver},
     * which refuses without a reviewed dispatch-entry selector and keeps the
     * feature inert.
     */
    @Override public boolean admitted(final HookEnvironment environment) {
        return environment.ordinaryReviewedRuntimeAdmitted();
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final var runtime = environment.runtime().orElseThrow();
        final var host = environment.host().orElseThrow();
        VerifiedEditApiDispatchInstaller installer = null;
        final java.util.List<AutoCloseable> toggleResources = new java.util.ArrayList<>();
        try {
            installer = VerifiedEditApiDispatchInstaller.fromVerifiedResolver(
                environment.instrumentation(),
                runtime.editorModelResolver(),
                host.classLoader()
            );
            final dev.turboism.sdk.cubism.edit.EditSessionService editSessions =
                runtime.hostAccess().modelAccess()
                    instanceof dev.turboism.adapter.cubism.edit.RuntimeEditSessionProvider provider
                    ? provider.editSessions(
                        "turboism.edit-api-bridge",
                        () -> activeDocument(runtime))
                    : dev.turboism.sdk.cubism.edit.EditSessionService.unavailable();
            // 051 P2: the native 「编辑」 checkbox replaces the connection-time approval
            // prompt whenever the verified injector surface is admitted. The toggle state
            // is loaded from the host-domain UUConfig key before the bridge gate is chosen.
            final EditApprovalGate approvalGate = installNativeEditToggle(
                environment, host, toggleResources)
                .orElseGet(() -> new SwingEditApprovalGate(java.util.Optional::empty));
            final EditProtocolBridge bridge = new EditProtocolBridge(
                EditSocketWriter.reflective(),
                EditBridgeEnvironment.production(
                    runtime.editorModelResolver(),
                    editSessions,
                    () -> activeDocument(runtime),
                    approvalGate));
            if (!installer.install(bridge.receiver())) {
                return () -> { };
            }
            NativeOptimizationHookContributor.log(
                environment,
                "TURBOISM_EDIT_API_DISPATCH installation=COMPLETE retransformed="
                    + String.join(",", installer.transformedClassNames())
            );
            final VerifiedEditApiDispatchInstaller installed = installer;
            return () -> {
                for (AutoCloseable resource : toggleResources) {
                    resource.close();
                }
                installed.close();
            };
        } catch (final Throwable failure) {
            for (AutoCloseable resource : toggleResources) {
                try {
                    resource.close();
                } catch (final Throwable ignored) {
                    // cleanup is best effort
                }
            }
            if (installer != null) {
                try {
                    installer.close();
                } catch (final Throwable ignored) {
                    // cleanup is best effort
                }
            }
            NativeOptimizationHookContributor.log(
                environment,
                "Turboism edit-protocol dispatch hook disabled safely: "
                    + failure.getClass().getName() + ": " + failure.getMessage()
            );
            return () -> { };
        }
    }

    /**
     * Wires the native 「编辑」 edit checkbox (spec 051, Phase 2) when the verified
     * surface is admitted.
     *
     * <p>On success this installs the {@code y.b} return hook (re-injection before every
     * dialog open), loads the persisted state from the host-domain UUConfig key
     * {@code CExternalAppSettingDialog.EditEnabled}, registers the write-back listener, and
     * returns the live-state {@link EditToggleApprovalGate} the protocol bridge consults —
     * the checkbox IS the grant, so the 050 connection-time Swing prompt is not installed on
     * this path. Every failure — missing admission, kill switch, hook failure — returns
     * {@code Optional.empty()} and the caller falls back to the 050 prompt gate; nothing is
     * left half-installed and the native dialog stays untouched.</p>
     */
    private static Optional<EditApprovalGate> installNativeEditToggle(
        final HookEnvironment environment,
        final HostClassLocator.LocatedHost host,
        final java.util.List<AutoCloseable> toggleResources
    ) {
        if ("false".equalsIgnoreCase(
            System.getProperty(NativeEditToggleInjector.ENABLED_PROPERTY))) {
            return Optional.empty();
        }
        final var resolver = environment.runtime().orElseThrow().editorModelResolver();
        final EditToggleState state = new EditToggleState();
        final Optional<NativeEditToggleInjector> injector =
            NativeEditToggleInjector.fromVerifiedResolver(resolver, state);
        if (injector.isEmpty()) {
            return Optional.empty();
        }
        EditToggleConfigStore.fromVerifiedResolver(resolver).ifPresent(store -> {
            state.setEnabled(store.load());
            state.addListener(store::store);
        });
        VerifiedEditToggleHookInstaller hook = null;
        try {
            hook = VerifiedEditToggleHookInstaller.fromVerifiedResolver(
                environment.instrumentation(), resolver, host.classLoader());
            if (!hook.install(() -> injector.get().ensureInjectedOnEdt())) {
                hook.close();
                return Optional.empty();
            }
            final VerifiedEditToggleHookInstaller installed = hook;
            final NativeEditToggleInjector bound = injector.get();
            toggleResources.add(() -> {
                try {
                    installed.close();
                } finally {
                    javax.swing.SwingUtilities.invokeLater(bound::removeInjected);
                }
            });
            NativeOptimizationHookContributor.log(
                environment,
                "TURBOISM_EDIT_TOGGLE_HOOK installation=COMPLETE retransformed="
                    + String.join(",", installed.transformedClassNames()));
            return Optional.of(new EditToggleApprovalGate(state));
        } catch (final Throwable failure) {
            if (hook != null) {
                try {
                    hook.close();
                } catch (final Throwable ignored) {
                    // cleanup is best effort
                }
            }
            NativeOptimizationHookContributor.log(
                environment,
                "Turboism native edit-toggle hook disabled safely: "
                    + failure.getClass().getName());
            return Optional.empty();
        }
    }

    /**
     * {@return the host's active-document identity for edit-session admission}
     *
     * <p>Mirrors {@code DefaultCubismServicesFactory.activeDocumentId}: empty
     * whenever the workspace cannot report a document, which fails engine calls
     * closed.</p>
     */
    private static java.util.Optional<dev.turboism.sdk.cubism.id.DocumentId>
        activeDocument(final PreviewRuntime runtime) {
        final dev.turboism.adapter.cubism.ProjectWorkspaceAdapter
            .AdapterResult<java.util.Optional<dev.turboism.sdk.cubism.DocumentSnapshot>> result =
            runtime.hostAccess().adapters().projectWorkspace().activeDocument();
        if (!result.isAvailable() || result.value().isEmpty()) {
            return java.util.Optional.empty();
        }
        return result.value().orElseThrow().map(
            snapshot -> new dev.turboism.sdk.cubism.id.DocumentId(snapshot.documentId()));
    }
}
