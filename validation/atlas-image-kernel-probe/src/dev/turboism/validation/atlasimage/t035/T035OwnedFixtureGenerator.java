package dev.turboism.validation.atlasimage.t035;

import java.util.Arrays;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassReader;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassWriter;
import dev.turboism.validation.atlasimage.shaded.asm97.FieldVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.Label;
import dev.turboism.validation.atlasimage.shaded.asm97.MethodVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.Opcodes;

/** Hand-generated owned variant; it is not extracted from or named after the official B method. */
final class T035OwnedFixtureGenerator implements Opcodes {
    static final String INTERNAL_NAME = "dev/turboism/validation/atlasimage/t035/T035OwnedAtlasFixture";
    static final String CLASS_NAME = INTERNAL_NAME.replace('/', '.');
    static final String RENDER_DESCRIPTOR = "(II[III[IIIII)V";
    static final String EVENTS_OWNER = "dev/turboism/validation/atlasimage/t033/T033FixtureEvents";
    static final String ADMISSION_OWNER = "dev/turboism/validation/atlasimage/t033/T033FixtureAdmission";
    static final String BOUNDS_DESCRIPTOR = "(II[III[IIIIIZ)J";

    enum Variant {
        EXACT,
        WRONG_DESCRIPTOR,
        WRONG_FLAGS,
        WRONG_BACKEDGE,
        WRONG_PREDECESSOR,
        TYPE_MERGE,
        WRONG_FRAME
    }

    private T035OwnedFixtureGenerator() {
    }

    static byte[] generate() {
        return generate(Variant.EXACT);
    }

