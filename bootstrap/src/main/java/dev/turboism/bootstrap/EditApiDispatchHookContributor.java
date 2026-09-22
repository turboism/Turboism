package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.integration.EditBridgeEnvironment;
import dev.turboism.adapter.cubism.integration.EditProtocolBridge;
import dev.turboism.adapter.cubism.integration.EditSocketWriter;
import dev.turboism.adapter.cubism.integration.VerifiedEditApiDispatchInstaller;
import dev.turboism.preview.PreviewRuntime;

/**
 * Declarative contributor for the edit-protocol dispatch interception point on
 * the host dispatcher. It needs the runtime's verified editor-model resolver
 * and host session access, so it installs in {@link Phase#RUNTIME_STARTED} and
 * closes on the process-exit path with the {@code cleanup=COMPLETE} protocol
 * line.
 *
 * <p>The bridge answers the official-1.1.0-compatible editing methods through
 * the verified-selector environment (connection records, Phase-046 edit
 * sessions, the approval dialog) while every other message keeps flowing
 * through the native path — including when the reviewed selectors are absent,
 * the bridge is disabled, or the receiver throws.</p>
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
            final EditProtocolBridge bridge = new EditProtocolBridge(
                EditSocketWriter.reflective(),
                EditBridgeEnvironment.production(
                    runtime.editorModelResolver(),
                    editSessions,
                    () -> activeDocument(runtime),
                    java.util.Optional::empty));
            if (!installer.install(bridge.receiver())) {
                return () -> { };
            }
            NativeOptimizationHookContributor.log(
                environment,
                "TURBOISM_EDIT_API_DISPATCH installation=COMPLETE retransformed="
                    + String.join(",", installer.transformedClassNames())
            );
            final VerifiedEditApiDispatchInstaller installed = installer;
            return installed::close;
        } catch (final Throwable failure) {
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
