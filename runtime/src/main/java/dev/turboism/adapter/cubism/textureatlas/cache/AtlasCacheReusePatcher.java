package dev.turboism.adapter.cubism.textureatlas.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Rewrites the body of {@code CTextureAtlas.updateTexture(boolean, boolean,
 * com/live2d/util/a/a)} so a redundant page rebuild is skipped when the draw inputs are
 * unchanged.
 *
 * <p>The reviewed host body is exactly:</p>
 * <pre>
 *   if (first) setupCacheImage$cubism(second, progress);
 *   cachedImageManager.a(cachedAtlasImage);
 * </pre>
 *
 * <p>The patched body keeps that shape and inserts the signature guard around the rebuild:</p>
 * <pre>
 *   if (first) {
 *     if (!AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, this, second)) {
 *       setupCacheImage$cubism(second, progress);
 *       AtlasCacheReuseDelegate.rebuilt(CTextureAtlas.class, this, second);
 *     }
 *   }
 *   cachedImageManager.a(cachedAtlasImage);
 * </pre>
 *
 * <p>A skipped rebuild leaves {@code cachedAtlasImage}, {@code isDirty} and the cache-manager
 * registration exactly as the original post-build state; {@code atlasVersion} is not
 * incremented, which is safe because nothing reads it.</p>
 *
 * <p>Fail-closed in two layers: the method must match the reviewed anchor shape before any
 * rewrite, and the delegate declines reuse for any input it cannot fully read.</p>
 */
public final class AtlasCacheReusePatcher {

    static final String METHOD_NAME = "updateTexture";
    static final String METHOD_DESC = "(ZZLcom/live2d/util/a/a;)V";
    static final String OWNER =
        "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas";
    static final String SETUP_NAME = "setupCacheImage$cubism";
    static final String SETUP_DESC = "(ZLcom/live2d/util/a/a;)V";
    static final String MANAGER =
        "com/live2d/graphics/cachedImage/CCachedImageManager";
    static final String DELEGATE =
        "dev/turboism/bootstrap/atlascache/AtlasCacheReuseDelegate";

    private AtlasCacheReusePatcher() {
    }

    /** Thrown when the observed method shape differs from the reviewed structure. */
    public static final class NotApplicable extends Exception {
        NotApplicable(final String detail) {
            super(detail);
        }
    }

    /** Anchors observed in the target method, for diagnostics. */
    public record Anchors(Map<String, Integer> counts) {
    }

    /**
     * Returns the patched class bytes, or throws {@link NotApplicable} when the target
     * method does not match the reviewed shape.
     */
    public static byte[] patch(final byte[] original) throws NotApplicable {
        final Map<String, Integer> anchors = scan(original);
        if (!shapeOk(anchors)) {
            throw new NotApplicable("anchor mismatch: " + anchors);
        }
        return rewrite(original);
    }

    /** Runs only the shape gate; the counts are returned for diagnostics. */
    public static Anchors inspect(final byte[] original) {
        return new Anchors(scan(original));
    }

