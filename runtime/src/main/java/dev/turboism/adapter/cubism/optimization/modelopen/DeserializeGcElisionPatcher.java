package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Removes unconditional {@link System#gc()} pauses from Cubism's {@code UUSerialize}
 * deserialization entry points.
 */
final class DeserializeGcElisionPatcher {

    static final String TARGET_INTERNAL_NAME = "com/live2d/serialize/UUSerialize";

    private static final String DESERIALIZE_FROM_FILE = "deserializeFromFile";
    private static final String DESERIALIZE_FROM_BYTES = "deserializeFromBytes";
    private static final String SYSTEM_OWNER = "java/lang/System";
    private static final String GC_METHOD_NAME = "gc";
    private static final String VOID_NO_ARG_DESC = "()V";

    private DeserializeGcElisionPatcher() {}

    /**
     * Patches {@code UUSerialize} to elide explicit {@code System.gc()} calls in
     * {@code deserializeFromFile} and {@code deserializeFromBytes}.
     *
     * @param internalName JVM internal name of the class under transformation
     * @param loader class loader defining the target class
     * @param classfileBuffer original class bytecode
     * @return patched bytecode, or {@code null} when the class does not match or is already patched
     */
    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (!TARGET_INTERNAL_NAME.equals(internalName) || classfileBuffer == null) {
            return null;
        }
        final ClassReader reader = new ClassReader(classfileBuffer);
        final GcCallScan scan = new GcCallScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.gcCallCount == 0) {
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
                        if (!isTargetMethod(name)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String methodName,
                                    final String methodDesc,
                                    final boolean isInterface) {
                                if (isSystemGcCall(opcode, owner, methodName, methodDesc)) {
                                    patched[0] = true;
                                    return;
                                }
                                super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static boolean isTargetMethod(final String methodName) {
        return DESERIALIZE_FROM_FILE.equals(methodName) || DESERIALIZE_FROM_BYTES.equals(methodName);
    }

    private static boolean isSystemGcCall(
            final int opcode, final String owner, final String methodName, final String methodDesc) {
        return opcode == INVOKESTATIC
                && SYSTEM_OWNER.equals(owner)
                && GC_METHOD_NAME.equals(methodName)
                && VOID_NO_ARG_DESC.equals(methodDesc);
    }

    private static final class GcCallScan extends ClassVisitor {

        private int gcCallCount;

        GcCallScan() {
            super(ASM9);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String desc,
                final String signature,
                final String[] exceptions) {
            if (!isTargetMethod(name)) {
                return null;
            }
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String methodName,
                        final String methodDesc,
                        final boolean isInterface) {
                    if (isSystemGcCall(opcode, owner, methodName, methodDesc)) {
                        gcCallCount++;
                    }
                }
            };
        }
    }
}
