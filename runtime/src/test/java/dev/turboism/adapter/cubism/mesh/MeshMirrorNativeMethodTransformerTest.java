package dev.turboism.adapter.cubism.mesh;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

final class MeshMirrorNativeMethodTransformerTest {

    @Test
    void rejectsWrongProtectionDomainAndLoaderAfterExactFirstAdmission() throws Exception {
        final MeshMirrorHostProfile profile = MeshMirrorHostProfile.reviewed52And53();
        final PathHolder paths = new PathHolder();
        final ClassLoader first = new ClassLoader() { };
        final ClassLoader second = new ClassLoader() { };
        final MeshMirrorNativeMethodTransformer transformer = new MeshMirrorNativeMethodTransformer(
            profile, null, paths.expected, null, null, ignored -> { }
        );
        assertNotNull(transformer.transform(
            null, first, profile.meshEditorOwner(), null, paths.domain(paths.expected), fixture(profile.meshEditorOwner(), profile)
        ));
        assertNull(transformer.transform(
            null, second, profile.mirrorWidgetOwner(), null, paths.domain(paths.expected), fixture(profile.mirrorWidgetOwner(), profile)
        ));
        assertEquals(MeshMirrorNativeMethodTransformer.Outcome.LOADER_MISMATCH, transformer.outcome());
    }

    /**
     * Regression: the loader expectation must not be gated behind the artifact expectation.
     * Nesting them let a target skip a check its owner had declared.
     */
    @Test
    void enforcesTheExpectedLoaderEvenWithoutAnExpectedArtifact() throws Exception {
        final MeshMirrorHostProfile profile = MeshMirrorHostProfile.reviewed52And53();
        final List<String> diagnostics = new ArrayList<>();
        final ClassLoader expected = new ClassLoader() { };
        final MeshMirrorNativeMethodTransformer transformer = new MeshMirrorNativeMethodTransformer(
            profile, expected, null, null, null, diagnostics::add
        );

        assertNull(transformer.transform(
            null, new ClassLoader() { }, profile.mirrorWidgetOwner(), null, null, new byte[] {0}
        ));
        assertEquals(MeshMirrorNativeMethodTransformer.Outcome.LOADER_MISMATCH, transformer.outcome());
        assertTrue(diagnostics.contains("MESH_MIRROR_LOADER_MISMATCH"));
        assertNull(transformer.admittedClassLoader());
    }

    /** The bootstrap loader can satisfy no exact expectation, so it is rejected outright. */
    @Test
    void rejectsTheBootstrapLoaderWheneverAnExactExpectationExists() throws Exception {
        final MeshMirrorHostProfile profile = MeshMirrorHostProfile.reviewed52And53();
        final List<String> diagnostics = new ArrayList<>();
        final MeshMirrorNativeMethodTransformer transformer = new MeshMirrorNativeMethodTransformer(
            profile, null, java.nio.file.Path.of("/tmp/mesh-mirror-expected.jar"), null, null, diagnostics::add
        );

        assertNull(transformer.transform(
            null, null, profile.mirrorWidgetOwner(), null, null, new byte[] {0}
        ));
        assertEquals(
            MeshMirrorNativeMethodTransformer.Outcome.BOOTSTRAP_LOADER_REJECTED, transformer.outcome()
        );
        assertTrue(diagnostics.contains("MESH_MIRROR_BOOTSTRAP_LOADER_REJECTED"));
    }

    @Test
    void rejectsWrongArtifactBeforeLoaderAdmission() throws Exception {
        final MeshMirrorHostProfile profile = MeshMirrorHostProfile.reviewed52And53();
        final PathHolder paths = new PathHolder();
        final List<String> diagnostics = new ArrayList<>();
        final MeshMirrorNativeMethodTransformer transformer = new MeshMirrorNativeMethodTransformer(
            profile, null, paths.expected, null, null, diagnostics::add
        );

        assertNull(transformer.transform(
            null, getClass().getClassLoader(), profile.meshEditorOwner(), null,
            paths.domain(java.nio.file.Path.of("/tmp/mesh-mirror-other.jar")), fixture(profile.meshEditorOwner(), profile)
        ));
        assertEquals(MeshMirrorNativeMethodTransformer.Outcome.ARTIFACT_MISMATCH, transformer.outcome());
        assertNull(transformer.admittedClassLoader());
        assertTrue(diagnostics.contains("MESH_MIRROR_ARTIFACT_MISMATCH"));
    }

    private static final class PathHolder {
        final java.nio.file.Path expected = java.nio.file.Path.of("/tmp/mesh-mirror-expected.jar");

        java.security.ProtectionDomain domain(final java.nio.file.Path path) throws Exception {
            return new java.security.ProtectionDomain(
                new java.security.CodeSource(path.toUri().toURL(), (java.security.cert.Certificate[]) null), null
            );
        }
    }

