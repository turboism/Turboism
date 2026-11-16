package dev.turboism.adapter.cubism.optimization.renderpass;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites {@code com/live2d/graphics3d/rendering/e} (5.3.x) and
 * {@code com/live2d/graphics3d/rendering/d} (5.0.x–5.2.x) to eliminate iterator allocations,
 * redundant {@code GEntity.getRenderer()} queries, and per-draw debug string concatenations.
 *
 * <p>All modifications target {@code b(...)} and (on 5.3.x) {@code a(GEntity, Z, Z, Z, ArrayList)},
 * leaving the clipping-mask and top-level render entry points untouched so clipping-mask and probe
 * transformers compose cleanly in any order.</p>
 */
public final class RenderPassTraversalTransformer implements ClassFileTransformer {

    private static final String SYSTEM_OWNER = "java/lang/System";
    private static final String GET_PROPERTIES_METHOD = "getProperties";
    private static final String GET_PROPERTIES_DESCRIPTOR = "()Ljava/util/Properties;";
    private static final String PROPERTIES_OWNER = "java/util/Properties";
    private static final String PROPERTIES_GET_METHOD = "get";
    private static final String PROPERTIES_GET_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String THROWABLE_OWNER = "java/lang/Throwable";

    private static final String STRING_ARRAY_DESCRIPTOR = "[Ljava/lang/String;";
    private static final String ITERABLE_OWNER = "java/lang/Iterable";
    private static final String ITERATOR_METHOD = "iterator";
    private static final String ITERATOR_DESCRIPTOR = "()Ljava/util/Iterator;";
    private static final String ITERATOR_OWNER = "java/util/Iterator";
    private static final String HAS_NEXT_METHOD = "hasNext";
    private static final String HAS_NEXT_DESCRIPTOR = "()Z";
    private static final String NEXT_METHOD = "next";
    private static final String NEXT_DESCRIPTOR = "()Ljava/lang/Object;";
    private static final String LIST_OWNER = "java/util/List";
    private static final String LIST_SIZE_METHOD = "size";
    private static final String LIST_SIZE_DESCRIPTOR = "()I";
    private static final String LIST_GET_METHOD = "get";
    private static final String LIST_GET_DESCRIPTOR = "(I)Ljava/lang/Object;";
    private static final String ARRAY_LIST_OWNER = "java/util/ArrayList";
    private static final String ARRAY_LIST_INIT_CAPACITY_DESCRIPTOR = "(I)V";
    private static final String COLLECTION_ADD_METHOD = "add";
    private static final String COLLECTION_ADD_DESCRIPTOR = "(Ljava/lang/Object;)Z";
    private static final String LIST_REMOVE_METHOD = "remove";
    private static final String LIST_REMOVE_DESCRIPTOR = "(I)Ljava/lang/Object;";
    private static final String LIST_IS_EMPTY_METHOD = "isEmpty";
    private static final String LIST_IS_EMPTY_DESCRIPTOR = "()Z";

    private static final String COLLECTIONS_KT_OWNER = "kotlin/collections/CollectionsKt";
    private static final String LIST_OF_METHOD = "listOf";
    private static final String LIST_OF_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/util/List;";
    private static final String THROW_INDEX_OVERFLOW_METHOD = "throwIndexOverflow";
    private static final String THROW_INDEX_OVERFLOW_DESCRIPTOR = "()V";

    private static final String STRING_CONCAT_FACTORY_OWNER = "java/lang/invoke/StringConcatFactory";
    private static final String MAKE_CONCAT_WITH_CONSTANTS_METHOD = "makeConcatWithConstants";
    private static final String INT_TO_STRING_DESCRIPTOR = "(I)Ljava/lang/String;";
    private static final String PRE_DRAW_TEMPLATE = "pre draw[" + '\001' + "]";
    private static final String POST_DRAW_TEMPLATE = "post draw[" + '\001' + "]";

    private static final String FIELD_DEBUG_FLAG_D = "d";
    private static final String FIELD_RENDERERS_H = "h";
    private static final String BOOLEAN_DESCRIPTOR = "Z";
    private static final String CARRAY_LIST_DESCRIPTOR = "Lcom/live2d/type/CArrayList;";
    private static final String ROOT_ENTITY_GETTER_METHOD = "a";
    private static final String ROOT_ENTITY_GETTER_DESCRIPTOR = "()Lcom/live2d/graphics3d/entity/GEntity;";
    private static final String CLEAR_METHOD = "clear";
    private static final String VOID_DESCRIPTOR = "()V";
    private static final String GET_CHILDREN_METHOD = "getChildren";
    private static final String GET_CHILDREN_DESCRIPTOR = "()Lcom/live2d/graphics3d/entity/Entities;";
    private static final String GET_LIST_METHOD = "getList";
    private static final String GET_LIST_DESCRIPTOR = "()Ljava/util/List;";
    private static final String GET_ENABLED_METHOD = "getEnabled";
    private static final String GET_IGNORE_GROUP_METHOD = "getIgnoreGroupRenderer";
    private static final String GET_RENDERER_METHOD = "getRenderer";
    private static final String GET_RENDERER_DESCRIPTOR = "()Lcom/live2d/graphics3d/component/AGRenderer;";
    private static final String GET_SORTING_INFO_METHOD = "getSortingInfo";
    private static final String GET_SORTING_INFO_DESCRIPTOR =
            "(Lcom/live2d/graphics3d/GCamera;)Lcom/live2d/graphics3d/component/a/b;";
    private static final String TRAVERSE_OLD_METHOD = "traverse";
    private static final String TRAVERSE_OLD_DESCRIPTOR = "(Lkotlin/jvm/functions/Function2;)Ljava/lang/Iterable;";

    private static final int INIT_STACK_CAPACITY = 32;
    private static final int EXPECTED_DRAW_LOOPS = 2;
    private static final int EXPECTED_LABEL_SITES = 4;
    private static final int TRAVERSE_EXPECTED_GET_RENDERER = 3;
    private static final int TRAVERSE_EXPECTED_ITERATORS = 1;
    private static final int TRAVERSE_PARAM_ENTITY = 1;
    private static final int TRAVERSE_PARAM_IN_GROUP = 2;
    private static final int TRAVERSE_PARAM_IGNORED = 3;
    private static final int TRAVERSE_PARAM_EXPORT = 4;
    private static final int TRAVERSE_PARAM_OUT = 5;
    private static final int TRAVERSE_LOCAL_IGNORED = 6;
    private static final int TRAVERSE_LOCAL_SHOULD_DRAW = 7;
    private static final int TRAVERSE_LOCAL_RENDERER = 8;
    private static final int TRAVERSE_LOCAL_NEXT_IN_GROUP = 9;
    private static final int TRAVERSE_LOCAL_CHILDREN = 10;
    private static final int TRAVERSE_LOCAL_COUNT = 11;
    private static final int TRAVERSE_LOCAL_INDEX = 12;
    private static final int RENDER_IMPL_PARAM_EXPORT_53 = 4;
    private static final int MIN_RENDER_IMPL_LOCALS = 4;
    private static final int RING_BUFFER_SIZE = 32;

    private static final String ERROR_LOADER_OR_SOURCE = "unexpected loader or code source for ";
    private static final String ERROR_ABSENT_REFERENCE = "reviewed render-pass method absent: ";
    private static final String ERROR_METHOD_SHAPE = "render-pass method shape mismatch: ";
    private static final String ERROR_SITE_COUNT = "render-pass site count mismatch: ";
    private static final String ERROR_DRAW_HEADER = "unrecognized draw loop header around throwIndexOverflow";
    private static final String ERROR_LOCALS = "renderImpl locals unavailable: ";
    private static final String ERROR_TRAV_ABSENT = "renderImpl traversal loop absent";
    private static final String ERROR_SORT_ABSENT = "renderImpl sorting prep loop absent";
    private static final String ERROR_DRAW_COUNT = "renderImpl draw loops != ";
    private static final String ERROR_DRAW_BOUNDS = "renderImpl draw loop bounds incomplete";
    private static final String ERROR_LABEL_COUNT = "renderImpl debug label sites mismatch: ";
    private static final String INIT_METHOD = "<init>";

    private final ClassLoader loader;
    private final Path artifact;
    private final RenderPassTraversalTarget target;
    private final List<String> renderImplShape;
    private final List<String> traverseShape;

