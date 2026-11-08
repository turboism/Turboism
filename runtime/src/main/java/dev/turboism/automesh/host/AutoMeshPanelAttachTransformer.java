package dev.turboism.automesh.host;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.StaticSelector;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
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
 * Container attach hook for the three native Auto Mesh Generate containers of the reviewed builds.
 *
 * <p>The hook runs at the single {@code RETURN} of each container constructor. That point is the
 * first moment the container is fully built, and the last moment before the host puts it on screen,
 * so the selector row can join the container's own layout instead of being pushed in later from a
 * timer. The constructor is the anchor because the panel's private property list changes its
 * generic owner between 5.2.03 and 5.3.00, while the constructor descriptor is identical on every
 * reviewed build.</p>
 *
 * <p>The injected instructions reference only JDK types ({@code java/lang/System},
 * {@code java/util/Properties}, {@code java/util/function/Function}), so transformed bytecode never
 * names a runtime, SDK or ASM type. No frame is recomputed: the hook carries its own frames, and a
 * constructor that already carries a branch is refused instead of being rewritten.</p>
 *
 * <p>Cardinality is exact and fail-closed. A constructor whose descriptor drifted, that appears
 * more than once, that is not a concrete instance constructor, that already carries the attach
 * marker, or that returns from more than one place leaves the class bytes untouched. The same input
 * always produces the same output, which keeps a retransformation byte-identical to the first
 * one.</p>
 */
public final class AutoMeshPanelAttachTransformer implements ClassFileTransformer {

    /**
     * One exact attach site: the container identity plus the owner and constructor descriptor the
     * hook decorates.
     *
     * @param container the container this site decorates
     * @param ownerInternalName JVM internal name of the container class
     * @param descriptor the container constructor's exact descriptor
     */
    public record Site(AutoMeshContainer container, String ownerInternalName, String descriptor) {

        /** A constructor returns the instance, so its descriptor must end with a void return. */
        private static final String VOID_RETURN_SUFFIX = ")V";

        public Site {
            container = Objects.requireNonNull(container, "container");
            ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
            descriptor = requireText(descriptor, "descriptor");
            if (!descriptor.endsWith(VOID_RETURN_SUFFIX)) {
                throw new IllegalArgumentException("attach sites must return void: " + descriptor);
            }
        }
    }

    private static final String FUNCTION_TYPE = "java/util/function/Function";
    private static final String FUNCTION_APPLY = "apply";
    private static final String FUNCTION_APPLY_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String PROPERTIES_TYPE = "java/util/Properties";
    private static final String PROPERTIES_GET = "get";
    private static final String PROPERTIES_GET_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String SYSTEM_TYPE = "java/lang/System";
    private static final String SYSTEM_PROPERTIES = "getProperties";
    private static final String SYSTEM_PROPERTIES_DESCRIPTOR = "()Ljava/util/Properties;";
    private static final String OBJECT_TYPE = "java/lang/Object";
    private static final String THROWABLE_TYPE = "java/lang/Throwable";
    private static final String CONSTRUCTOR_NAME = "<init>";
    private static final int RECEIVER_SLOT = 0;
    private static final int TOKEN_AND_RECEIVER_COUNT = 2;
    private static final int MAX_INLINE_INDEX = 5;
    private static final int EXACTLY_ONE = 1;
    private static final int NO_BRANCHES = 0;
    private static final String[] NO_STACK = new String[0];

    private final String ownerInternalName;
    private final Site site;
    private final ClassLoader expectedClassLoader;
    private final Consumer<String> diagnostics;

    /**
     * Creates a transformer for the exact container constructor of one owner class.
     *
     * @param site the container attach site
     * @param expectedClassLoader loader of the host classes; any other loader is ignored
     * @param diagnostics sink for a refused transformation; it must not throw
     */
    public AutoMeshPanelAttachTransformer(
            final Site site,
            final ClassLoader expectedClassLoader,
            final Consumer<String> diagnostics) {
        this.site = Objects.requireNonNull(site, "site");
        this.ownerInternalName = site.ownerInternalName();
        this.expectedClassLoader = Objects.requireNonNull(expectedClassLoader, "expectedClassLoader");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /**
     * Returns the attach sites one host profile pins, in installer order.
     *
     * @param profile the resolved reviewed profile of the loaded host
     * @return the exact site of every container
     */
    public static List<Site> sitesOf(final AutoMeshHostProfile profile) {
        final AutoMeshHostProfile requested = Objects.requireNonNull(profile, "profile");
        final List<Site> sites = new ArrayList<>();
        for (final AutoMeshContainer container : AutoMeshContainer.values()) {
            sites.add(siteOf(container, requested.containerConstructor(container)));
        }
        return List.copyOf(sites);
    }

    private static Site siteOf(final AutoMeshContainer container, final StaticSelector selector) {
        if (selector.kind() != StaticSelector.Kind.CONSTRUCTOR
                || !CONSTRUCTOR_NAME.equals(selector.memberName())) {
            throw new IllegalArgumentException("container anchors must be constructors: " + selector.alias());
        }
        return new Site(container, selector.ownerInternalName(), selector.descriptor());
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
            return attachContainer(classfileBuffer);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("auto mesh attach: " + className + " left unchanged after "
                    + failure.getClass().getName());
            return null;
        }
    }

