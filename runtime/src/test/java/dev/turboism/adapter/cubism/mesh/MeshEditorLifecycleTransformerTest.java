package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class MeshEditorLifecycleTransformerTest {
    private static final String OWNER = "example/ExactMeshEditor";
    private static final MeshToolSessionHostProfile PROFILE =
            new MeshToolSessionHostProfile("5.3.03", OWNER, "startMode", "(Ljava/util/List;)V", "endMode", "()V");

    @TempDir
    Path tempDir;

    @Test
    void injectsOnlyExactStartAndEndCallbacks() throws Exception {
        final ClassLoader loader = new ClassLoader() {};
        final Path artifact =
                Files.createFile(tempDir.resolve("Cubism.jar")).toAbsolutePath().normalize();
        final MeshEditorLifecycleTransformer transformer =
                new MeshEditorLifecycleTransformer(PROFILE, loader, artifact, ignored -> {});

        final byte[] transformed = transformer.transform(
                null,
                loader,
                OWNER,
                null,
                domain(artifact),
                hostClass("startMode", "(Ljava/util/List;)V", "endMode", "()V"));

        assertEquals(
                List.of("afterStart(Ljava/lang/Object;Ljava/util/List;)V", "beforeEnd(Ljava/lang/Object;)V"),
                callbacks(transformed));
        assertEquals(MeshEditorLifecycleTransformer.Outcome.TARGET_TRANSFORMED, transformer.outcome());
    }

    @Test
    void rejectsWrongLoaderArtifactOrMethodShapeWithoutBroadTransformation() throws Exception {
        final ClassLoader loader = new ClassLoader() {};
        final Path artifact =
                Files.createFile(tempDir.resolve("Cubism.jar")).toAbsolutePath().normalize();
        final MeshEditorLifecycleTransformer transformer =
                new MeshEditorLifecycleTransformer(PROFILE, loader, artifact, ignored -> {});
        final byte[] bytes = hostClass("startMode", "(Ljava/util/List;)V", "endMode", "()V");

        assertNull(transformer.transform(null, new ClassLoader() {}, OWNER, null, domain(artifact), bytes));
        assertEquals(MeshEditorLifecycleTransformer.Outcome.LOADER_MISMATCH, transformer.outcome());
        assertNull(transformer.transform(null, loader, OWNER, null, domain(tempDir), bytes));
        assertEquals(MeshEditorLifecycleTransformer.Outcome.ARTIFACT_MISMATCH, transformer.outcome());
        // A retransformation of the exact owner is the normal path: the host loads its mesh-editor
        // class during its own startup, so rejecting `classBeingRedefined` would silently prevent the
        // lifecycle hook from ever being applied.
        assertNotNull(transformer.transform(null, loader, OWNER, Object.class, domain(artifact), bytes));
        assertEquals(MeshEditorLifecycleTransformer.Outcome.TARGET_TRANSFORMED, transformer.outcome());
        assertNull(new MeshEditorLifecycleTransformer(PROFILE, loader, artifact, ignored -> {})
                .transform(
                        null, loader, OWNER, null, domain(artifact), hostClass("startMode", "()V", "endMode", "()V")));
    }

    private static ProtectionDomain domain(final Path artifact) throws Exception {
        return new ProtectionDomain(
                new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
    }

    private static byte[] hostClass(
            final String startName, final String startDescriptor, final String endName, final String endDescriptor) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        method(writer, startName, startDescriptor);
        method(writer, endName, endDescriptor);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void method(final ClassWriter writer, final String name, final String descriptor) {
        final MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, descriptor.startsWith("()") ? 1 : 2);
        method.visitEnd();
    }

    private static List<String> callbacks(final byte[] bytes) {
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
                                        if (owner.equals(
                                                "dev/turboism/adapter/cubism/mesh/NativeMeshToolSessionBridge"))
                                            calls.add(name + descriptor);
                                    }
                                };
                            }
                        },
                        0);
        return calls;
    }
}
