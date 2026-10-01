package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

/** JVM retransformation replays registered transformers over the original definition. */
class MeshToolHookCompositionTest {
    @TempDir
    Path temporary;

    @Test
    void replayAndIndependentRemovalPreserveBothHookFamiliesWithoutDuplicateCallbacks() throws Exception {
        final var profile = MeshMirrorHostProfile.reviewed52And53();
        final String owner = profile.meshEditorOwner();
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(MeshMirrorNativeMethodTransformerTest.fixture(owner, profile)).accept(writer, 0);
        emptyMethod(writer, "startMode", "(Ljava/util/List;)V");
        emptyMethod(writer, "endMode", "()V");
        final byte[] original = writer.toByteArray();
        final Loader loader = new Loader();
        final Class<?> target = loader.define(owner.replace('/', '.'), original);
        final Path artifact = temporary.resolve("Cubism.jar").toAbsolutePath();
        final var domain = new ProtectionDomain(
                new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
        final var mirror = new MeshMirrorNativeMethodTransformer(
                profile,
                loader,
                artifact,
                null,
                null,
                Map.of(
                        owner,
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(original))),
                ignored -> {});
        final var sessions = new MeshEditorLifecycleTransformer(
                new MeshToolSessionHostProfile("5.3.03", owner, "startMode", "(Ljava/util/List;)V", "endMode", "()V"),
                loader,
                artifact,
                ignored -> {});
        final byte[] initialMirror = mirror.transform(null, loader, owner, null, domain, original);
        assertNotNull(initialMirror);
        final byte[] initial = sessions.transform(null, loader, owner, null, domain, initialMirror);
        final List<String> expected = bridgeCalls(initial);
        assertEquals(1, expected.stream().filter("session.afterStart"::equals).count());
        assertEquals(1, expected.stream().filter("session.beforeEnd"::equals).count());
        assertTrue(expected.stream().anyMatch(call -> call.startsWith("mirror.")));
        for (int replay = 0; replay < 3; replay++) {
            final byte[] mirrorReplay = mirror.transform(null, loader, owner, target, domain, original);
            assertNotNull(mirrorReplay, "replaying the session hook must not remove mirror instrumentation");
            assertEquals(expected, bridgeCalls(sessions.transform(null, loader, owner, target, domain, mirrorReplay)));
        }
        // Removing the session transformer replays only mirror from original bytes.
        final List<String> mirrorOnly = bridgeCalls(mirror.transform(null, loader, owner, target, domain, original));
        assertFalse(mirrorOnly.stream().anyMatch(call -> call.startsWith("session.")));
        assertEquals(
                expected.stream().filter(call -> call.startsWith("mirror.")).toList(), mirrorOnly);
        // Removing mirror leaves the session transformer and its two callbacks intact.
        mirror.deactivate();
        assertNull(mirror.transform(null, loader, owner, target, domain, original));
        assertEquals(
                List.of("session.afterStart", "session.beforeEnd"),
                bridgeCalls(sessions.transform(null, loader, owner, target, domain, original)));
    }

    private static void emptyMethod(ClassWriter writer, String name, String descriptor) {
        final var method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static List<String> bridgeCalls(byte[] bytes) {
        final List<String> calls = new ArrayList<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String name,
                                            String descriptor,
                                            boolean isInterface) {
                                        if (owner.endsWith("/NativeMeshMirrorBridge")) calls.add("mirror." + name);
                                        if (owner.endsWith("/NativeMeshToolSessionBridge"))
                                            calls.add("session." + name);
                                    }
                                };
                            }
                        },
                        0);
        return calls;
    }

    private static final class Loader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
