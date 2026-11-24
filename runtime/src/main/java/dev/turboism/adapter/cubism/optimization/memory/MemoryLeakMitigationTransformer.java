package dev.turboism.adapter.cubism.optimization.memory;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
import dev.turboism.adapter.cubism.optimization.SlotBridgeEmitter;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites the 21 reviewed Cubism host classes to wire {@link MemoryLeakMitigationBridge} lifecycle,
 * OpenGL cleanup/reuse, undo eviction, image cache pruning, and inspector/listener guards.
 */
public final class MemoryLeakMitigationTransformer implements ClassFileTransformer {

    /** Total instrumentation sites required across all 21 rewritten classes. */
    public static final int EXPECTED_SITES = 29;

    private static final int PARTS_REUSE_ARRAY_SIZE = 3;
    private static final int DEFORMER_REUSE_ARRAY_SIZE = 2;
    private static final int SLOT_THIS = 0;
    private static final int SLOT_ARG1 = 1;
    private static final int SLOT_ARG2 = 2;

    private static final String OBJECT_OWNER = "java/lang/Object";
    private static final String BOOLEAN_OWNER = "java/lang/Boolean";
    private static final String BOOLEAN_VALUE_OF_METHOD = "valueOf";
    private static final String BOOLEAN_VALUE_OF_DESCRIPTOR = "(Z)Ljava/lang/Boolean;";
    private static final String CONSUMER_OWNER = "java/util/function/Consumer";
    private static final String CONSUMER_ACCEPT_METHOD = "accept";
    private static final String CONSUMER_ACCEPT_DESCRIPTOR = "(Ljava/lang/Object;)V";
    private static final String BICONSUMER_OWNER = "java/util/function/BiConsumer";
    private static final String BICONSUMER_ACCEPT_METHOD = "accept";
    private static final String BICONSUMER_ACCEPT_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String BIPREDICATE_OWNER = "java/util/function/BiPredicate";
    private static final String BIPREDICATE_TEST_METHOD = "test";
    private static final String BIPREDICATE_TEST_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

    private static final String INT_BUFFER_GETTER_METHOD = "i";
    private static final String INT_BUFFER_GETTER_DESCRIPTOR = "()Ljava/nio/IntBuffer;";
    private static final String ARRAY_LIST_OWNER = "java/util/ArrayList";
    private static final String ARRAY_LIST_ADD_METHOD = "add";
    private static final String ARRAY_LIST_ADD_DESCRIPTOR = "(Ljava/lang/Object;)Z";
    private static final String C_IMAGE_COMPANION_OWNER = "com/live2d/graphics/CImage$a";
    private static final String SENTINEL_IMAGE_METHOD = "a";
    private static final String SENTINEL_IMAGE_DESCRIPTOR = "()Ljava/awt/image/BufferedImage;";
    private static final String LIST_OWNER = "java/util/List";
    private static final String LIST_IS_EMPTY_METHOD = "isEmpty";

    private final ClassLoader loader;
    private final Path artifact;
    private final Set<String> allowedOwners;
    private final String partsCloseMethod;
    private final String deformerPanelOwner;
    private final String deformerCloseMethod;
    private final Set<String> transformedOwners = ConcurrentHashMap.newKeySet();

    private volatile String failure;
    private volatile int sites;