    @Test
    void instrumentsOnlyTheExactVerifiedMethods() {
        final MeshMirrorHostProfile profile = MeshMirrorHostProfile.reviewed52And53();
        final MeshMirrorNativeMethodTransformer transformer =
            new MeshMirrorNativeMethodTransformer(profile, null);

        assertNull(transformer.transform(
            null, null, "fixture/Other", null, null, fixture("fixture/Other", profile)
        ));

        final byte[] meshTransformed = transformer.transform(
            null, null, profile.meshEditorOwner(), null, null,
            fixture(profile.meshEditorOwner(), profile)
        );
        final byte[] widgetTransformed = transformer.transform(
            null, null, profile.mirrorWidgetOwner(), null, null,
            fixture(profile.mirrorWidgetOwner(), profile)
        );
        final byte[] drawTransformed = transformer.transform(
            null, null, profile.mirrorAxisDrawOwner(), null, null,
            fixture(profile.mirrorAxisDrawOwner(), profile)
        );
        assertNotNull(meshTransformed);
        assertNotNull(widgetTransformed);
        assertNotNull(drawTransformed);
        assertEquals(List.of("adjustPoint", "adjustAxisPoint", "adjustHit"), bridgeCalls(meshTransformed));
        assertEquals(List.of("attachControl"), bridgeCalls(widgetTransformed));
        assertEquals(List.of("drawAxis"), bridgeCalls(drawTransformed));
    }

    @Test
    void exact52AddsTheToolEligibilityBridgeButExact53DoesNot() {
        final MeshMirrorHostProfile exact52 = MeshMirrorHostProfile.reviewed52();
        final byte[] transformed52 = new MeshMirrorNativeMethodTransformer(exact52, null).transform(
            null, null, exact52.meshEditorOwner(), null, null,
            fixture(exact52.meshEditorOwner(), exact52)
        );
        assertNotNull(transformed52);
        assertEquals(
            List.of("adjustPoint", "adjustAxisPoint", "adjustHit", "adjustToolEligibility"),
            bridgeCalls(transformed52)
        );

        final MeshMirrorHostProfile exact53 = MeshMirrorHostProfile.reviewed52And53();
        final byte[] transformed53 = new MeshMirrorNativeMethodTransformer(exact53, null).transform(
            null, null, exact53.meshEditorOwner(), null, null,
            fixture(exact53.meshEditorOwner(), exact53)
        );
        assertNotNull(transformed53);
        assertEquals(
            List.of("adjustPoint", "adjustAxisPoint", "adjustHit"),
            bridgeCalls(transformed53)
        );
    }

    @Test
    void exact52AddsTheSelectedMovementBridgeButExact53DoesNot() {
        final MeshMirrorHostProfile exact52 = MeshMirrorHostProfile.reviewed52();
        final byte[] transformed52 = new MeshMirrorNativeMethodTransformer(exact52, null).transform(
            null, null, exact52.selectedPointMove().owner(), null, null,
            fixture(exact52.selectedPointMove().owner(), exact52)
        );
        assertNotNull(transformed52);
        assertEquals(List.of("mirrorMoveSelected"), bridgeCalls(transformed52));

        final MeshMirrorHostProfile exact53 = MeshMirrorHostProfile.reviewed52And53();
        assertNull(exact53.selectedPointMove());
        assertNull(new MeshMirrorNativeMethodTransformer(exact53, null).transform(
            null, null, "com/live2d/cubism/view/context/action/action_meshEditor/d",
            null, null, fixture("fixture/Other", exact53)
        ));
    }

