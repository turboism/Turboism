package dev.turboism.automesh.host;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.internal.Requirements;
import dev.turboism.mapping.verification.StaticSelector;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Entry guard for the two native Auto Mesh Generate funnels of the reviewed Cubism builds.
 *
 * <p>The guard is injected at offset 0 of each funnel, before the host resolves the current
 * document and before {@code preUpdateMesh} opens the native Undo group. A handled confirmation
 * returns from the funnel immediately, so a custom run never nests inside a host edit group. An
 * unhandled confirmation falls through to the untouched native body.</p>
 *
 * <p>The injected instructions reference only JDK types ({@code java/lang/System},
 * {@code java/util/Properties}, {@code java/util/function/Function}, {@code java/lang/Boolean}),
 * so transformed bytecode never names a runtime, SDK or ASM type. No frame is recomputed: the
 * guard carries its own four stack map frames, and the host frames are re-emitted unchanged.</p>
 *
 * <p>Cardinality is exact and fail-closed. A funnel whose name appears more than once, whose
 * descriptor drifted, or whose binding is not a concrete instance method leaves the class bytes
 * untouched, and so does a class that already carries the guard marker. The same input always
 * produces the same output, which keeps a retransformation byte-identical to the first one.</p>
 */
public final class AutoMeshFunnelTransformer implements ClassFileTransformer {

    /**
     * One exact injection site: the funnel identity plus the owner, method name and descriptor
     * the guard protects.
     *
     * @param funnel the funnel this site guards
     * @param ownerInternalName JVM internal name of the declaring class
     * @param methodName the funnel's exact name
     * @param descriptor the funnel's exact descriptor
     */
    public record Site(AutoMeshFunnel funnel, String ownerInternalName, String methodName, String descriptor) {

        /** Appended to a descriptor whose parameters the guard can hand over unchanged. */
        private static final String VOID_RETURN_SUFFIX = ")V";

        public Site {
            funnel = Objects.requireNonNull(funnel, "funnel");
            ownerInternalName = Requirements.text(ownerInternalName, "ownerInternalName");
            methodName = Requirements.text(methodName, "methodName");
            descriptor = Requirements.text(descriptor, "descriptor");
            if (!descriptor.endsWith(VOID_RETURN_SUFFIX)) {
                throw new IllegalArgumentException("guard sites must return void: " + descriptor);
            }
            try {
                referenceParameters(descriptor);
            } catch (IllegalArgumentException notReferenceOnly) {
                throw new IllegalArgumentException(
                        "guard sites must take reference parameters: " + descriptor, notReferenceOnly);
            }
        }
    }

    /**
     * Parses the declared parameters as stack map values, so the guard can forward each one as an
     * {@code Object} element and name it in its own frames.
     *
     * @param descriptor the method descriptor to read
     * @return every parameter as a frame value: an internal name, or an array descriptor
     * @throws IllegalArgumentException when a parameter is not a reference or the descriptor is
     *     malformed
     */
    private static List<String> referenceParameters(final String descriptor) {
        final List<String> parameters = new ArrayList<>();
        int index = 1;
        while (descriptor.charAt(index) != ')') {
            final int start = index;
            if (descriptor.charAt(index) == '[') {
                while (descriptor.charAt(index) == '[') {
                    index++;
                }
                if (descriptor.charAt(index) != 'L') {
                    throw new IllegalArgumentException("array parameter is not a reference: " + descriptor);
                }
                index = descriptor.indexOf(';', index) + 1;
                parameters.add(descriptor.substring(start, index));
            } else if (descriptor.charAt(index) == 'L') {
                index = descriptor.indexOf(';', index) + 1;
                parameters.add(descriptor.substring(start + 1, index - 1));
            } else {
                throw new IllegalArgumentException("parameter is not a reference: " + descriptor);
            }
            if (index <= 0) {
                throw new IllegalArgumentException("malformed parameter descriptor: " + descriptor);
            }
        }
        return List.copyOf(parameters);
    }

    private static final String FUNCTION_TYPE = "java/util/function/Function";
    private static final String FUNCTION_APPLY = "apply";
    private static final String FUNCTION_APPLY_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String BOOLEAN_TYPE = "java/lang/Boolean";
    private static final String BOOLEAN_VALUE = "booleanValue";
    private static final String BOOLEAN_VALUE_DESCRIPTOR = "()Z";
    private static final String PROPERTIES_TYPE = "java/util/Properties";
    private static final String PROPERTIES_GET = "get";
    private static final String PROPERTIES_GET_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String SYSTEM_TYPE = "java/lang/System";
    private static final String SYSTEM_PROPERTIES = "getProperties";
    private static final String SYSTEM_PROPERTIES_DESCRIPTOR = "()Ljava/util/Properties;";
    private static final String OBJECT_TYPE = "java/lang/Object";
    private static final String THROWABLE_TYPE = "java/lang/Throwable";
    private static final int RECEIVER_SLOT = 0;
    private static final int TOKEN_AND_RECEIVER_COUNT = 2;
    private static final int MAX_INLINE_INDEX = 5;
    private static final String[] NO_STACK = new String[0];

