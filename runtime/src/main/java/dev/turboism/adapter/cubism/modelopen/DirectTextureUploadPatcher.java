package dev.turboism.adapter.cubism.modelopen;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.IALOAD;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ICONST_2;
import static org.objectweb.asm.Opcodes.ICONST_3;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IFNULL;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.L2I;
import static org.objectweb.asm.Opcodes.LAND;
import static org.objectweb.asm.Opcodes.LCMP;
import static org.objectweb.asm.Opcodes.LCONST_0;
import static org.objectweb.asm.Opcodes.LCONST_1;
import static org.objectweb.asm.Opcodes.LLOAD;
import static org.objectweb.asm.Opcodes.LSTORE;
import static org.objectweb.asm.Opcodes.LUSHR;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.NEWARRAY;
import static org.objectweb.asm.Opcodes.T_INT;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Structurally detects the Cubism GL shader texture factory across all versions and rewrites its
 * two texture upload overloads to invoke {@link DirectTextureUploadBridge}.
 */
final class DirectTextureUploadPatcher {

    static final String SHADER_PACKAGE_PREFIX = "com/live2d/graphics3d/shader/";
    static final String FLAGGED_METHOD_NAME = "a";
    static final String FLAGGED_METHOD_DESC =
            "(Lcom/live2d/graphics3d/a;Lcom/live2d/graphics/CWritableImage;ZZLjava/lang/String;)I";
    static final String MIPMAP_METHOD_NAME = "a";
    static final String MIPMAP_METHOD_DESC =
            "(Lcom/live2d/graphics3d/a;Lcom/live2d/graphics/CWritableImage;ILjava/lang/String;)Lkotlin/Pair;";

    static final String NATIVE_FLAGGED_NAME = "turboism$nativeUploadFlagged";
    static final String NATIVE_MIPMAP_NAME = "turboism$nativeUploadMipmap";

