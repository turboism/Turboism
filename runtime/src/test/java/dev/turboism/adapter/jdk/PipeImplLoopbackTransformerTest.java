package dev.turboism.adapter.jdk;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeImplLoopbackTransformerTest {

    /** Pinned SHA-256 of the byte-identical Cubism 5.2.03 JRE 17.0.3.1 class. */
    private static final String FIXTURE_SHA256 =
        "d1bbbf7e94bb46bb40bc222dc7cd933161476e65c97efbcab97a9b3c07d61c26";

    @Test
    void fixtureIsByteIdenticalToThePinnedJdkClass() throws Exception {
        assertEquals(FIXTURE_SHA256, sha256(fixture()));
    }

    @Test
    void forcesTheFlagTrueInClinitSoCreateListenerAlwaysTakesTheInetBranch() throws Exception {
        final byte[] fixture = fixture();
        final AtomicInteger cleanupCalls = new AtomicInteger();
        final List<String> diagnostics = new ArrayList<>();
        final PipeImplLoopbackClassFileTransformer transformer =
            new PipeImplLoopbackClassFileTransformer(
                ignored -> cleanupCalls.incrementAndGet(),
                diagnostics::add
            );

        final byte[] transformed = transformer.transform(
            null,
            null,
            PipeImplLoopbackTransformer.TARGET_OWNER,
            null,
            null,
            fixture
        );

        assertNotNull(transformed);
        final List<Insn> clinit = methodInstructions(transformed, "<clinit>");
        assertEquals(Opcodes.ICONST_1, clinit.get(0).opcode);
        assertEquals(Opcodes.PUTSTATIC, clinit.get(1).opcode);
        assertEquals(PipeImplLoopbackTransformer.TARGET_OWNER, clinit.get(1).owner);
        assertEquals(PipeImplLoopbackTransformer.FIELD_NAME, clinit.get(1).name);
        assertEquals(PipeImplLoopbackTransformer.FIELD_DESCRIPTOR, clinit.get(1).descriptor);

        final List<Insn> createListener = methodInstructions(transformed, "createListener");
        final int gate = indexOfGetstatic(createListener, PipeImplLoopbackTransformer.FIELD_NAME);
        assertTrue(gate >= 0, "createListener must consult the noUnixDomainSockets flag");
        assertEquals(Opcodes.IFNE, createListener.get(gate + 1).opcode,
            "a true flag must jump straight to the AF_INET branch");
        assertTrue(createListener.stream().anyMatch(insn ->
                insn.opcode == Opcodes.INVOKESTATIC
                    && "java/nio/channels/ServerSocketChannel".equals(insn.owner)
                    && "open".equals(insn.name)
                    && "()Ljava/nio/channels/ServerSocketChannel;".equals(insn.descriptor)),
            "the flag-true path must use the no-family AF_INET ServerSocketChannel.open()");

        assertEquals(1, cleanupCalls.get());
        assertEquals(List.of("PIPE_IMPL_SHIM_TRANSFORM_TRANSFORMED"), diagnostics);
        assertEquals(PipeImplLoopbackClassFileTransformer.Outcome.TRANSFORMED, transformer.outcome());
        assertNull(transformer.transform(
            null,
            null,
            PipeImplLoopbackTransformer.TARGET_OWNER,
            null,
            null,
            fixture
        ));
        assertEquals(1, cleanupCalls.get());
    }

    @Test
    void ignoresNonTargetClassNamesWithoutRecordingAnyOutcome() throws Exception {
        final byte[] fixture = fixture();
        final AtomicInteger cleanupCalls = new AtomicInteger();
        final List<String> diagnostics = new ArrayList<>();
        final PipeImplLoopbackClassFileTransformer transformer =
            new PipeImplLoopbackClassFileTransformer(
                ignored -> cleanupCalls.incrementAndGet(),
                diagnostics::add
            );

        assertNull(transformer.transform(null, null, "java/lang/Object", null, null, fixture));

        assertEquals(0, cleanupCalls.get());
        assertEquals(List.of(), diagnostics);
        assertEquals(PipeImplLoopbackClassFileTransformer.Outcome.PENDING, transformer.outcome());
    }

    @Test
    void rejectsAnNonBootstrapLoaderForTheTargetName() throws Exception {
        final byte[] fixture = fixture();
        final List<String> diagnostics = new ArrayList<>();
        final PipeImplLoopbackClassFileTransformer transformer =
            new PipeImplLoopbackClassFileTransformer(ignored -> { }, diagnostics::add);

        assertNull(transformer.transform(
            null,
            new ClassLoader() { },
            PipeImplLoopbackTransformer.TARGET_OWNER,
            null,
            null,
            fixture
        ));

        assertEquals(PipeImplLoopbackClassFileTransformer.Outcome.LOADER_REJECTED, transformer.outcome());
        assertEquals(
            List.of("PIPE_IMPL_SHIM_TRANSFORM_LOADER_REJECTED"),
            diagnostics
        );
    }

    @Test
    void failsOpenOnCorruptBytesWithoutThrowing() throws Exception {
        final List<String> diagnostics = new ArrayList<>();
        final PipeImplLoopbackClassFileTransformer transformer =
            new PipeImplLoopbackClassFileTransformer(ignored -> { }, diagnostics::add);

        final byte[] result = transformer.transform(
            null,
            null,
            PipeImplLoopbackTransformer.TARGET_OWNER,
            null,
            null,
            new byte[]{1, 2, 3}
        );

        assertNull(result);
        assertEquals(
            PipeImplLoopbackClassFileTransformer.Outcome.TRANSFORMATION_FAILED,
            transformer.outcome()
        );
        assertEquals(
            List.of("PIPE_IMPL_SHIM_TRANSFORM_TRANSFORMATION_FAILED"),
            diagnostics
        );
    }

    @Test
    void failsOpenWhenTheExpectedShapeIsMissing() throws Exception {
        final byte[] missingField = syntheticClass(true, false);
        final List<String> diagnostics = new ArrayList<>();
        final PipeImplLoopbackClassFileTransformer transformer =
            new PipeImplLoopbackClassFileTransformer(ignored -> { }, diagnostics::add);

        assertNull(transformer.transform(
            null,
            null,
            PipeImplLoopbackTransformer.TARGET_OWNER,
            null,
            null,
            missingField
        ));
        assertEquals(
            PipeImplLoopbackClassFileTransformer.Outcome.SHAPE_REJECTED,
            transformer.outcome()
        );
        assertEquals(List.of("PIPE_IMPL_SHIM_TRANSFORM_SHAPE_REJECTED"), diagnostics);

        final byte[] noClinit = syntheticClass(false, true);
        assertNull(transformer.transform(
            null,
            null,
            PipeImplLoopbackTransformer.TARGET_OWNER,
            null,
            null,
            noClinit
        ));
        assertEquals(
            PipeImplLoopbackClassFileTransformer.Outcome.SHAPE_REJECTED,
            transformer.outcome()
        );
    }

    @Test
    void refusesRetransformOfAnAlreadyDefinedClass() throws Exception {
        final byte[] fixture = fixture();
        final List<String> diagnostics = new ArrayList<>();
        final PipeImplLoopbackClassFileTransformer transformer =
            new PipeImplLoopbackClassFileTransformer(ignored -> { }, diagnostics::add);

        assertNull(transformer.transform(
            null,
            null,
            PipeImplLoopbackTransformer.TARGET_OWNER,
            Class.forName("sun.nio.ch.PipeImpl"),
            null,
            fixture
        ));

        assertEquals(
            PipeImplLoopbackClassFileTransformer.Outcome.RETRANSFORM_REJECTED,
            transformer.outcome()
        );
        assertEquals(
            List.of("PIPE_IMPL_SHIM_TRANSFORM_RETRANSFORM_REJECTED"),
            diagnostics
        );
    }

    private static byte[] fixture() throws IOException {
        try (InputStream in = PipeImplLoopbackTransformerTest.class.getResourceAsStream(
            "/fixtures/pipeimpl/PipeImpl.class"
        )) {
            assertNotNull(in, "fixture resource must exist");
            return in.readAllBytes();
        }
    }

    private static byte[] syntheticClass(final boolean withClinit, final boolean withFlag) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            PipeImplLoopbackTransformer.TARGET_OWNER,
            null,
            "java/lang/Object",
            null
        );
        if (withFlag) {
            writer.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                PipeImplLoopbackTransformer.FIELD_NAME,
                PipeImplLoopbackTransformer.FIELD_DESCRIPTOR,
                null,
                null
            ).visitEnd();
        }
        if (withClinit) {
            final MethodVisitor clinit = writer.visitMethod(
                Opcodes.ACC_STATIC,
                "<clinit>",
                "()V",
                null,
                null
            );
            clinit.visitCode();
            clinit.visitInsn(Opcodes.RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<Insn> methodInstructions(final byte[] classBytes, final String methodName) {
        final List<Insn> instructions = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                if (!methodName.equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInsn(final int opcode) {
                        instructions.add(new Insn(opcode, null, null, null));
                    }

                    @Override
                    public void visitFieldInsn(
                        final int opcode,
                        final String owner,
                        final String name,
                        final String descriptor
                    ) {
                        instructions.add(new Insn(opcode, owner, name, descriptor));
                    }

                    @Override
                    public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String name,
                        final String descriptor,
                        final boolean isInterface
                    ) {
                        instructions.add(new Insn(opcode, owner, name, descriptor));
                    }

                    @Override
                    public void visitJumpInsn(final int opcode, final org.objectweb.asm.Label label) {
                        instructions.add(new Insn(opcode, null, null, null));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return instructions;
    }

    private static int indexOfGetstatic(final List<Insn> instructions, final String fieldName) {
        for (int i = 0; i < instructions.size(); i++) {
            final Insn insn = instructions.get(i);
            if (insn.opcode == Opcodes.GETSTATIC && fieldName.equals(insn.name)) {
                return i;
            }
        }
        return -1;
    }

    private static String sha256(final byte[] bytes) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        final StringBuilder hex = new StringBuilder();
        for (final byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static final class Insn {
        private final int opcode;
        private final String owner;
        private final String name;
        private final String descriptor;

        private Insn(final int opcode, final String owner, final String name, final String descriptor) {
            this.opcode = opcode;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }
    }
}
