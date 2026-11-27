package dev.turboism.adapter.cubism.optimization.atlas;

import static org.objectweb.asm.Opcodes.*;

import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Patches Cubism atlas compositing and multi-page rebuild entrypoints across versions
 * {@code 5.0.00–5.3.05}: {@code CTextureAtlas.setupCacheImage$cubism},
 * {@code com.live2d.util.{d,e,f}/g.a} (public 7-argument tile blit),
 * {@code atlasEditor.impl.c} confirm loop, and {@code doc.model.texture.G}.
 */
public final class AtlasDirectCompositingPatcher {

    public static final String TEXTURE_ATLAS = "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas";
    public static final String CONFIRM_LOOP = "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/c";
    public static final String TEXTURE_UPDATE_LOOP = "com/live2d/cubism/doc/model/texture/G";
    private static final Set<String> TILE_HELPER_TARGETS =
            Set.of("com/live2d/util/d/g", "com/live2d/util/e/g", "com/live2d/util/f/g");

    private static final String SETUP_CACHE_NAME = "setupCacheImage$cubism";
    private static final String SETUP_CACHE_DESC = "(ZLcom/live2d/util/a/a;)V";
    private static final String SETUP_CACHE_ORIG = "setupCacheImage$turboismOrig";
    private static final String SETUP_CACHE_COMMIT = "setupCacheImage$turboismCommit";
    private static final String SETUP_CACHE_COMMIT_DESC =
            "(Lcom/live2d/graphics/CWritableImage;Lcom/live2d/util/a/a;)V";

    private static final String G_TILE_METHOD = "a";
    private static final String G_TILE_DESC =
            "(Ljava/awt/image/BufferedImage;Ljava/awt/Graphics;Ljava/awt/image/BufferedImage;IIDZ)V";
    private static final String G_TILE_ORIG = "a$turboismOrig";

    private static final String CONFIRM_METHOD = "a";
    private static final String CONFIRM_DESC = "(Lcom/live2d/util/a/a;)V";
    private static final String CONFIRM_ORIG = "a$turboismOrig";

    private static final String COMPOSITOR_INTERNAL = Type.getInternalName(AtlasTilePageCompositor.class);
    private static final String COORDINATOR_INTERNAL = Type.getInternalName(AtlasPageRebuildCoordinator.class);

    private static final String COMPOSITOR_SETUP_DESC =
            "(Ljava/lang/Class;Ljava/lang/Object;ZLjava/lang/Object;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)V";
    private static final String COMPOSITOR_TILE_DESC =
            "(Ljava/lang/Class;Ljava/lang/Object;Ljava/awt/image/BufferedImage;Ljava/awt/Graphics;"
                    + "Ljava/awt/image/BufferedImage;IIDZLjava/lang/invoke/MethodHandle;)V";
    private static final String COORDINATOR_ENQUEUE_DESC = "(Ljava/lang/Object;ILjava/lang/Object;)V";
    private static final String COORDINATOR_RUN_DESC =
            "(Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/invoke/MethodHandle;)V";

    private static final String PROGRESS_INTERNAL = "com/live2d/util/a/a";
    private static final String PROGRESS_SUB_DESC = "(ILkotlin/jvm/functions/Function1;)V";
    private static final String CONFIRM_TASK_CTOR_OWNER = "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/d";

    private static final String WRITABLE_IMAGE_INTERNAL = "com/live2d/graphics/CWritableImage";
    private static final String IMAGE_RESOURCE_INTERNAL = "com/live2d/graphics/CImageResource";
    private static final String IMAGE_RESOURCE_CTOR_DESC =
            "(Lcom/live2d/graphics/CWritableImage;Lcom/live2d/graphics/n;ZILkotlin/jvm/internal/DefaultConstructorMarker;)V";
    private static final int IMAGE_RESOURCE_DEFAULT_MASK = 6;

    private AtlasDirectCompositingPatcher() {}

    /** Patches a supported host class if its structure matches and it is not yet patched. */
    public static byte[] patch(final String className, final byte[] bytes) {
        if (bytes == null || className == null) {
            return null;
        }
        if (TEXTURE_ATLAS.equals(className)) {
            return patchTextureAtlas(bytes);
        }
        if (TILE_HELPER_TARGETS.contains(className)) {
            return patchTileHelper(className, bytes);
        }
        if (CONFIRM_LOOP.equals(className)) {
            return patchConfirmLoop(bytes);
        }
        if (TEXTURE_UPDATE_LOOP.equals(className)) {
            return patchTextureUpdateLoop(bytes);
        }
        return null;
    }

