package dev.turboism.adapter.cubism.optimization.psd;

import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.RETURN;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Patches {@code layer.k$a}, {@code layer.{n,o}}, {@code CLayeredImage}, and {@code CLayer} for
 * lock-free parallel PSD layer decoding and icon precomputation.
 */
final class PsdLayerDecodePatcher {

    static final String LAYER_INFO_READER = "com/live2d/graphics/psd/layer/k$a";
    static final String LAYER_MODERN = "com/live2d/graphics/psd/layer/o";
    static final String LAYER_LEGACY = "com/live2d/graphics/psd/layer/n";
    static final String LAYERED_IMAGE = "com/live2d/cubism/doc/resources/CLayeredImage";
    static final String LAYER_ENTRY = "com/live2d/cubism/doc/resources/CLayer";

    private static final String LAYER_BRIDGE = "dev/turboism/adapter/cubism/optimization/psd/PsdLayerDecodeBridge";
    private static final String IMAGE_OPS = "dev/turboism/adapter/cubism/optimization/psd/PsdImageOps";
    private static final String PSD_READER = "com/live2d/graphics/psd/b";
    private static final String PSD_DOC = "com/live2d/graphics/psd/CPsdDocument";
    private static final String LDIMAGE = "jp/live2d/type_editor/image/LDImage";
    private static final String EDGE_CLEAN_HOST = "jp/noids/graphics/h";
    private static final String WRITABLE_IMAGE = "com/live2d/graphics/CWritableImage";

    private static final Set<String> TARGETS =
            Set.of(LAYER_INFO_READER, LAYER_MODERN, LAYER_LEGACY, LAYERED_IMAGE, LAYER_ENTRY);

    private static final String K_A_METHOD = "a";
    private static final String K_A_DESC = "(Lcom/live2d/graphics/psd/b;)Lcom/live2d/graphics/psd/layer/k;";
    private static final String LAYER_RECORDS_LOG = "--- layer records of layer[%d] ---\n";
    private static final String LAYER_ASSEMBLE_METHOD = "b";
    private static final String LAYER_ASSEMBLE_DESC = "(Z)V";
    private static final String LAYERED_IMAGE_CTOR_DESC =
            "(Lcom/live2d/graphics/psd/CPsdDocument;Ljava/io/File;Ljava/lang/String;)V";
    private static final String SETUP_ICON_METHOD = "setupIcon";
    private static final String SETUP_ICON_DESC = "()V";
    private static final String CREATE_FIT_METHOD = "createFit";
    private static final String CREATE_FIT_DESC = "(IILcom/live2d/graphics/n;)Lcom/live2d/graphics/CWritableImage;";

    private PsdLayerDecodePatcher() {}