    private final String ownerInternalName;
    private final Map<String, Site> sitesByName;
    private final ClassLoader expectedClassLoader;
    private final Consumer<String> diagnostics;

    /**
     * Creates a transformer for the exact funnel sites of one owner class.
     *
     * @param ownerInternalName JVM internal name of the owner class
     * @param sites every funnel this owner declares
     * @param expectedClassLoader loader of the host classes; any other loader is ignored
     * @param diagnostics sink for a refused transformation; it must not throw
     * @throws IllegalArgumentException when a site belongs to another owner or repeats a name
     */
    public AutoMeshFunnelTransformer(
            final String ownerInternalName,
            final List<Site> sites,
            final ClassLoader expectedClassLoader,
            final Consumer<String> diagnostics) {
        this.ownerInternalName = Requirements.text(ownerInternalName, "ownerInternalName");
        this.expectedClassLoader = Objects.requireNonNull(expectedClassLoader, "expectedClassLoader");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        final Map<String, Site> byName = new LinkedHashMap<>();
        for (final Site site : Objects.requireNonNull(sites, "sites")) {
            if (!ownerInternalName.equals(site.ownerInternalName())) {
                throw new IllegalArgumentException("site owner does not match the transformed class");
            }
            if (byName.put(site.methodName(), site) != null) {
                throw new IllegalArgumentException("two guard sites share the method name " + site.methodName());
            }
        }
        if (byName.isEmpty()) {
            throw new IllegalArgumentException("a guard transformer needs at least one site");
        }
        this.sitesByName = Map.copyOf(byName);
    }

    /**
     * Returns the guard sites one host profile pins, in installer order.
     *
     * @param profile the resolved reviewed profile of the loaded host
     * @return the exact sites of both funnels
     */
    public static List<Site> sitesOf(final AutoMeshHostProfile profile) {
        final AutoMeshHostProfile requested = Objects.requireNonNull(profile, "profile");
        return List.of(
                siteOf(AutoMeshFunnel.MODELING_MODE, requested.modelingFunnel()),
                siteOf(AutoMeshFunnel.MESH_EDITOR_MODE, requested.meshEditorFunnel()));
    }

