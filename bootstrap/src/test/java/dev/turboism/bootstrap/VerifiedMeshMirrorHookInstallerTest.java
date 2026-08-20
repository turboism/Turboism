package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile;
import dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VerifiedMeshMirrorHookInstallerTest {

    @AfterEach
    void clearBridge() {
        NativeMeshMirrorBridge.uninstall();
    }

    @Test
    void earlyInstallLeavesBridgeUnboundUntilTheSingleLaterBind() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = instrumentation(calls);
        final MeshMirrorHostProfile profile = profile();
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
        final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation(calls), getClass().getClassLoader(), axis, ui, profile, calls::add
        );

        installer.install();
        assertEquals("add:true", calls.get(0));
        final Object original = new Object();
        assertSame(original, NativeMeshMirrorBridge.adjustPoint(original, new Object(), new Object()));

        installer.bind();
        installer.bind();
        assertTrue(installer.isInstalled());
        assertTrue(installer.isBound());
        assertEquals(1, calls.stream().filter(value -> value.equals("add:true")).count());

        installer.close();
    }

    @Test
    void registrationDoesNotClaimTargetTransformationReadiness() throws Exception {
        final List<String> calls = new ArrayList<>();
        final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation(calls), getClass().getClassLoader(), null, null, profile(), calls::add
        );
        installer.install();
        assertTrue(installer.isInstalled());
        assertFalse(installer.targetTransformed());
        assertTrue(calls.contains("MESH_MIRROR_DIAG stage=TRANSFORMER_REGISTERED"));
        assertFalse(calls.contains("MESH_MIRROR_DIAG stage=HOOK_INSTALLED"));
        installer.close();
    }

    /** Registration, target transformation, and control attachment are three separate facts. */
    @Test
    void registrationAndBindingNeverReportControlAttachment() throws Exception {
        final List<String> calls = new ArrayList<>();
        final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation(calls), getClass().getClassLoader(),
            new RuntimeMeshMirrorAxisService(), new RuntimeMeshEditUiService(), profile(), calls::add
        );

        installer.install();
        assertTrue(installer.isInstalled());
        assertFalse(installer.targetTransformed());
        assertFalse(installer.controlAttached());

        installer.bind();
        assertTrue(installer.isBound());
        assertFalse(installer.controlAttached());

        installer.close();
    }

    @Test
    void unboundWidgetCallbackPreservesNativeResultAndDoesNotAttach() {
        final Object original = new Object();
        assertSame(original, NativeMeshMirrorBridge.attachControl(original, new Object()));
    }

    @Test
    void closeIsIdempotentForEarlyOnlyAndBoundStates() throws Exception {
        final List<String> earlyCalls = new ArrayList<>();
        final VerifiedMeshMirrorHookInstaller early = new VerifiedMeshMirrorHookInstaller(
            instrumentation(earlyCalls), getClass().getClassLoader(),
            new RuntimeMeshMirrorAxisService(), new RuntimeMeshEditUiService(), profile(), earlyCalls::add
        );
        early.install();
        early.close();
        early.close();
        assertEquals(1, earlyCalls.stream().filter(value -> value.equals("remove")).count());

        final List<String> boundCalls = new ArrayList<>();
        final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
        final VerifiedMeshMirrorHookInstaller bound = new VerifiedMeshMirrorHookInstaller(
            instrumentation(boundCalls), getClass().getClassLoader(),
            new RuntimeMeshMirrorAxisService(), ui, profile(), boundCalls::add
        );
        bound.install();
        bound.bind();
        bound.close();
        bound.close();
        assertFalse(bound.isBound());
        assertEquals(1, boundCalls.stream().filter(value -> value.equals("remove")).count());
    }

    @Test
    void contributionRemovalRevokesBoundBridgeAndRestoresTargets() throws Exception {
        final List<String> calls = new ArrayList<>();
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
        final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation(calls), getClass().getClassLoader(),
            axis, ui, profile(), calls::add
        );
        installer.install();
        installer.bind();
        final var contribution = ui.contributeMirrorAxisAngleControl(
            new dev.turboism.sdk.cubism.mesh.MeshEditUiService.MirrorAxisAngleControl(
                "test-mirror", "Mirror", "Reset", -180.0f, 180.0f, 1.0f, ignored -> { }
            )
        );
        assertTrue(ui.contribution() != null);

        contribution.close();


        assertFalse(installer.isBound());
        assertFalse(installer.isInstalled());
        assertTrue(calls.contains("remove"));
    }

    @Test
    void closeAfterEarlyInstallationRevokesWithoutBinding() throws Exception {
        final List<String> calls = new ArrayList<>();
        final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation(calls), getClass().getClassLoader(), null, null, profile(), calls::add
        );

        installer.install();
        installer.close();

        assertFalse(installer.isInstalled());
        assertFalse(installer.isBound());
        assertEquals(1, calls.stream().filter(value -> value.equals("remove")).count());
    }

    @Test
    void closeMeshMirrorHookIfCurrentClearsAndClosesOnlyTheCurrentInstaller() throws Exception {
        final List<String> calls = new ArrayList<>();
        final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation(calls), getClass().getClassLoader(),
            null, null, profile(), calls::add
        );
        installer.install();
        TurboismAgent.MESH_MIRROR_HOOK.set(installer);

        TurboismAgent.closeMeshMirrorHookIfCurrent(installer);

        assertFalse(installer.isInstalled());
        assertFalse(installer.isBound());
        assertTrue(TurboismAgent.MESH_MIRROR_HOOK.get() == null);
        assertEquals(1, calls.stream().filter(value -> value.equals("remove")).count());
    }

    @Test
    void previewRuntimeStartFailureClosesCandidateAndPreservesUnrelatedHook() throws Exception {
        final VerifiedMeshMirrorHookInstaller candidate = new VerifiedMeshMirrorHookInstaller(
            instrumentation(new ArrayList<>()), getClass().getClassLoader(),
            null, null, profile()
        );
        candidate.install();
        TurboismAgent.MESH_MIRROR_HOOK.set(candidate);
        try {
            assertThrows(IllegalStateException.class, () -> TurboismAgent.startPreviewRuntime(
                candidate, () -> { throw new IllegalStateException("preview start failed"); }
            ));
            assertFalse(candidate.isInstalled());
            assertNull(TurboismAgent.MESH_MIRROR_HOOK.get());

            final VerifiedMeshMirrorHookInstaller prior = new VerifiedMeshMirrorHookInstaller(
                instrumentation(new ArrayList<>()), getClass().getClassLoader(),
                null, null, profile()
            );
            final VerifiedMeshMirrorHookInstaller different = new VerifiedMeshMirrorHookInstaller(
                instrumentation(new ArrayList<>()), getClass().getClassLoader(),
                null, null, profile()
            );
            prior.install();
            different.install();
            TurboismAgent.MESH_MIRROR_HOOK.set(prior);
            try {
                assertThrows(IllegalStateException.class, () -> TurboismAgent.startPreviewRuntime(
                    different, () -> { throw new IllegalStateException("preview start failed"); }
                ));
                assertTrue(prior.isInstalled());
                assertTrue(different.isInstalled());
                assertSame(prior, TurboismAgent.MESH_MIRROR_HOOK.get());
            } finally {
                TurboismAgent.MESH_MIRROR_HOOK.compareAndSet(prior, null);
                prior.close();
                different.close();
            }
        } finally {
            TurboismAgent.MESH_MIRROR_HOOK.compareAndSet(candidate, null);
            candidate.close();
        }
    }

    @Test
    void duplicateCasCleanupClosesCurrentHookAndRuntime() throws Exception {
        final List<String> calls = new ArrayList<>();
        final VerifiedMeshMirrorHookInstaller candidate = new VerifiedMeshMirrorHookInstaller(
            instrumentation(calls), getClass().getClassLoader(),
            null, null, profile(), calls::add
        );
        candidate.install();
        TurboismAgent.MESH_MIRROR_HOOK.set(candidate);
        final boolean[] runtimeClosed = {false};

        TurboismAgent.closeDuplicateRuntimeAndMeshMirrorHook(
            () -> runtimeClosed[0] = true,
            candidate
        );

        assertTrue(runtimeClosed[0]);
        assertFalse(candidate.isInstalled());
        assertNull(TurboismAgent.MESH_MIRROR_HOOK.get());
        assertTrue(calls.contains("remove"));
    }

    @Test
    void duplicateCasCleanupClosesHookWhenRuntimeCloseThrows() throws Exception {
        final VerifiedMeshMirrorHookInstaller candidate = new VerifiedMeshMirrorHookInstaller(
            instrumentation(new ArrayList<>()), getClass().getClassLoader(),
            null, null, profile()
        );
        candidate.install();
        TurboismAgent.MESH_MIRROR_HOOK.set(candidate);
        try {
            assertThrows(IllegalStateException.class, () ->
                TurboismAgent.closeDuplicateRuntimeAndMeshMirrorHook(
                    () -> { throw new IllegalStateException("runtime close failed"); }, candidate
                )
            );
            assertFalse(candidate.isInstalled());
            assertNull(TurboismAgent.MESH_MIRROR_HOOK.get());
        } finally {
            TurboismAgent.MESH_MIRROR_HOOK.compareAndSet(candidate, null);
            candidate.close();
        }
    }


    @Test
    void reportsPartialRestorationFailureAndContinuesRestoringOtherOwners() throws Exception {
        final List<String> calls = new ArrayList<>();
        final List<String> diagnostics = new ArrayList<>();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isRetransformClassesSupported" -> true;
                case "addTransformer" -> { calls.add("add"); yield null; }
                case "removeTransformer" -> { calls.add("remove"); yield true; }
                case "getAllLoadedClasses" -> calls.contains("remove")
                    ? new Class<?>[] {TargetMesh.class, TargetWidget.class, TargetDraw.class}
                    : new Class<?>[0];
                case "isModifiableClass" -> true;
                case "retransformClasses" -> {
                    final Class<?> owner = ((Class<?>[]) arguments[0])[0];
                    if (owner == TargetWidget.class) {
                        throw new IllegalStateException("widget restore failed");
                    }
                    calls.add("restore:" + owner.getSimpleName());
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            }
        );
        final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation,
            getClass().getClassLoader(),
            new RuntimeMeshMirrorAxisService(),
            new RuntimeMeshEditUiService(),
            profile(),
            diagnostics::add
        );

        installer.install();
        calls.clear();
        installer.close();

        assertEquals(List.of("remove", "restore:TargetMesh", "restore:TargetDraw"), calls);
        assertTrue(diagnostics.contains("MESH_MIRROR_RESTORE_FAILED owner=" + TargetWidget.class.getName()));
    }

    @Test
    void instrumentationCleanupFailureStillUninstallsBridgeAndResetsRuntimeState() throws Exception {
        final List<String> calls = new ArrayList<>();
        final List<String> diagnostics = new ArrayList<>();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isRetransformClassesSupported" -> true;
                case "addTransformer" -> null;
                case "getAllLoadedClasses" -> {
                    if (calls.contains("remove")) throw new IllegalStateException("enumeration failed");
                    yield new Class<?>[0];
                }
                case "removeTransformer" -> { calls.add("remove"); yield false; }
                default -> defaultValue(method.getReturnType());
            }
        );
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        axis.setCurrentAngleDegrees(45.0f);
        final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
        ui.contributeMirrorAxisAngleControl(new dev.turboism.sdk.cubism.mesh.MeshEditUiService.MirrorAxisAngleControl(
            "mesh.mirror-axis.angle", "Angle", "", -180.0f, 180.0f, 0.1f, ignored -> { }
        ));
        final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation,
            getClass().getClassLoader(),
            axis,
            ui,
            profile(),
            diagnostics::add
        );

        installer.install();
        installer.bind();
        installer.close();

        assertEquals(0.0f, axis.currentAngleDegrees());
        assertTrue(calls.contains("remove"));
        assertTrue(diagnostics.contains("MESH_MIRROR_TRANSFORMER_REMOVE_FAILED"));
        assertTrue(diagnostics.contains("MESH_MIRROR_RESTORE_ENUMERATION_FAILED"));
        final var observer = ui.observeContribution(ignored -> { });
        assertNotNull(observer);
        observer.close();
        NativeMeshMirrorBridge.install(new RuntimeMeshMirrorAxisService(), new RuntimeMeshEditUiService());
    }

    @Test
    void bindFailureClosesTheObserverWithoutLeakingRuntimeBinding() throws Exception {
        final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
        NativeMeshMirrorBridge.install(new RuntimeMeshMirrorAxisService(), new RuntimeMeshEditUiService());
        final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation(new ArrayList<>()),
            getClass().getClassLoader(),
            new RuntimeMeshMirrorAxisService(),
            ui,
            profile()
        );

        installer.install();
        assertThrows(IllegalStateException.class, installer::bind);
        assertFalse(installer.isBound());
        final var observer = ui.observeContribution(ignored -> { });
        assertNotNull(observer);
        observer.close();
        installer.close();
        NativeMeshMirrorBridge.install(new RuntimeMeshMirrorAxisService(), new RuntimeMeshEditUiService());
    }

    private static Instrumentation instrumentation(
        final List<String> calls,
        final Class<?>... loaded
    ) {
        return (Instrumentation) Proxy.newProxyInstance(
            VerifiedMeshMirrorHookInstallerTest.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isRetransformClassesSupported" -> true;
                case "addTransformer" -> { calls.add("add:" + arguments[1]); yield null; }
                case "getAllLoadedClasses" -> loaded;
                case "isModifiableClass" -> true;
                case "retransformClasses" -> {
                    calls.add("retransform:" + ((Class<?>[]) arguments[0])[0].getName());
                    yield null;
                }
                case "removeTransformer" -> { calls.add("remove"); yield true; }
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static MeshMirrorHostProfile profile() {
        return new MeshMirrorHostProfile(
            TargetMesh.class.getName().replace('.', '/'), "a", "b", "(Ljava/lang/Object;)Ljava/lang/Object;",
            "a", "(Ljava/lang/Object;F)Z",
            TargetWidget.class.getName().replace('.', '/'), "widget", "(Ljava/lang/Object;)Ljava/lang/Object;",
            TargetDraw.class.getName().replace('.', '/'), "a", "(FZFLjava/lang/Object;)V"
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    public static final class TargetMesh { }
    public static final class TargetWidget { }
    public static final class TargetDraw { }
}