    private static final String BRIDGE_INTERNAL_NAME =
            "dev/turboism/adapter/cubism/modelopen/DirectTextureUploadBridge";
    private static final String TRY_UPLOAD_FLAGGED_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/awt/image/BufferedImage;ZZ)J";
    private static final String TRY_UPLOAD_MIPMAP_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/awt/image/BufferedImage;II[I)Z";
    private static final String GRAPHICS_ENV_OWNER = "com/live2d/graphics3d/a";
    private static final String GET_GL3_DESC = "()Lcom/jogamp/opengl/GL3;";
    private static final String GL3_OWNER = "com/jogamp/opengl/GL3";
    private static final String WRITABLE_IMAGE_OWNER = "com/live2d/graphics/CWritableImage";
    private static final String GET_BUFFERED_IMAGE_DESC = "()Ljava/awt/image/BufferedImage;";
    private static final String GET_TYPE_DESC = "()Lcom/live2d/graphics/n;";
    private static final String COLOR_TYPE_OWNER = "com/live2d/graphics/n";
    private static final String COLOR_TYPE_CODE_DESC = "()I";
    private static final String CSIZE_OWNER = "com/live2d/type/CSize";
    private static final String CSIZE_CTOR_DESC = "(II)V";
    private static final String PAIR_OWNER = "kotlin/Pair";
    private static final String PAIR_CTOR_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String INTEGER_OWNER = "java/lang/Integer";
    private static final String INTEGER_VALUE_OF_DESC = "(I)Ljava/lang/Integer;";

    private static final int RESULT_ARRAY_SIZE = 3;
    private static final int PACKED_ID_SHIFT = 32;

    private DirectTextureUploadPatcher() {}

    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (!internalName.startsWith(SHADER_PACKAGE_PREFIX) || internalName.indexOf('$') >= 0) {
            return null;
        }
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (ModelOpenClassWriters.hasDeclaredMethod(reader, NATIVE_FLAGGED_NAME, FLAGGED_METHOD_DESC)
                || ModelOpenClassWriters.hasDeclaredMethod(reader, NATIVE_MIPMAP_NAME, MIPMAP_METHOD_DESC)) {
            return null;
        }
        if (!ModelOpenClassWriters.hasDeclaredMethod(reader, FLAGGED_METHOD_NAME, FLAGGED_METHOD_DESC)
                || !ModelOpenClassWriters.hasDeclaredMethod(reader, MIPMAP_METHOD_NAME, MIPMAP_METHOD_DESC)) {
            return null;
        }

        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final int[] patchedFlags = new int[2];
        final ClassVisitor visitor = new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String desc,
                    final String signature,
                    final String[] exceptions) {
                if (FLAGGED_METHOD_NAME.equals(name) && FLAGGED_METHOD_DESC.equals(desc)) {
                    patchedFlags[0] = 1;
                    emitFlaggedWrapper(super.visitMethod(access, name, desc, signature, exceptions), internalName);
                    return super.visitMethod(ACC_PRIVATE | ACC_FINAL, NATIVE_FLAGGED_NAME, desc, signature, exceptions);
                }
                if (MIPMAP_METHOD_NAME.equals(name) && MIPMAP_METHOD_DESC.equals(desc)) {
                    patchedFlags[1] = 1;
                    emitMipmapWrapper(super.visitMethod(access, name, desc, signature, exceptions), internalName);
                    return super.visitMethod(ACC_PRIVATE | ACC_FINAL, NATIVE_MIPMAP_NAME, desc, signature, exceptions);
                }
                return super.visitMethod(access, name, desc, signature, exceptions);
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (patchedFlags[0] == 0 || patchedFlags[1] == 0) {
            return null;
        }
        return writer.toByteArray();
    }

    private static void emitFlaggedWrapper(final MethodVisitor mv, final String ownerInternalName) {
        final Label fallback = new Label();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitJumpInsn(IFNULL, fallback);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitJumpInsn(IFNULL, fallback);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, GRAPHICS_ENV_OWNER, "a", GET_GL3_DESC, false);
        mv.visitMethodInsn(INVOKEINTERFACE, GL3_OWNER, "getGL3", GET_GL3_DESC, true);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, WRITABLE_IMAGE_OWNER, "getJBufferedImage", GET_BUFFERED_IMAGE_DESC, false);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ILOAD, 4);
        mv.visitMethodInsn(INVOKESTATIC, BRIDGE_INTERNAL_NAME, "tryUploadFlagged", TRY_UPLOAD_FLAGGED_DESC, false);
        mv.visitVarInsn(LSTORE, 6);

        mv.visitVarInsn(LLOAD, 6);
        mv.visitInsn(LCONST_1);
        mv.visitInsn(LAND);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        mv.visitJumpInsn(IFEQ, fallback);

        mv.visitVarInsn(LLOAD, 6);
        mv.visitIntInsn(BIPUSH, PACKED_ID_SHIFT);
        mv.visitInsn(LUSHR);
        mv.visitInsn(L2I);
        mv.visitInsn(IRETURN);

        mv.visitLabel(fallback);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ILOAD, 4);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKESPECIAL, ownerInternalName, NATIVE_FLAGGED_NAME, FLAGGED_METHOD_DESC, false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitMipmapWrapper(final MethodVisitor mv, final String ownerInternalName) {
        final Label fallback = new Label();
        final Label nullSize = new Label();
        final Label buildPair = new Label();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitJumpInsn(IFNULL, fallback);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitJumpInsn(IFNULL, fallback);

        mv.visitInsn(ICONST_3);
        mv.visitIntInsn(NEWARRAY, T_INT);
        mv.visitVarInsn(ASTORE, 5);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, GRAPHICS_ENV_OWNER, "a", GET_GL3_DESC, false);
        mv.visitMethodInsn(INVOKEINTERFACE, GL3_OWNER, "getGL3", GET_GL3_DESC, true);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, WRITABLE_IMAGE_OWNER, "getJBufferedImage", GET_BUFFERED_IMAGE_DESC, false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, WRITABLE_IMAGE_OWNER, "getType", GET_TYPE_DESC, false);
        mv.visitMethodInsn(INVOKEVIRTUAL, COLOR_TYPE_OWNER, "a", COLOR_TYPE_CODE_DESC, false);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKESTATIC, BRIDGE_INTERNAL_NAME, "tryUploadMipmapAligned", TRY_UPLOAD_MIPMAP_DESC, false);
        mv.visitJumpInsn(IFEQ, fallback);

        mv.visitTypeInsn(NEW, PAIR_OWNER);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IALOAD);
        mv.visitMethodInsn(INVOKESTATIC, INTEGER_OWNER, "valueOf", INTEGER_VALUE_OF_DESC, false);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IALOAD);
        mv.visitJumpInsn(IFEQ, nullSize);

        mv.visitTypeInsn(NEW, CSIZE_OWNER);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IALOAD);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitInsn(ICONST_2);
        mv.visitInsn(IALOAD);
        mv.visitMethodInsn(INVOKESPECIAL, CSIZE_OWNER, "<init>", CSIZE_CTOR_DESC, false);
        mv.visitJumpInsn(org.objectweb.asm.Opcodes.GOTO, buildPair);

        mv.visitLabel(nullSize);
        mv.visitInsn(ACONST_NULL);

        mv.visitLabel(buildPair);
        mv.visitMethodInsn(INVOKESPECIAL, PAIR_OWNER, "<init>", PAIR_CTOR_DESC, false);
        mv.visitInsn(ARETURN);

        mv.visitLabel(fallback);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKESPECIAL, ownerInternalName, NATIVE_MIPMAP_NAME, MIPMAP_METHOD_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
