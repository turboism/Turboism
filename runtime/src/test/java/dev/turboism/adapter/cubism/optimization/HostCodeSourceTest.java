package dev.turboism.adapter.cubism.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies the shared weave primitives: code-source attestation, the loader-aware frame writer,
 * and the property-slot instruction sequence every bridge consult depends on.
 */
class HostCodeSourceTest {

    private static final Path ARTIFACT = Path.of("/opt/cubism/CubismSdkForJava.jar");
    private static final String SLOT_KEY = "turboism.test.slot";
    private static final String PREDICATE_TYPE = "java/util/function/Predicate";
    private static final String PREDICATE_TEST_METHOD = "test";
    private static final String PREDICATE_TEST_DESCRIPTOR = "(Ljava/lang/Object;)Z";
    private static final String FUNCTION_TYPE = "java/util/function/Function";
    private static final String FUNCTION_APPLY_METHOD = "apply";
    private static final String FUNCTION_APPLY_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String GET_SLOT_TRACE = "INVOKESTATIC java/lang/System getProperties ()Ljava/util/Properties;"
            + " false | LDC " + SLOT_KEY
            + " | INVOKEVIRTUAL java/util/Properties get (Ljava/lang/Object;)Ljava/lang/Object; false";

    private static ProtectionDomain domain(final URL location) {
        return new ProtectionDomain(new CodeSource(location, (Certificate[]) null), new Permissions());
    }

    private static URL url(final String value) throws Exception {
        return new URL(value);
    }

    @Test
    void matchesTheExactNormalizedArtifact() throws Exception {
        assertTrue(HostCodeSource.matchesArtifact(
                domain(url("file:/opt/cubism/./sub/../CubismSdkForJava.jar")), ARTIFACT));
    }

    @Test
    void refusesOtherArtifactsAndMissingSources() throws Exception {
        assertFalse(HostCodeSource.matchesArtifact(domain(url("file:/opt/cubism/other.jar")), ARTIFACT));
        assertFalse(HostCodeSource.matchesArtifact(domain(url("file:/opt/cubism/CubismSdkForJava.jar")), null));
        assertFalse(HostCodeSource.matchesArtifact(null, ARTIFACT));
        assertFalse(HostCodeSource.matchesArtifact(new ProtectionDomain(null, new Permissions()), ARTIFACT));
        assertFalse(HostCodeSource.matchesArtifact(domain(null), ARTIFACT));
    }

    @Test
    void admitsOnlyRuntimeImageDefinitions() throws Exception {
        assertTrue(HostCodeSource.isJdkBootOrPlatform(null, null));
        assertTrue(HostCodeSource.isJdkBootOrPlatform(ClassLoader.getPlatformClassLoader(), null));
        assertFalse(HostCodeSource.isJdkBootOrPlatform(ClassLoader.getSystemClassLoader(), null));
        assertFalse(HostCodeSource.isJdkBootOrPlatform(null, domain(url("file:/opt/cubism/host.jar"))));
    }

    @Test
    void frameWriterRecomputesFramesAndDegradesMissingSupertypes() {
        final ClassLoader loader = new ClassLoader(null) {};
        final ClassWriter writer = HostClassWriters.newFrameWriter(loader);
        assertNotNull(writer);
        assertEquals(
                "java/lang/String", HostClassWriters.commonSuperClass(loader, "java/lang/String", "java/lang/String"));
        assertEquals(
                "java/lang/Object", HostClassWriters.commonSuperClass(loader, "java/lang/String", "java/lang/Integer"));
        assertEquals("java/lang/Object", HostClassWriters.commonSuperClass(loader, "missing/Type", "java/lang/String"));
        assertEquals(
                "java/lang/Object", HostClassWriters.commonSuperClass(null, "java/lang/Runnable", "java/lang/String"));
    }

    @Test
    void frameWriterProducesLoadableFrames() {
        final ClassReader reader = new ClassReader(reference());
        final ClassWriter writer =
                HostClassWriters.newFrameWriter(reader, getClass().getClassLoader());
        reader.accept(writer, 0);
        assertEquals("probe/Subject", new ClassReader(writer.toByteArray()).getClassName());
    }

    @Test
    void slotLookupEmitsTheBridgeProtocol() {
        final RecordingVisitor visitor = new RecordingVisitor();
        SlotBridgeEmitter.emitGetSlot(visitor, SLOT_KEY);
        assertEquals(GET_SLOT_TRACE, visitor.trace());
    }

    @Test
    void typedConsultGuardsWithInstanceOfAndCheckcast() {
        final RecordingVisitor visitor = new RecordingVisitor();
        SlotBridgeEmitter.emitPredicateConsult(visitor, SLOT_KEY, new Label());
        assertEquals(
                GET_SLOT_TRACE + " | op:" + Opcodes.DUP + " | INSTANCEOF " + PREDICATE_TYPE + " | IFEQ | CHECKCAST "
                        + PREDICATE_TYPE,
                visitor.trace());
        assertEquals(1, visitor.jumps());
    }

    @Test
    void predicateAndFunctionCallsUseTheSharedDescriptors() {
        final RecordingVisitor test = new RecordingVisitor();
        SlotBridgeEmitter.emitTest(test);
        assertEquals(
                "INVOKEINTERFACE " + PREDICATE_TYPE + " " + PREDICATE_TEST_METHOD + " " + PREDICATE_TEST_DESCRIPTOR
                        + " true",
                test.trace());

        final RecordingVisitor apply = new RecordingVisitor();
        SlotBridgeEmitter.emitApply(apply);
        assertEquals(
                "INVOKEINTERFACE " + FUNCTION_TYPE + " " + FUNCTION_APPLY_METHOD + " " + FUNCTION_APPLY_DESCRIPTOR
                        + " true",
                apply.trace());
    }

    @Test
    void methodHandleConsultGuardsWithoutIssuingTheCall() {
        final RecordingVisitor visitor = new RecordingVisitor();
        SlotBridgeEmitter.emitMethodHandleConsult(visitor, SLOT_KEY, new Label());
        assertEquals(
                GET_SLOT_TRACE + " | op:" + Opcodes.DUP
                        + " | INSTANCEOF java/lang/invoke/MethodHandle | IFEQ | CHECKCAST java/lang/invoke/MethodHandle",
                visitor.trace());
    }

    /** Minimal class with one branch so the frame writer must compute a merged frame. */
    private static byte[] reference() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/Subject", null, "java/lang/Object", null);
        final MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Records the emitted instruction sequence so the shared protocol stays byte-exact. */
    private static final class RecordingVisitor extends MethodVisitor {
        private final StringBuilder trace = new StringBuilder();
        private int jumps;

        RecordingVisitor() {
            super(Opcodes.ASM9);
        }

        String trace() {
            return trace.toString();
        }

        int jumps() {
            return jumps;
        }

        private void add(final String instruction) {
            if (trace.length() > 0) trace.append(" | ");
            trace.append(instruction);
        }

        @Override
        public void visitInsn(final int opcode) {
            add("op:" + opcode);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            add("LDC " + value);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            add((opcode == Opcodes.INSTANCEOF ? "INSTANCEOF " : "CHECKCAST ") + type);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            jumps++;
            add(opcode == Opcodes.IFEQ ? "IFEQ" : "jump:" + opcode);
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface) {
            add(opcodeName(opcode) + " " + owner + " " + name + " " + descriptor + " " + isInterface);
        }

        private static String opcodeName(final int opcode) {
            return switch (opcode) {
                case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
                case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
                case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
                default -> "invoke:" + opcode;
            };
        }
    }
}