    @Test
    void selectedMovementInjectionExecutesBeforeTheUnmodifiedHostBody() throws Exception {
        final String owner = "fixture/SelectedPointMovementAction";
        final MeshMirrorHostProfile shared = MeshMirrorHostProfile.reviewed52And53();
        final MeshMirrorHostProfile profile = new MeshMirrorHostProfile(
            shared.meshEditorOwner(), shared.mirrorPointMethod(), shared.mirrorAxisPointMethod(),
            shared.mirrorPointDescriptor(), shared.mirrorHitMethod(), shared.mirrorHitDescriptor(),
            shared.mirrorWidgetOwner(), shared.mirrorWidgetMethod(), shared.mirrorWidgetDescriptor(),
            shared.mirrorAxisDrawOwner(), shared.mirrorAxisDrawMethod(), shared.mirrorAxisDrawDescriptor(),
            null,
            new MeshMirrorHostProfile.SelectedPointMove(owner, "c", "(Ljava/lang/Object;)V"),
            null
        );
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false
        );
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        final MethodVisitor movement = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "c", "(Ljava/lang/Object;)V", null, null
        );
        movement.visitCode();
        movement.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "dev/turboism/adapter/cubism/mesh/MeshMirrorNativeMethodTransformerTest$MovementRecorder",
            "hostBody",
            "()V",
            false
        );
        movement.visitInsn(Opcodes.RETURN);
        movement.visitMaxs(0, 0);
        movement.visitEnd();
        writer.visitEnd();

        final byte[] transformed = new MeshMirrorNativeMethodTransformer(profile, null).transform(
            null, null, owner, null, null, writer.toByteArray()
        );
        assertNotNull(transformed);
        final Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(owner.replace('/', '.'), transformed, 0, transformed.length);
            }
        }.define();
        MovementRecorder.calls = 0;
        type.getMethod("c", Object.class).invoke(type.getDeclaredConstructor().newInstance(), new Object());
        assertEquals(1, MovementRecorder.calls);
    }

    public static final class MovementRecorder {
        static int calls;

        public static void hostBody() {
            calls++;
        }
    }

    @Test
    void transformedBytecodeRemainsJvmVerifiableWithFrames() throws Exception {
        final MeshMirrorHostProfile profile = MeshMirrorHostProfile.reviewed52And53();
        final MeshMirrorNativeMethodTransformer transformer =
            new MeshMirrorNativeMethodTransformer(profile, null);

        // A branching method forces stack-map frames; a frame-stale transform fails verification.
        final byte[] meshTransformed = transformer.transform(
            null, null, profile.meshEditorOwner(), null, null,
            branchingFixture(profile.meshEditorOwner(), profile)
        );

        assertNotNull(meshTransformed);
        final Class<?> loaded = new ClassLoader() {
            Class<?> define() {
                return defineClass(profile.meshEditorOwner().replace('/', '.'), meshTransformed, 0, meshTransformed.length);
            }
        }.define();
        assertEquals(profile.meshEditorOwner().replace('/', '.'), loaded.getName());
    }

    private static byte[] branchingFixture(final String owner, final MeshMirrorHostProfile profile) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        // mirrorPointMethod: (GVector2)GVector2 with a branch -> frames in the method body.
        final MethodVisitor point = writer.visitMethod(
            Opcodes.ACC_PUBLIC, profile.mirrorPointMethod(), profile.mirrorPointDescriptor(), null, null);
        point.visitCode();
        final org.objectweb.asm.Label branch = new org.objectweb.asm.Label();
        point.visitVarInsn(Opcodes.ALOAD, 1);
        point.visitJumpInsn(Opcodes.IFNULL, branch);
        point.visitInsn(Opcodes.ACONST_NULL);
        point.visitInsn(Opcodes.ARETURN);
        point.visitLabel(branch);
        point.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        point.visitInsn(Opcodes.ACONST_NULL);
        point.visitInsn(Opcodes.ARETURN);
        point.visitMaxs(1, 2);
        point.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fixture(final String owner, final MeshMirrorHostProfile profile) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        if (owner.equals(profile.meshEditorOwner())) {
            method(writer, profile.mirrorPointMethod(), profile.mirrorPointDescriptor(), Opcodes.ARETURN);
            method(writer, profile.mirrorAxisPointMethod(), profile.mirrorPointDescriptor(), Opcodes.ARETURN);
            method(writer, profile.mirrorHitMethod(), profile.mirrorHitDescriptor(), Opcodes.IRETURN);
            if (profile.toolEligibility() != null) {
                method(
                    writer,
                    profile.toolEligibility().method(),
                    profile.toolEligibility().descriptor(),
                    Opcodes.IRETURN
                );
            }
        }
        if (owner.equals(profile.mirrorWidgetOwner())) {
            method(writer, profile.mirrorWidgetMethod(), profile.mirrorWidgetDescriptor(), Opcodes.ARETURN);
        }
        if (owner.equals(profile.mirrorAxisDrawOwner())) {
            method(writer, profile.mirrorAxisDrawMethod(), profile.mirrorAxisDrawDescriptor(), Opcodes.RETURN);
        }
        if (profile.selectedPointMove() != null && owner.equals(profile.selectedPointMove().owner())) {
            method(
                writer,
                profile.selectedPointMove().method(),
                profile.selectedPointMove().descriptor(),
                Opcodes.RETURN
            );
        }
        method(writer, "unrelated", "()V", Opcodes.RETURN);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void method(
        final ClassWriter writer,
        final String name,
        final String descriptor,
        final int returnOpcode
    ) {
        final MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        switch (returnOpcode) {
            case Opcodes.ARETURN -> method.visitInsn(Opcodes.ACONST_NULL);
            case Opcodes.IRETURN -> method.visitInsn(Opcodes.ICONST_0);
            default -> { }
        }
        method.visitInsn(returnOpcode);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static List<String> bridgeCalls(final byte[] bytecode) {
        final List<String> calls = new ArrayList<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String calledName,
                        final String calledDescriptor,
                        final boolean isInterface
                    ) {
                        if (owner.equals("dev/turboism/adapter/cubism/mesh/NativeMeshMirrorBridge")) {
                            calls.add(calledName);
                        }
                    }
                };
            }
        }, 0);
        return calls;
    }
}
