package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.NEW;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Configures Cubism's JDOM {@code SAXBuilder} utility to use {@code org.jdom.UncheckedJDOMFactory}.
 *
 * <p>Xerces SAX already validates XML syntax during parsing; bypassing JDOM's duplicate per-char
 * {@code Verifier.checkCharacterData} scan removes a major deserialization CPU hotspot across model
 * open and export cloning.</p>
 */
final class SaxBuilderFastPatcher {

    static final String UTIL_PACKAGE_PREFIX = "com/live2d/util/";
    private static final int MAX_SIMPLE_NAME_LENGTH = 3;
    private static final String SAX_BUILDER_INTERNAL = "org/jdom/input/SAXBuilder";
    private static final String UNCHECKED_FACTORY_INTERNAL = "org/jdom/UncheckedJDOMFactory";
    private static final String INIT_NAME = "<init>";
    private static final String VOID_DESC = "()V";
    private static final String SET_FACTORY_NAME = "setFactory";
    private static final String SET_FACTORY_DESC = "(Lorg/jdom/JDOMFactory;)V";
    private static final String BUILD_NAME = "build";
    private static final String BUILD_RETURN_SUFFIX = ")Lorg/jdom/Document;";

    private SaxBuilderFastPatcher() {}

    static boolean isCandidateTarget(final String internalName) {
        if (internalName == null || !internalName.startsWith(UTIL_PACKAGE_PREFIX) || internalName.indexOf('$') >= 0) {
            return false;
        }
        final int slash = internalName.indexOf('/', UTIL_PACKAGE_PREFIX.length());
        if (slash >= 0) {
            return false;
        }
        return internalName.length() - UTIL_PACKAGE_PREFIX.length() <= MAX_SIMPLE_NAME_LENGTH;
    }

    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (!isCandidateTarget(internalName)) {
            return null;
        }
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ShapeScan scan = new ShapeScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
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
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                                if (opcode == INVOKESPECIAL
                                        && SAX_BUILDER_INTERNAL.equals(owner)
                                        && INIT_NAME.equals(mName)
                                        && VOID_DESC.equals(mDesc)) {
                                    patched[0] = true;
                                    super.visitInsn(DUP);
                                    super.visitTypeInsn(NEW, UNCHECKED_FACTORY_INTERNAL);
                                    super.visitInsn(DUP);
                                    super.visitMethodInsn(
                                            INVOKESPECIAL, UNCHECKED_FACTORY_INTERNAL, INIT_NAME, VOID_DESC, false);
                                    super.visitMethodInsn(
                                            INVOKEVIRTUAL,
                                            SAX_BUILDER_INTERNAL,
                                            SET_FACTORY_NAME,
                                            SET_FACTORY_DESC,
                                            false);
                                }
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static final class ShapeScan extends ClassVisitor {
        private boolean createsSaxBuilder;
        private boolean callsBuild;
        private boolean alreadyPatched;

        ShapeScan() {
            super(ASM9);
        }

        boolean matches() {
            return createsSaxBuilder && callsBuild;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String signature, final String[] ex) {
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean isInterface) {
                    if (opcode == INVOKESPECIAL
                            && SAX_BUILDER_INTERNAL.equals(owner)
                            && INIT_NAME.equals(mName)
                            && VOID_DESC.equals(mDesc)) {
                        createsSaxBuilder = true;
                    } else if (opcode == INVOKEVIRTUAL
                            && SAX_BUILDER_INTERNAL.equals(owner)
                            && BUILD_NAME.equals(mName)
                            && mDesc.endsWith(BUILD_RETURN_SUFFIX)) {
                        callsBuild = true;
                    } else if (UNCHECKED_FACTORY_INTERNAL.equals(owner)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }
}
