package dev.turboism.exportsettings;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/**
 * Exact-owner transformer for the Cubism 5.3.02 embedded-model Export Settings dialog.
 *
 * <p>Transforms exactly {@code com/live2d/cubism/doc/model/exporter/e} at two bounded
 * sites:</p>
 *
 * <ul>
 *   <li><b>content attach</b> — at the single {@code RETURN} of the private content
 *       builder {@code b()V}, injects one loader-neutral bridge call that materializes
 *       the contributed checkbox panel into the {@code JDialog} content pane;</li>
 *   <li><b>post-modal decision gate</b> — in {@code a(...)Z}, injects a cancel-cleanup
 *       call before the {@code iconst_0; ireturn} cancel pair and a decision gate at the
 *       start of the confirm path. The gate returns {@code false} (preventing native
 *       export continuation) on any rejection and otherwise falls through to the native
 *       confirm write-back byte-for-byte.</li>
 * </ul>
 *
 * <p>The injected instructions reference only bootstrap/JDK-visible APIs
 * ({@code java/lang/System}, {@code java/util/Properties}, {@code java/util/function/*},
 * {@code java/lang/Boolean}, {@code javax/swing/JDialog}, {@code java/awt/Container})
 * plus the exact verified host members ({@code e.c} and {@code y.e}); no
 * SDK/runtime/ASM type ever appears in transformed bytecode.</p>
 *
 * <p>Cardinality is exact and fail-closed: the content builder must contain exactly one
 * {@code RETURN} and the gate method exactly two {@code IRETURN}s preceded by
 * {@code ICONST_0}/{@code ICONST_1}. Any other shape, or a class that already carries
 * the bridge marker (idempotent retransformation), leaves the class bytes unchanged.</p>
 */
public final class ExportSettingsNativeMethodTransformer implements ClassFileTransformer {

    private final String ownerInternalName;
    private final String attachMethodName;
    private final String attachMethodDescriptor;
    private final String gateMethodName;
    private final String gateMethodDescriptor;
    private final String windowFieldName;
    private final String windowFieldDescriptor;
    private final String jdialogMethodOwner;
    private final String jdialogMethodName;
    private final String jdialogMethodDescriptor;
    private final ClassLoader expectedClassLoader;

    public ExportSettingsNativeMethodTransformer(
        final String ownerInternalName,
        final String attachMethodName,
        final String attachMethodDescriptor,
        final String gateMethodName,
        final String gateMethodDescriptor,
        final String windowFieldName,
        final String windowFieldDescriptor,
        final String jdialogMethodOwner,
        final String jdialogMethodName,
        final String jdialogMethodDescriptor,
        final ClassLoader expectedClassLoader
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.attachMethodName = requireText(attachMethodName, "attachMethodName");
        this.attachMethodDescriptor = requireText(attachMethodDescriptor, "attachMethodDescriptor");
        this.gateMethodName = requireText(gateMethodName, "gateMethodName");
        this.gateMethodDescriptor = requireText(gateMethodDescriptor, "gateMethodDescriptor");
        this.windowFieldName = requireText(windowFieldName, "windowFieldName");
        this.windowFieldDescriptor = requireText(windowFieldDescriptor, "windowFieldDescriptor");
        this.jdialogMethodOwner = requireText(jdialogMethodOwner, "jdialogMethodOwner");
        this.jdialogMethodName = requireText(jdialogMethodName, "jdialogMethodName");
        this.jdialogMethodDescriptor = requireText(jdialogMethodDescriptor, "jdialogMethodDescriptor");
        this.expectedClassLoader = Objects.requireNonNull(expectedClassLoader, "expectedClassLoader");
    }