    private static Site siteOf(final AutoMeshFunnel funnel, final StaticSelector selector) {
        return new Site(funnel, selector.ownerInternalName(), selector.memberName(), selector.descriptor());
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null || loader != expectedClassLoader) {
            return null;
        }
        if (!ownerInternalName.equals(className)) {
            return null;
        }
        try {
            return guardFunnels(classfileBuffer);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("auto mesh guard: " + className + " left unchanged after "
                    + failure.getClass().getName());
            return null;
        }
    }

    private byte[] guardFunnels(final byte[] original) {
        final ShapeScan scan = new ShapeScan();
        new ClassReader(original).accept(scan, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (scan.carriesGuardMarker()) {
            diagnostic("auto mesh guard: " + ownerInternalName + " is already guarded");
            return null;
        }
        final String refusal = scan.refusalReason();
        if (refusal != null) {
            diagnostic("auto mesh guard: " + ownerInternalName + " left unchanged (" + refusal + ")");
            return null;
        }
        final ClassReader reader = new ClassReader(original);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor delegate =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        final Site site = sitesByName.get(name);
                        if (site == null) {
                            return delegate;
                        }
                        return new FunnelGuardMethodVisitor(delegate, site);
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private void diagnostic(final String message) {
        try {
            diagnostics.accept(message);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            // A diagnostic sink must never change whether the host class is transformed.
        }
    }

    /**
     * Read-only first pass: proves the exact funnel cardinality, the exact descriptors and the
     * concrete instance binding before any bytecode is written.
     */
    private final class ShapeScan extends ClassVisitor {

        private static final int ABSENT = 0;

        private final Map<String, Integer> membersPerName = new HashMap<>();
        private String refusal;
        private boolean marker;

        private ShapeScan() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions) {
            final Site site = sitesByName.get(name);
            if (site == null) {
                return null;
            }
            final int seen = membersPerName.merge(name, 1, Integer::sum);
            if (seen > 1) {
                refuse("funnel " + name + " has " + seen + " members");
                return null;
            }
            if (!site.descriptor().equals(descriptor)) {
                refuse("funnel " + name + " descriptor is " + descriptor);
                return null;
            }
            if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                refuse("funnel " + name + " is not a concrete instance method");
                return null;
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitLdcInsn(final Object value) {
                    if (NativeAutoMeshBridge.callbackKey(site.funnel()).equals(value)) {
                        marker = true;
                    }
                }
            };
        }

        private void refuse(final String reason) {
            if (refusal == null) {
                refusal = reason;
            }
        }

        private boolean carriesGuardMarker() {
            return marker;
        }

        private String refusalReason() {
            if (refusal != null) {
                return refusal;
            }
            for (final Site site : sitesByName.values()) {
                if (membersPerName.getOrDefault(site.methodName(), ABSENT) != 1) {
                    return "funnel " + site.methodName() + " is missing";
                }
            }
            return null;
        }
    }

    /** Second pass: writes the entry guard of one proven funnel site. */
    private final class FunnelGuardMethodVisitor extends MethodVisitor {

        private final Site site;
        private final List<String> frameLocals = new ArrayList<>();
        private final List<Integer> argumentSlots = new ArrayList<>();
        private int argumentCount;

        private FunnelGuardMethodVisitor(final MethodVisitor delegate, final Site site) {
            super(Opcodes.ASM9, delegate);
            this.site = site;
            collectFrameLocals(site.descriptor());
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitGuard();
        }

        /**
         * Mirrors the initial frame of the method: the receiver plus every declared reference
         * parameter, in slot order. The callback receives them after the funnel token.
         */
        private void collectFrameLocals(final String descriptor) {
            frameLocals.add(ownerInternalName);
            frameLocals.addAll(referenceParameters(descriptor));
            int slot = RECEIVER_SLOT + 1;
            for (int index = 0; index < frameLocals.size() - 1; index++) {
                argumentSlots.add(slot);
                slot++;
            }
            argumentCount = argumentSlots.size() + TOKEN_AND_RECEIVER_COUNT;
        }

        /**
         * Emits the whole guard before the first native instruction.
         *
         * <p>Every branch target carries its own frame because the host frames are copied, never
         * recomputed. The protected range covers the callback only, so a throwing policy lands in
         * the handler and continues into the native body.</p>
         */
        private void emitGuard() {
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label noBridge = new Label();
            final Label handled = new Label();
            final Label handler = new Label();
            final Label nativeBody = new Label();
            super.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_TYPE);
            super.visitLabel(tryStart);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, SYSTEM_TYPE, SYSTEM_PROPERTIES, SYSTEM_PROPERTIES_DESCRIPTOR, false);
            super.visitLdcInsn(NativeAutoMeshBridge.callbackKey(site.funnel()));
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, PROPERTIES_TYPE, PROPERTIES_GET, PROPERTIES_GET_DESCRIPTOR, false);
            super.visitInsn(Opcodes.DUP);
            super.visitJumpInsn(Opcodes.IFNULL, noBridge);
            super.visitTypeInsn(Opcodes.CHECKCAST, FUNCTION_TYPE);
            emitArguments();
            super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, FUNCTION_TYPE, FUNCTION_APPLY, FUNCTION_APPLY_DESCRIPTOR, true);
            super.visitTypeInsn(Opcodes.CHECKCAST, BOOLEAN_TYPE);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BOOLEAN_TYPE, BOOLEAN_VALUE, BOOLEAN_VALUE_DESCRIPTOR, false);
            super.visitJumpInsn(Opcodes.IFNE, handled);
            super.visitJumpInsn(Opcodes.GOTO, nativeBody);
            super.visitLabel(tryEnd);
            super.visitLabel(noBridge);
            super.visitFrame(Opcodes.F_NEW, frameLocals.size(), frameLocals.toArray(), 1, new String[] {OBJECT_TYPE});
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, nativeBody);
            super.visitLabel(handled);
            super.visitFrame(Opcodes.F_NEW, frameLocals.size(), frameLocals.toArray(), 0, NO_STACK);
            super.visitInsn(Opcodes.RETURN);
            super.visitLabel(handler);
            super.visitFrame(
                    Opcodes.F_NEW, frameLocals.size(), frameLocals.toArray(), 1, new String[] {THROWABLE_TYPE});
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, nativeBody);
            super.visitLabel(nativeBody);
            super.visitFrame(Opcodes.F_NEW, frameLocals.size(), frameLocals.toArray(), 0, NO_STACK);
        }

        /** Builds the funnel token, the receiver and every declared argument as one array. */
        private void emitArguments() {
            pushInt(argumentCount);
            super.visitTypeInsn(Opcodes.ANEWARRAY, OBJECT_TYPE);
            pushArraySlot(0);
            super.visitLdcInsn(site.funnel().token());
            super.visitInsn(Opcodes.AASTORE);
            pushArraySlot(1);
            super.visitVarInsn(Opcodes.ALOAD, RECEIVER_SLOT);
            super.visitInsn(Opcodes.AASTORE);
            for (int index = 0; index < argumentSlots.size(); index++) {
                pushArraySlot(index + TOKEN_AND_RECEIVER_COUNT);
                super.visitVarInsn(Opcodes.ALOAD, argumentSlots.get(index));
                super.visitInsn(Opcodes.AASTORE);
            }
        }

        /** Duplicates the array under construction and pushes the index of the next element. */
        private void pushArraySlot(final int index) {
            super.visitInsn(Opcodes.DUP);
            pushInt(index);
        }

        private void pushInt(final int value) {
            if (value <= MAX_INLINE_INDEX) {
                super.visitInsn(Opcodes.ICONST_0 + value);
                return;
            }
            super.visitIntInsn(Opcodes.SIPUSH, value);
        }
    }
}