    /**
     * Creates a fail-closed transformer bound to an attested host loader, JAR path, and target contract.
     *
     * @param loader admitted host class loader
     * @param artifact official Cubism JAR path
     * @param target reviewed version target contract
     */
    public MemoryLeakMitigationTransformer(
            final ClassLoader loader, final Path artifact, final MemoryLeakMitigationTarget target) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(target, "target");
        this.allowedOwners = Set.copyOf(target.rewrittenOwners());
        this.partsCloseMethod = Objects.requireNonNull(target.partsCloseMethod(), "partsCloseMethod");
        this.deformerPanelOwner = Objects.requireNonNull(target.deformerPanelOwner(), "deformerPanelOwner");
        this.deformerCloseMethod = Objects.requireNonNull(target.deformerCloseMethod(), "deformerCloseMethod");
    }

    /** Returns the latest fail-closed rejection reason, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns the number of distinct reviewed host classes rewritten so far. */
    public int matches() {
        return transformedOwners.size();
    }

    /** Returns the total number of instrumented sites across rewritten classes. */
    public int sites() {
        return sites;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (actualLoader != loader || name == null || bytes == null || !allowedOwners.contains(name)) {
            return null;
        }
        try {
            if (!HostCodeSource.matchesArtifact(domain, artifact)) {
                failure = "memory leak mitigation source mismatch: " + name;
                return null;
            }
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
            final ClassRewriteTracker tracker =
                    new ClassRewriteTracker(name, expectedSitesForOwner(name, deformerPanelOwner));
            reader.accept(
                    new OwnerDispatchClassVisitor(
                            writer, tracker, partsCloseMethod, deformerPanelOwner, deformerCloseMethod),
                    ClassReader.EXPAND_FRAMES);
            if (!tracker.complete()) {
                failure = "memory leak mitigation anchor drift on " + name + ": " + tracker.describe();
                return null;
            }
            final byte[] result = writer.toByteArray();
            if (transformedOwners.add(name)) {
                sites += tracker.sitesAdded();
            }
            return result;
        } catch (final Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure = rejected + " at " + (trace.length == 0 ? "<no frames>" : trace[0].toString());
            return null;
        }
    }

    private static int expectedSitesForOwner(final String owner, final String deformerPanelOwner) {
        if (deformerPanelOwner.equals(owner)) {
            return 3;
        }
        return switch (owner) {
            case MemoryLeakMitigationTarget.PARTS_PANEL_OWNER -> 3;
            case MemoryLeakMitigationTarget.GL_BUFFER_FLOAT_OWNER,
                    MemoryLeakMitigationTarget.GL_BUFFER_INT_OWNER,
                    MemoryLeakMitigationTarget.G_TEXTURE_OWNER,
                    MemoryLeakMitigationTarget.G_TEXTURE_2D_OWNER -> 2;
            default -> 1;
        };
    }

    private static final class ClassRewriteTracker {
        private final String owner;
        private final int expectedSites;
        private int instrumentedSites;

        private ClassRewriteTracker(final String owner, final int expectedSites) {
            this.owner = owner;
            this.expectedSites = expectedSites;
        }

        private void recordSite() {
            instrumentedSites++;
        }

        private int sitesAdded() {
            return instrumentedSites;
        }

        private boolean complete() {
            return instrumentedSites == expectedSites;
        }

        private String describe() {
            return "expected=" + expectedSites + ", actual=" + instrumentedSites;
        }
    }

    private static final class OwnerDispatchClassVisitor extends ClassVisitor {
        private final ClassRewriteTracker tracker;
        private final String partsCloseMethod;
        private final String deformerPanelOwner;
        private final String deformerCloseMethod;

        private OwnerDispatchClassVisitor(
                final ClassWriter writer,
                final ClassRewriteTracker tracker,
                final String partsCloseMethod,
                final String deformerPanelOwner,
                final String deformerCloseMethod) {
            super(Opcodes.ASM9, writer);
            this.tracker = tracker;
            this.partsCloseMethod = partsCloseMethod;
            this.deformerPanelOwner = deformerPanelOwner;
            this.deformerCloseMethod = deformerCloseMethod;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions) {
            final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (delegate == null) {
                return null;
            }
            return selectMethodVisitor(
                    tracker, delegate, name, descriptor, partsCloseMethod, deformerPanelOwner, deformerCloseMethod);
        }
    }

    private static MethodVisitor selectMethodVisitor(
            final ClassRewriteTracker tracker,
            final MethodVisitor delegate,
            final String name,
            final String descriptor,
            final String partsCloseMethod,
            final String deformerPanelOwner,
            final String deformerCloseMethod) {
        final String owner = tracker.owner;
        if (deformerPanelOwner.equals(owner)) {
            if (MemoryLeakMitigationTarget.PARTS_SET_TREE_METHOD.equals(name)
                    && MemoryLeakMitigationTarget.PARTS_SET_TREE_DESCRIPTOR.equals(descriptor)) {
                return entryBiConsumer(
                        delegate, tracker, MemoryLeakMitigationBridge.ON_BEFORE_SET_DEFORMER_TREE_SLOT, SLOT_ARG1);
            }
            if (MemoryLeakMitigationTarget.DEFORMER_UPDATE_METHOD.equals(name)
                    && MemoryLeakMitigationTarget.DEFORMER_UPDATE_DESCRIPTOR.equals(descriptor)) {
                return new DeformerUpdateMethodVisitor(delegate, tracker);
            }
            if (deformerCloseMethod.equals(name)
                    && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                return new PanelCloseMethodVisitor(
                        delegate, tracker, MemoryLeakMitigationBridge.ON_DEFORMER_CLOSE_SLOT);
            }
            return delegate;
        }
        switch (owner) {
            case MemoryLeakMitigationTarget.PARTS_PANEL_OWNER -> {
                if (MemoryLeakMitigationTarget.PARTS_SET_TREE_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.PARTS_SET_TREE_DESCRIPTOR.equals(descriptor)) {
                    return entryBiConsumer(
                            delegate, tracker, MemoryLeakMitigationBridge.ON_BEFORE_SET_TREE_SLOT, SLOT_ARG1);
                }
                if (MemoryLeakMitigationTarget.PARTS_UPDATE_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.PARTS_BOOLEAN_PAIR_DESCRIPTOR.equals(descriptor)) {
                    return new PartsUpdateMethodVisitor(delegate, tracker);
                }
                if (partsCloseMethod.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return new PanelCloseMethodVisitor(
                            delegate, tracker, MemoryLeakMitigationBridge.ON_PARTS_CLOSE_SLOT);
                }
            }
            case MemoryLeakMitigationTarget.GL_BUFFER_FLOAT_OWNER, MemoryLeakMitigationTarget.GL_BUFFER_INT_OWNER -> {
                if (MemoryLeakMitigationTarget.GL_BUFFER_UPLOAD_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.GL_BUFFER_UPLOAD_DESCRIPTOR.equals(descriptor)) {
                    return new VboPrepareMethodVisitor(delegate, tracker, owner);
                }
            }
            case MemoryLeakMitigationTarget.G_ENTITY_OWNER -> {
                if (MemoryLeakMitigationTarget.ENTITY_DISPOSE_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    // Wait until dispose() detaches from parent and sets enabled=false before queueing VBO release.
                    return returnConsumer(delegate, tracker, MemoryLeakMitigationBridge.DISPOSE_ENTITY_SLOT, SLOT_THIS);
                }
            }
            case MemoryLeakMitigationTarget.EDITABLE_MESH_SEL_OWNER -> {
                if (MemoryLeakMitigationTarget.SELECTION_RESET_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return entryConsumer(
                            delegate, tracker, MemoryLeakMitigationBridge.RESET_SELECTION_ENTITY_SLOT, SLOT_THIS);
                }
            }
            case MemoryLeakMitigationTarget.MESH_EDITOR_VC_OWNER -> {
                if (MemoryLeakMitigationTarget.MESH_EDITOR_DEACTIVATE_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return entryConsumer(
                            delegate, tracker, MemoryLeakMitigationBridge.MESH_EDITOR_DEACTIVATE_SLOT, SLOT_THIS);
                }
            }
            case MemoryLeakMitigationTarget.MESH_EDITOR_EM_OWNER -> {
                if (MemoryLeakMitigationTarget.MESH_EDITOR_END_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return entryConsumer(delegate, tracker, MemoryLeakMitigationBridge.MESH_EDITOR_END_SLOT, SLOT_THIS);
                }
            }
            case MemoryLeakMitigationTarget.G_TEXTURE_OWNER -> {
                if (MemoryLeakMitigationTarget.TEXTURE_RELEASE_GL_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.TEXTURE_RELEASE_GL_DESCRIPTOR.equals(descriptor)) {
                    return entryConsumer(
                            delegate, tracker, MemoryLeakMitigationBridge.CLEAR_TEXTURE_TRACKING_SLOT, SLOT_THIS);
                }
                if (MemoryLeakMitigationTarget.ENTITY_DISPOSE_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return entryConsumer(delegate, tracker, MemoryLeakMitigationBridge.DISPOSE_TEXTURE_SLOT, SLOT_THIS);
                }
            }
            case MemoryLeakMitigationTarget.G_TEXTURE_2D_OWNER -> {
                if (MemoryLeakMitigationTarget.TEXTURE_SETUP_EXE_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.TEXTURE_SETUP_EXE_DESCRIPTOR.equals(descriptor)) {
                    return returnBiConsumer(
                            delegate, tracker, MemoryLeakMitigationBridge.OBSERVE_TEXTURE_SLOT, SLOT_ARG1);
                }
                if (MemoryLeakMitigationTarget.ENTITY_DISPOSE_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return returnConsumer(
                            delegate, tracker, MemoryLeakMitigationBridge.DISPOSE_TEXTURE_SLOT, SLOT_THIS);
                }
            }
            case MemoryLeakMitigationTarget.GL_WRAPPER_OWNER -> {
                if (MemoryLeakMitigationTarget.GL_WRAPPER_DESTROY_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return returnConsumer(
                            delegate, tracker, MemoryLeakMitigationBridge.GL_WRAPPER_DESTROY_SLOT, SLOT_THIS);
                }
            }
            case MemoryLeakMitigationTarget.GL_WRAPPER_LISTENER_OWNER -> {
                if (MemoryLeakMitigationTarget.GL_EVENT_DISPOSE_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.GL_EVENT_DISPOSE_DESCRIPTOR.equals(descriptor)) {
                    return entryConsumer(
                            delegate, tracker, MemoryLeakMitigationBridge.DISPOSE_GL_CONTEXT_SLOT, SLOT_ARG1);
                }
            }
            case MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER -> {
                if (MemoryLeakMitigationTarget.UNDO_CLEANUP_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return returnConsumer(delegate, tracker, MemoryLeakMitigationBridge.UNDO_CLEANUP_SLOT, SLOT_THIS);
                }
            }
            case MemoryLeakMitigationTarget.MODELING_DOC_OWNER -> {
                if (MemoryLeakMitigationTarget.CLOSE_FILE_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.CLOSE_FILE_DESCRIPTOR.equals(descriptor)) {
                    return new ModelingDocCloseMethodVisitor(delegate, tracker);
                }
            }
            case MemoryLeakMitigationTarget.SCENE_DOC_OWNER -> {
                if (MemoryLeakMitigationTarget.CLOSE_DOCUMENT_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return returnConsumer(
                            delegate, tracker, MemoryLeakMitigationBridge.SCENE_DOC_CLOSE_SLOT, SLOT_THIS);
                }
            }
            case MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER -> {
                if (MemoryLeakMitigationTarget.RELEASE_RESOURCE_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.RELEASE_RESOURCE_DESCRIPTOR.equals(descriptor)) {
                    return new ImageReleaseMethodVisitor(delegate, tracker);
                }
            }
            case MemoryLeakMitigationTarget.IMAGE_RESOURCE_COMPANION_OWNER -> {
                if (MemoryLeakMitigationTarget.IMAGE_CLEANUP_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return new ImageArchiveCleanupVisitor(delegate, tracker);
                }
            }
            case MemoryLeakMitigationTarget.WRITABLE_IMAGE_OWNER -> {
                if (MemoryLeakMitigationTarget.FLUSH_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return new WritableImageFlushVisitor(delegate, tracker);
                }
            }
            case MemoryLeakMitigationTarget.INSPECTOR_CTRL_OWNER -> {
                if (MemoryLeakMitigationTarget.INSPECTOR_UPDATE_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.INSPECTOR_UPDATE_DESCRIPTOR.equals(descriptor)) {
                    return new InspectorUpdateVisitor(delegate, tracker);
                }
            }
            case MemoryLeakMitigationTarget.VIEW_CONTEXT_OWNER -> {
                if (MemoryLeakMitigationTarget.CLOSE_VIEW_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return entryConsumer(delegate, tracker, MemoryLeakMitigationBridge.VIEW_CLOSE_SLOT, SLOT_THIS);
                }
            }
            case MemoryLeakMitigationTarget.PARAM_GROUP_ROW_WIDGET_OWNER -> {
                if (MemoryLeakMitigationTarget.RENAME_FOLDER_METHOD.equals(name)
                        && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return returnConsumer(delegate, tracker, MemoryLeakMitigationBridge.PARAM_RENAME_SLOT, SLOT_THIS);
                }
            }
            default -> {
                // Handled exclusively for declared owners.
            }
        }
        return delegate;
    }

    private static MethodVisitor entryConsumer(
            final MethodVisitor delegate,
            final ClassRewriteTracker tracker,
            final String slotKey,
            final int argLocalSlot) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                emitConsumerNotify(mv, slotKey, argLocalSlot);
                tracker.recordSite();
            }
        };
    }

    private static MethodVisitor entryBiConsumer(
            final MethodVisitor delegate,
            final ClassRewriteTracker tracker,
            final String slotKey,
            final int secondArgSlot) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                emitBiConsumerNotify(mv, slotKey, SLOT_THIS, secondArgSlot);
                tracker.recordSite();
            }
        };
    }

    private static MethodVisitor returnConsumer(
            final MethodVisitor delegate,
            final ClassRewriteTracker tracker,
            final String slotKey,
            final int argLocalSlot) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            private boolean instrumented;

            @Override
            public void visitInsn(final int opcode) {
                if (opcode == Opcodes.RETURN) {
                    emitConsumerNotify(mv, slotKey, argLocalSlot);
                    if (!instrumented) {
                        instrumented = true;
                        tracker.recordSite();
                    }
                }
                super.visitInsn(opcode);
            }
        };
    }

    private static MethodVisitor returnBiConsumer(
            final MethodVisitor delegate,
            final ClassRewriteTracker tracker,
            final String slotKey,
            final int secondArgSlot) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            private boolean instrumented;

            @Override
            public void visitInsn(final int opcode) {
                if (opcode == Opcodes.RETURN) {
                    emitBiConsumerNotify(mv, slotKey, SLOT_THIS, secondArgSlot);
                    if (!instrumented) {
                        instrumented = true;
                        tracker.recordSite();
                    }
                }
                super.visitInsn(opcode);
            }
        };
    }

    private static void emitConsumerNotify(final MethodVisitor mv, final String slotKey, final int argLocalSlot) {
        final Label fallback = new Label();
        final Label done = new Label();
        SlotBridgeEmitter.emitGetSlot(mv, slotKey);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, CONSUMER_OWNER);
        mv.visitJumpInsn(Opcodes.IFEQ, fallback);
        mv.visitTypeInsn(Opcodes.CHECKCAST, CONSUMER_OWNER);
        mv.visitVarInsn(Opcodes.ALOAD, argLocalSlot);
        mv.visitMethodInsn(
                Opcodes.INVOKEINTERFACE, CONSUMER_OWNER, CONSUMER_ACCEPT_METHOD, CONSUMER_ACCEPT_DESCRIPTOR, true);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(fallback);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(done);
    }

    private static void emitBiConsumerNotify(
            final MethodVisitor mv, final String slotKey, final int firstArgSlot, final int secondArgSlot) {
        final Label fallback = new Label();
        final Label done = new Label();
        SlotBridgeEmitter.emitGetSlot(mv, slotKey);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, BICONSUMER_OWNER);
        mv.visitJumpInsn(Opcodes.IFEQ, fallback);
        mv.visitTypeInsn(Opcodes.CHECKCAST, BICONSUMER_OWNER);
        mv.visitVarInsn(Opcodes.ALOAD, firstArgSlot);
        mv.visitVarInsn(Opcodes.ALOAD, secondArgSlot);
        mv.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                BICONSUMER_OWNER,
                BICONSUMER_ACCEPT_METHOD,
                BICONSUMER_ACCEPT_DESCRIPTOR,
                true);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(fallback);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(done);
    }

    private static final class PartsUpdateMethodVisitor extends MethodVisitor {
        private final ClassRewriteTracker tracker;

        private PartsUpdateMethodVisitor(final MethodVisitor delegate, final ClassRewriteTracker tracker) {
            super(Opcodes.ASM9, delegate);
            this.tracker = tracker;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label fallback = new Label();
            final Label continueHost = new Label();
            SlotBridgeEmitter.emitPredicateConsult(mv, MemoryLeakMitigationBridge.TRY_REUSE_PARTS_SLOT, fallback);
            mv.visitIntInsn(Opcodes.BIPUSH, PARTS_REUSE_ARRAY_SIZE);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, OBJECT_OWNER);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ALOAD, SLOT_THIS);
            mv.visitInsn(Opcodes.AASTORE);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitVarInsn(Opcodes.ILOAD, SLOT_ARG1);
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, BOOLEAN_OWNER, BOOLEAN_VALUE_OF_METHOD, BOOLEAN_VALUE_OF_DESCRIPTOR, false);
            mv.visitInsn(Opcodes.AASTORE);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.ICONST_2);
            mv.visitVarInsn(Opcodes.ILOAD, SLOT_ARG2);
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, BOOLEAN_OWNER, BOOLEAN_VALUE_OF_METHOD, BOOLEAN_VALUE_OF_DESCRIPTOR, false);
            mv.visitInsn(Opcodes.AASTORE);
            SlotBridgeEmitter.emitTest(mv);
            mv.visitJumpInsn(Opcodes.IFEQ, continueHost);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(fallback);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(continueHost);
            tracker.recordSite();
        }
    }

    private static final class DeformerUpdateMethodVisitor extends MethodVisitor {
        private final ClassRewriteTracker tracker;

        private DeformerUpdateMethodVisitor(final MethodVisitor delegate, final ClassRewriteTracker tracker) {
            super(Opcodes.ASM9, delegate);
            this.tracker = tracker;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label fallback = new Label();
            final Label continueHost = new Label();
            SlotBridgeEmitter.emitPredicateConsult(mv, MemoryLeakMitigationBridge.TRY_REUSE_DEFORMER_SLOT, fallback);
            mv.visitIntInsn(Opcodes.BIPUSH, DEFORMER_REUSE_ARRAY_SIZE);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, OBJECT_OWNER);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ALOAD, SLOT_THIS);
            mv.visitInsn(Opcodes.AASTORE);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitVarInsn(Opcodes.ILOAD, SLOT_ARG1);
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, BOOLEAN_OWNER, BOOLEAN_VALUE_OF_METHOD, BOOLEAN_VALUE_OF_DESCRIPTOR, false);
            mv.visitInsn(Opcodes.AASTORE);
            SlotBridgeEmitter.emitTest(mv);
            mv.visitJumpInsn(Opcodes.IFEQ, continueHost);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(fallback);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(continueHost);
            tracker.recordSite();
        }
    }

    private static final class PanelCloseMethodVisitor extends MethodVisitor {
        private final ClassRewriteTracker tracker;
        private final String slotKey;
        private boolean instrumented;

        private PanelCloseMethodVisitor(
                final MethodVisitor delegate, final ClassRewriteTracker tracker, final String slotKey) {
            super(Opcodes.ASM9, delegate);
            this.tracker = tracker;
            this.slotKey = slotKey;
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String methodOwner,
                final String name,
                final String descriptor,
                final boolean itf) {
            super.visitMethodInsn(opcode, methodOwner, name, descriptor, itf);
            if (!instrumented
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && MemoryLeakMitigationTarget.CVBOX_OWNER.equals(methodOwner)
                    && MemoryLeakMitigationTarget.REMOVE_ALL_METHOD.equals(name)
                    && MemoryLeakMitigationTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                instrumented = true;
                emitConsumerNotify(mv, slotKey, SLOT_THIS);
                tracker.recordSite();
            }
        }
    }

    /**
     * Rewrites {@code mesh/a/{b,c}.b(GL2ES2,int)} to drain deferred GL deletions on entry and
     * replace the unconditional {@code glDeleteBuffers} + {@code glGenBuffers} pair with a
     * {@code BiPredicate} reuse/queue consult, preserving compatibility with upload elision.
     */
    private static final class VboPrepareMethodVisitor extends MethodVisitor {
        private final ClassRewriteTracker tracker;
        private final String bufferOwner;
        private int matchStep;
        private boolean replacedPair;

        private VboPrepareMethodVisitor(
                final MethodVisitor delegate, final ClassRewriteTracker tracker, final String bufferOwner) {
            super(Opcodes.ASM9, delegate);
            this.tracker = tracker;
            this.bufferOwner = bufferOwner;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitConsumerNotify(mv, MemoryLeakMitigationBridge.DRAIN_GL_SLOT, SLOT_ARG1);
            tracker.recordSite();
        }

        private void flushMatchedPrefix() {
            if (matchStep >= 1) mv.visitVarInsn(Opcodes.ALOAD, SLOT_ARG1);
            if (matchStep >= 2) mv.visitInsn(Opcodes.ICONST_1);
            if (matchStep >= 3) mv.visitVarInsn(Opcodes.ALOAD, SLOT_THIS);
            if (matchStep >= 4) {
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        bufferOwner,
                        INT_BUFFER_GETTER_METHOD,
                        INT_BUFFER_GETTER_DESCRIPTOR,
                        false);
            }
            if (matchStep >= 5) {
                mv.visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        MemoryLeakMitigationTarget.GL2ES2_OWNER,
                        MemoryLeakMitigationTarget.GL_DELETE_BUFFERS_METHOD,
                        MemoryLeakMitigationTarget.GL_BUFFERS_DESCRIPTOR,
                        true);
            }
            if (matchStep >= 6) mv.visitVarInsn(Opcodes.ALOAD, SLOT_ARG1);
            if (matchStep >= 7) mv.visitInsn(Opcodes.ICONST_1);
            if (matchStep >= 8) mv.visitVarInsn(Opcodes.ALOAD, SLOT_THIS);
            if (matchStep >= 9) {
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        bufferOwner,
                        INT_BUFFER_GETTER_METHOD,
                        INT_BUFFER_GETTER_DESCRIPTOR,
                        false);
            }
            matchStep = 0;
        }

        @Override
        public void visitVarInsn(final int opcode, final int var) {
            if (!replacedPair && opcode == Opcodes.ALOAD && var == SLOT_ARG1 && matchStep == 0) {
                matchStep = 1;
                return;
            }
            if (!replacedPair && opcode == Opcodes.ALOAD && var == SLOT_THIS && matchStep == 2) {
                matchStep = 3;
                return;
            }
            if (!replacedPair && opcode == Opcodes.ALOAD && var == SLOT_ARG1 && matchStep == 5) {
                matchStep = 6;
                return;
            }
            if (!replacedPair && opcode == Opcodes.ALOAD && var == SLOT_THIS && matchStep == 7) {
                matchStep = 8;
                return;
            }
            flushMatchedPrefix();
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (!replacedPair && opcode == Opcodes.ICONST_1 && matchStep == 1) {
                matchStep = 2;
                return;
            }
            if (!replacedPair && opcode == Opcodes.ICONST_1 && matchStep == 6) {
                matchStep = 7;
                return;
            }
            flushMatchedPrefix();
            super.visitInsn(opcode);
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String methodOwner,
                final String name,
                final String descriptor,
                final boolean itf) {
            if (!replacedPair
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && !itf
                    && bufferOwner.equals(methodOwner)
                    && INT_BUFFER_GETTER_METHOD.equals(name)
                    && INT_BUFFER_GETTER_DESCRIPTOR.equals(descriptor)) {
                if (matchStep == 3) {
                    matchStep = 4;
                    return;
                }
                if (matchStep == 8) {
                    matchStep = 9;
                    return;
                }
            }
            if (!replacedPair
                    && opcode == Opcodes.INVOKEINTERFACE
                    && itf
                    && MemoryLeakMitigationTarget.GL2ES2_OWNER.equals(methodOwner)
                    && MemoryLeakMitigationTarget.GL_DELETE_BUFFERS_METHOD.equals(name)
                    && MemoryLeakMitigationTarget.GL_BUFFERS_DESCRIPTOR.equals(descriptor)
                    && matchStep == 4) {
                matchStep = 5;
                return;
            }
            if (!replacedPair
                    && opcode == Opcodes.INVOKEINTERFACE
                    && itf
                    && MemoryLeakMitigationTarget.GL2ES2_OWNER.equals(methodOwner)
                    && MemoryLeakMitigationTarget.GL_GEN_BUFFERS_METHOD.equals(name)
                    && MemoryLeakMitigationTarget.GL_BUFFERS_DESCRIPTOR.equals(descriptor)
                    && matchStep == 9) {
                matchStep = 0;
                replacedPair = true;
                emitPreparedOrOriginalPair();
                tracker.recordSite();
                return;
            }
            flushMatchedPrefix();
            super.visitMethodInsn(opcode, methodOwner, name, descriptor, itf);
        }

        private void emitPreparedOrOriginalPair() {
            final Label popFallback = new Label();
            final Label runOriginal = new Label();
            final Label done = new Label();

            SlotBridgeEmitter.emitGetSlot(mv, MemoryLeakMitigationBridge.PREPARE_VBO_SLOT);
            mv.visitInsn(Opcodes.DUP);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, BIPREDICATE_OWNER);
            mv.visitJumpInsn(Opcodes.IFEQ, popFallback);
            mv.visitTypeInsn(Opcodes.CHECKCAST, BIPREDICATE_OWNER);
            mv.visitVarInsn(Opcodes.ALOAD, SLOT_THIS);
            mv.visitVarInsn(Opcodes.ALOAD, SLOT_ARG1);
            mv.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    BIPREDICATE_OWNER,
                    BIPREDICATE_TEST_METHOD,
                    BIPREDICATE_TEST_DESCRIPTOR,
                    true);
            mv.visitJumpInsn(Opcodes.IFNE, done);
            mv.visitJumpInsn(Opcodes.GOTO, runOriginal);

            mv.visitLabel(popFallback);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(runOriginal);
            matchStep = 9;
            flushMatchedPrefix();
            mv.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    MemoryLeakMitigationTarget.GL2ES2_OWNER,
                    MemoryLeakMitigationTarget.GL_GEN_BUFFERS_METHOD,
                    MemoryLeakMitigationTarget.GL_BUFFERS_DESCRIPTOR,
                    true);
            mv.visitLabel(done);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            flushMatchedPrefix();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            flushMatchedPrefix();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(
                final int opcode, final String fieldOwner, final String name, final String descriptor) {
            flushMatchedPrefix();
            super.visitFieldInsn(opcode, fieldOwner, name, descriptor);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            flushMatchedPrefix();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(final Label label) {
            flushMatchedPrefix();
            super.visitLabel(label);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            flushMatchedPrefix();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(final int var, final int increment) {
            flushMatchedPrefix();
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name, final String descriptor, final Handle bootstrap, final Object... args) {
            flushMatchedPrefix();
            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, args);
        }
    }

    private static final class ModelingDocCloseMethodVisitor extends MethodVisitor {
        private final ClassRewriteTracker tracker;
        private boolean instrumented;

        private ModelingDocCloseMethodVisitor(final MethodVisitor delegate, final ClassRewriteTracker tracker) {
            super(Opcodes.ASM9, delegate);
            this.tracker = tracker;
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == Opcodes.IRETURN) {
                final Label skipNotify = new Label();
                mv.visitInsn(Opcodes.DUP);
                mv.visitJumpInsn(Opcodes.IFEQ, skipNotify);
                emitConsumerNotify(mv, MemoryLeakMitigationBridge.MODEL_DOC_CLOSE_SLOT, SLOT_THIS);
                mv.visitLabel(skipNotify);
                if (!instrumented) {
                    instrumented = true;
                    tracker.recordSite();
                }
            }
            super.visitInsn(opcode);
        }
    }

    /**
     * Guards the {@code releasedRetainUserData_forDebug.add(...)} call inside
     * {@code CImageResource.releaseResource} behind the debug retain {@code Predicate} slot.
     */
    private static final class ImageReleaseMethodVisitor extends MethodVisitor {
        private final ClassRewriteTracker tracker;
        private int matchStep;
        private int debugUserVar = -1;
        private boolean patched;

        private ImageReleaseMethodVisitor(final MethodVisitor delegate, final ClassRewriteTracker tracker) {
            super(Opcodes.ASM9, delegate);
            this.tracker = tracker;
        }

        private void flushMatchedPrefix() {
            if (matchStep >= 1) mv.visitVarInsn(Opcodes.ALOAD, SLOT_THIS);
            if (matchStep >= 2) {
                mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER,
                        MemoryLeakMitigationTarget.RELEASED_DEBUG_FIELD,
                        "L" + ARRAY_LIST_OWNER + ";");
            }
            if (matchStep >= 3) mv.visitVarInsn(Opcodes.ALOAD, debugUserVar);
            if (matchStep >= 4) {
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        ARRAY_LIST_OWNER,
                        ARRAY_LIST_ADD_METHOD,
                        ARRAY_LIST_ADD_DESCRIPTOR,
                        false);
            }
            matchStep = 0;
        }

        @Override
        public void visitVarInsn(final int opcode, final int var) {
            if (!patched && opcode == Opcodes.ALOAD) {
                if (var == SLOT_THIS && matchStep == 0) {
                    matchStep = 1;
                    return;
                }
                if (matchStep == 2) {
                    debugUserVar = var;
                    matchStep = 3;
                    return;
                }
            }
            flushMatchedPrefix();
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitFieldInsn(
                final int opcode, final String fieldOwner, final String name, final String descriptor) {
            if (!patched
                    && opcode == Opcodes.GETFIELD
                    && matchStep == 1
                    && MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER.equals(fieldOwner)
                    && MemoryLeakMitigationTarget.RELEASED_DEBUG_FIELD.equals(name)) {
                matchStep = 2;
                return;
            }
            flushMatchedPrefix();
            super.visitFieldInsn(opcode, fieldOwner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String methodOwner,
                final String name,
                final String descriptor,
                final boolean itf) {
            if (!patched
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && matchStep == 3
                    && ARRAY_LIST_OWNER.equals(methodOwner)
                    && ARRAY_LIST_ADD_METHOD.equals(name)
                    && ARRAY_LIST_ADD_DESCRIPTOR.equals(descriptor)) {
                matchStep = 4;
                return;
            }
            flushMatchedPrefix();
            super.visitMethodInsn(opcode, methodOwner, name, descriptor, itf);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (!patched && opcode == Opcodes.POP && matchStep == 4) {
                matchStep = 0;
                patched = true;
                emitGuardedDebugRetain();
                tracker.recordSite();
                return;
            }
            flushMatchedPrefix();
            super.visitInsn(opcode);
        }

        private void emitGuardedDebugRetain() {
            final Label popFallback = new Label();
            final Label doAdd = new Label();
            final Label done = new Label();
            SlotBridgeEmitter.emitPredicateConsult(
                    mv, MemoryLeakMitigationBridge.RETAIN_DEBUG_IMAGE_USER_SLOT, popFallback);
            mv.visitVarInsn(Opcodes.ALOAD, SLOT_THIS);
            SlotBridgeEmitter.emitTest(mv);
            mv.visitJumpInsn(Opcodes.IFEQ, done);
            mv.visitJumpInsn(Opcodes.GOTO, doAdd);
            mv.visitLabel(popFallback);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(doAdd);
            matchStep = 4;
            flushMatchedPrefix();
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(done);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            flushMatchedPrefix();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            flushMatchedPrefix();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            flushMatchedPrefix();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(final Label label) {
            flushMatchedPrefix();
            super.visitLabel(label);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            flushMatchedPrefix();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(final int var, final int increment) {
            flushMatchedPrefix();
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name, final String descriptor, final Handle bootstrap, final Object... args) {
            flushMatchedPrefix();
            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, args);
        }
    }

    /**
     * Rewrites {@code CImageResource$a.f()} so achieving one idle archive continues looping over all
     * remaining idle image resources instead of breaking early on {@code if (archived) break;}.
     */
    private static final class ImageArchiveCleanupVisitor extends MethodVisitor {
        private final ClassRewriteTracker tracker;
        private int matchStep;
        private int archivedFlagVar = -1;
        private Label loopHeadLabel;
        private boolean patched;

        private ImageArchiveCleanupVisitor(final MethodVisitor delegate, final ClassRewriteTracker tracker) {
            super(Opcodes.ASM9, delegate);
            this.tracker = tracker;
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String methodOwner,
                final String name,
                final String descriptor,
                final boolean itf) {
            super.visitMethodInsn(opcode, methodOwner, name, descriptor, itf);
            if (!patched
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && MemoryLeakMitigationTarget.IMAGE_RESOURCE_OWNER.equals(methodOwner)
                    && MemoryLeakMitigationTarget.ARCHIVE_IF_IDLE_METHOD.equals(name)
                    && MemoryLeakMitigationTarget.BOOLEAN_GETTER_DESCRIPTOR.equals(descriptor)) {
                matchStep = 1;
            } else {
                matchStep = 0;
            }
        }

        @Override
        public void visitInsn(final int opcode) {
            super.visitInsn(opcode);
            matchStep = (!patched && matchStep == 1 && opcode == Opcodes.IOR) ? 2 : 0;
        }

        @Override
        public void visitVarInsn(final int opcode, final int var) {
            super.visitVarInsn(opcode, var);
            if (!patched && opcode == Opcodes.ISTORE && matchStep == 2) {
                archivedFlagVar = var;
                matchStep = 3;
            } else if (!patched && opcode == Opcodes.ILOAD && matchStep == 3 && var == archivedFlagVar) {
                matchStep = 4;
            } else {
                matchStep = 0;
            }
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            if (!patched && opcode == Opcodes.IFEQ && matchStep == 4) {
                loopHeadLabel = label;
                matchStep = 5;
                super.visitJumpInsn(opcode, label);
                return;
            }
            if (!patched && opcode == Opcodes.GOTO && matchStep == 5 && loopHeadLabel != null) {
                // Continue iterating so every idle image resource is archived in a single sweep.
                patched = true;
                matchStep = 0;
                super.visitJumpInsn(Opcodes.GOTO, loopHeadLabel);
                tracker.recordSite();
                return;
            }
            matchStep = 0;
            super.visitJumpInsn(opcode, label);
        }
    }

    /** Fixes the inverted {@code IF_ACMPNE} sentinel comparison in {@code CWritableImage.flush()}. */
    private static final class WritableImageFlushVisitor extends MethodVisitor {
        private final ClassRewriteTracker tracker;
        private boolean sawSentinelGetter;
        private boolean patched;

        private WritableImageFlushVisitor(final MethodVisitor delegate, final ClassRewriteTracker tracker) {
            super(Opcodes.ASM9, delegate);
            this.tracker = tracker;
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String methodOwner,
                final String name,
                final String descriptor,
                final boolean itf) {
            super.visitMethodInsn(opcode, methodOwner, name, descriptor, itf);
            sawSentinelGetter = !patched
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && C_IMAGE_COMPANION_OWNER.equals(methodOwner)
                    && SENTINEL_IMAGE_METHOD.equals(name)
                    && SENTINEL_IMAGE_DESCRIPTOR.equals(descriptor);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            if (!patched && sawSentinelGetter && opcode == Opcodes.IF_ACMPNE) {
                sawSentinelGetter = false;
                patched = true;
                super.visitJumpInsn(Opcodes.IF_ACMPEQ, label);
                tracker.recordSite();
                return;
            }
            sawSentinelGetter = false;
            super.visitJumpInsn(opcode, label);
        }
    }

    /** Clears retained inspector targets when {@code palette/inspector/d.a(List)} exits on an empty list. */
    private static final class InspectorUpdateVisitor extends MethodVisitor {
        private final ClassRewriteTracker tracker;
        private boolean sawEmptyCheck;
        private boolean instrumented;

        private InspectorUpdateVisitor(final MethodVisitor delegate, final ClassRewriteTracker tracker) {
            super(Opcodes.ASM9, delegate);
            this.tracker = tracker;
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String methodOwner,
                final String name,
                final String descriptor,
                final boolean itf) {
            super.visitMethodInsn(opcode, methodOwner, name, descriptor, itf);
            if (!instrumented
                    && opcode == Opcodes.INVOKEINTERFACE
                    && LIST_OWNER.equals(methodOwner)
                    && LIST_IS_EMPTY_METHOD.equals(name)
                    && MemoryLeakMitigationTarget.BOOLEAN_GETTER_DESCRIPTOR.equals(descriptor)) {
                sawEmptyCheck = true;
            }
        }

        @Override
        public void visitInsn(final int opcode) {
            if (!instrumented && sawEmptyCheck && opcode == Opcodes.RETURN) {
                instrumented = true;
                emitConsumerNotify(mv, MemoryLeakMitigationBridge.INSPECTOR_CLEAR_SLOT, SLOT_THIS);
                tracker.recordSite();
            }
            super.visitInsn(opcode);
        }
    }
}
