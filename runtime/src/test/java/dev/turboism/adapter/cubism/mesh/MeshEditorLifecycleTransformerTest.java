package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.model.Point2;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class MeshEditorLifecycleTransformerTest {
    private static final String OWNER = "example/ExactMeshEditor";
    private static final String DOCUMENT_OWNER = "example/ExactModelingDocument";
    private static final MeshToolSessionHostProfile PROFILE = new MeshToolSessionHostProfile(
            "5.3.03",
            OWNER,
            "startMode",
            "(Ljava/util/List;)V",
            "endMode",
            "()V",
            DOCUMENT_OWNER,
            "internal_setEditMode",
            "(Ljava/lang/Object;)V");

    @TempDir
    Path tempDir;

    @AfterEach
    void resetBridge() {
        NativeMeshToolSessionBridge.resetForTests();
    }

    @Test
    void nativeConfirmationModeSwitchRevokesToolAndOverlayWithoutCallingEndMode() throws Exception {
        final Loader loader = new Loader();
        final Path artifact = Files.createFile(tempDir.resolve("Cubism.jar"));
        final var transformer = new MeshEditorLifecycleTransformer(PROFILE, loader, artifact, ignored -> {});
        final byte[] transformed =
                transformer.transform(null, loader, DOCUMENT_OWNER, null, domain(artifact), documentClass());
        assertNotNull(transformed, "the real confirmation changes the document mode without calling endMode");
        final Class<?> documentType = loader.define(DOCUMENT_OWNER.replace('/', '.'), transformed);
        final Object document = documentType.getConstructor().newInstance();
        final Object foreignDocument = documentType.getConstructor().newInstance();
        final var setMode = documentType.getMethod("internal_setEditMode", Object.class);
        final Object meshMode = new Object();
        final Object mainMode = new Object();
        final JPanel canvas = new JPanel(null);
        canvas.setSize(320, 200);
        final var snapshot = new MeshToolSessionResolver.Snapshot(
                meshMode,
                new Object(),
                document,
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                canvas,
                new Object(),
                new Object(),
                new Object(),
                new Object());
        final var resolver = new MeshToolSessionResolver((mode, entries) -> snapshot);
        final var coordinator = new MeshToolCoordinator();
        final AtomicInteger deactivations = new AtomicInteger();
        final var brush = new RuntimeSelectionBrush(new RuntimeSelectionBrush.Host() {
            @Override
            public JComponent component() {
                return canvas;
            }

            @Override
            public List<Point2> projectedVertices() {
                return List.of();
            }

            @Override
            public void commitSelection(List<Integer> indices, SelectionMode mode) {
                throw new AssertionError("mode changes must not select vertices");
            }

            @Override
            public SelectionMode selectionMode(boolean shift, boolean control, boolean alt) {
                return SelectionMode.ADD;
            }

            @Override
            public boolean revalidate() {
                return true;
            }

            @Override
            public void deactivate() {
                coordinator.deactivate("plugin", 7L, "brush");
            }
        });
        coordinator.register(
                "plugin",
                7L,
                new MeshTool() {
                    @Override
                    public String id() {
                        return "brush";
                    }

                    @Override
                    public String label() {
                        return "Brush";
                    }

                    @Override
                    public String iconResourcePath() {
                        return "icons/brush.png";
                    }

                    @Override
                    public void activate(MeshToolContext context) {
                        brush.install();
                    }

                    @Override
                    public void deactivate() {
                        deactivations.incrementAndGet();
                        brush.close();
                    }
                },
                (permission, operation) -> {});
        NativeMeshToolSessionBridge.install(
                new Object(), 41L, resolver, new NativeMeshToolSessionBridge.SessionListener() {
                    @Override
                    public void opened(NativeMeshToolSession session) {
                        coordinator.beginSession(session);
                    }

                    @Override
                    public void closed(NativeMeshToolSession session) {
                        coordinator.endSession();
                    }
                });
        try {
            NativeMeshToolSessionBridge.afterStart(meshMode, List.of());
            coordinator.activate("plugin", 7L, "brush");
            final var lease = coordinator.activeLease().orElseThrow();
            assertEquals(1, canvas.getComponentCount());

            setMode.invoke(document, meshMode);
            setMode.invoke(foreignDocument, mainMode);
            assertThrows(InvocationTargetException.class, () -> setMode.invoke(document, new Object[] {null}));
            assertTrue(lease.revalidate(), "same mode, foreign document and failed switches keep the lease");
            assertEquals(0, deactivations.get());

            setMode.invoke(document, mainMode);

            assertSame(mainMode, documentType.getField("mode").get(document));
            assertFalse(lease.revalidate());
            assertTrue(coordinator.activeTool().isEmpty());
            assertNull(NativeMeshToolSessionBridge.currentSessionForTests());
            assertEquals(0, canvas.getComponentCount(), "native confirmation must remove the real brush overlay");
            assertEquals(0, brush.overlayForTests().getMouseListeners().length);
            setMode.invoke(document, mainMode);
            NativeMeshToolSessionBridge.beforeEnd(meshMode);
            assertEquals(1, deactivations.get(), "overlapping exit paths must close only once");
        } finally {
            coordinator.close();
            brush.close();
        }
    }

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

    private static byte[] documentClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, DOCUMENT_OWNER, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "mode", "Ljava/lang/Object;", null, null)
                .visitEnd();
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        final MethodVisitor setter =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "internal_setEditMode", "(Ljava/lang/Object;)V", null, null);
        setter.visitCode();
        setter.visitVarInsn(Opcodes.ALOAD, 1);
        setter.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/util/Objects",
                "requireNonNull",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        setter.visitInsn(Opcodes.POP);
        setter.visitVarInsn(Opcodes.ALOAD, 0);
        setter.visitVarInsn(Opcodes.ALOAD, 1);
        setter.visitFieldInsn(Opcodes.PUTFIELD, DOCUMENT_OWNER, "mode", "Ljava/lang/Object;");
        setter.visitInsn(Opcodes.RETURN);
        setter.visitMaxs(0, 0);
        setter.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class Loader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
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
