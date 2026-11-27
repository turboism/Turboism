package dev.turboism.adapter.cubism.optimization.save;

import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.POP;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Rewrites primitive {@code float[]} and {@code int[]} XML element serializers to bypass Kotlin
 * {@code ArraysKt.joinToString$default} boxing and dynamically resized buffers.
 *
 * <p>Modifies only {@code createElement} so deserialization methods patched concurrently on
 * {@code com/live2d/serialize/impl/G} and {@code com/live2d/serialize/impl/H} remain intact.</p>
 */
public final class PrimitiveArraySerializerPatcher {

    static final String FLOAT_SERIALIZER = "com/live2d/serialize/impl/G";
    static final String INT_SERIALIZER = "com/live2d/serialize/impl/H";

    private static final String BRIDGE_OWNER =
            "dev/turboism/adapter/cubism/optimization/save/PrimitiveArraySerializerBridge";
    private static final String CREATE_ELEMENT_NAME = "createElement";
    private static final String CREATE_ELEMENT_DESC =
            "(Ljava/lang/String;Ljava/lang/Object;Lcom/live2d/serialize/XmlWriter;)Lorg/jdom/Element;";
    private static final String ARRAYS_KT_OWNER = "kotlin/collections/ArraysKt";
    private static final String JOIN_TO_STRING_DEFAULT = "joinToString$default";
    private static final String FLOAT_JOIN_DESC =
            "([FLjava/lang/CharSequence;Ljava/lang/CharSequence;Ljava/lang/CharSequence;"
                    + "ILjava/lang/CharSequence;Lkotlin/jvm/functions/Function1;"
                    + "ILjava/lang/Object;)Ljava/lang/String;";
    private static final String INT_JOIN_DESC =
            "([ILjava/lang/CharSequence;Ljava/lang/CharSequence;Ljava/lang/CharSequence;"
                    + "ILjava/lang/CharSequence;Lkotlin/jvm/functions/Function1;"
                    + "ILjava/lang/Object;)Ljava/lang/String;";
    private static final String FORMAT_FLOAT_NAME = "formatFloatArray";
    private static final String FORMAT_FLOAT_DESC = "([FLjava/lang/CharSequence;)Ljava/lang/String;";
    private static final String FORMAT_INT_NAME = "formatIntArray";
    private static final String FORMAT_INT_DESC = "([ILjava/lang/CharSequence;)Ljava/lang/String;";
    private static final int TRAILING_DEFAULT_ARG_SLOTS = 7;

    private PrimitiveArraySerializerPatcher() {}

    /**
     * Returns whether {@code internalName} is one of the supported array serializer targets.
     *
     * @param internalName JVM internal class name
     * @return {@code true} for {@code impl/G} or {@code impl/H}
     */
    public static boolean isSupportedTarget(final String internalName) {
        return FLOAT_SERIALIZER.equals(internalName) || INT_SERIALIZER.equals(internalName);
    }

    /**
     * Patches {@code createElement} when it still calls {@code ArraysKt.joinToString$default}.
     *
     * @param internalName JVM internal class name
     * @param loader target host class loader, nullable
     * @param classfileBuffer input class bytes
     * @return transformed class bytes, or {@code null} when not applicable or already patched
     */
    public static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (!isSupportedTarget(internalName) || classfileBuffer == null) {
            return null;
        }
        final boolean isFloat = FLOAT_SERIALIZER.equals(internalName);
        final String expectedJoinDesc = isFloat ? FLOAT_JOIN_DESC : INT_JOIN_DESC;
        final String bridgeMethodName = isFloat ? FORMAT_FLOAT_NAME : FORMAT_INT_NAME;
        final String bridgeMethodDesc = isFloat ? FORMAT_FLOAT_DESC : FORMAT_INT_DESC;

        final ClassReader reader = new ClassReader(classfileBuffer);
        if (SavePerfClassWriters.callsOwner(reader, BRIDGE_OWNER)
                || !hasTargetCreateElementCall(reader, expectedJoinDesc)) {
            return null;
        }

        final ClassWriter writer = SavePerfClassWriters.newFrameWriter(reader, loader);
        final boolean[] rewritten = new boolean[1];
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!CREATE_ELEMENT_NAME.equals(name) || !CREATE_ELEMENT_DESC.equals(desc)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                if (opcode == INVOKESTATIC
                                        && ARRAYS_KT_OWNER.equals(owner)
                                        && JOIN_TO_STRING_DEFAULT.equals(mName)
                                        && expectedJoinDesc.equals(mDesc)) {
                                    rewritten[0] = true;
                                    // Discard the 7 Kotlin default-argument slots above (array, separator).
                                    for (int i = 0; i < TRAILING_DEFAULT_ARG_SLOTS; i++) {
                                        super.visitInsn(POP);
                                    }
                                    super.visitMethodInsn(
                                            INVOKESTATIC, BRIDGE_OWNER, bridgeMethodName, bridgeMethodDesc, false);
                                    return;
                                }
                                super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return rewritten[0] ? writer.toByteArray() : null;
    }

    private static boolean hasTargetCreateElementCall(final ClassReader reader, final String expectedJoinDesc) {
        final boolean[] found = new boolean[1];
        reader.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        if (!CREATE_ELEMENT_NAME.equals(name) || !CREATE_ELEMENT_DESC.equals(desc)) {
                            return null;
                        }
                        return new MethodVisitor(ASM9) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                if (opcode == INVOKESTATIC
                                        && ARRAYS_KT_OWNER.equals(owner)
                                        && JOIN_TO_STRING_DEFAULT.equals(mName)
                                        && expectedJoinDesc.equals(mDesc)) {
                                    found[0] = true;
                                }
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }
}