    private byte[] attachContainer(final byte[] original) {
        final ShapeScan scan = new ShapeScan();
        new ClassReader(original).accept(scan, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (scan.carriesAttachMarker()) {
            diagnostic("auto mesh attach: " + ownerInternalName + " is already decorated");
            return null;
        }
        final String refusal = scan.refusalReason();
        if (refusal != null) {
            diagnostic("auto mesh attach: " + ownerInternalName + " left unchanged (" + refusal + ")");
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
                        if (!CONSTRUCTOR_NAME.equals(name) || !site.descriptor().equals(descriptor)) {
                            return delegate;
                        }
                        return new AttachMethodVisitor(delegate);
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
     * Read-only first pass: proves the exact constructor cardinality, the exact descriptor, the
     * concrete instance binding and the single return before any bytecode is written.
     */
    private final class ShapeScan extends ClassVisitor {

        private final Map<String, Integer> membersPerName = new LinkedHashMap<>();
        private String refusal;
        private boolean marker;
        private int matchingConstructors;
        private int returns;
        private int branches;

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
            if (!CONSTRUCTOR_NAME.equals(name)) {
                return null;
            }
            membersPerName.merge(name, 1, Integer::sum);
            if (!site.descriptor().equals(descriptor)) {
                return null;
            }
            matchingConstructors++;
            if (matchingConstructors > EXACTLY_ONE) {
                // A second constructor with the pinned descriptor is a shape the anchor cannot name.
                refuse("constructor " + descriptor + " has more than one binding");
                return null;
            }
            if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                refuse("constructor " + descriptor + " is not a concrete instance method");
                return null;
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitLdcInsn(final Object value) {
                    if (NativeAutoMeshBridge.ATTACH_KEY.equals(value)) {
                        marker = true;
                    }
                }

                @Override
                public void visitInsn(final int opcode) {
                    if (opcode == Opcodes.RETURN) {
                        returns++;
                    }
                }

                @Override
                public void visitJumpInsn(final int opcode, final Label label) {
                    branches++;
                }

                @Override
                public void visitTableSwitchInsn(
                        final int min, final int max, final Label dflt, final Label... labels) {
                    branches++;
                }

                @Override
                public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
                    branches++;
                }
            };
        }

        private void refuse(final String reason) {
            if (refusal == null) {
                refusal = reason;
            }
        }

        private boolean carriesAttachMarker() {
            return marker;
        }

        private String refusalReason() {
            if (refusal != null) {
                return refusal;
            }
            if (matchingConstructors != EXACTLY_ONE) {
                return "constructor " + site.descriptor() + " is missing";
            }
            if (returns != EXACTLY_ONE) {
                return "constructor " + site.descriptor() + " has " + returns + " returns";
            }
            if (branches != NO_BRANCHES) {
                // A branch would need a frame the hook does not author, so the anchor refuses it.
                return "constructor " + site.descriptor() + " has " + branches + " branches";
            }
            return null;
        }
    }

    /** Second pass: writes the attach call immediately before the single constructor return. */
    private final class AttachMethodVisitor extends MethodVisitor {

        private final List<String> frameLocals = List.of(ownerInternalName, OBJECT_TYPE);
        private boolean emitted;

        private AttachMethodVisitor(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == Opcodes.RETURN && !emitted) {
                emitted = true;
                emitAttach();
            }
            super.visitInsn(opcode);
        }

        /**
         * Emits the whole attach call before the native return.
         *
         * <p>Every branch target carries its own frame because the host frames are copied, never
         * recomputed. The protected range covers the callback only, so a throwing policy lands in
         * the handler and continues into the native return.</p>
         */
        private void emitAttach() {
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label noBridge = new Label();
            final Label handler = new Label();
            final Label complete = new Label();
            super.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_TYPE);
            super.visitLabel(tryStart);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, SYSTEM_TYPE, SYSTEM_PROPERTIES, SYSTEM_PROPERTIES_DESCRIPTOR, false);
            super.visitLdcInsn(NativeAutoMeshBridge.ATTACH_KEY);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, PROPERTIES_TYPE, PROPERTIES_GET, PROPERTIES_GET_DESCRIPTOR, false);
            super.visitInsn(Opcodes.DUP);
            super.visitJumpInsn(Opcodes.IFNULL, noBridge);
            super.visitTypeInsn(Opcodes.CHECKCAST, FUNCTION_TYPE);
            emitArguments();
            super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, FUNCTION_TYPE, FUNCTION_APPLY, FUNCTION_APPLY_DESCRIPTOR, true);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, complete);
            super.visitLabel(tryEnd);
            super.visitLabel(noBridge);
            super.visitFrame(Opcodes.F_NEW, frameLocals.size(), frameLocals.toArray(), 1, new String[] {OBJECT_TYPE});
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, complete);
            super.visitLabel(handler);
            super.visitFrame(
                    Opcodes.F_NEW, frameLocals.size(), frameLocals.toArray(), 1, new String[] {THROWABLE_TYPE});
            super.visitInsn(Opcodes.POP);
            super.visitLabel(complete);
            super.visitFrame(Opcodes.F_NEW, frameLocals.size(), frameLocals.toArray(), 0, NO_STACK);
        }

        /** Builds the container token and the live container as one array. */
        private void emitArguments() {
            pushInt(TOKEN_AND_RECEIVER_COUNT);
            super.visitTypeInsn(Opcodes.ANEWARRAY, OBJECT_TYPE);
            pushArraySlot(0);
            super.visitLdcInsn(site.container().token());
            super.visitInsn(Opcodes.AASTORE);
            pushArraySlot(1);
            super.visitVarInsn(Opcodes.ALOAD, RECEIVER_SLOT);
            super.visitInsn(Opcodes.AASTORE);
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

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
