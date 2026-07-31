package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class VerifiedMeshMirrorHookInstallerTest {

    @Test
    void ownsOneTransformerAndRetransformsBothExactOwners() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "addTransformer" -> { calls.add("add:" + arguments[1]); yield null; }
                case "getAllLoadedClasses" -> new Class<?>[] {TargetMesh.class, TargetWidget.class, TargetDraw.class};
                case "isModifiableClass" -> true;
                case "retransformClasses" -> {
                    calls.add("retransform:" + ((Class<?>[]) arguments[0])[0].getName());
                    yield null;
                }
                case "removeTransformer" -> { calls.add("remove"); yield true; }
                default -> defaultValue(method.getReturnType());
            }
        );
        final MeshMirrorHostProfile profile = new MeshMirrorHostProfile(
            TargetMesh.class.getName().replace('.', '/'), "a", "b", "(Ljava/lang/Object;)Ljava/lang/Object;",
            "a", "(Ljava/lang/Object;F)Z",
            TargetWidget.class.getName().replace('.', '/'), "widget", "(Ljava/lang/Object;)Ljava/lang/Object;",
            TargetDraw.class.getName().replace('.', '/'), "a", "(FZFLjava/lang/Object;)V"
        );

        try (VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation, getClass().getClassLoader(), new RuntimeMeshMirrorAxisService(),
            new RuntimeMeshEditUiService(), profile
        )) {
            installer.install();
        }

        assertEquals(List.of(
            "add:true",
            "retransform:" + TargetMesh.class.getName(),
            "retransform:" + TargetWidget.class.getName(),
            "retransform:" + TargetDraw.class.getName(),
            "remove",
            "retransform:" + TargetMesh.class.getName(),
            "retransform:" + TargetWidget.class.getName(),
            "retransform:" + TargetDraw.class.getName()
        ), calls);
    }

    @Test
    void removingTheLiveConsumerRestoresAllOwners() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "addTransformer" -> null;
                case "getAllLoadedClasses" -> new Class<?>[] {TargetMesh.class, TargetWidget.class, TargetDraw.class};
                case "isModifiableClass" -> true;
                case "retransformClasses" -> {
                    calls.add("retransform:" + ((Class<?>[]) arguments[0])[0].getName());
                    yield null;
                }
                case "removeTransformer" -> { calls.add("remove"); yield true; }
                default -> defaultValue(method.getReturnType());
            }
        );
        final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
        final var contribution = ui.contributeMirrorAxisAngleControl(
            new dev.turboism.sdk.cubism.mesh.MeshEditUiService.MirrorAxisAngleControl(
                "mesh.mirror-axis.angle", "Angle", -180.0f, 180.0f, 0.1f, ignored -> { }
            )
        );
        final MeshMirrorHostProfile profile = new MeshMirrorHostProfile(
            TargetMesh.class.getName().replace('.', '/'), "a", "b", "(Ljava/lang/Object;)Ljava/lang/Object;",
            "a", "(Ljava/lang/Object;F)Z",
            TargetWidget.class.getName().replace('.', '/'), "widget", "(Ljava/lang/Object;)Ljava/lang/Object;",
            TargetDraw.class.getName().replace('.', '/'), "a", "(FZFLjava/lang/Object;)V"
        );
        final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation, getClass().getClassLoader(), new RuntimeMeshMirrorAxisService(), ui, profile
        );
        installer.install();
        calls.clear();

        contribution.close();

        assertEquals(List.of(
            "remove",
            "retransform:" + TargetMesh.class.getName(),
            "retransform:" + TargetWidget.class.getName(),
            "retransform:" + TargetDraw.class.getName()
        ), calls);
    }

    @Test
    void reportsPartialRestorationFailureAndContinuesCleanup() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "addTransformer" -> null;
                case "getAllLoadedClasses" -> new Class<?>[] {TargetMesh.class, TargetWidget.class, TargetDraw.class};
                case "isModifiableClass" -> true;
                case "retransformClasses" -> {
                    final Class<?> type = ((Class<?>[]) arguments[0])[0];
                    if (type == TargetWidget.class && calls.contains("remove")) throw new IllegalStateException("boom");
                    yield null;
                }
                case "removeTransformer" -> { calls.add("remove"); yield true; }
                default -> defaultValue(method.getReturnType());
            }
        );
        final MeshMirrorHostProfile profile = new MeshMirrorHostProfile(
            TargetMesh.class.getName().replace('.', '/'), "a", "b", "(Ljava/lang/Object;)Ljava/lang/Object;",
            "a", "(Ljava/lang/Object;F)Z",
            TargetWidget.class.getName().replace('.', '/'), "widget", "(Ljava/lang/Object;)Ljava/lang/Object;",
            TargetDraw.class.getName().replace('.', '/'), "a", "(FZFLjava/lang/Object;)V"
        );

        final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation, getClass().getClassLoader(), new RuntimeMeshMirrorAxisService(),
            new RuntimeMeshEditUiService(), profile, calls::add
        );
        installer.install();
        installer.close();

        assertEquals(List.of(
            "remove", "MESH_MIRROR_RESTORE_FAILED owner=" + TargetWidget.class.getName()
        ), calls);
    }

    @Test
    void instrumentationCleanupFailureStillRevokesRuntimeState() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "addTransformer" -> null;
                case "getAllLoadedClasses" -> {
                    if (calls.contains("remove")) throw new IllegalStateException("enumeration");
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
            "mesh.mirror-axis.angle", "Angle", -180.0f, 180.0f, 0.1f, ignored -> { }
        ));
        final MeshMirrorHostProfile profile = new MeshMirrorHostProfile(
            TargetMesh.class.getName().replace('.', '/'), "a", "b", "(Ljava/lang/Object;)Ljava/lang/Object;",
            "a", "(Ljava/lang/Object;F)Z",
            TargetWidget.class.getName().replace('.', '/'), "widget", "(Ljava/lang/Object;)Ljava/lang/Object;",
            TargetDraw.class.getName().replace('.', '/'), "a", "(FZFLjava/lang/Object;)V"
        );
        final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation, getClass().getClassLoader(), axis, ui, profile, calls::add
        );
        installer.install();

        installer.close();

        assertEquals(0.0f, axis.currentAngleDegrees());
        assertEquals(List.of(
            "remove", "MESH_MIRROR_TRANSFORMER_REMOVE_FAILED", "MESH_MIRROR_RESTORE_ENUMERATION_FAILED"
        ), calls);
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
