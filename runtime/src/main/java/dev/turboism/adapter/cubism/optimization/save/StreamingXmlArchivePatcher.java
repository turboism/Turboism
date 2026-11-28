package dev.turboism.adapter.cubism.optimization.save;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Patches {@code com/live2d/serialize/UUSerialize.serialize_exe} to pre-encode dirty image
 * resources in parallel and stream XML directly into compressed or raw archive payloads.
 */
public final class StreamingXmlArchivePatcher {

    public static final String TARGET = "com/live2d/serialize/UUSerialize";

    private static final String STREAMING_BRIDGE_OWNER =
            "dev/turboism/adapter/cubism/optimization/save/StreamingXmlArchiveBridge";
    private static final String PRE_ENCODER_OWNER = "dev/turboism/adapter/cubism/optimization/save/ImageSavePreEncoder";

    private static final String SERIALIZE_EXE_NAME = "serialize_exe";
    private static final String SERIALIZE_EXE_DESC = "(Ljava/lang/Object;ZLcom/live2d/serialize/CompressOption;"
            + "Lcom/live2d/graphics/CWritableImage;Ljava/util/HashMap;ZI)"
            + "Lcom/live2d/serialize/XmlWriter;";

    private static final String UTIL_PACKAGE_PREFIX = "com/live2d/util/";
    private static final String XML_STRING_METHOD_NAME = "a";
    private static final String XML_STRING_METHOD_DESC = "(Lorg/jdom/Document;Ljava/lang/String;Z)Ljava/lang/String;";

    private static final String ARCHIVE_WRITER_INTERNAL = "com/live2d/serialize/archiver/ArchiveWriter";
    private static final String ADD_FILE_ENTRY_NAME = "addFileEntry";
    private static final String ADD_FILE_ENTRY_DESC =
            "(Ljava/lang/String;Ljava/lang/String;Lcom/live2d/serialize/CompressOption;"
                    + "Lkotlin/jvm/functions/Function2;)Ljava/lang/String;";

    private static final String PRE_ENCODE_NAME = "preEncodeBeforeSerialize";
    private static final String PRE_ENCODE_DESC = "(Ljava/lang/Object;)V";
    private static final String CAPTURE_DOC_NAME = "captureDocumentForArchive";
    private static final String CAPTURE_DOC_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Z)Ljava/lang/String;";
    private static final String ADD_MAIN_XML_NAME = "addMainXmlEntry";
    private static final String ADD_MAIN_XML_DESC =
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)"
                    + "Ljava/lang/String;";
    private static final String CLEAR_PENDING_NAME = "clearPendingDocument";
    private static final String VOID_DESC = "()V";
    private static final int EXPECTED_XML_TO_STRING_CALLS = 2;
    private static final int TARGET_XML_CALL_ORDINAL = 2;
    private static final int SOURCE_OBJECT_SLOT = 1;

    private StreamingXmlArchivePatcher() {}

    /**
     * Patches {@code UUSerialize.serialize_exe} if present in expected shape and not yet patched.
     *
     * @param internalName JVM internal class name
     * @param loader target host class loader, nullable
     * @param classfileBuffer input class bytes
     * @return patched bytes, or {@code null} when not applicable or already patched
     */
    public static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (!TARGET.equals(internalName) || classfileBuffer == null) {
            return null;
        }
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (SavePerfClassWriters.callsOwner(reader, STREAMING_BRIDGE_OWNER)
                || SavePerfClassWriters.callsOwner(reader, PRE_ENCODER_OWNER)) {
            return null;
        }
        final ShapeScan scan = new ShapeScan();
        reader.accept(scan, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!scan.matches()) {
            return null;
        }

        final ClassWriter writer = SavePerfClassWriters.newFrameWriter(reader, loader);
        final boolean[] modified = new boolean[1];
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
                        if (!SERIALIZE_EXE_NAME.equals(name) || !SERIALIZE_EXE_DESC.equals(desc)) {
                            return mv;
                        }
                        return new SerializeMethodVisitor(mv, modified);
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return modified[0] ? writer.toByteArray() : null;
    }

    private static boolean isXmlToStringCall(
            final int opcode, final String owner, final String name, final String desc) {
        return opcode == INVOKEVIRTUAL
                && owner != null
                && owner.startsWith(UTIL_PACKAGE_PREFIX)
                && XML_STRING_METHOD_NAME.equals(name)
                && XML_STRING_METHOD_DESC.equals(desc);
    }

    private static boolean isAddFileEntryCall(
            final int opcode, final String owner, final String name, final String desc) {
        return opcode == INVOKEVIRTUAL
                && ARCHIVE_WRITER_INTERNAL.equals(owner)
                && ADD_FILE_ENTRY_NAME.equals(name)
                && ADD_FILE_ENTRY_DESC.equals(desc);
    }

    private static final class ShapeScan extends ClassVisitor {

        private int xmlToStringCalls;
        private int addFileEntryCalls;
        private boolean hasSerializeExe;

        private ShapeScan() {
            super(ASM9);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String desc,
                final String signature,
                final String[] exceptions) {
            if (!SERIALIZE_EXE_NAME.equals(name) || !SERIALIZE_EXE_DESC.equals(desc)) {
                return null;
            }
            this.hasSerializeExe = true;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean isInterface) {
                    if (isXmlToStringCall(opcode, owner, mName, mDesc)) {
                        xmlToStringCalls++;
                    } else if (isAddFileEntryCall(opcode, owner, mName, mDesc)) {
                        addFileEntryCalls++;
                    }
                }
            };
        }

        private boolean matches() {
            return this.hasSerializeExe
                    && this.xmlToStringCalls == EXPECTED_XML_TO_STRING_CALLS
                    && this.addFileEntryCalls == 1;
        }
    }

    private static final class SerializeMethodVisitor extends MethodVisitor {

        private final boolean[] modified;
        private int seenXmlToStringCalls;

        private SerializeMethodVisitor(final MethodVisitor delegate, final boolean[] modified) {
            super(ASM9, delegate);
            this.modified = modified;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(ALOAD, SOURCE_OBJECT_SLOT);
            super.visitMethodInsn(INVOKESTATIC, PRE_ENCODER_OWNER, PRE_ENCODE_NAME, PRE_ENCODE_DESC, false);
            this.modified[0] = true;
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String desc, final boolean isInterface) {
            if (isXmlToStringCall(opcode, owner, name, desc)) {
                this.seenXmlToStringCalls++;
                if (this.seenXmlToStringCalls == TARGET_XML_CALL_ORDINAL) {
                    super.visitMethodInsn(
                            INVOKESTATIC, STREAMING_BRIDGE_OWNER, CAPTURE_DOC_NAME, CAPTURE_DOC_DESC, false);
                    this.modified[0] = true;
                    return;
                }
            } else if (isAddFileEntryCall(opcode, owner, name, desc)) {
                super.visitMethodInsn(
                        INVOKESTATIC, STREAMING_BRIDGE_OWNER, ADD_MAIN_XML_NAME, ADD_MAIN_XML_DESC, false);
                this.modified[0] = true;
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, isInterface);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == ARETURN) {
                super.visitMethodInsn(INVOKESTATIC, STREAMING_BRIDGE_OWNER, CLEAR_PENDING_NAME, VOID_DESC, false);
            }
            super.visitInsn(opcode);
        }
    }
}