    private static byte[] patchTextureUpdateLoop(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        if (!TEXTURE_UPDATE_LOOP.equals(reader.getClassName())) {
            return null;
        }
        final TextureUpdateScan scan = new TextureUpdateScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
            return null;
        }
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!"a".equals(name) || !CONFIRM_DESC.equals(descriptor)) {
                            return output;
                        }
                        return new MethodVisitor(ASM9, output) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                final Label fallback = new Label();
                                super.visitVarInsn(ALOAD, 0);
                                super.visitFieldInsn(GETFIELD, TEXTURE_UPDATE_LOOP, "a", "Ljava/util/List;");
                                super.visitVarInsn(ALOAD, 1);
                                super.visitMethodInsn(
                                        INVOKESTATIC,
                                        COORDINATOR_INTERNAL,
                                        "runParallelTextureUpdates",
                                        "(Ljava/util/List;Ljava/lang/Object;)Z",
                                        false);
                                super.visitJumpInsn(IFEQ, fallback);
                                super.visitInsn(RETURN);
                                super.visitLabel(fallback);
                                super.visitFrame(
                                        F_NEW,
                                        2,
                                        new Object[] {TEXTURE_UPDATE_LOOP, PROGRESS_INTERNAL},
                                        0,
                                        new Object[0]);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchTextureAtlas(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        if (!TEXTURE_ATLAS.equals(reader.getClassName())) {
            return null;
        }
        final TextureAtlasScan scan = scanTextureAtlas(reader);
        if (!scan.matches()) {
            return null;
        }
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (SETUP_CACHE_NAME.equals(name) && SETUP_CACHE_DESC.equals(descriptor)) {
                            // Emit cloned private original method preserving exact bytecode & frames.
                            return super.visitMethod(
                                    ACC_PRIVATE | ACC_FINAL, SETUP_CACHE_ORIG, descriptor, null, exceptions);
                        }
                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }

                    @Override
                    public void visitEnd() {
                        emitCommitHelper(writer);
                        emitSetupCacheWrapper(writer);
                        super.visitEnd();
                    }
                },
                0);
        return writer.toByteArray();
    }

    private static void emitCommitHelper(final ClassVisitor cv) {
        final MethodVisitor mv =
                cv.visitMethod(ACC_PRIVATE | ACC_FINAL, SETUP_CACHE_COMMIT, SETUP_CACHE_COMMIT_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(NEW, IMAGE_RESOURCE_INTERNAL);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ICONST_0);
        mv.visitIntInsn(BIPUSH, IMAGE_RESOURCE_DEFAULT_MASK);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESPECIAL, IMAGE_RESOURCE_INTERNAL, "<init>", IMAGE_RESOURCE_CTOR_DESC, false);
        mv.visitMethodInsn(
                INVOKEVIRTUAL, TEXTURE_ATLAS, "setCachedAtlasImage", "(L" + IMAGE_RESOURCE_INTERNAL + ";)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKEVIRTUAL, TEXTURE_ATLAS, "setDirty_cachedAtlasImage", "(Z)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETFIELD, TEXTURE_ATLAS, "atlasVersion", "I");
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitFieldInsn(PUTFIELD, TEXTURE_ATLAS, "atlasVersion", "I");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, PROGRESS_INTERNAL, "b", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitSetupCacheWrapper(final ClassVisitor cv) {
        final MethodVisitor mv = cv.visitMethod(ACC_PUBLIC | ACC_FINAL, SETUP_CACHE_NAME, SETUP_CACHE_DESC, null, null);
        mv.visitCode();
        mv.visitLdcInsn(Type.getObjectType(TEXTURE_ATLAS));
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitLdcInsn(new Handle(H_INVOKESPECIAL, TEXTURE_ATLAS, SETUP_CACHE_COMMIT, SETUP_CACHE_COMMIT_DESC, false));
        mv.visitLdcInsn(new Handle(H_INVOKESPECIAL, TEXTURE_ATLAS, SETUP_CACHE_ORIG, SETUP_CACHE_DESC, false));
        mv.visitMethodInsn(INVOKESTATIC, COMPOSITOR_INTERNAL, "setupCacheImage", COMPOSITOR_SETUP_DESC, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static byte[] patchTileHelper(final String internalName, final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        if (!internalName.equals(reader.getClassName())) {
            return null;
        }
        final boolean[] foundAndUnpatched = new boolean[2]; // [0]=foundTarget, [1]=alreadyPatched
        reader.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (G_TILE_METHOD.equals(name) && G_TILE_DESC.equals(descriptor)) {
                            foundAndUnpatched[0] = true;
                        } else if (G_TILE_ORIG.equals(name) && G_TILE_DESC.equals(descriptor)) {
                            foundAndUnpatched[1] = true;
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!foundAndUnpatched[0] || foundAndUnpatched[1]) {
            return null;
        }

        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (G_TILE_METHOD.equals(name) && G_TILE_DESC.equals(descriptor)) {
                            return super.visitMethod(
                                    ACC_PRIVATE | ACC_FINAL, G_TILE_ORIG, descriptor, null, exceptions);
                        }
                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }

                    @Override
                    public void visitEnd() {
                        final MethodVisitor mv =
                                writer.visitMethod(ACC_PUBLIC | ACC_FINAL, G_TILE_METHOD, G_TILE_DESC, null, null);
                        mv.visitCode();
                        mv.visitLdcInsn(Type.getObjectType(internalName));
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitVarInsn(ALOAD, 1);
                        mv.visitVarInsn(ALOAD, 2);
                        mv.visitVarInsn(ALOAD, 3);
                        mv.visitVarInsn(ILOAD, 4);
                        mv.visitVarInsn(ILOAD, 5);
                        mv.visitVarInsn(DLOAD, 6);
                        mv.visitVarInsn(ILOAD, 8);
                        mv.visitLdcInsn(new Handle(H_INVOKESPECIAL, internalName, G_TILE_ORIG, G_TILE_DESC, false));
                        mv.visitMethodInsn(
                                INVOKESTATIC,
                                COMPOSITOR_INTERNAL,
                                "drawTileWithEdgeFillOrFallback",
                                COMPOSITOR_TILE_DESC,
                                false);
                        mv.visitInsn(RETURN);
                        mv.visitMaxs(0, 0);
                        mv.visitEnd();
                        super.visitEnd();
                    }
                },
                0);
        return writer.toByteArray();
    }

    private static byte[] patchConfirmLoop(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        if (!CONFIRM_LOOP.equals(reader.getClassName())) {
            return null;
        }
        final ConfirmScan scan = scanConfirmLoop(reader);
        if (!scan.matches()) {
            return null;
        }
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (CONFIRM_METHOD.equals(name) && CONFIRM_DESC.equals(descriptor)) {
                            final MethodVisitor mv = super.visitMethod(
                                    ACC_PRIVATE | ACC_FINAL, CONFIRM_ORIG, descriptor, null, exceptions);
                            return new MethodVisitor(ASM9, mv) {
                                @Override
                                public void visitMethodInsn(
                                        final int opcode,
                                        final String owner,
                                        final String mName,
                                        final String mDesc,
                                        final boolean isInterface) {
                                    if (opcode == INVOKEVIRTUAL
                                            && PROGRESS_INTERNAL.equals(owner)
                                            && "a".equals(mName)
                                            && PROGRESS_SUB_DESC.equals(mDesc)) {
                                        super.visitMethodInsn(
                                                INVOKESTATIC,
                                                COORDINATOR_INTERNAL,
                                                "enqueueConfirmPage",
                                                COORDINATOR_ENQUEUE_DESC,
                                                false);
                                        return;
                                    }
                                    super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                                }
                            };
                        }
                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }

                    @Override
                    public void visitEnd() {
                        final MethodVisitor mv =
                                writer.visitMethod(ACC_PUBLIC | ACC_FINAL, CONFIRM_METHOD, CONFIRM_DESC, null, null);
                        mv.visitCode();
                        mv.visitLdcInsn(Type.getObjectType(CONFIRM_LOOP));
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitVarInsn(ALOAD, 1);
                        mv.visitLdcInsn(new Handle(H_INVOKESPECIAL, CONFIRM_LOOP, CONFIRM_ORIG, CONFIRM_DESC, false));
                        mv.visitMethodInsn(
                                INVOKESTATIC, COORDINATOR_INTERNAL, "runConfirmBatch", COORDINATOR_RUN_DESC, false);
                        mv.visitInsn(RETURN);
                        mv.visitMaxs(0, 0);
                        mv.visitEnd();
                        super.visitEnd();
                    }
                },
                0);
        return writer.toByteArray();
    }

    private static TextureAtlasScan scanTextureAtlas(final ClassReader reader) {
        final TextureAtlasScan scan = new TextureAtlasScan();
        reader.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public org.objectweb.asm.FieldVisitor visitField(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final Object value) {
                        if ("atlasVersion".equals(name) && "I".equals(descriptor)) {
                            scan.hasAtlasVersionField = true;
                        }
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (SETUP_CACHE_ORIG.equals(name) || SETUP_CACHE_COMMIT.equals(name)) {
                            scan.alreadyPatched = true;
                            return null;
                        }
                        if (!SETUP_CACHE_NAME.equals(name) || !SETUP_CACHE_DESC.equals(descriptor)) {
                            return null;
                        }
                        scan.hasSetupMethod = true;
                        return new MethodVisitor(ASM9) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                if (IMAGE_RESOURCE_INTERNAL.equals(owner)
                                        && "<init>".equals(mName)
                                        && IMAGE_RESOURCE_CTOR_DESC.equals(mDesc)) {
                                    scan.hasImageResourceCtor = true;
                                } else if (TEXTURE_ATLAS.equals(owner) && "setCachedAtlasImage".equals(mName)) {
                                    scan.hasSetCached = true;
                                } else if (TEXTURE_ATLAS.equals(owner) && "setDirty_cachedAtlasImage".equals(mName)) {
                                    scan.hasSetDirty = true;
                                }
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return scan;
    }

    private static ConfirmScan scanConfirmLoop(final ClassReader reader) {
        final ConfirmScan scan = new ConfirmScan();
        reader.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (CONFIRM_ORIG.equals(name) && CONFIRM_DESC.equals(descriptor)) {
                            scan.alreadyPatched = true;
                            return null;
                        }
                        if (!CONFIRM_METHOD.equals(name) || !CONFIRM_DESC.equals(descriptor)) {
                            return null;
                        }
                        scan.hasMethod = true;
                        return new MethodVisitor(ASM9) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                if (opcode == INVOKESPECIAL
                                        && CONFIRM_TASK_CTOR_OWNER.equals(owner)
                                        && "<init>".equals(mName)) {
                                    scan.taskCtorCalls++;
                                } else if (opcode == INVOKEVIRTUAL
                                        && PROGRESS_INTERNAL.equals(owner)
                                        && "a".equals(mName)
                                        && PROGRESS_SUB_DESC.equals(mDesc)) {
                                    scan.progressSubCalls++;
                                }
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return scan;
    }

    private static final class TextureAtlasScan {
        boolean alreadyPatched;
        boolean hasSetupMethod;
        boolean hasAtlasVersionField;
        boolean hasImageResourceCtor;
        boolean hasSetCached;
        boolean hasSetDirty;

        boolean matches() {
            return !alreadyPatched
                    && hasSetupMethod
                    && hasAtlasVersionField
                    && hasImageResourceCtor
                    && hasSetCached
                    && hasSetDirty;
        }
    }

    private static final class ConfirmScan {
        boolean alreadyPatched;
        boolean hasMethod;
        int taskCtorCalls;
        int progressSubCalls;

        boolean matches() {
            return !alreadyPatched && hasMethod && taskCtorCalls == 1 && progressSubCalls == 1;
        }
    }

    private static final class TextureUpdateScan extends ClassVisitor {
        private static final String PAGE_LAMBDA = "com/live2d/cubism/doc/model/texture/H";
        private static final String LEGACY_PARALLELIZER =
                "dev/turboism/adapter/cubism/optimization/export/AtlasRebuildParallelizer";
        private boolean hasAtlasListField;
        private boolean hasRunMethod;
        private boolean callsPageLambda;
        private boolean alreadyPatched;

        TextureUpdateScan() {
            super(ASM9);
        }

        boolean matches() {
            return hasAtlasListField && hasRunMethod && callsPageLambda;
        }

        @Override
        public FieldVisitor visitField(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final Object value) {
            if ("a".equals(name) && "Ljava/util/List;".equals(descriptor)) {
                hasAtlasListField = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions) {
            if (!"a".equals(name) || !CONFIRM_DESC.equals(descriptor)) {
                return null;
            }
            hasRunMethod = true;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitTypeInsn(final int opcode, final String type) {
                    if (opcode == NEW && PAGE_LAMBDA.equals(type)) {
                        callsPageLambda = true;
                    }
                }

                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean isInterface) {
                    if (opcode == INVOKESTATIC
                            && ((COORDINATOR_INTERNAL.equals(owner) && "runParallelTextureUpdates".equals(mName))
                                    || (LEGACY_PARALLELIZER.equals(owner) && "runParallelUpdate".equals(mName)))) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }
}
