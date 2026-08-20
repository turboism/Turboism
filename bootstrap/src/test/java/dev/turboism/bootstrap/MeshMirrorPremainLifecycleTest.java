package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile;
import dev.turboism.adapter.cubism.mesh.MeshMirrorNativeMethodTransformer;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MeshMirrorPremainLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void transformerCanObserveFirstDefinitionAndRetainsLoaderIdentity() throws Exception {
        final Path artifact = Files.createFile(tempDir.resolve("live2d_cubism.jar"));
        final MeshMirrorHostProfile profile = profile();
        final ClassLoader loader = getClass().getClassLoader();
        final MeshMirrorNativeMethodTransformer transformer = new MeshMirrorNativeMethodTransformer(
            profile, loader, artifact, null, null
        );

        assertNull(transformer.transform(
            null, loader, "fixture/Host", null, protectionDomain(artifact), new byte[0]
        ));
        assertEquals(MeshMirrorNativeMethodTransformer.Outcome.NONE, transformer.outcome());
        assertNull(transformer.admittedClassLoader());
    }

    @Test
    void preloadedTargetIsRejectedWithoutRetransformation() throws Exception {
        final Path artifact = Files.createFile(tempDir.resolve("live2d_cubism.jar"));
        final List<String> calls = new ArrayList<>();
        final AtomicReference<ClassFileTransformer> registered = new AtomicReference<>();
        final Instrumentation instrumentation = instrumentation(calls, registered);
        final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
            instrumentation, getClass().getClassLoader(), new RuntimeMeshMirrorAxisService(),
            new RuntimeMeshEditUiService(), profileFor(TargetMesh.class), calls::add
        );

        installer.install();

        assertTrue(installer.isInstalled());
        assertFalse(calls.contains("MESH_MIRROR_UNAVAILABLE_TARGET_ALREADY_LOADED owner=" + TargetMesh.class.getName()));
        assertFalse(calls.stream().anyMatch(value -> value.startsWith("retransform:")));
    }

    @Test
    void mismatchedLoaderAndArtifactFailClosed() throws Exception {
        final Path artifact = Files.createFile(tempDir.resolve("live2d_cubism.jar"));
        final Path other = Files.createFile(tempDir.resolve("other.jar"));
        final AtomicReference<ClassFileTransformer> registered = new AtomicReference<>();
        final ClassLoader loader = getClass().getClassLoader();
        final List<String> calls = new ArrayList<>();
        final MeshMirrorNativeMethodTransformer transformer = new MeshMirrorNativeMethodTransformer(
            profileFor(TargetMesh.class), loader, artifact, profileFor(TargetMesh.class).mirrorWidgetOwner(), null,
            calls::add
        );
        registered.set(transformer);

        assertNull(registered.get().transform(
            null, loader, profileFor(TargetMesh.class).mirrorWidgetOwner(), null, protectionDomain(other), new byte[] {0}
        ));
        assertTrue(calls.contains("MESH_MIRROR_ARTIFACT_MISMATCH"));

        assertNull(registered.get().transform(
            null, new ClassLoader(loader) { }, profileFor(TargetMesh.class).mirrorWidgetOwner(), null,
            protectionDomain(artifact), new byte[] {0}
        ));
        assertTrue(calls.contains("MESH_MIRROR_LOADER_MISMATCH"));
    }

    @Test
    void agentmainCannotAdmitPremainOnlyFeature() {
        assertFalse(TurboismAgent.meshMirrorPremainOnly(
            dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller.AttachmentMode.AGENTMAIN
        ));
        assertTrue(TurboismAgent.meshMirrorPremainOnly(
            dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller.AttachmentMode.PREMAIN
        ));
    }

    private static Instrumentation instrumentation(
        final List<String> calls,
        final AtomicReference<ClassFileTransformer> registered,
        final Class<?>... loaded
    ) {
        return (Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
            MeshMirrorPremainLifecycleTest.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isRetransformClassesSupported" -> true;
                case "addTransformer" -> {
                    registered.set((ClassFileTransformer) arguments[0]);
                    calls.add("add:" + arguments[1]);
                    yield null;
                }
                case "getAllLoadedClasses" -> loaded;
                case "isModifiableClass" -> true;
                case "removeTransformer" -> {
                    calls.add("remove");
                    yield true;
                }
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static ProtectionDomain protectionDomain(final Path artifact) throws Exception {
        return new ProtectionDomain(
            new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null
        );
    }

    private static MeshMirrorHostProfile profile() {
        return new MeshMirrorHostProfile(
            "fixture/Mesh", "point", "axisPoint", "(Ljava/lang/Object;)Ljava/lang/Object;",
            "hit", "(Ljava/lang/Object;F)Z",
            "fixture/Widget", "widget", "(Ljava/lang/Object;)Ljava/lang/Object;",
            "fixture/Draw", "draw", "(FZFLjava/lang/Object;)V"
        );
    }

    private static MeshMirrorHostProfile profileFor(final Class<?> owner) {
        return new MeshMirrorHostProfile(
            owner.getName().replace('.', '/'), "point", "axisPoint", "(Ljava/lang/Object;)Ljava/lang/Object;",
            "hit", "(Ljava/lang/Object;F)Z",
            "fixture/Widget", "widget", "(Ljava/lang/Object;)Ljava/lang/Object;",
            "fixture/Draw", "draw", "(FZFLjava/lang/Object;)V"
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
}