    private static Map<String, Integer> scan(final byte[] original) {
        final Map<String, Integer> anchors = new LinkedHashMap<>();
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(
                    final int access, final String name,
                    final String descriptor, final String signature,
                    final Object value) {
                // The delegate reads these exact fields reflectively; each must exist on
                // the reviewed class for the rewritten body to work.
                switch (name) {
                    case "cachedAtlasImage" -> anchors.merge("fieldCache", 1, Integer::sum);
                    case "width" -> anchors.merge("fieldW", 1, Integer::sum);
                    case "height" -> anchors.merge("fieldH", 1, Integer::sum);
                    case "modelImages" -> anchors.merge("fieldEntries", 1, Integer::sum);
                    default -> { }
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                if (!METHOD_NAME.equals(name) || !METHOD_DESC.equals(descriptor)
                    || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return null;
                }
                anchors.merge("method", 1, Integer::sum);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner,
                                                final String nm, final String desc,
                                                final boolean isInterface) {
                        if (owner.equals(OWNER) && nm.equals(SETUP_NAME)
                                && desc.equals(SETUP_DESC)) {
                            anchors.merge("setup", 1, Integer::sum);
                        } else if (owner.equals(MANAGER) && nm.equals("a")) {
                            anchors.merge("managerRegister", 1, Integer::sum);
                        }
                    }

                    @Override
                    public void visitFieldInsn(final int opcode, final String owner,
                                               final String nm, final String desc) {
                        if (opcode == Opcodes.GETFIELD && owner.equals(OWNER)) {
                            if (nm.equals("cachedImageManager")) {
                                anchors.merge("getManager", 1, Integer::sum);
                            } else if (nm.equals("cachedAtlasImage")) {
                                anchors.merge("getCache", 1, Integer::sum);
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return anchors;
    }

    private static boolean shapeOk(final Map<String, Integer> a) {
        return a.getOrDefault("method", 0) == 1
            && a.getOrDefault("setup", 0) == 1
            && a.getOrDefault("managerRegister", 0) == 1
            && a.getOrDefault("getManager", 0) == 1
            && a.getOrDefault("getCache", 0) == 1
            && a.getOrDefault("fieldCache", 0) == 1
            && a.getOrDefault("fieldW", 0) == 1
            && a.getOrDefault("fieldH", 0) == 1
            && a.getOrDefault("fieldEntries", 0) == 1;
    }

    private static byte[] rewrite(final byte[] original) {
        final ClassWriter writer = new ClassWriter(new ClassReader(original),
            ClassWriter.COMPUTE_MAXS);
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate =
                    super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!METHOD_NAME.equals(name) || !METHOD_DESC.equals(descriptor)
                    || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return delegate;
                }
                // Swallow the entire original body; emit the guarded rebuild.
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitCode() {
                        delegate.visitCode();
                        final Label tail = new Label();
                        delegate.visitVarInsn(Opcodes.ILOAD, 1);
                        delegate.visitJumpInsn(Opcodes.IFEQ, tail);
                        delegate.visitLdcInsn(Type.getObjectType(OWNER));
                        delegate.visitVarInsn(Opcodes.ALOAD, 0);
                        delegate.visitVarInsn(Opcodes.ILOAD, 2);
                        delegate.visitMethodInsn(Opcodes.INVOKESTATIC, DELEGATE,
                            "tryReuse", "(Ljava/lang/Class;Ljava/lang/Object;Z)Z", false);
                        delegate.visitJumpInsn(Opcodes.IFNE, tail);
                        delegate.visitVarInsn(Opcodes.ALOAD, 0);
                        delegate.visitVarInsn(Opcodes.ILOAD, 2);
                        delegate.visitVarInsn(Opcodes.ALOAD, 3);
                        delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER,
                            SETUP_NAME, SETUP_DESC, false);
                        delegate.visitLdcInsn(Type.getObjectType(OWNER));
                        delegate.visitVarInsn(Opcodes.ALOAD, 0);
                        delegate.visitVarInsn(Opcodes.ILOAD, 2);
                        delegate.visitMethodInsn(Opcodes.INVOKESTATIC, DELEGATE,
                            "rebuilt", "(Ljava/lang/Class;Ljava/lang/Object;Z)V", false);
                        delegate.visitLabel(tail);
                        // The branch targets need a frame: same locals as entry,
                        // empty stack — the implicit initial frame restated.
                        delegate.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        delegate.visitVarInsn(Opcodes.ALOAD, 0);
                        delegate.visitFieldInsn(Opcodes.GETFIELD, OWNER,
                            "cachedImageManager",
                            "Lcom/live2d/graphics/cachedImage/CCachedImageManager;");
                        delegate.visitVarInsn(Opcodes.ALOAD, 0);
                        delegate.visitFieldInsn(Opcodes.GETFIELD, OWNER,
                            "cachedAtlasImage", "Lcom/live2d/graphics/CImageResource;");
                        delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MANAGER, "a",
                            "(Lcom/live2d/graphics/CImageResource;)V", false);
                        delegate.visitInsn(Opcodes.RETURN);
                    }

                    @Override
                    public void visitMaxs(final int maxStack, final int maxLocals) {
                        delegate.visitMaxs(maxStack, maxLocals);
                    }

                    @Override
                    public void visitEnd() {
                        delegate.visitEnd();
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return writer.toByteArray();
    }
}
