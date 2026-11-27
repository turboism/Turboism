package dev.turboism.adapter.cubism.modelopen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V17;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

final class IntArrayDeserializerParityTest {

    @Test
    void parsesAllBoundaryAndEdgeTokensBitIdenticallyToJdkInteger() {
        final List<String> edgeCases = List.of(
                "0",
                "-0",
                "+0",
                "1",
                "-1",
                "+42",
                "00007",
                "-00007",
                "999999999",
                "-999999999",
                "1000000000",
                "-1000000000",
                String.valueOf(Integer.MAX_VALUE),
                String.valueOf(Integer.MIN_VALUE));

        for (final String token : edgeCases) {
            final int expected = Integer.parseInt(token);
            final int actual = IntArrayDeserializerBridge.parseIntToken(token, 0, token.length());
            assertEquals(expected, actual, "Mismatch for token: [" + token + "]");
        }
    }

    @Test
    void fuzzMatchesJdkIntegerParseIntAcrossTwentyThousandTokens() {
        final Random random = new Random(0xC0DE_4B12_9876L);
        final StringBuilder joined = new StringBuilder();
        final int[] expected = new int[20_000];
        for (int i = 0; i < expected.length; i++) {
            final int value =
                    switch (i % 5) {
                        case 0 -> random.nextInt();
                        case 1 -> random.nextInt(2_000_000_000) - 1_000_000_000;
                        case 2 -> random.nextInt(1_000) - 500;
                        case 3 -> i % 10 == 3 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
                        default -> -random.nextInt(999_999_999);
                    };
            expected[i] = value;
            if (i > 0) {
                joined.append(' ');
            }
            joined.append(value);
        }
        final int[] actual = IntArrayDeserializerBridge.parseIntArray(expected.length, joined.toString(), true);
        assertArrayEquals(expected, actual);
    }

    @Test
    void enforcesAssertionsEmptyInvalidAndOverflowTokensConsistently() {
        assertArrayEquals(new int[0], IntArrayDeserializerBridge.parseIntArray(0, "", true));
        assertArrayEquals(new int[0], IntArrayDeserializerBridge.parseIntArray(0, "", false));
        assertThrows(AssertionError.class, () -> IntArrayDeserializerBridge.parseIntArray(0, " ", true));
        assertThrows(AssertionError.class, () -> IntArrayDeserializerBridge.parseIntArray(2, "1", true));
        assertThrows(AssertionError.class, () -> IntArrayDeserializerBridge.parseIntArray(2, "1 2 ", true));
        assertArrayEquals(new int[] {1, 2}, IntArrayDeserializerBridge.parseIntArray(2, "1 2 ", false));
        assertThrows(NegativeArraySizeException.class, () -> IntArrayDeserializerBridge.parseIntArray(-1, "1", false));

        for (final String bad :
                List.of("", "   ", " 1", "1 ", "+", "-", "abc", "12a", "2147483648", "-2147483649", "999999999999")) {
            assertThrows(
                    NumberFormatException.class,
                    () -> IntArrayDeserializerBridge.parseIntToken(bad, 0, bad.length()),
                    "Expected NumberFormatException for [" + bad + "]");
        }
        assertThrows(IndexOutOfBoundsException.class, () -> IntArrayDeserializerBridge.parseIntArray(2, "1", false));
    }

    @Test
    void composesCleanlyWithCreateElementPatcherInEitherOrder() {
        final byte[] original = buildSyntheticImplH();

        // Order A: createInstance (load perf) then createElement (save perf)
        final byte[] loadThenSave = patchSyntheticCreateElement(IntArrayDeserializerPatcher.tryPatch(
                IntArrayDeserializerPatcher.TARGET_INTERNAL_NAME, getClass().getClassLoader(), original));
        assertNotNull(loadThenSave);
        assertCreateInstanceAndCreateElementPresent(loadThenSave);
        assertNull(IntArrayDeserializerPatcher.tryPatch(
                IntArrayDeserializerPatcher.TARGET_INTERNAL_NAME, getClass().getClassLoader(), loadThenSave));

        // Order B: createElement (save perf) then createInstance (load perf)
        final byte[] saveThenLoad = IntArrayDeserializerPatcher.tryPatch(
                IntArrayDeserializerPatcher.TARGET_INTERNAL_NAME,
                getClass().getClassLoader(),
                patchSyntheticCreateElement(original));
        assertNotNull(saveThenLoad);
        assertCreateInstanceAndCreateElementPresent(saveThenLoad);
    }

    private static void assertCreateInstanceAndCreateElementPresent(final byte[] bytes) {
        final AtomicBoolean sawCreateInstance = new AtomicBoolean();
        final AtomicBoolean sawCreateElement = new AtomicBoolean();
        final AtomicBoolean sawBridgeCall = new AtomicBoolean();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                if ("createInstance".equals(name)
                                        && "(Lorg/jdom/Element;Lcom/live2d/serialize/XmlReader;)Ljava/lang/Object;"
                                                .equals(desc)) {
                                    sawCreateInstance.set(true);
                                    return new MethodVisitor(ASM9) {
                                        @Override
                                        public void visitMethodInsn(
                                                final int opcode,
                                                final String owner,
                                                final String mName,
                                                final String mDesc,
                                                final boolean isInterface) {
                                            if ("dev/turboism/adapter/cubism/modelopen/IntArrayDeserializerBridge"
                                                    .equals(owner)) {
                                                sawBridgeCall.set(true);
                                            }
                                        }
                                    };
                                }
                                if ("createElement".equals(name)) {
                                    sawCreateElement.set(true);
                                }
                                return null;
                            }
                        },
                        0);
        assertEquals(true, sawCreateInstance.get());
        assertEquals(true, sawCreateElement.get());
        assertEquals(true, sawBridgeCall.get());
    }

    private static byte[] patchSyntheticCreateElement(final byte[] input) {
        final ClassWriter cw = new ClassWriter(0);
        new ClassReader(input)
                .accept(
                        new ClassVisitor(ASM9, cw) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                                if ("createElement".equals(name)) {
                                    return new MethodVisitor(ASM9, mv) {
                                        @Override
                                        public void visitCode() {
                                            super.visitCode();
                                            super.visitInsn(org.objectweb.asm.Opcodes.NOP);
                                        }
                                    };
                                }
                                return mv;
                            }
                        },
                        0);
        return cw.toByteArray();
    }

    private static byte[] buildSyntheticImplH() {
        final ClassWriter cw = new ClassWriter(0);
        cw.visit(
                V17,
                ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
                IntArrayDeserializerPatcher.TARGET_INTERNAL_NAME,
                null,
                "java/lang/Object",
                null);
        final MethodVisitor init = cw.visitMethod(0, "<init>", "(Ljava/lang/String;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 2);
        init.visitEnd();

        final MethodVisitor ce = cw.visitMethod(
                ACC_PUBLIC,
                "createElement",
                "(Ljava/lang/String;Ljava/lang/Object;Lcom/live2d/serialize/XmlWriter;)Lorg/jdom/Element;",
                null,
                null);
        ce.visitCode();
        ce.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        ce.visitInsn(ARETURN);
        ce.visitMaxs(1, 4);
        ce.visitEnd();

        final MethodVisitor fallback =
                cw.visitMethod(ACC_PUBLIC, "a", "(ILjava/util/List;)Ljava/lang/Object;", null, null);
        fallback.visitCode();
        fallback.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        fallback.visitInsn(ARETURN);
        fallback.visitMaxs(1, 3);
        fallback.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