    @Override
    public byte[] transform(
        final Module module,
        final ClassLoader loader,
        final String className,
        final Class<?> classBeingRedefined,
        final ProtectionDomain protectionDomain,
        final byte[] classfileBuffer
    ) {
        if (!ownerInternalName.equals(className)
            || loader != expectedClassLoader
            || classfileBuffer == null) {
            return null;
        }
        final boolean[] markerFound = {false};
        final boolean[] attachShape = {false};
        final boolean[] gateShape = {false};
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(
            reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
        );
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                final MethodVisitor delegate = super.visitMethod(
                    access, name, descriptor, signature, exceptions
                );
                if (attachMethodName.equals(name) && attachMethodDescriptor.equals(descriptor)) {
                    return new AttachMethodVisitor(delegate, markerFound, attachShape);
                }
                if (gateMethodName.equals(name) && gateMethodDescriptor.equals(descriptor)) {
                    return new GateMethodVisitor(delegate, markerFound, gateShape);
                }
                return delegate;
            }
        }, ClassReader.EXPAND_FRAMES);
        if (markerFound[0] || !attachShape[0] || !gateShape[0]) {
            return null;
        }
        return writer.toByteArray();
    }

    // ------------------------------------------------------------------
    // Attach site: end of b()V
    // ------------------------------------------------------------------

    private final class AttachMethodVisitor extends MethodVisitor {
        private final boolean[] markerFound;
        private final boolean[] shape;
        private boolean sawMarker;
        private boolean emitting;
        private int returns;

        private AttachMethodVisitor(
            final MethodVisitor delegate,
            final boolean[] markerFound,
            final boolean[] shape
        ) {
            super(Opcodes.ASM9, delegate);
            this.markerFound = markerFound;
            this.shape = shape;
        }

        @Override
        public void visitLdcInsn(final Object value) {
            // The marker is the bridge property key; only an LDC present in the original
            // class bytes (a previous transformation) counts. The emitting guard keeps the
            // transformer's own emission from poisoning the marker detection of this pass.
            if (!emitting && NativeExportSettingsDialogBridge.ATTACH_KEY.equals(value)) {
                sawMarker = true;
                markerFound[0] = true;
            }
            super.visitLdcInsn(value);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (sawMarker) {
                super.visitInsn(opcode);
                return;
            }
            if (opcode == Opcodes.RETURN) {
                returns++;
                if (returns == 1) {
                    emitAttach();
                }
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            if (!sawMarker && returns == 1) {
                shape[0] = true;
            }
            super.visitMaxs(maxStack, maxLocals);
        }

        private void emitAttach() {
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label noBridge = new Label();
            final Label handler = new Label();
            final Label complete = new Label();
            emitting = true;
            try {
                super.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
                super.visitLabel(tryStart);
                emitPropertyLookup(this, NativeExportSettingsDialogBridge.ATTACH_KEY);
            } finally {
                emitting = false;
            }
            super.visitJumpInsn(Opcodes.IFNULL, noBridge);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction");
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(
                Opcodes.GETFIELD,
                ownerInternalName,
                windowFieldName,
                windowFieldDescriptor
            );
            super.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                jdialogMethodOwner,
                jdialogMethodName,
                jdialogMethodDescriptor,
                false
            );
            super.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "javax/swing/JDialog",
                "getContentPane",
                "()Ljava/awt/Container;",
                false
            );
            super.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/function/BiFunction",
                "apply",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                true
            );
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, tryEnd);
            super.visitLabel(noBridge);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(tryEnd);
            super.visitJumpInsn(Opcodes.GOTO, complete);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(complete);
        }
    }

    // ------------------------------------------------------------------
    // Gate site: cancel cleanup + confirm decision in a(...)Z
    // ------------------------------------------------------------------

    private final class GateMethodVisitor extends MethodVisitor {
        private final boolean[] markerFound;
        private final boolean[] shape;
        private boolean sawMarker;
        private boolean emitting;
        private boolean sawFirstIreturn;
        private boolean gatePending;
        private boolean gateEmitted;
        private boolean invalid;
        private Integer buffered;
        private int ireturnCount;

        private GateMethodVisitor(
            final MethodVisitor delegate,
            final boolean[] markerFound,
            final boolean[] shape
        ) {
            super(Opcodes.ASM9, delegate);
            this.markerFound = markerFound;
            this.shape = shape;
        }

        @Override
        public void visitLdcInsn(final Object value) {
            // Same marker discipline as the attach site: only an LDC present in the
            // original class bytes counts; the transformer's own emission is guarded.
            if (!emitting
                && (NativeExportSettingsDialogBridge.CANCEL_KEY.equals(value)
                    || NativeExportSettingsDialogBridge.DECIDE_KEY.equals(value))) {
                sawMarker = true;
                markerFound[0] = true;
                super.visitLdcInsn(value);
                return;
            }
            beforeOtherInstruction();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (sawMarker) {
                super.visitInsn(opcode);
                return;
            }
            if (emitting) {
                // The transformer's own emission must never interact with the
                // buffered-constant/gate machinery: the original cancel constant is
                // still buffered while the cleanup is emitted and re-enters this
                // visitor through the property lookup.
                super.visitInsn(opcode);
                return;
            }
            if (gatePending) {
                gatePending = false;
                gateEmitted = true;
                emitGate();
            }
            if (opcode == Opcodes.IRETURN) {
                ireturnCount++;
                if (!sawFirstIreturn && ireturnCount == 1 && bufferedIs(Opcodes.ICONST_0)) {
                    // The first return must be the cancel pair: notify the bridge, then
                    // reproduce iconst_0; ireturn.
                    emitCancelCleanup();
                    super.visitInsn(Opcodes.ICONST_0);
                    super.visitInsn(Opcodes.IRETURN);
                    buffered = null;
                    sawFirstIreturn = true;
                    gatePending = true;
                    return;
                }
                if (sawFirstIreturn && ireturnCount == 2 && bufferedIs(Opcodes.ICONST_1)) {
                    // The second and final return must be the confirm pair. Reaching it
                    // implies the decision gate was emitted before this path continued.
                    super.visitInsn(Opcodes.ICONST_1);
                    super.visitInsn(Opcodes.IRETURN);
                    buffered = null;
                    return;
                }
                // Unexpected shape: reproduce the original instruction sequence exactly
                // (including any buffered constant) and fail the whole transform.
                invalid = true;
                shape[0] = false;
                if (buffered != null) {
                    super.visitInsn(buffered);
                    buffered = null;
                }
                super.visitInsn(opcode);
                return;
            }
            if (buffered != null) {
                super.visitInsn(buffered);
                buffered = null;
            }
            if (!sawFirstIreturn && opcode == Opcodes.ICONST_0) {
                buffered = opcode;
                return;
            }
            if (sawFirstIreturn && ireturnCount == 1 && opcode == Opcodes.ICONST_1) {
                buffered = opcode;
                return;
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            beforeOtherInstruction();
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitFieldInsn(
            final int opcode, final String owner, final String name, final String descriptor
        ) {
            beforeOtherInstruction();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(
            final int opcode,
            final String owner,
            final String name,
            final String descriptor,
            final boolean isInterface
        ) {
            beforeOtherInstruction();
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            beforeOtherInstruction();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            beforeOtherInstruction();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            beforeOtherInstruction();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitIincInsn(final int varIndex, final int increment) {
            beforeOtherInstruction();
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitInvokeDynamicInsn(
            final String name,
            final String descriptor,
            final org.objectweb.asm.Handle bootstrapMethodHandle,
            final Object... bootstrapMethodArguments
        ) {
            beforeOtherInstruction();
            super.visitInvokeDynamicInsn(
                name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments
            );
        }

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
            beforeOtherInstruction();
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitLabel(final Label label) {
            if (sawMarker) {
                super.visitLabel(label);
                return;
            }
            if (buffered != null) {
                super.visitInsn(buffered);
                buffered = null;
            }
            super.visitLabel(label);
        }

        @Override
        public void visitFrame(
            final int type,
            final int numLocal,
            final Object[] local,
            final int numStack,
            final Object[] stack
        ) {
            if (sawMarker) {
                super.visitFrame(type, numLocal, local, numStack, stack);
                return;
            }
            if (buffered != null) {
                super.visitInsn(buffered);
                buffered = null;
            }
            super.visitFrame(type, numLocal, local, numStack, stack);
        }

        @Override
        public void visitLineNumber(final int line, final Label start) {
            if (sawMarker) {
                super.visitLineNumber(line, start);
                return;
            }
            if (buffered != null) {
                super.visitInsn(buffered);
                buffered = null;
            }
            super.visitLineNumber(line, start);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            if (!sawMarker
                && !invalid
                && sawFirstIreturn
                && ireturnCount == 2
                && gateEmitted) {
                shape[0] = true;
            }
            super.visitMaxs(maxStack, maxLocals);
        }

        private boolean bufferedIs(final int opcode) {
            return buffered != null && buffered == opcode;
        }

        private void beforeOtherInstruction() {
            if (sawMarker) {
                return;
            }
            if (emitting) {
                // Same re-entrancy guard as {@link #visitInsn}: emission re-enters via
                // {@code emitPropertyLookup}, and the buffered original constant must
                // stay buffered until the cancel pair is reproduced.
                return;
            }
            if (gatePending) {
                gatePending = false;
                gateEmitted = true;
                emitGate();
            }
            if (buffered != null) {
                super.visitInsn(buffered);
                buffered = null;
            }
        }

        /**
         * Injected before the first {@code iconst_0; ireturn} cancel pair: notifies the
         * bridge so dialog-scoped state is removed without invoking any plugin callback.
         */
        private void emitCancelCleanup() {
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label noBridge = new Label();
            final Label handler = new Label();
            final Label complete = new Label();
            emitting = true;
            try {
                super.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
                super.visitLabel(tryStart);
                emitPropertyLookup(this, NativeExportSettingsDialogBridge.CANCEL_KEY);
                super.visitJumpInsn(Opcodes.IFNULL, noBridge);
                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/function/Consumer",
                    "accept",
                    "(Ljava/lang/Object;)V",
                    true
                );
                super.visitJumpInsn(Opcodes.GOTO, tryEnd);
                super.visitLabel(noBridge);
                super.visitInsn(Opcodes.POP);
                super.visitLabel(tryEnd);
                super.visitJumpInsn(Opcodes.GOTO, complete);
                super.visitLabel(handler);
                super.visitInsn(Opcodes.POP);
                super.visitLabel(complete);
            } finally {
                emitting = false;
            }
        }

        /**
         * Injected at the start of the confirm path: asks the bridge whether native
         * export may continue. Any rejection (including a throwing bridge) returns
         * {@code false} from the modal method, preventing export continuation.
         */
        private void emitGate() {
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label reject = new Label();
            final Label noBridge = new Label();
            final Label handler = new Label();
            final Label afterHandler = new Label();
            emitting = true;
            try {
                super.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
                super.visitLabel(tryStart);
                emitPropertyLookup(this, NativeExportSettingsDialogBridge.DECIDE_KEY);
                super.visitJumpInsn(Opcodes.IFNULL, noBridge);
                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function");
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/function/Function",
                    "apply",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    true
                );
                super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false
                );
                // Every path into the native continuation below carries an empty stack:
                // false decision and throwing/null bridge all return false from the modal
                // method, and a true decision falls through with the stack untouched.
                super.visitJumpInsn(Opcodes.IFEQ, reject);
                super.visitJumpInsn(Opcodes.GOTO, afterHandler);
                super.visitLabel(reject);
                super.visitInsn(Opcodes.ICONST_0);
                super.visitInsn(Opcodes.IRETURN);
                super.visitLabel(noBridge);
                super.visitInsn(Opcodes.POP);
                super.visitJumpInsn(Opcodes.GOTO, reject);
                super.visitLabel(tryEnd);
                super.visitJumpInsn(Opcodes.GOTO, afterHandler);
                super.visitLabel(handler);
                super.visitInsn(Opcodes.POP);
                super.visitInsn(Opcodes.ICONST_0);
                super.visitInsn(Opcodes.IRETURN);
                super.visitLabel(afterHandler);
            } finally {
                emitting = false;
            }
        }
    }

    /** Loader-neutral property lookup leaving {@code [value, value]} on the stack. */
    private static void emitPropertyLookup(final MethodVisitor mv, final String key) {
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "getProperties",
            "()Ljava/util/Properties;",
            false
        );
        mv.visitLdcInsn(key);
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/util/Properties",
            "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            false
        );
        mv.visitInsn(Opcodes.DUP);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