    private volatile String failure;
    private volatile int matches;
    private volatile int sites;

    /**
     * Captures and verifies reviewed shapes for the target render-system methods.
     *
     * @param loader admitted host class loader
     * @param artifact official host JAR path
     * @param reference reference bytes of the version-specific render-system class
     * @param target reviewed target metadata for the artifact digest
     */
    public RenderPassTraversalTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] reference,
            final RenderPassTraversalTarget target) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reference, "reference");

        renderImplShape = ReviewedMethodShape.read(
                reference,
                target.renderSystemOwner(),
                RenderPassTraversalTarget.RENDER_IMPL_METHOD,
                target.renderImplDescriptor());
        if (renderImplShape == null) {
            throw new IllegalArgumentException(ERROR_ABSENT_REFERENCE + target.renderSystemOwner() + "."
                    + RenderPassTraversalTarget.RENDER_IMPL_METHOD);
        }
        if (target.hasRecursiveTraverseMethod()) {
            traverseShape = ReviewedMethodShape.read(
                    reference,
                    target.renderSystemOwner(),
                    RenderPassTraversalTarget.TRAVERSE_METHOD,
                    RenderPassTraversalTarget.TRAVERSE_DESCRIPTOR);
            if (traverseShape == null) {
                throw new IllegalArgumentException(ERROR_ABSENT_REFERENCE
                        + target.renderSystemOwner()
                        + "."
                        + RenderPassTraversalTarget.TRAVERSE_METHOD);
            }
        } else {
            traverseShape = null;
        }
    }

    /** Returns the latest fail-closed rejection reason, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns the number of rewritten classes. */
    public int matches() {
        return matches;
    }

    /** Returns the total number of rewritten sites across target methods. */
    public int sites() {
        return sites;
    }

    /** Returns the expected rewrite site count for this artifact target. */
    public int expectedSites() {
        return target.expectedSites();
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (bytes == null || !target.renderSystemOwner().equals(name)) {
            return null;
        }
        if (actualLoader != loader || !HostCodeSource.matchesArtifact(domain, artifact)) {
            failure = ERROR_LOADER_OR_SOURCE + name;
            return null;
        }
        try {
            final List<String> observedImplShape = ReviewedMethodShape.read(
                    bytes,
                    target.renderSystemOwner(),
                    RenderPassTraversalTarget.RENDER_IMPL_METHOD,
                    target.renderImplDescriptor());
            if (!renderImplShape.equals(observedImplShape)) {
                failure = ERROR_METHOD_SHAPE + target.renderSystemOwner() + "."
                        + RenderPassTraversalTarget.RENDER_IMPL_METHOD;
                return null;
            }
            if (target.hasRecursiveTraverseMethod()) {
                final List<String> observedTraverseShape = ReviewedMethodShape.read(
                        bytes,
                        target.renderSystemOwner(),
                        RenderPassTraversalTarget.TRAVERSE_METHOD,
                        RenderPassTraversalTarget.TRAVERSE_DESCRIPTOR);
                if (!traverseShape.equals(observedTraverseShape)) {
                    failure = ERROR_METHOD_SHAPE + target.renderSystemOwner() + "."
                            + RenderPassTraversalTarget.TRAVERSE_METHOD;
                    return null;
                }
            }

            final RenderImplPlan plan = inspectRenderImpl(bytes, target);
            if (target.hasRecursiveTraverseMethod()) {
                verifyTraverseMethod(bytes, target);
            }

            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
            final int[] appliedSites = {0};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String methodName,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor downstream =
                                    super.visitMethod(access, methodName, descriptor, signature, exceptions);
                            if (RenderPassTraversalTarget.RENDER_IMPL_METHOD.equals(methodName)
                                    && target.renderImplDescriptor().equals(descriptor)) {
                                return new RenderImplRewriter(downstream, target, plan, appliedSites);
                            }
                            if (target.hasRecursiveTraverseMethod()
                                    && RenderPassTraversalTarget.TRAVERSE_METHOD.equals(methodName)
                                    && RenderPassTraversalTarget.TRAVERSE_DESCRIPTOR.equals(descriptor)) {
                                return new RecursiveTraverseRewriter(downstream, target, appliedSites);
                            }
                            return downstream;
                        }
                    },
                    ClassReader.EXPAND_FRAMES);

            if (appliedSites[0] != target.expectedSites()) {
                failure = ERROR_SITE_COUNT + appliedSites[0] + " != " + target.expectedSites();
                return null;
            }

            final byte[] output = writer.toByteArray();
            matches++;
            sites += appliedSites[0];
            return output;
        } catch (final Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure = rejected + (trace.length == 0 ? "" : " at " + trace[0]);
            return null;
        }
    }

    private static void verifyTraverseMethod(final byte[] bytes, final RenderPassTraversalTarget target) {
        final int[] counts = {0, 0, 0, 0};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!RenderPassTraversalTarget.TRAVERSE_METHOD.equals(name)
                                        || !RenderPassTraversalTarget.TRAVERSE_DESCRIPTOR.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitTryCatchBlock(
                                            final Label start,
                                            final Label end,
                                            final Label handler,
                                            final String type) {
                                        counts[3]++;
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String invoked,
                                            final String desc,
                                            final boolean itf) {
                                        if (opcode == Opcodes.INVOKEVIRTUAL
                                                && RenderPassTraversalTarget.ENTITY_OWNER.equals(owner)
                                                && GET_RENDERER_METHOD.equals(invoked)
                                                && GET_RENDERER_DESCRIPTOR.equals(desc)) {
                                            counts[0]++;
                                        } else if (opcode == Opcodes.INVOKEINTERFACE
                                                && ITERABLE_OWNER.equals(owner)
                                                && ITERATOR_METHOD.equals(invoked)
                                                && ITERATOR_DESCRIPTOR.equals(desc)) {
                                            counts[1]++;
                                        } else if (opcode == Opcodes.INVOKESPECIAL
                                                && target.renderSystemOwner().equals(owner)
                                                && RenderPassTraversalTarget.TRAVERSE_METHOD.equals(invoked)
                                                && RenderPassTraversalTarget.TRAVERSE_DESCRIPTOR.equals(desc)) {
                                            counts[2]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (counts[0] != TRAVERSE_EXPECTED_GET_RENDERER
                || counts[1] != TRAVERSE_EXPECTED_ITERATORS
                || counts[2] != 1
                || counts[3] != 0) {
            throw new IllegalArgumentException(
                    "unexpected traverse shape: getRenderer=" + counts[0] + " iter=" + counts[1]);
        }
    }

    private static RenderImplPlan inspectRenderImpl(final byte[] bytes, final RenderPassTraversalTarget target) {
        final RenderImplInspector inspector = new RenderImplInspector(target);
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!RenderPassTraversalTarget.RENDER_IMPL_METHOD.equals(name)
                                        || !target.renderImplDescriptor().equals(descriptor)) {
                                    return null;
                                }
                                return inspector;
                            }
                        },
                        ClassReader.EXPAND_FRAMES);
        return inspector.buildPlan();
    }

    private record DrawLoopSpec(
            int headerStartPos,
            int headerEndPos,
            int backEdgePos,
            int loopEndPos,
            int sortedListLocal,
            int rendererLocal,
            int drawIdxLocal) {}

    private record LabelSite(int pos, boolean preDraw) {}

    private record RenderImplPlan(
            int maxLocals,
            int travStartPos,
            int travEndPos,
            int rootEntityLocal,
            int sortStartPos,
            int sortEndPos,
            int cameraLocal,
            List<DrawLoopSpec> drawLoops,
            List<LabelSite> labelSites) {}

    private record InsnEvent(
            int pos, int kind, int opcode, int varIndex, String owner, String name, String desc, Label label) {
        static final int KIND_OTHER = 0;
        static final int KIND_LABEL = 1;
        static final int KIND_VAR = 2;
        static final int KIND_FIELD = 3;
        static final int KIND_METHOD = 4;
        static final int KIND_JUMP = 5;
        static final int KIND_TYPE = 6;
        static final int KIND_INSN = 7;
        static final int KIND_IINC = 8;
    }

    private static final class RenderImplInspector extends MethodVisitor {

        private final RenderPassTraversalTarget target;
        private final List<InsnEvent> events = new ArrayList<>();
        private final Map<Label, Integer> labelPositions = new HashMap<>();
        private final List<LabelSite> labelSites = new ArrayList<>();
        private int pos;
        private int maxLocals;

        private int travStartPos = -1;
        private Label travElseLabel;
        private int rootEntityLocal = -1;
        private boolean sawListOfInTrav;
        private boolean sawClearInTrav;
        private Label travEndLabel;
        private int travEndPos = -1;
        private boolean sawTravBodyCall;

        private int candSortStart = -1;
        private Label candSortEndLabel;
        private int sortStartPos = -1;
        private int sortEndPos = -1;
        private int cameraLocal = -1;

        private final List<PendingDrawLoop> pendingDrawLoops = new ArrayList<>();

        private static final class PendingDrawLoop {
            int headerStartPos = -1;
            int headerEndPos = -1;
            int sortedListLocal = -1;
            int rendererLocal = -1;
            int drawIdxLocal = -1;
            Label loopStartLabel;
            Label loopEndLabel;
            int backEdgePos = -1;
            int loopEndPos = -1;
        }

        RenderImplInspector(final RenderPassTraversalTarget target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        private InsnEvent recent(final int offsetBack) {
            final int idx = events.size() - 1 - offsetBack;
            return idx >= 0 ? events.get(idx) : null;
        }

        @Override
        public void visitLabel(final Label label) {
            labelPositions.put(label, pos);
            events.add(new InsnEvent(pos, InsnEvent.KIND_LABEL, 0, -1, null, null, null, label));
            if (travStartPos >= 0 && travEndPos < 0 && label == travEndLabel) {
                travEndPos = pos;
            }
            if (sortStartPos >= 0 && sortEndPos < 0 && label == candSortEndLabel) {
                sortEndPos = pos;
            }
            for (final PendingDrawLoop loop : pendingDrawLoops) {
                if (loop.loopEndPos < 0 && loop.backEdgePos >= 0 && label == loop.loopEndLabel) {
                    loop.loopEndPos = pos;
                }
            }
            pos++;
        }

        @Override
        public void visitInsn(final int opcode) {
            events.add(new InsnEvent(pos, InsnEvent.KIND_INSN, opcode, -1, null, null, null, null));
            pos++;
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            events.add(new InsnEvent(pos, InsnEvent.KIND_OTHER, opcode, operand, null, null, null, null));
            pos++;
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            events.add(new InsnEvent(pos, InsnEvent.KIND_VAR, opcode, varIndex, null, null, null, null));
            for (final PendingDrawLoop loop : pendingDrawLoops) {
                if (loop.headerEndPos < 0 && opcode == Opcodes.ISTORE) {
                    final InsnEvent prev1 = recent(1);
                    final InsnEvent prev2 = recent(2);
                    if (prev1 != null
                            && prev1.kind() == InsnEvent.KIND_VAR
                            && prev1.opcode() == Opcodes.ASTORE
                            && prev2 != null
                            && prev2.kind() == InsnEvent.KIND_TYPE
                            && prev2.opcode() == Opcodes.CHECKCAST
                            && RenderPassTraversalTarget.RENDERER_OWNER.equals(prev2.owner())) {
                        loop.rendererLocal = prev1.varIndex();
                        loop.drawIdxLocal = varIndex;
                        loop.headerEndPos = pos;
                    }
                }
            }
            pos++;
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            events.add(new InsnEvent(pos, InsnEvent.KIND_TYPE, opcode, -1, type, null, null, null));
            if (opcode == Opcodes.CHECKCAST && ITERABLE_OWNER.equals(type)) {
                final InsnEvent p1 = recent(1);
                final InsnEvent p2 = recent(2);
                if (p2 != null
                        && p2.kind() == InsnEvent.KIND_VAR
                        && p2.opcode() == Opcodes.ALOAD
                        && p2.varIndex() == 0
                        && p1 != null
                        && p1.kind() == InsnEvent.KIND_FIELD
                        && p1.opcode() == Opcodes.GETFIELD
                        && target.renderSystemOwner().equals(p1.owner())
                        && FIELD_RENDERERS_H.equals(p1.name())
                        && CARRAY_LIST_DESCRIPTOR.equals(p1.desc())
                        && sortStartPos < 0) {
                    candSortStart = p2.pos();
                    candSortEndLabel = null;
                }
            }
            pos++;
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            events.add(new InsnEvent(pos, InsnEvent.KIND_FIELD, opcode, -1, owner, name, descriptor, null));
            pos++;
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface) {
            events.add(new InsnEvent(pos, InsnEvent.KIND_METHOD, opcode, -1, owner, name, descriptor, null));
            if (travStartPos >= 0 && travEndPos < 0) {
                if (opcode == Opcodes.INVOKESTATIC
                        && COLLECTIONS_KT_OWNER.equals(owner)
                        && LIST_OF_METHOD.equals(name)
                        && LIST_OF_DESCRIPTOR.equals(descriptor)) {
                    sawListOfInTrav = true;
                } else if (opcode == Opcodes.INVOKEVIRTUAL
                        && RenderPassTraversalTarget.ENTITY_OWNER.equals(owner)
                        && GET_CHILDREN_METHOD.equals(name)
                        && GET_CHILDREN_DESCRIPTOR.equals(descriptor)
                        && rootEntityLocal < 0) {
                    final InsnEvent prev = recent(1);
                    if (prev != null && prev.kind() == InsnEvent.KIND_VAR && prev.opcode() == Opcodes.ALOAD) {
                        rootEntityLocal = prev.varIndex();
                    }
                } else if (opcode == Opcodes.INVOKEVIRTUAL
                        && RenderPassTraversalTarget.CARRAY_LIST_OWNER.equals(owner)
                        && CLEAR_METHOD.equals(name)
                        && VOID_DESCRIPTOR.equals(descriptor)) {
                    sawClearInTrav = true;
                } else if (target.hasRecursiveTraverseMethod()
                        && opcode == Opcodes.INVOKESPECIAL
                        && target.renderSystemOwner().equals(owner)
                        && RenderPassTraversalTarget.TRAVERSE_METHOD.equals(name)
                        && RenderPassTraversalTarget.TRAVERSE_DESCRIPTOR.equals(descriptor)) {
                    sawTravBodyCall = true;
                } else if (!target.hasRecursiveTraverseMethod()
                        && opcode == Opcodes.INVOKEVIRTUAL
                        && RenderPassTraversalTarget.ENTITY_OWNER.equals(owner)
                        && TRAVERSE_OLD_METHOD.equals(name)
                        && TRAVERSE_OLD_DESCRIPTOR.equals(descriptor)) {
                    sawTravBodyCall = true;
                }
            }
            if (sortStartPos < 0
                    && candSortStart >= 0
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && RenderPassTraversalTarget.RENDERER_OWNER.equals(owner)
                    && GET_SORTING_INFO_METHOD.equals(name)
                    && GET_SORTING_INFO_DESCRIPTOR.equals(descriptor)) {
                final InsnEvent prev = recent(1);
                if (prev != null && prev.kind() == InsnEvent.KIND_VAR && prev.opcode() == Opcodes.ALOAD) {
                    sortStartPos = candSortStart;
                    cameraLocal = prev.varIndex();
                }
            }
            if (opcode == Opcodes.INVOKESTATIC
                    && COLLECTIONS_KT_OWNER.equals(owner)
                    && THROW_INDEX_OVERFLOW_METHOD.equals(name)
                    && THROW_INDEX_OVERFLOW_DESCRIPTOR.equals(descriptor)) {
                pendingDrawLoops.add(matchDrawLoopHeader());
            }
            pos++;
        }

        private PendingDrawLoop matchDrawLoopHeader() {
            final int searchLimit = Math.min(events.size(), RING_BUFFER_SIZE);
            int headerStart = -1;
            int sortedLocal = -1;
            Label loopStart = null;
            Label loopEnd = null;
            for (int offset = 1; offset < searchLimit; offset++) {
                final InsnEvent ev = recent(offset);
                if (ev == null) {
                    break;
                }
                if (ev.kind() == InsnEvent.KIND_JUMP && ev.opcode() == Opcodes.IFEQ && loopEnd == null) {
                    final InsnEvent prev = recent(offset + 1);
                    if (prev != null
                            && prev.kind() == InsnEvent.KIND_METHOD
                            && ITERATOR_OWNER.equals(prev.owner())
                            && HAS_NEXT_METHOD.equals(prev.name())) {
                        loopEnd = ev.label();
                        final InsnEvent labelEv = recent(offset + 3);
                        if (labelEv != null && labelEv.kind() == InsnEvent.KIND_LABEL) {
                            loopStart = labelEv.label();
                        }
                    }
                }
                if (ev.kind() == InsnEvent.KIND_TYPE
                        && ev.opcode() == Opcodes.CHECKCAST
                        && ITERABLE_OWNER.equals(ev.owner())) {
                    final InsnEvent prev = recent(offset + 1);
                    if (prev != null && prev.kind() == InsnEvent.KIND_VAR && prev.opcode() == Opcodes.ALOAD) {
                        headerStart = prev.pos();
                        sortedLocal = prev.varIndex();
                        break;
                    }
                }
            }
            if (headerStart < 0 || sortedLocal < 0 || loopStart == null || loopEnd == null) {
                throw new IllegalArgumentException(ERROR_DRAW_HEADER);
            }
            final PendingDrawLoop loop = new PendingDrawLoop();
            loop.headerStartPos = headerStart;
            loop.sortedListLocal = sortedLocal;
            loop.loopStartLabel = loopStart;
            loop.loopEndLabel = loopEnd;
            return loop;
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name,
                final String descriptor,
                final Handle bootstrapMethodHandle,
                final Object... bootstrapMethodArguments) {
            events.add(
                    new InsnEvent(pos, InsnEvent.KIND_OTHER, Opcodes.INVOKEDYNAMIC, -1, null, name, descriptor, null));
            if (STRING_CONCAT_FACTORY_OWNER.equals(bootstrapMethodHandle.getOwner())
                    && MAKE_CONCAT_WITH_CONSTANTS_METHOD.equals(bootstrapMethodHandle.getName())
                    && INT_TO_STRING_DESCRIPTOR.equals(descriptor)
                    && bootstrapMethodArguments.length == 1
                    && bootstrapMethodArguments[0] instanceof String template) {
                if (PRE_DRAW_TEMPLATE.equals(template)) {
                    labelSites.add(new LabelSite(pos, true));
                } else if (POST_DRAW_TEMPLATE.equals(template)) {
                    labelSites.add(new LabelSite(pos, false));
                }
            }
            pos++;
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            events.add(new InsnEvent(pos, InsnEvent.KIND_JUMP, opcode, -1, null, null, null, label));
            if (opcode == Opcodes.IFEQ) {
                final InsnEvent p1 = recent(1);
                final InsnEvent p2 = recent(2);
                if (travStartPos < 0
                        && p2 != null
                        && p2.kind() == InsnEvent.KIND_VAR
                        && p2.opcode() == Opcodes.ALOAD
                        && p2.varIndex() == 0
                        && p1 != null
                        && p1.kind() == InsnEvent.KIND_FIELD
                        && p1.opcode() == Opcodes.GETFIELD
                        && target.renderSystemOwner().equals(p1.owner())
                        && FIELD_DEBUG_FLAG_D.equals(p1.name())
                        && BOOLEAN_DESCRIPTOR.equals(p1.desc())) {
                    travStartPos = p2.pos();
                    travElseLabel = label;
                } else if (travStartPos >= 0
                        && travEndPos < 0
                        && travEndLabel == null
                        && sawClearInTrav
                        && p1 != null
                        && p1.kind() == InsnEvent.KIND_METHOD
                        && ITERATOR_OWNER.equals(p1.owner())
                        && HAS_NEXT_METHOD.equals(p1.name())) {
                    travEndLabel = label;
                } else if (candSortStart >= 0
                        && sortStartPos < 0
                        && candSortEndLabel == null
                        && p1 != null
                        && p1.kind() == InsnEvent.KIND_METHOD
                        && ITERATOR_OWNER.equals(p1.owner())
                        && HAS_NEXT_METHOD.equals(p1.name())) {
                    candSortEndLabel = label;
                }
            } else if (opcode == Opcodes.GOTO) {
                for (final PendingDrawLoop loop : pendingDrawLoops) {
                    if (loop.backEdgePos < 0 && label == loop.loopStartLabel) {
                        loop.backEdgePos = pos;
                    }
                }
            }
            pos++;
        }

        @Override
        public void visitLdcInsn(final Object value) {
            events.add(new InsnEvent(pos, InsnEvent.KIND_OTHER, Opcodes.LDC, -1, null, null, null, null));
            pos++;
        }

        @Override
        public void visitIincInsn(final int varIndex, final int increment) {
            events.add(new InsnEvent(pos, InsnEvent.KIND_IINC, Opcodes.IINC, varIndex, null, null, null, null));
            pos++;
        }

        @Override
        public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {
            events.add(new InsnEvent(pos, InsnEvent.KIND_OTHER, Opcodes.TABLESWITCH, -1, null, null, null, null));
            pos++;
        }

        @Override
        public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
            events.add(new InsnEvent(pos, InsnEvent.KIND_OTHER, Opcodes.LOOKUPSWITCH, -1, null, null, null, null));
            pos++;
        }

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
            events.add(new InsnEvent(
                    pos, InsnEvent.KIND_OTHER, Opcodes.MULTIANEWARRAY, numDimensions, descriptor, null, null, null));
            pos++;
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            this.maxLocals = maxLocals;
        }

        RenderImplPlan buildPlan() {
            if (maxLocals < MIN_RENDER_IMPL_LOCALS) {
                throw new IllegalArgumentException(ERROR_LOCALS + maxLocals);
            }
            if (travStartPos < 0
                    || travEndPos <= travStartPos
                    || rootEntityLocal < 0
                    || !sawListOfInTrav
                    || !sawClearInTrav
                    || !sawTravBodyCall
                    || travElseLabel == null) {
                throw new IllegalArgumentException(ERROR_TRAV_ABSENT);
            }
            if (sortStartPos < 0 || sortEndPos <= sortStartPos || cameraLocal < 0) {
                throw new IllegalArgumentException(ERROR_SORT_ABSENT);
            }
            if (pendingDrawLoops.size() != EXPECTED_DRAW_LOOPS) {
                throw new IllegalArgumentException(ERROR_DRAW_COUNT + EXPECTED_DRAW_LOOPS);
            }
            final List<DrawLoopSpec> drawLoops = new ArrayList<>(EXPECTED_DRAW_LOOPS);
            for (final PendingDrawLoop loop : pendingDrawLoops) {
                if (loop.headerEndPos <= loop.headerStartPos
                        || loop.backEdgePos <= loop.headerEndPos
                        || loop.loopEndPos <= loop.backEdgePos
                        || loop.rendererLocal < 0
                        || loop.drawIdxLocal < 0) {
                    throw new IllegalArgumentException(ERROR_DRAW_BOUNDS);
                }
                drawLoops.add(new DrawLoopSpec(
                        loop.headerStartPos,
                        loop.headerEndPos,
                        loop.backEdgePos,
                        loop.loopEndPos,
                        loop.sortedListLocal,
                        loop.rendererLocal,
                        loop.drawIdxLocal));
            }
            final long preCount = labelSites.stream().filter(LabelSite::preDraw).count();
            final long postCount =
                    labelSites.stream().filter(site -> !site.preDraw()).count();
            if (labelSites.size() != EXPECTED_LABEL_SITES
                    || preCount != EXPECTED_DRAW_LOOPS
                    || postCount != EXPECTED_DRAW_LOOPS) {
                throw new IllegalArgumentException(ERROR_LABEL_COUNT + labelSites.size());
            }
            return new RenderImplPlan(
                    maxLocals,
                    travStartPos,
                    travEndPos,
                    rootEntityLocal,
                    sortStartPos,
                    sortEndPos,
                    cameraLocal,
                    List.copyOf(drawLoops),
                    List.copyOf(labelSites));
        }
    }

    private static final class RecursiveTraverseRewriter extends MethodVisitor {

        private final RenderPassTraversalTarget target;
        private final int[] appliedSites;

        RecursiveTraverseRewriter(
                final MethodVisitor downstream, final RenderPassTraversalTarget target, final int[] appliedSites) {
            super(Opcodes.ASM9, null);
            this.downstream = downstream;
            this.target = target;
            this.appliedSites = appliedSites;
        }

        private final MethodVisitor downstream;

        @Override
        public void visitCode() {
            downstream.visitCode();
            emitBody();
            appliedSites[0]++;
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            downstream.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            downstream.visitEnd();
        }

        private void emitBody() {
            final Label enabled = new Label();
            final Label ignoredTrue = new Label();
            final Label ignoredStore = new Label();
            final Label checkInGroup = new Label();
            final Label drawFalse = new Label();
            final Label drawStore = new Label();
            final Label checkGroup = new Label();
            final Label checkRenderer = new Label();
            final Label testInstanceOf = new Label();
            final Label storeNextInGroup = new Label();
            final Label traverseChildren = new Label();
            final Label loopCond = new Label();
            final Label doneReturn = new Label();

            downstream.visitVarInsn(Opcodes.ALOAD, TRAVERSE_PARAM_ENTITY);
            downstream.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITY_OWNER,
                    GET_ENABLED_METHOD,
                    "()" + BOOLEAN_DESCRIPTOR,
                    false);
            downstream.visitJumpInsn(Opcodes.IFNE, enabled);
            downstream.visitInsn(Opcodes.RETURN);

            downstream.visitLabel(enabled);
            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_PARAM_IGNORED);
            downstream.visitJumpInsn(Opcodes.IFNE, ignoredTrue);
            downstream.visitVarInsn(Opcodes.ALOAD, TRAVERSE_PARAM_ENTITY);
            downstream.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITY_OWNER,
                    GET_IGNORE_GROUP_METHOD,
                    "()" + BOOLEAN_DESCRIPTOR,
                    false);
            downstream.visitJumpInsn(Opcodes.IFNE, ignoredTrue);
            downstream.visitInsn(Opcodes.ICONST_0);
            downstream.visitJumpInsn(Opcodes.GOTO, ignoredStore);
            downstream.visitLabel(ignoredTrue);
            downstream.visitInsn(Opcodes.ICONST_1);
            downstream.visitLabel(ignoredStore);
            downstream.visitVarInsn(Opcodes.ISTORE, TRAVERSE_LOCAL_IGNORED);

            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_LOCAL_IGNORED);
            downstream.visitJumpInsn(Opcodes.IFEQ, checkInGroup);
            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_PARAM_EXPORT);
            downstream.visitJumpInsn(Opcodes.IFNE, drawFalse);
            downstream.visitInsn(Opcodes.ICONST_1);
            downstream.visitJumpInsn(Opcodes.GOTO, drawStore);
            downstream.visitLabel(checkInGroup);
            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_PARAM_IN_GROUP);
            downstream.visitJumpInsn(Opcodes.IFNE, drawFalse);
            downstream.visitInsn(Opcodes.ICONST_1);
            downstream.visitJumpInsn(Opcodes.GOTO, drawStore);
            downstream.visitLabel(drawFalse);
            downstream.visitInsn(Opcodes.ICONST_0);
            downstream.visitLabel(drawStore);
            downstream.visitVarInsn(Opcodes.ISTORE, TRAVERSE_LOCAL_SHOULD_DRAW);

            downstream.visitInsn(Opcodes.ACONST_NULL);
            downstream.visitVarInsn(Opcodes.ASTORE, TRAVERSE_LOCAL_RENDERER);

            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_LOCAL_SHOULD_DRAW);
            downstream.visitJumpInsn(Opcodes.IFEQ, checkGroup);
            downstream.visitVarInsn(Opcodes.ALOAD, TRAVERSE_PARAM_ENTITY);
            downstream.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITY_OWNER,
                    GET_RENDERER_METHOD,
                    GET_RENDERER_DESCRIPTOR,
                    false);
            downstream.visitVarInsn(Opcodes.ASTORE, TRAVERSE_LOCAL_RENDERER);
            downstream.visitVarInsn(Opcodes.ALOAD, TRAVERSE_LOCAL_RENDERER);
            downstream.visitJumpInsn(Opcodes.IFNULL, checkGroup);
            downstream.visitVarInsn(Opcodes.ALOAD, TRAVERSE_LOCAL_RENDERER);
            downstream.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.RENDERER_OWNER,
                    GET_ENABLED_METHOD,
                    "()" + BOOLEAN_DESCRIPTOR,
                    false);
            downstream.visitJumpInsn(Opcodes.IFEQ, checkGroup);
            downstream.visitVarInsn(Opcodes.ALOAD, TRAVERSE_PARAM_OUT);
            downstream.visitVarInsn(Opcodes.ALOAD, TRAVERSE_LOCAL_RENDERER);
            downstream.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, ARRAY_LIST_OWNER, COLLECTION_ADD_METHOD, COLLECTION_ADD_DESCRIPTOR, false);
            downstream.visitInsn(Opcodes.POP);

            downstream.visitLabel(checkGroup);
            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_PARAM_IN_GROUP);
            downstream.visitJumpInsn(Opcodes.IFEQ, checkRenderer);
            downstream.visitInsn(Opcodes.ICONST_1);
            downstream.visitJumpInsn(Opcodes.GOTO, storeNextInGroup);
            downstream.visitLabel(checkRenderer);
            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_LOCAL_SHOULD_DRAW);
            downstream.visitJumpInsn(Opcodes.IFNE, testInstanceOf);
            downstream.visitVarInsn(Opcodes.ALOAD, TRAVERSE_PARAM_ENTITY);
            downstream.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITY_OWNER,
                    GET_RENDERER_METHOD,
                    GET_RENDERER_DESCRIPTOR,
                    false);
            downstream.visitVarInsn(Opcodes.ASTORE, TRAVERSE_LOCAL_RENDERER);
            downstream.visitLabel(testInstanceOf);
            downstream.visitVarInsn(Opcodes.ALOAD, TRAVERSE_LOCAL_RENDERER);
            downstream.visitTypeInsn(Opcodes.INSTANCEOF, RenderPassTraversalTarget.GROUP_RENDERER_OWNER);
            downstream.visitLabel(storeNextInGroup);
            downstream.visitVarInsn(Opcodes.ISTORE, TRAVERSE_LOCAL_NEXT_IN_GROUP);

            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_LOCAL_NEXT_IN_GROUP);
            downstream.visitJumpInsn(Opcodes.IFEQ, traverseChildren);
            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_PARAM_EXPORT);
            downstream.visitJumpInsn(Opcodes.IFNE, doneReturn);

            downstream.visitLabel(traverseChildren);
            downstream.visitVarInsn(Opcodes.ALOAD, TRAVERSE_PARAM_ENTITY);
            downstream.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITY_OWNER,
                    GET_CHILDREN_METHOD,
                    GET_CHILDREN_DESCRIPTOR,
                    false);
            downstream.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITIES_OWNER,
                    GET_LIST_METHOD,
                    GET_LIST_DESCRIPTOR,
                    false);
            downstream.visitVarInsn(Opcodes.ASTORE, TRAVERSE_LOCAL_CHILDREN);
            downstream.visitVarInsn(Opcodes.ALOAD, TRAVERSE_LOCAL_CHILDREN);
            downstream.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_SIZE_METHOD, LIST_SIZE_DESCRIPTOR, true);
            downstream.visitVarInsn(Opcodes.ISTORE, TRAVERSE_LOCAL_COUNT);
            downstream.visitInsn(Opcodes.ICONST_0);
            downstream.visitVarInsn(Opcodes.ISTORE, TRAVERSE_LOCAL_INDEX);

            downstream.visitLabel(loopCond);
            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_LOCAL_INDEX);
            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_LOCAL_COUNT);
            downstream.visitJumpInsn(Opcodes.IF_ICMPGE, doneReturn);
            downstream.visitVarInsn(Opcodes.ALOAD, 0);
            downstream.visitVarInsn(Opcodes.ALOAD, TRAVERSE_LOCAL_CHILDREN);
            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_LOCAL_INDEX);
            downstream.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_GET_METHOD, LIST_GET_DESCRIPTOR, true);
            downstream.visitTypeInsn(Opcodes.CHECKCAST, RenderPassTraversalTarget.ENTITY_OWNER);
            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_LOCAL_NEXT_IN_GROUP);
            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_LOCAL_IGNORED);
            downstream.visitVarInsn(Opcodes.ILOAD, TRAVERSE_PARAM_EXPORT);
            downstream.visitVarInsn(Opcodes.ALOAD, TRAVERSE_PARAM_OUT);
            downstream.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    target.renderSystemOwner(),
                    RenderPassTraversalTarget.TRAVERSE_METHOD,
                    RenderPassTraversalTarget.TRAVERSE_DESCRIPTOR,
                    false);
            downstream.visitIincInsn(TRAVERSE_LOCAL_INDEX, 1);
            downstream.visitJumpInsn(Opcodes.GOTO, loopCond);

            downstream.visitLabel(doneReturn);
            downstream.visitInsn(Opcodes.RETURN);
        }
    }

    private static final class RenderImplRewriter extends MethodVisitor {

        private final RenderPassTraversalTarget target;
        private final RenderImplPlan plan;
        private final int[] appliedSites;

        private final int preLabelsLocal;
        private final int postLabelsLocal;
        private final int tempIntLocal;
        private final int loopIdxLocal;
        private final int loopLimitLocal;
        private final int stackLocal;
        private final int entityLocal;
        private final int rendererLocal;
        private final int childrenLocal;
        private final int childIdxLocal;

        private final Label[] newDrawLoopStarts;
        private final Label[] newDrawLoopEnds;
        private final Set<Label> visitedLabels = new HashSet<>();
        private final Map<Integer, Boolean> labelSiteMap = new HashMap<>();

        private int pos;
        private int skippingUntil = -1;

        RenderImplRewriter(
                final MethodVisitor downstream,
                final RenderPassTraversalTarget target,
                final RenderImplPlan plan,
                final int[] appliedSites) {
            super(Opcodes.ASM9, downstream);
            this.target = target;
            this.plan = plan;
            this.appliedSites = appliedSites;

            final int base = plan.maxLocals();
            preLabelsLocal = base;
            postLabelsLocal = base + 1;
            tempIntLocal = base + 2;
            loopIdxLocal = base + 3;
            loopLimitLocal = base + 4;
            stackLocal = base + 5;
            entityLocal = base + 6;
            rendererLocal = base + 7;
            childrenLocal = base + 8;
            childIdxLocal = base + 9;

            newDrawLoopStarts = new Label[plan.drawLoops().size()];
            newDrawLoopEnds = new Label[plan.drawLoops().size()];
            for (int i = 0; i < plan.drawLoops().size(); i++) {
                newDrawLoopStarts[i] = new Label();
                newDrawLoopEnds[i] = new Label();
            }
            for (final LabelSite site : plan.labelSites()) {
                labelSiteMap.put(site.pos(), site.preDraw());
            }
        }

        private boolean skipping() {
            return skippingUntil >= 0 && pos < skippingUntil;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitLabelPrologue();
        }

        @Override
        public void visitLabel(final Label label) {
            if (pos == skippingUntil) {
                skippingUntil = -1;
            }
            if (!skipping()) {
                for (int i = 0; i < plan.drawLoops().size(); i++) {
                    if (pos == plan.drawLoops().get(i).loopEndPos()) {
                        super.visitLabel(newDrawLoopEnds[i]);
                    }
                }
                super.visitLabel(label);
                visitedLabels.add(label);
            }
            pos++;
        }

        @Override
        public void visitLineNumber(final int line, final Label start) {
            if (!skipping() && visitedLabels.contains(start)) {
                super.visitLineNumber(line, start);
            }
        }

        @Override
        public void visitLocalVariable(
                final String name,
                final String descriptor,
                final String signature,
                final Label start,
                final Label end,
                final int index) {
            if (visitedLabels.contains(start) && visitedLabels.contains(end)) {
                super.visitLocalVariable(name, descriptor, signature, start, end, index);
            }
        }

        @Override
        public void visitInsn(final int opcode) {
            if (!skipping()) {
                super.visitInsn(opcode);
            }
            pos++;
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            if (!skipping()) {
                super.visitIntInsn(opcode, operand);
            }
            pos++;
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (pos == plan.travStartPos()) {
                emitTraversalReplacement();
                skippingUntil = plan.travEndPos();
                pos++;
                return;
            }
            if (pos == plan.sortStartPos()) {
                emitSortPrepReplacement();
                skippingUntil = plan.sortEndPos();
                pos++;
                return;
            }
            for (int i = 0; i < plan.drawLoops().size(); i++) {
                final DrawLoopSpec loop = plan.drawLoops().get(i);
                if (pos == loop.headerStartPos()) {
                    emitDrawLoopHeader(loop, newDrawLoopStarts[i], newDrawLoopEnds[i]);
                    skippingUntil = loop.headerEndPos() + 1;
                    pos++;
                    return;
                }
            }
            if (!skipping()) {
                super.visitVarInsn(opcode, varIndex);
            }
            pos++;
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            if (!skipping()) {
                super.visitTypeInsn(opcode, type);
            }
            pos++;
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            if (!skipping()) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }
            pos++;
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface) {
            if (!skipping()) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
            pos++;
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name,
                final String descriptor,
                final Handle bootstrapMethodHandle,
                final Object... bootstrapMethodArguments) {
            final Boolean preDraw = labelSiteMap.get(pos);
            if (!skipping() && preDraw != null) {
                emitCachedLabelLookup(preDraw, name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
                appliedSites[0]++;
                pos++;
                return;
            }
            if (!skipping()) {
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            }
            pos++;
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            for (int i = 0; i < plan.drawLoops().size(); i++) {
                final DrawLoopSpec loop = plan.drawLoops().get(i);
                if (pos == loop.backEdgePos()) {
                    super.visitIincInsn(loop.drawIdxLocal(), 1);
                    super.visitJumpInsn(Opcodes.GOTO, newDrawLoopStarts[i]);
                    appliedSites[0]++;
                    pos++;
                    return;
                }
            }
            if (!skipping()) {
                super.visitJumpInsn(opcode, label);
            }
            pos++;
        }

        @Override
        public void visitLdcInsn(final Object value) {
            if (!skipping()) {
                super.visitLdcInsn(value);
            }
            pos++;
        }

        @Override
        public void visitIincInsn(final int varIndex, final int increment) {
            if (!skipping()) {
                super.visitIincInsn(varIndex, increment);
            }
            pos++;
        }

        @Override
        public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {
            if (!skipping()) {
                super.visitTableSwitchInsn(min, max, dflt, labels);
            }
            pos++;
        }

        @Override
        public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
            if (!skipping()) {
                super.visitLookupSwitchInsn(dflt, keys, labels);
            }
            pos++;
        }

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
            if (!skipping()) {
                super.visitMultiANewArrayInsn(descriptor, numDimensions);
            }
            pos++;
        }

        @Override
        public void visitFrame(
                final int type, final int numLocal, final Object[] local, final int numStack, final Object[] stack) {
            // ClassWriter recomputes all frames via COMPUTE_FRAMES.
        }

        private void emitLabelPrologue() {
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label tryCatch = new Label();
            final Label skipPre = new Label();
            final Label checkPost = new Label();
            final Label skipPost = new Label();
            final Label afterPrologue = new Label();

            super.visitTryCatchBlock(tryStart, tryEnd, tryCatch, THROWABLE_OWNER);
            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitVarInsn(Opcodes.ASTORE, preLabelsLocal);
            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitVarInsn(Opcodes.ASTORE, postLabelsLocal);

            super.visitLabel(tryStart);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, SYSTEM_OWNER, GET_PROPERTIES_METHOD, GET_PROPERTIES_DESCRIPTOR, false);
            super.visitInsn(Opcodes.DUP);
            super.visitLdcInsn(RenderPassTraversalBridge.PRE_DRAW_LABELS_PROPERTY);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, PROPERTIES_OWNER, PROPERTIES_GET_METHOD, PROPERTIES_GET_DESCRIPTOR, false);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, STRING_ARRAY_DESCRIPTOR);
            super.visitJumpInsn(Opcodes.IFEQ, skipPre);
            super.visitTypeInsn(Opcodes.CHECKCAST, STRING_ARRAY_DESCRIPTOR);
            super.visitVarInsn(Opcodes.ASTORE, preLabelsLocal);
            super.visitJumpInsn(Opcodes.GOTO, checkPost);

            super.visitLabel(skipPre);
            super.visitInsn(Opcodes.POP);

            super.visitLabel(checkPost);
            super.visitLdcInsn(RenderPassTraversalBridge.POST_DRAW_LABELS_PROPERTY);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, PROPERTIES_OWNER, PROPERTIES_GET_METHOD, PROPERTIES_GET_DESCRIPTOR, false);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, STRING_ARRAY_DESCRIPTOR);
            super.visitJumpInsn(Opcodes.IFEQ, skipPost);
            super.visitTypeInsn(Opcodes.CHECKCAST, STRING_ARRAY_DESCRIPTOR);
            super.visitVarInsn(Opcodes.ASTORE, postLabelsLocal);
            super.visitLabel(tryEnd);
            super.visitJumpInsn(Opcodes.GOTO, afterPrologue);

            super.visitLabel(skipPost);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, afterPrologue);

            super.visitLabel(tryCatch);
            super.visitInsn(Opcodes.POP);

            super.visitLabel(afterPrologue);
        }

        private void emitTraversalReplacement() {
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(
                    Opcodes.GETFIELD, target.renderSystemOwner(), FIELD_RENDERERS_H, CARRAY_LIST_DESCRIPTOR);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.CARRAY_LIST_OWNER,
                    CLEAR_METHOD,
                    VOID_DESCRIPTOR,
                    false);
            if (target.hasRecursiveTraverseMethod()) {
                emitRecursiveTraverse();
            } else {
                emitStackTraverse();
            }
            appliedSites[0]++;
        }

        private void emitRecursiveTraverse() {
            final Label childrenBranch = new Label();
            final Label loopCond = new Label();
            final Label done = new Label();

            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, target.renderSystemOwner(), FIELD_DEBUG_FLAG_D, BOOLEAN_DESCRIPTOR);
            super.visitJumpInsn(Opcodes.IFEQ, childrenBranch);

            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    target.renderSystemOwner(),
                    ROOT_ENTITY_GETTER_METHOD,
                    ROOT_ENTITY_GETTER_DESCRIPTOR,
                    false);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitVarInsn(Opcodes.ILOAD, RENDER_IMPL_PARAM_EXPORT_53);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(
                    Opcodes.GETFIELD, target.renderSystemOwner(), FIELD_RENDERERS_H, CARRAY_LIST_DESCRIPTOR);
            super.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    target.renderSystemOwner(),
                    RenderPassTraversalTarget.TRAVERSE_METHOD,
                    RenderPassTraversalTarget.TRAVERSE_DESCRIPTOR,
                    false);
            super.visitJumpInsn(Opcodes.GOTO, done);

            super.visitLabel(childrenBranch);
            super.visitVarInsn(Opcodes.ALOAD, plan.rootEntityLocal());
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITY_OWNER,
                    GET_CHILDREN_METHOD,
                    GET_CHILDREN_DESCRIPTOR,
                    false);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITIES_OWNER,
                    GET_LIST_METHOD,
                    GET_LIST_DESCRIPTOR,
                    false);
            super.visitVarInsn(Opcodes.ASTORE, childrenLocal);
            super.visitVarInsn(Opcodes.ALOAD, childrenLocal);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_SIZE_METHOD, LIST_SIZE_DESCRIPTOR, true);
            super.visitVarInsn(Opcodes.ISTORE, loopLimitLocal);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitVarInsn(Opcodes.ISTORE, loopIdxLocal);

            super.visitLabel(loopCond);
            super.visitVarInsn(Opcodes.ILOAD, loopIdxLocal);
            super.visitVarInsn(Opcodes.ILOAD, loopLimitLocal);
            super.visitJumpInsn(Opcodes.IF_ICMPGE, done);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ALOAD, childrenLocal);
            super.visitVarInsn(Opcodes.ILOAD, loopIdxLocal);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_GET_METHOD, LIST_GET_DESCRIPTOR, true);
            super.visitTypeInsn(Opcodes.CHECKCAST, RenderPassTraversalTarget.ENTITY_OWNER);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitVarInsn(Opcodes.ILOAD, RENDER_IMPL_PARAM_EXPORT_53);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(
                    Opcodes.GETFIELD, target.renderSystemOwner(), FIELD_RENDERERS_H, CARRAY_LIST_DESCRIPTOR);
            super.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    target.renderSystemOwner(),
                    RenderPassTraversalTarget.TRAVERSE_METHOD,
                    RenderPassTraversalTarget.TRAVERSE_DESCRIPTOR,
                    false);
            super.visitIincInsn(loopIdxLocal, 1);
            super.visitJumpInsn(Opcodes.GOTO, loopCond);

            super.visitLabel(done);
        }

        private void emitStackTraverse() {
            final Label pushChildrenRoots = new Label();
            final Label rootPushLoop = new Label();
            final Label dfsLoop = new Label();
            final Label checkGroupChildren = new Label();
            final Label pushSubChildren = new Label();
            final Label childPushLoop = new Label();
            final Label done = new Label();

            super.visitTypeInsn(Opcodes.NEW, ARRAY_LIST_OWNER);
            super.visitInsn(Opcodes.DUP);
            super.visitIntInsn(Opcodes.BIPUSH, INIT_STACK_CAPACITY);
            super.visitMethodInsn(
                    Opcodes.INVOKESPECIAL, ARRAY_LIST_OWNER, INIT_METHOD, ARRAY_LIST_INIT_CAPACITY_DESCRIPTOR, false);
            super.visitVarInsn(Opcodes.ASTORE, stackLocal);

            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, target.renderSystemOwner(), FIELD_DEBUG_FLAG_D, BOOLEAN_DESCRIPTOR);
            super.visitJumpInsn(Opcodes.IFEQ, pushChildrenRoots);

            super.visitVarInsn(Opcodes.ALOAD, stackLocal);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    target.renderSystemOwner(),
                    ROOT_ENTITY_GETTER_METHOD,
                    ROOT_ENTITY_GETTER_DESCRIPTOR,
                    false);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, ARRAY_LIST_OWNER, COLLECTION_ADD_METHOD, COLLECTION_ADD_DESCRIPTOR, false);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, dfsLoop);

            super.visitLabel(pushChildrenRoots);
            super.visitVarInsn(Opcodes.ALOAD, plan.rootEntityLocal());
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITY_OWNER,
                    GET_CHILDREN_METHOD,
                    GET_CHILDREN_DESCRIPTOR,
                    false);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITIES_OWNER,
                    GET_LIST_METHOD,
                    GET_LIST_DESCRIPTOR,
                    false);
            super.visitVarInsn(Opcodes.ASTORE, childrenLocal);
            super.visitVarInsn(Opcodes.ALOAD, childrenLocal);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_SIZE_METHOD, LIST_SIZE_DESCRIPTOR, true);
            super.visitInsn(Opcodes.ICONST_1);
            super.visitInsn(Opcodes.ISUB);
            super.visitVarInsn(Opcodes.ISTORE, childIdxLocal);

            super.visitLabel(rootPushLoop);
            super.visitVarInsn(Opcodes.ILOAD, childIdxLocal);
            super.visitJumpInsn(Opcodes.IFLT, dfsLoop);
            super.visitVarInsn(Opcodes.ALOAD, stackLocal);
            super.visitVarInsn(Opcodes.ALOAD, childrenLocal);
            super.visitVarInsn(Opcodes.ILOAD, childIdxLocal);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_GET_METHOD, LIST_GET_DESCRIPTOR, true);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, ARRAY_LIST_OWNER, COLLECTION_ADD_METHOD, COLLECTION_ADD_DESCRIPTOR, false);
            super.visitInsn(Opcodes.POP);
            super.visitIincInsn(childIdxLocal, -1);
            super.visitJumpInsn(Opcodes.GOTO, rootPushLoop);

            super.visitLabel(dfsLoop);
            super.visitVarInsn(Opcodes.ALOAD, stackLocal);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, ARRAY_LIST_OWNER, LIST_IS_EMPTY_METHOD, LIST_IS_EMPTY_DESCRIPTOR, false);
            super.visitJumpInsn(Opcodes.IFNE, done);
            super.visitVarInsn(Opcodes.ALOAD, stackLocal);
            super.visitVarInsn(Opcodes.ALOAD, stackLocal);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, ARRAY_LIST_OWNER, LIST_SIZE_METHOD, LIST_SIZE_DESCRIPTOR, false);
            super.visitInsn(Opcodes.ICONST_1);
            super.visitInsn(Opcodes.ISUB);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, ARRAY_LIST_OWNER, LIST_REMOVE_METHOD, LIST_REMOVE_DESCRIPTOR, false);
            super.visitTypeInsn(Opcodes.CHECKCAST, RenderPassTraversalTarget.ENTITY_OWNER);
            super.visitVarInsn(Opcodes.ASTORE, entityLocal);

            super.visitVarInsn(Opcodes.ALOAD, entityLocal);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITY_OWNER,
                    GET_ENABLED_METHOD,
                    "()" + BOOLEAN_DESCRIPTOR,
                    false);
            super.visitJumpInsn(Opcodes.IFEQ, dfsLoop);

            super.visitVarInsn(Opcodes.ALOAD, entityLocal);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITY_OWNER,
                    GET_RENDERER_METHOD,
                    GET_RENDERER_DESCRIPTOR,
                    false);
            super.visitVarInsn(Opcodes.ASTORE, rendererLocal);
            super.visitVarInsn(Opcodes.ALOAD, rendererLocal);
            super.visitJumpInsn(Opcodes.IFNULL, checkGroupChildren);
            super.visitVarInsn(Opcodes.ALOAD, rendererLocal);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.RENDERER_OWNER,
                    GET_ENABLED_METHOD,
                    "()" + BOOLEAN_DESCRIPTOR,
                    false);
            super.visitJumpInsn(Opcodes.IFEQ, checkGroupChildren);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(
                    Opcodes.GETFIELD, target.renderSystemOwner(), FIELD_RENDERERS_H, CARRAY_LIST_DESCRIPTOR);
            super.visitVarInsn(Opcodes.ALOAD, rendererLocal);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.CARRAY_LIST_OWNER,
                    COLLECTION_ADD_METHOD,
                    COLLECTION_ADD_DESCRIPTOR,
                    false);
            super.visitInsn(Opcodes.POP);

            super.visitLabel(checkGroupChildren);
            super.visitVarInsn(Opcodes.ALOAD, rendererLocal);
            super.visitTypeInsn(Opcodes.INSTANCEOF, RenderPassTraversalTarget.GROUP_RENDERER_OWNER);
            super.visitJumpInsn(Opcodes.IFEQ, pushSubChildren);
            super.visitVarInsn(Opcodes.ALOAD, rendererLocal);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.RENDERER_OWNER,
                    GET_ENABLED_METHOD,
                    "()" + BOOLEAN_DESCRIPTOR,
                    false);
            super.visitJumpInsn(Opcodes.IFNE, dfsLoop);

            super.visitLabel(pushSubChildren);
            super.visitVarInsn(Opcodes.ALOAD, entityLocal);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITY_OWNER,
                    GET_CHILDREN_METHOD,
                    GET_CHILDREN_DESCRIPTOR,
                    false);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.ENTITIES_OWNER,
                    GET_LIST_METHOD,
                    GET_LIST_DESCRIPTOR,
                    false);
            super.visitVarInsn(Opcodes.ASTORE, childrenLocal);
            super.visitVarInsn(Opcodes.ALOAD, childrenLocal);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_SIZE_METHOD, LIST_SIZE_DESCRIPTOR, true);
            super.visitInsn(Opcodes.ICONST_1);
            super.visitInsn(Opcodes.ISUB);
            super.visitVarInsn(Opcodes.ISTORE, childIdxLocal);

            super.visitLabel(childPushLoop);
            super.visitVarInsn(Opcodes.ILOAD, childIdxLocal);
            super.visitJumpInsn(Opcodes.IFLT, dfsLoop);
            super.visitVarInsn(Opcodes.ALOAD, stackLocal);
            super.visitVarInsn(Opcodes.ALOAD, childrenLocal);
            super.visitVarInsn(Opcodes.ILOAD, childIdxLocal);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_GET_METHOD, LIST_GET_DESCRIPTOR, true);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, ARRAY_LIST_OWNER, COLLECTION_ADD_METHOD, COLLECTION_ADD_DESCRIPTOR, false);
            super.visitInsn(Opcodes.POP);
            super.visitIincInsn(childIdxLocal, -1);
            super.visitJumpInsn(Opcodes.GOTO, childPushLoop);

            super.visitLabel(done);
        }

        private void emitSortPrepReplacement() {
            final Label loopCond = new Label();
            final Label done = new Label();

            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(
                    Opcodes.GETFIELD, target.renderSystemOwner(), FIELD_RENDERERS_H, CARRAY_LIST_DESCRIPTOR);
            super.visitVarInsn(Opcodes.ASTORE, childrenLocal);
            super.visitVarInsn(Opcodes.ALOAD, childrenLocal);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.CARRAY_LIST_OWNER,
                    LIST_SIZE_METHOD,
                    LIST_SIZE_DESCRIPTOR,
                    false);
            super.visitVarInsn(Opcodes.ISTORE, loopLimitLocal);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitVarInsn(Opcodes.ISTORE, loopIdxLocal);

            super.visitLabel(loopCond);
            super.visitVarInsn(Opcodes.ILOAD, loopIdxLocal);
            super.visitVarInsn(Opcodes.ILOAD, loopLimitLocal);
            super.visitJumpInsn(Opcodes.IF_ICMPGE, done);
            super.visitVarInsn(Opcodes.ALOAD, childrenLocal);
            super.visitVarInsn(Opcodes.ILOAD, loopIdxLocal);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.CARRAY_LIST_OWNER,
                    LIST_GET_METHOD,
                    LIST_GET_DESCRIPTOR,
                    false);
            super.visitTypeInsn(Opcodes.CHECKCAST, RenderPassTraversalTarget.RENDERER_OWNER);
            super.visitVarInsn(Opcodes.ALOAD, plan.cameraLocal());
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    RenderPassTraversalTarget.RENDERER_OWNER,
                    GET_SORTING_INFO_METHOD,
                    GET_SORTING_INFO_DESCRIPTOR,
                    false);
            super.visitInsn(Opcodes.POP);
            super.visitIincInsn(loopIdxLocal, 1);
            super.visitJumpInsn(Opcodes.GOTO, loopCond);

            super.visitLabel(done);
            appliedSites[0]++;
        }

        private void emitDrawLoopHeader(final DrawLoopSpec loop, final Label loopStart, final Label loopEnd) {
            super.visitInsn(Opcodes.ICONST_0);
            super.visitVarInsn(Opcodes.ISTORE, loop.drawIdxLocal());
            super.visitVarInsn(Opcodes.ALOAD, loop.sortedListLocal());
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_SIZE_METHOD, LIST_SIZE_DESCRIPTOR, true);
            super.visitVarInsn(Opcodes.ISTORE, loopLimitLocal);

            super.visitLabel(loopStart);
            super.visitVarInsn(Opcodes.ILOAD, loop.drawIdxLocal());
            super.visitVarInsn(Opcodes.ILOAD, loopLimitLocal);
            super.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);
            super.visitVarInsn(Opcodes.ALOAD, loop.sortedListLocal());
            super.visitVarInsn(Opcodes.ILOAD, loop.drawIdxLocal());
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_GET_METHOD, LIST_GET_DESCRIPTOR, true);
            super.visitTypeInsn(Opcodes.CHECKCAST, RenderPassTraversalTarget.RENDERER_OWNER);
            super.visitVarInsn(Opcodes.ASTORE, loop.rendererLocal());
        }

        private void emitCachedLabelLookup(
                final boolean preDraw,
                final String name,
                final String descriptor,
                final Handle bootstrapMethodHandle,
                final Object[] bootstrapMethodArguments) {
            final int labelsLocal = preDraw ? preLabelsLocal : postLabelsLocal;
            final Label fallback = new Label();
            final Label done = new Label();

            super.visitVarInsn(Opcodes.ISTORE, tempIntLocal);
            super.visitVarInsn(Opcodes.ALOAD, labelsLocal);
            super.visitJumpInsn(Opcodes.IFNULL, fallback);
            super.visitVarInsn(Opcodes.ILOAD, tempIntLocal);
            super.visitJumpInsn(Opcodes.IFLT, fallback);
            super.visitVarInsn(Opcodes.ILOAD, tempIntLocal);
            super.visitVarInsn(Opcodes.ALOAD, labelsLocal);
            super.visitInsn(Opcodes.ARRAYLENGTH);
            super.visitJumpInsn(Opcodes.IF_ICMPGE, fallback);
            super.visitVarInsn(Opcodes.ALOAD, labelsLocal);
            super.visitVarInsn(Opcodes.ILOAD, tempIntLocal);
            super.visitInsn(Opcodes.AALOAD);
            super.visitJumpInsn(Opcodes.GOTO, done);

            super.visitLabel(fallback);
            super.visitVarInsn(Opcodes.ILOAD, tempIntLocal);
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);

            super.visitLabel(done);
        }
    }
}
