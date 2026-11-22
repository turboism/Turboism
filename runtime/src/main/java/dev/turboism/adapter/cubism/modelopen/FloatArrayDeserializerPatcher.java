package dev.turboism.adapter.cubism.modelopen;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Overrides {@code createInstance} on {@code com/live2d/serialize/impl/G} to eliminate token
 * list splitting and boxed float conversion during XML array deserialization.
 */
final class FloatArrayDeserializerPatcher {

    static final String TARGET_INTERNAL_NAME = "com/live2d/serialize/impl/G";
    private static final String CREATE_INSTANCE_NAME = "createInstance";
    private static final String CREATE_INSTANCE_DESC =
            "(Lorg/jdom/Element;Lcom/live2d/serialize/XmlReader;)Ljava/lang/Object;";
    private static final String INTRINSICS_OWNER = "kotlin/jvm/internal/Intrinsics";
    private static final String CHECK_NOT_NULL_DESC = "(Ljava/lang/Object;Ljava/lang/String;)V";
    private static final String ELEMENT_OWNER = "org/jdom/Element";
    private static final String ATTR_COUNT = "count";
    private static final String GET_ATTR_VALUE_DESC = "(Ljava/lang/String;)Ljava/lang/String;";
    private static final String GET_TEXT_DESC = "()Ljava/lang/String;";
    private static final String INTEGER_OWNER = "java/lang/Integer";
    private static final String PARSE_INT_DESC = "(Ljava/lang/String;)I";
    private static final String KOTLIN_ASSERTIONS_OWNER = "kotlin/_Assertions";
    private static final String BRIDGE_OWNER = "dev/turboism/adapter/cubism/modelopen/FloatArrayDeserializerBridge";
    private static final String PARSE_FLOAT_ARRAY_DESC = "(ILjava/lang/String;Z)[F";

    private FloatArrayDeserializerPatcher() {}

    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (!TARGET_INTERNAL_NAME.equals(internalName)) {
            return null;
        }
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.hasDeclaredMethod(reader, CREATE_INSTANCE_NAME, CREATE_INSTANCE_DESC)) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final ClassVisitor visitor = new ClassVisitor(ASM9, writer) {
            @Override
            public void visitEnd() {
                emitCreateInstance(
                        super.visitMethod(ACC_PUBLIC, CREATE_INSTANCE_NAME, CREATE_INSTANCE_DESC, null, null));
                super.visitEnd();
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static void emitCreateInstance(final MethodVisitor mv) {
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn("");
        mv.visitMethodInsn(INVOKESTATIC, INTRINSICS_OWNER, "checkNotNullParameter", CHECK_NOT_NULL_DESC, false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitLdcInsn("");
        mv.visitMethodInsn(INVOKESTATIC, INTRINSICS_OWNER, "checkNotNullParameter", CHECK_NOT_NULL_DESC, false);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn(ATTR_COUNT);
        mv.visitMethodInsn(INVOKEVIRTUAL, ELEMENT_OWNER, "getAttributeValue", GET_ATTR_VALUE_DESC, false);
        mv.visitMethodInsn(INVOKESTATIC, INTEGER_OWNER, "parseInt", PARSE_INT_DESC, false);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, ELEMENT_OWNER, "getText", GET_TEXT_DESC, false);
        mv.visitFieldInsn(GETSTATIC, KOTLIN_ASSERTIONS_OWNER, "ENABLED", "Z");
        mv.visitMethodInsn(INVOKESTATIC, BRIDGE_OWNER, "parseFloatArray", PARSE_FLOAT_ARRAY_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