    static byte[] generate(final Variant variant) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
            V17,
            ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
            INTERNAL_NAME,
            null,
            "java/lang/Object",
            null
        );
        final FieldVisitor field = writer.visitField(
            ACC_PRIVATE | ACC_STATIC,
            "ASSERTIONS_ENABLED",
            "Z",
            null,
            Boolean.FALSE
        );
        field.visitEnd();
        emitConstructor(writer);
        emitAssertionSetter(writer);
        final int access = variant == Variant.WRONG_FLAGS ? ACC_PUBLIC : ACC_PUBLIC | ACC_FINAL;
        if (variant == Variant.WRONG_DESCRIPTOR) {
            final MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_FINAL, "render", "(I)V", null, null);
            method.visitCode();
            method.visitInsn(RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        } else {
            emitRender(
                writer,
                access,
                variant == Variant.WRONG_BACKEDGE,
                variant == Variant.WRONG_PREDECESSOR
            );
        }
        if (variant == Variant.TYPE_MERGE) {
            emitReferenceMerge(writer);
        }
        writer.visitEnd();
        return normalizeOwnedFrames(writer.toByteArray(), variant == Variant.WRONG_FRAME);
    }

    private static byte[] normalizeOwnedFrames(final byte[] classBytes, final boolean wrongFrame) {
        final T035Shape.OffsetReader reader = new T035Shape.OffsetReader(classBytes);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                final MethodVisitor downstream = super.visitMethod(
                    access, name, descriptor, signature, exceptions);
                if (!name.equals("render") || !descriptor.equals(RENDER_DESCRIPTOR)) {
                    return downstream;
                }
                return new MethodVisitor(ASM9, downstream) {
                    @Override
                    public void visitFrame(
                        final int type,
                        final int numberOfLocals,
                        final Object[] locals,
                        final int numberOfStack,
                        final Object[] stack
                    ) {
                        final Object[] adjusted = Arrays.copyOf(locals, numberOfLocals);
                        if (adjusted.length > 0) {
                            adjusted[0] = TOP;
                        }
                        if ((wrongFrame || reader.offset() >= 60) && adjusted.length > 9) {
                            adjusted[9] = TOP;
                        }
                        mv.visitFrame(F_NEW, adjusted.length, adjusted, numberOfStack, stack);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static void emitConstructor(final ClassWriter writer) {
        final MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitAssertionSetter(final ClassWriter writer) {
        final MethodVisitor method = writer.visitMethod(
            ACC_PUBLIC | ACC_STATIC,
            "setAssertionsEnabled",
            "(Z)V",
            null,
            null
        );
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitFieldInsn(PUTSTATIC, INTERNAL_NAME, "ASSERTIONS_ENABLED", "Z");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitRender(
        final ClassWriter writer,
        final int access,
        final boolean wrongBackedge,
        final boolean wrongPredecessor
    ) {
        final MethodVisitor method = writer.visitMethod(access, "render", RENDER_DESCRIPTOR, null, null);
        method.visitCode();

        method.visitVarInsn(ILOAD, 1);
        method.visitVarInsn(ILOAD, 2);
        method.visitInsn(IMUL);
        method.visitVarInsn(ISTORE, 11);

        final Label assertionPass = new Label();
        method.visitVarInsn(ALOAD, 6);
        method.visitInsn(ARRAYLENGTH);
        method.visitVarInsn(ILOAD, 8);
        method.visitVarInsn(ILOAD, 9);
        method.visitInsn(IMUL);
        method.visitJumpInsn(IF_ICMPGE, assertionPass);
        method.visitFieldInsn(GETSTATIC, INTERNAL_NAME, "ASSERTIONS_ENABLED", "Z");
        method.visitJumpInsn(IFEQ, assertionPass);
        method.visitLdcInsn("owned fixture assertion");
        method.visitVarInsn(ASTORE, 13);
        method.visitTypeInsn(NEW, "java/lang/AssertionError");
        method.visitInsn(DUP);
        method.visitVarInsn(ALOAD, 13);
        method.visitMethodInsn(
            INVOKESPECIAL,
            "java/lang/AssertionError",
            "<init>",
            "(Ljava/lang/Object;)V",
            false
        );
        method.visitInsn(ATHROW);
        method.visitLabel(assertionPass);

        method.visitVarInsn(ALOAD, 6);
        pushInt(method, 0);
        method.visitVarInsn(ALOAD, 6);
        method.visitInsn(ARRAYLENGTH);
        pushInt(method, 0);
        method.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "fill", "([IIII)V", false);
        method.visitMethodInsn(INVOKESTATIC, EVENTS_OWNER, "afterClear", "()V", false);

        final Label yEntry = new Label();
        final Label yBody = new Label();
        final Label yExit = new Label();
        final Label xEntry = new Label();
        final Label xBody = new Label();
        final Label xExit = new Label();
        final Label syEntry = new Label();
        final Label syBody = new Label();
        final Label syExit = new Label();
        final Label sxEntry = new Label();
        final Label sxBody = new Label();
        final Label sxExit = new Label();
        final Label noAlpha = new Label();

        method.visitVarInsn(ILOAD, 9);
        pushInt(method, 1);
        method.visitInsn(ISUB);
        if (wrongPredecessor) {
            method.visitInsn(NOP);
        }
        method.visitVarInsn(ISTORE, 13);
        pushInt(method, 0);
        method.visitVarInsn(ISTORE, 12);
        method.visitLabel(yEntry);
        method.visitVarInsn(ILOAD, 12);
        method.visitVarInsn(ILOAD, 13);
        method.visitJumpInsn(IF_ICMPGT, yExit);
        method.visitLabel(yBody);

        method.visitVarInsn(ILOAD, 12);
        method.visitVarInsn(ILOAD, 10);
        method.visitInsn(IADD);
        method.visitVarInsn(ILOAD, 7);
        method.visitInsn(IMUL);
        method.visitVarInsn(ISTORE, 14);
        method.visitVarInsn(ILOAD, 12);
        method.visitVarInsn(ILOAD, 2);
        method.visitInsn(IMUL);
        method.visitVarInsn(ISTORE, 15);
        method.visitVarInsn(ILOAD, 15);
        method.visitVarInsn(ILOAD, 2);
        method.visitInsn(IADD);
        pushInt(method, 1);
        method.visitInsn(ISUB);
        method.visitVarInsn(ILOAD, 5);
        pushInt(method, 1);
        method.visitInsn(ISUB);
        method.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
        method.visitVarInsn(ISTORE, 16);

        method.visitVarInsn(ILOAD, 8);
        pushInt(method, 1);
        method.visitInsn(ISUB);
        method.visitVarInsn(ISTORE, 18);
        pushInt(method, 0);
        method.visitVarInsn(ISTORE, 17);
        method.visitLabel(xEntry);
        method.visitVarInsn(ILOAD, 17);
        method.visitVarInsn(ILOAD, 18);
        method.visitJumpInsn(IF_ICMPGT, xExit);
        method.visitLabel(xBody);

        method.visitVarInsn(ILOAD, 17);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IMUL);
        method.visitVarInsn(ISTORE, 19);
        method.visitVarInsn(ILOAD, 19);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IADD);
        pushInt(method, 1);
        method.visitInsn(ISUB);
        method.visitVarInsn(ILOAD, 4);
        pushInt(method, 1);
        method.visitInsn(ISUB);
        method.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
        method.visitVarInsn(ISTORE, 20);
        pushInt(method, 0);
        method.visitVarInsn(ISTORE, 21);
        pushInt(method, 0);
        method.visitVarInsn(ISTORE, 22);
        pushInt(method, 0);
        method.visitVarInsn(ISTORE, 23);
        pushInt(method, 0);
        method.visitVarInsn(ISTORE, 24);
        pushInt(method, 0);
        method.visitVarInsn(ISTORE, 25);
        method.visitVarInsn(ILOAD, 15);
        method.visitVarInsn(ISTORE, 27);
        method.visitLabel(syEntry);
        method.visitVarInsn(ILOAD, 27);
        method.visitVarInsn(ILOAD, 16);
        method.visitJumpInsn(IF_ICMPGT, syExit);
        method.visitLabel(syBody);
        method.visitVarInsn(ILOAD, 19);
        method.visitVarInsn(ISTORE, 29);
        method.visitLabel(sxEntry);
        method.visitVarInsn(ILOAD, 29);
        method.visitVarInsn(ILOAD, 20);
        method.visitJumpInsn(IF_ICMPGT, sxExit);
        method.visitLabel(sxBody);
        method.visitVarInsn(ILOAD, 27);
        method.visitVarInsn(ILOAD, 4);
        method.visitInsn(IMUL);
        method.visitVarInsn(ILOAD, 29);
        method.visitInsn(IADD);
        method.visitVarInsn(ISTORE, 28);
        method.visitVarInsn(ILOAD, 28);
        method.visitMethodInsn(INVOKESTATIC, EVENTS_OWNER, "beforeSourceRead", "(I)V", false);
        method.visitVarInsn(ALOAD, 3);
        method.visitVarInsn(ILOAD, 28);
        method.visitInsn(IALOAD);
        method.visitVarInsn(ISTORE, 30);
        method.visitVarInsn(ILOAD, 28);
        method.visitMethodInsn(INVOKESTATIC, EVENTS_OWNER, "afterSourceRead", "(I)V", false);
        method.visitVarInsn(ILOAD, 30);
        pushInt(method, 24);
        method.visitInsn(IUSHR);
        pushInt(method, 255);
        method.visitInsn(IAND);
        method.visitVarInsn(ISTORE, 31);
        method.visitVarInsn(ILOAD, 30);
        pushInt(method, 16);
        method.visitInsn(IUSHR);
        pushInt(method, 255);
        method.visitInsn(IAND);
        method.visitVarInsn(ISTORE, 32);
        method.visitVarInsn(ILOAD, 30);
        pushInt(method, 8);
        method.visitInsn(IUSHR);
        pushInt(method, 255);
        method.visitInsn(IAND);
        method.visitVarInsn(ISTORE, 33);
        method.visitVarInsn(ILOAD, 30);
        pushInt(method, 255);
        method.visitInsn(IAND);
        method.visitVarInsn(ISTORE, 34);
        method.visitVarInsn(ILOAD, 21);
        method.visitVarInsn(ILOAD, 31);
        method.visitInsn(IADD);
        method.visitVarInsn(ISTORE, 21);
        method.visitVarInsn(ILOAD, 32);
        method.visitVarInsn(ILOAD, 31);
        method.visitInsn(IMUL);
        pushInt(method, 255);
        method.visitInsn(IDIV);
        method.visitVarInsn(ILOAD, 22);
        method.visitInsn(IADD);
        method.visitVarInsn(ISTORE, 22);
        method.visitVarInsn(ILOAD, 33);
        method.visitVarInsn(ILOAD, 31);
        method.visitInsn(IMUL);
        pushInt(method, 255);
        method.visitInsn(IDIV);
        method.visitVarInsn(ILOAD, 23);
        method.visitInsn(IADD);
        method.visitVarInsn(ISTORE, 23);
        method.visitVarInsn(ILOAD, 34);
        method.visitVarInsn(ILOAD, 31);
        method.visitInsn(IMUL);
        pushInt(method, 255);
        method.visitInsn(IDIV);
        method.visitVarInsn(ILOAD, 24);
        method.visitInsn(IADD);
        method.visitVarInsn(ISTORE, 24);
        method.visitIincInsn(25, 1);
        method.visitVarInsn(ILOAD, 29);
        method.visitVarInsn(ILOAD, 20);
        method.visitJumpInsn(IF_ICMPEQ, sxExit);
        method.visitIincInsn(29, 1);
        method.visitJumpInsn(GOTO, wrongBackedge ? sxEntry : sxBody);
        method.visitLabel(sxExit);
        method.visitVarInsn(ILOAD, 27);
        method.visitVarInsn(ILOAD, 16);
        method.visitJumpInsn(IF_ICMPEQ, syExit);
        method.visitIincInsn(27, 1);
        method.visitJumpInsn(GOTO, wrongBackedge ? syEntry : syBody);
        method.visitLabel(syExit);

        method.visitVarInsn(ILOAD, 21);
        method.visitVarInsn(ISTORE, 26);
        method.visitVarInsn(ILOAD, 21);
        method.visitJumpInsn(IFEQ, noAlpha);
        method.visitVarInsn(ILOAD, 21);
        method.visitVarInsn(ILOAD, 11);
        method.visitInsn(IDIV);
        method.visitVarInsn(ISTORE, 21);
        method.visitVarInsn(ILOAD, 22);
        pushInt(method, 255);
        method.visitInsn(IMUL);
        method.visitVarInsn(ILOAD, 26);
        method.visitInsn(IDIV);
        method.visitVarInsn(ISTORE, 22);
        method.visitVarInsn(ILOAD, 23);
        pushInt(method, 255);
        method.visitInsn(IMUL);
        method.visitVarInsn(ILOAD, 26);
        method.visitInsn(IDIV);
        method.visitVarInsn(ISTORE, 23);
        method.visitVarInsn(ILOAD, 24);
        pushInt(method, 255);
        method.visitInsn(IMUL);
        method.visitVarInsn(ILOAD, 26);
        method.visitInsn(IDIV);
        method.visitVarInsn(ISTORE, 24);
        method.visitLabel(noAlpha);

        method.visitVarInsn(ILOAD, 14);
        method.visitVarInsn(ILOAD, 17);
        method.visitVarInsn(ILOAD, 10);
        method.visitInsn(IADD);
        method.visitInsn(IADD);
        method.visitVarInsn(ISTORE, 30);
        method.visitVarInsn(ILOAD, 30);
        method.visitMethodInsn(INVOKESTATIC, EVENTS_OWNER, "beforeTargetWrite", "(I)V", false);
        method.visitVarInsn(ALOAD, 6);
        method.visitVarInsn(ILOAD, 30);
        method.visitVarInsn(ILOAD, 21);
        pushInt(method, 24);
        method.visitInsn(ISHL);
        method.visitVarInsn(ILOAD, 22);
        pushInt(method, 16);
        method.visitInsn(ISHL);
        method.visitInsn(IOR);
        method.visitVarInsn(ILOAD, 23);
        pushInt(method, 8);
        method.visitInsn(ISHL);
        method.visitInsn(IOR);
        method.visitVarInsn(ILOAD, 24);
        method.visitInsn(IOR);
        method.visitInsn(IASTORE);
        method.visitVarInsn(ILOAD, 30);
        method.visitMethodInsn(INVOKESTATIC, EVENTS_OWNER, "afterTargetWrite", "(I)V", false);

        method.visitVarInsn(ILOAD, 17);
        method.visitVarInsn(ILOAD, 18);
        method.visitJumpInsn(IF_ICMPEQ, xExit);
        method.visitIincInsn(17, 1);
        method.visitJumpInsn(GOTO, wrongBackedge ? xEntry : xBody);
        method.visitLabel(xExit);
        method.visitVarInsn(ILOAD, 12);
        method.visitVarInsn(ILOAD, 13);
        method.visitJumpInsn(IF_ICMPEQ, yExit);
        method.visitIincInsn(12, 1);
        method.visitJumpInsn(GOTO, wrongBackedge ? yEntry : yBody);
        method.visitLabel(yExit);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitReferenceMerge(final ClassWriter writer) {
        final MethodVisitor method = writer.visitMethod(ACC_PRIVATE | ACC_STATIC, "referenceMerge", "()V", null, null);
        final Label integerPath = new Label();
        final Label join = new Label();
        method.visitCode();
        method.visitInsn(ICONST_0);
        method.visitJumpInsn(IFEQ, integerPath);
        method.visitLdcInsn("string branch");
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(integerPath);
        method.visitTypeInsn(NEW, "java/lang/Integer");
        method.visitInsn(DUP);
        method.visitInsn(ICONST_1);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Integer", "<init>", "(I)V", false);
        method.visitLabel(join);
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void pushInt(final MethodVisitor method, final int value) {
        if (value >= -1 && value <= 5) {
            method.visitInsn(ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            method.visitIntInsn(BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            method.visitIntInsn(SIPUSH, value);
        } else {
            method.visitLdcInsn(value);
        }
    }
}
