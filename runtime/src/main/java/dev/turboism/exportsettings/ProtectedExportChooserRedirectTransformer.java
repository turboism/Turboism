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
 * Exact-owner transformer redirecting the native export chooser result on
 * {@code com/live2d/cubism/doc/model/exporter/b} while an armed protected-export
 * session is active.
 *
 * <p>Two bounded sites, each immediately after the private chooser call so the picked
 * {@code File} on the stack is rewritten before the exporter consumes it:</p>
 *
 * <ul>
 *   <li>{@code b(V, CModelSource, Function2)Z} — after the {@code a(V,String)File} pick
 *       (the moc3 export path);</li>
 *   <li>{@code c(V, CModelSource, Function2)Z} — after the {@code a(String,boolean,V)File}
 *       pick (the save-gated export path), so no armed export can bypass staging.</li>
 * </ul>
 *
 * <p>The injected sequence is pure stack manipulation (no new locals): it reads the
 * {@link NativeExportSettingsDialogBridge#REDIRECT_KEY} callback from {@link System}
 * properties, substitutes its result for the picked file, treats a {@code null} result
 * exactly like a dismissed chooser, and produces {@code null} on any callback failure so
 * a broken redirect can never write to the user's real destination. With no callback
 * installed the pick passes through untouched and native export is byte-identical.</p>
 *
 * <p>Cardinality is exact and fail-closed: each method must contain exactly one matching
 * chooser call. Any other shape, or a class that already carries the redirect marker,
 * leaves the class bytes unchanged.</p>
 */
public final class ProtectedExportChooserRedirectTransformer implements ClassFileTransformer {

    private static final String FUNCTION2_DESC = "Lkotlin/jvm/functions/Function2;";

    private final String ownerInternalName;
    private final String moc3HostMethod;
    private final String moc3HostDescriptor;
    private final String moc3ChooserName;
    private final String moc3ChooserDescriptor;
    private final String gatedHostMethod;
    private final String gatedHostDescriptor;
    private final String gatedChooserName;
    private final String gatedChooserDescriptor;
    private final ClassLoader expectedClassLoader;

    public ProtectedExportChooserRedirectTransformer(
        final String ownerInternalName,
        final String moc3HostMethod,
        final String moc3HostDescriptor,
        final String moc3ChooserName,
        final String moc3ChooserDescriptor,
        final String gatedHostMethod,
        final String gatedHostDescriptor,
        final String gatedChooserName,
        final String gatedChooserDescriptor,
        final ClassLoader expectedClassLoader
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.moc3HostMethod = requireText(moc3HostMethod, "moc3HostMethod");
        this.moc3HostDescriptor = requireText(moc3HostDescriptor, "moc3HostDescriptor");
        this.moc3ChooserName = requireText(moc3ChooserName, "moc3ChooserName");
        this.moc3ChooserDescriptor = requireText(moc3ChooserDescriptor, "moc3ChooserDescriptor");
        this.gatedHostMethod = requireText(gatedHostMethod, "gatedHostMethod");
        this.gatedHostDescriptor = requireText(gatedHostDescriptor, "gatedHostDescriptor");
        this.gatedChooserName = requireText(gatedChooserName, "gatedChooserName");
        this.gatedChooserDescriptor = requireText(gatedChooserDescriptor, "gatedChooserDescriptor");
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
        final boolean[] moc3Shape = {false};
        final boolean[] gatedShape = {false};
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
                if (moc3HostMethod.equals(name) && moc3HostDescriptor.equals(descriptor)) {
                    return new RedirectMethodVisitor(
                        delegate, markerFound, moc3Shape, moc3ChooserName, moc3ChooserDescriptor);
                }
                if (gatedHostMethod.equals(name) && gatedHostDescriptor.equals(descriptor)) {
                    return new RedirectMethodVisitor(
                        delegate, markerFound, gatedShape, gatedChooserName, gatedChooserDescriptor);
                }
                return delegate;
            }
        }, ClassReader.EXPAND_FRAMES);
        if (markerFound[0] || !moc3Shape[0] || !gatedShape[0]) {
            return null;
        }
        return writer.toByteArray();
    }

    private final class RedirectMethodVisitor extends MethodVisitor {
        private final boolean[] markerFound;
        private final boolean[] shape;
        private final String chooserName;
        private final String chooserDescriptor;
        private boolean sawMarker;
        private boolean emitting;
        private boolean callsiteFound;
        private boolean invalid;

        private RedirectMethodVisitor(
            final MethodVisitor delegate,
            final boolean[] markerFound,
            final boolean[] shape,
            final String chooserName,
            final String chooserDescriptor
        ) {
            super(Opcodes.ASM9, delegate);
            this.markerFound = markerFound;
            this.shape = shape;
            this.chooserName = chooserName;
            this.chooserDescriptor = chooserDescriptor;
        }

        @Override
        public void visitLdcInsn(final Object value) {
            // Only an LDC already present in the original bytes (a previous transform)
            // marks the class as redirected; our own emission is guarded.
            if (!emitting && NativeExportSettingsDialogBridge.REDIRECT_KEY.equals(value)) {
                sawMarker = true;
                markerFound[0] = true;
            }
            super.visitLdcInsn(value);
        }

        @Override
        public void visitMethodInsn(
            final int opcode,
            final String owner,
            final String name,
            final String descriptor,
            final boolean isInterface
        ) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (sawMarker || emitting || invalid) {
                return;
            }
            if (opcode == Opcodes.INVOKESPECIAL
                && ownerInternalName.equals(owner)
                && chooserName.equals(name)
                && chooserDescriptor.equals(descriptor)) {
                if (callsiteFound) {
                    // A second matching callsite means the host shape drifted; emit the
                    // redirect anyway is unsafe, so mark the transform invalid.
                    invalid = true;
                    return;
                }
                callsiteFound = true;
                emitRedirect();
            }
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            if (!sawMarker && callsiteFound && !invalid) {
                shape[0] = true;
            }
            super.visitMaxs(maxStack, maxLocals);
        }

        /**
         * Rewrites the picked {@code File} on the stack through the bridge callback:
         * absent callback passes the pick through, a returned {@code File} substitutes
         * it, and {@code null}/throwing results yield {@code null} (native chooser-cancel
         * semantics) so a failed redirect can never write to the real destination.
         *
         * <p>Entry stack {@code [picked]}; every exit path leaves exactly one reference.</p>
         */
        private void emitRedirect() {
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label haveCallback = new Label();
            final Label resultOk = new Label();
            final Label handler = new Label();
            final Label done = new Label();
            emitting = true;
            try {
                super.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
                super.visitLabel(tryStart);
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/System",
                    "getProperties",
                    "()Ljava/util/Properties;",
                    false
                );
                super.visitLdcInsn(NativeExportSettingsDialogBridge.REDIRECT_KEY);
                super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/util/Properties",
                    "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false
                );
                // stack: [picked, callback]
                super.visitInsn(Opcodes.DUP);
                super.visitJumpInsn(Opcodes.IFNONNULL, haveCallback);
                super.visitInsn(Opcodes.POP);
                // stack: [picked] — absent callback passes the pick through untouched
                super.visitJumpInsn(Opcodes.GOTO, done);
                super.visitLabel(haveCallback);
                // stack: [picked, callback]
                super.visitInsn(Opcodes.SWAP);
                // stack: [callback, picked]
                super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/function/Function",
                    "apply",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    true
                );
                // stack: [result]
                super.visitInsn(Opcodes.DUP);
                super.visitJumpInsn(Opcodes.IFNONNULL, resultOk);
                super.visitInsn(Opcodes.POP);
                super.visitInsn(Opcodes.ACONST_NULL);
                super.visitJumpInsn(Opcodes.GOTO, done);
                super.visitLabel(resultOk);
                super.visitTypeInsn(Opcodes.CHECKCAST, "java/io/File");
                super.visitLabel(tryEnd);
                super.visitJumpInsn(Opcodes.GOTO, done);
                super.visitLabel(handler);
                super.visitInsn(Opcodes.POP);
                super.visitInsn(Opcodes.ACONST_NULL);
                super.visitLabel(done);
            } finally {
                emitting = false;
            }
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