    static boolean isSupportedTarget(final String internalName) {
        return internalName != null && TARGETS.contains(internalName);
    }

    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (LAYER_INFO_READER.equals(internalName)) {
            return patchLayerInfoReader(loader, classfileBuffer);
        }
        if (LAYER_MODERN.equals(internalName) || LAYER_LEGACY.equals(internalName)) {
            return patchLayerAssembly(internalName, loader, classfileBuffer);
        }
        if (LAYERED_IMAGE.equals(internalName)) {
            return patchLayeredImageCtor(loader, classfileBuffer);
        }
        if (LAYER_ENTRY.equals(internalName)) {
            return patchLayerSetupIcon(loader, classfileBuffer);
        }
        return null;
    }

    private static byte[] patchLayerInfoReader(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = HostClassWriters.newClassReader(classfileBuffer);
        if (hasCallToOwner(reader, LAYER_BRIDGE)) {
            return null;
        }
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        final ClassVisitor visitor =
                new ClassVisitor(ASM9, HostClassWriters.preservingClassVersion(classfileBuffer, writer)) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!K_A_METHOD.equals(name) || !K_A_DESC.equals(desc)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            private boolean sawLayerRecordsLog;
                            private boolean sawPreLoopIndent;
                            private int loopIndexVar = -1;
                            private boolean loop2Patched;

                            @Override
                            public void visitLdcInsn(final Object value) {
                                if (LAYER_RECORDS_LOG.equals(value)) {
                                    sawLayerRecordsLog = true;
                                }
                                super.visitLdcInsn(value);
                            }

                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                                if (sawLayerRecordsLog
                                        && !sawPreLoopIndent
                                        && opcode == INVOKEVIRTUAL
                                        && PSD_READER.equals(owner)
                                        && "e".equals(mName)
                                        && "(I)V".equals(mDesc)) {
                                    sawPreLoopIndent = true;
                                }
                            }

                            @Override
                            public void visitVarInsn(final int opcode, final int varIndex) {
                                if (sawPreLoopIndent && !loop2Patched && loopIndexVar < 0 && opcode == ISTORE) {
                                    loopIndexVar = varIndex;
                                }
                                super.visitVarInsn(opcode, varIndex);
                            }

                            @Override
                            public void visitJumpInsn(final int opcode, final Label label) {
                                super.visitJumpInsn(opcode, label);
                                if (sawPreLoopIndent
                                        && !loop2Patched
                                        && loopIndexVar >= 0
                                        && opcode == org.objectweb.asm.Opcodes.IF_ICMPGT) {
                                    loop2Patched = true;
                                    patched[0] = true;
                                    final Label sequentialLoop = new Label();
                                    super.visitVarInsn(ILOAD, loopIndexVar);
                                    super.visitJumpInsn(IFNE, sequentialLoop);
                                    super.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2); // k
                                    super.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1); // b
                                    super.visitMethodInsn(
                                            INVOKESTATIC,
                                            LAYER_BRIDGE,
                                            "tryDecodeAllLayersParallel",
                                            "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                                            false);
                                    super.visitJumpInsn(IFNE, label);
                                    super.visitLabel(sequentialLoop);
                                }
                            }
                        };
                    }
                };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static byte[] patchLayerAssembly(
            final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = HostClassWriters.newClassReader(classfileBuffer);
        if (hasCallToOwner(reader, LAYER_BRIDGE) || hasCallToOwner(reader, IMAGE_OPS)) {
            return null;
        }
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        final ClassVisitor visitor =
                new ClassVisitor(ASM9, HostClassWriters.preservingClassVersion(classfileBuffer, writer)) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!LAYER_ASSEMBLE_METHOD.equals(name) || !LAYER_ASSEMBLE_DESC.equals(desc)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            private boolean pendingNewLdImage;
                            private boolean suppressingDupForLdImage;

                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                if (opcode == INVOKESTATIC
                                        && EDGE_CLEAN_HOST.equals(owner)
                                        && "a".equals(mName)
                                        && "(Ljava/awt/image/BufferedImage;IZ)Ljava/awt/image/BufferedImage;"
                                                .equals(mDesc)) {
                                    patched[0] = true;
                                    super.visitMethodInsn(
                                            INVOKESTATIC,
                                            IMAGE_OPS,
                                            "cleanEdgesThreadSafe",
                                            "(Ljava/awt/image/BufferedImage;IZ)Ljava/awt/image/BufferedImage;",
                                            false);
                                    return;
                                }
                                if (suppressingDupForLdImage
                                        && opcode == INVOKESPECIAL
                                        && LDIMAGE.equals(owner)
                                        && "<init>".equals(mName)
                                        && "(Ljava/awt/image/BufferedImage;)V".equals(mDesc)) {
                                    suppressingDupForLdImage = false;
                                    patched[0] = true;
                                    super.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(
                                            INVOKESTATIC,
                                            LAYER_BRIDGE,
                                            "createOrCaptureWorkerLdImage",
                                            "(Ljava/awt/image/BufferedImage;Ljava/lang/Object;)Ljava/lang/Object;",
                                            false);
                                    super.visitTypeInsn(CHECKCAST, LDIMAGE);
                                    return;
                                }
                                flushPendingNewLdImage();
                                super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                            }

                            @Override
                            public void visitTypeInsn(final int opcode, final String type) {
                                flushPendingNewLdImage();
                                if (!suppressingDupForLdImage && opcode == NEW && LDIMAGE.equals(type)) {
                                    pendingNewLdImage = true;
                                    return;
                                }
                                super.visitTypeInsn(opcode, type);
                            }

                            @Override
                            public void visitInsn(final int opcode) {
                                if (pendingNewLdImage && opcode == DUP) {
                                    pendingNewLdImage = false;
                                    suppressingDupForLdImage = true;
                                    return;
                                }
                                flushPendingNewLdImage();
                                super.visitInsn(opcode);
                            }

                            @Override
                            public void visitVarInsn(final int opcode, final int varIndex) {
                                flushPendingNewLdImage();
                                super.visitVarInsn(opcode, varIndex);
                            }

                            @Override
                            public void visitFieldInsn(
                                    final int opcode, final String owner, final String fName, final String fDesc) {
                                flushPendingNewLdImage();
                                super.visitFieldInsn(opcode, owner, fName, fDesc);
                            }

                            @Override
                            public void visitLabel(final Label label) {
                                flushPendingNewLdImage();
                                super.visitLabel(label);
                            }

                            @Override
                            public void visitJumpInsn(final int opcode, final Label label) {
                                flushPendingNewLdImage();
                                super.visitJumpInsn(opcode, label);
                            }

                            private void flushPendingNewLdImage() {
                                if (pendingNewLdImage) {
                                    pendingNewLdImage = false;
                                    super.visitTypeInsn(NEW, LDIMAGE);
                                }
                            }
                        };
                    }
                };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static byte[] patchLayeredImageCtor(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = HostClassWriters.newClassReader(classfileBuffer);
        if (hasCallToOwner(reader, LAYER_BRIDGE)) {
            return null;
        }
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        final ClassVisitor visitor =
                new ClassVisitor(ASM9, HostClassWriters.preservingClassVersion(classfileBuffer, writer)) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!"<init>".equals(name) || !LAYERED_IMAGE_CTOR_DESC.equals(desc)) {
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
                                super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                                if (opcode == INVOKEVIRTUAL
                                        && PSD_DOC.equals(owner)
                                        && "h".equals(mName)
                                        && mDesc != null
                                        && mDesc.startsWith("()[L")) {
                                    patched[0] = true;
                                    super.visitInsn(DUP);
                                    super.visitMethodInsn(
                                            INVOKESTATIC,
                                            LAYER_BRIDGE,
                                            "prepareLayerIcons",
                                            "([Ljava/lang/Object;)V",
                                            false);
                                }
                            }

                            @Override
                            public void visitInsn(final int opcode) {
                                if (opcode == RETURN || opcode == ATHROW) {
                                    super.visitMethodInsn(
                                            INVOKESTATIC, LAYER_BRIDGE, "clearPreparedLayerIcons", "()V", false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static byte[] patchLayerSetupIcon(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = HostClassWriters.newClassReader(classfileBuffer);
        if (hasCallToOwner(reader, LAYER_BRIDGE)) {
            return null;
        }
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        final ClassVisitor visitor =
                new ClassVisitor(ASM9, HostClassWriters.preservingClassVersion(classfileBuffer, writer)) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!SETUP_ICON_METHOD.equals(name) || !SETUP_ICON_DESC.equals(desc)) {
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
                                if (opcode == INVOKEVIRTUAL
                                        && WRITABLE_IMAGE.equals(owner)
                                        && CREATE_FIT_METHOD.equals(mName)
                                        && CREATE_FIT_DESC.equals(mDesc)) {
                                    patched[0] = true;
                                    super.visitMethodInsn(
                                            INVOKESTATIC,
                                            LAYER_BRIDGE,
                                            "consumeOrCreateFit",
                                            "(Ljava/lang/Object;IILjava/lang/Object;)Ljava/lang/Object;",
                                            false);
                                    super.visitTypeInsn(CHECKCAST, WRITABLE_IMAGE);
                                    return;
                                }
                                super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                            }
                        };
                    }
                };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    static boolean hasCallToOwner(final ClassReader reader, final String expectedOwner) {
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
                        return new MethodVisitor(ASM9) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                if (expectedOwner.equals(owner)) {
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
