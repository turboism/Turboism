package dev.turboism.validation.triprobe;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Structural shape gate for the official {@code TriangleList} bytes. Checks, without executing or
 * defining the class:
 *
 * <ul>
 *   <li>private final field {@code b} with descriptor {@code Ljava/util/LinkedHashSet;}
 *   <li>{@code <init>()V} contains {@code NEW java/util/LinkedHashSet} followed by
 *       {@code PUTFIELD b}
 *   <li>{@code iterator()Ljava/util/Iterator;} contains {@code GETFIELD b} feeding an
 *       {@code INVOKEVIRTUAL java/util/LinkedHashSet.iterator()Ljava/util/Iterator;}
 * </ul>
 */
final class TriangleListShape {
    private TriangleListShape() {}

    static final class Result {
        final boolean accepted;
        final String reason;

        private Result(boolean accepted, String reason) {
            this.accepted = accepted;
            this.reason = reason;
        }

        static Result ok() { return new Result(true, "shape-ok"); }
        static Result reject(String reason) { return new Result(false, reason); }
    }

    static Result check(byte[] classBytes) {
        try {
            ShapeScan scan = new ShapeScan();
            new ClassReader(classBytes).accept(scan, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!scan.fieldOk) return Result.reject("field-b-not-linkedhashset");
            if (!scan.ctorOk) return Result.reject("ctor-missing-linkedhashset-alloc");
            if (!scan.iteratorOk) return Result.reject("iterator-missing-field-dispatch");
            return Result.ok();
        } catch (Throwable t) {
            return Result.reject("shape-scan-error:" + t.getClass().getSimpleName());
        }
    }

    private static final class ShapeScan extends ClassVisitor {
        boolean fieldOk, ctorOk, iteratorOk;

        ShapeScan() { super(Opcodes.ASM9); }

        @Override public FieldVisitor visitField(int access, String name, String descriptor,
                String signature, Object value) {
            if ("b".equals(name) && "Ljava/util/LinkedHashSet;".equals(descriptor)
                    && (access & Opcodes.ACC_PRIVATE) != 0 && (access & Opcodes.ACC_FINAL) != 0) {
                fieldOk = true;
            }
            return null;
        }

        @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            if ("<init>".equals(name) && "()V".equals(descriptor)) {
                return new MethodVisitor(Opcodes.ASM9) {
                    boolean sawAlloc;
                    @Override public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.NEW && "java/util/LinkedHashSet".equals(type)) {
                            sawAlloc = true;
                        }
                    }
                    @Override public void visitFieldInsn(int opcode, String owner, String fname,
                            String fdesc) {
                        if (opcode == Opcodes.PUTFIELD && "b".equals(fname)
                                && "Ljava/util/LinkedHashSet;".equals(fdesc) && sawAlloc) {
                            ctorOk = true;
                        }
                    }
                };
            }
            if ("iterator".equals(name) && "()Ljava/util/Iterator;".equals(descriptor)) {
                return new MethodVisitor(Opcodes.ASM9) {
                    boolean sawGet;
                    @Override public void visitFieldInsn(int opcode, String owner, String fname,
                            String fdesc) {
                        if (opcode == Opcodes.GETFIELD && "b".equals(fname)
                                && "Ljava/util/LinkedHashSet;".equals(fdesc)) {
                            sawGet = true;
                        }
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String mname,
                            String mdesc, boolean itf) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && "java/util/LinkedHashSet".equals(owner)
                                && "iterator".equals(mname)
                                && "()Ljava/util/Iterator;".equals(mdesc) && sawGet) {
                            iteratorOk = true;
                        }
                    }
                };
            }
            return null;
        }
    }
}
