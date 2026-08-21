package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the injected bytecode by loading and running it. Scanning the transformed
 * class for a bridge call would not prove the stack manipulation is correct; only the
 * verifier and an actual invocation do.
 */
final class MeshMirrorLinkedDeletionInjectionTest {

    private static final String POINT_FIXTURE = "fixture/PointDeleteAction";
    private static final String EDGE_FIXTURE = "fixture/EdgeDeleteAction";
    private static final String ERASER_FIXTURE = "fixture/EraserDeleteAction";
    private static final String RECORDER = "dev/turboism/adapter/cubism/mesh/MeshMirrorLinkedDeletionInjectionTest$Recorder";
    private static final String EDIT_MODE = RECORDER + "$EditMode";
    private static final String MESH = RECORDER + "$Mesh";
    private static final String REAL_POINT_ACTION =
        "com/live2d/cubism/view/context/action/action_meshEditor/d$g";
    private static final String REAL_EDGE_ACTION =
        "com/live2d/cubism/view/context/action/action_meshEditor/d$f";

    @AfterEach
    void reset() {
        NativeMeshMirrorBridge.participation().resetSession();
        NativeMeshMirrorBridge.counterparts().resetSession();
        NativeMeshMirrorBridge.uninstall();
        Recorder.reset();
    }

    @Test
    void pointDeletionKeepsItsOwnOperandsAndStillReachesTheBridge() throws Exception {
        final List<String> diagnostics = installBridge();
        final Object action = transformAndInstantiate(POINT_FIXTURE, pointFixture());

        action.getClass().getMethod("b", Object.class).invoke(action, new Object());

        // The host's own deletion must be untouched by the duplicated operands.
        assertEquals(List.of(Recorder.SOURCES), Recorder.deletedSources);
        assertEquals(List.of(Recorder.UNDO), Recorder.deletedUndo);
        // ...and the bridge must have been entered with those same non-null operands.
        assertTrue(diagnostics.contains(
            "MESH_MIRROR_DIAG stage=PARTICIPATION_SKIPPED reason=NO_PARTICIPANT"
        ));
    }

    @Test
    void edgeDeletionCapturesTheUndoGroupAndStillRemovesTheOriginalEdge() throws Exception {
        final List<String> diagnostics = installBridge();
        final Object action = transformAndInstantiate(EDGE_FIXTURE, edgeFixture());

        action.getClass().getMethod("b", Object.class).invoke(action, new Object());

        assertEquals(List.of(Recorder.EDGE), Recorder.removedEdges);
        assertTrue(diagnostics.contains(
            "MESH_MIRROR_DIAG stage=EDGE_DELETE_ACTION_ENTER"
        ));
        assertTrue(diagnostics.contains(
            "MESH_MIRROR_DIAG stage=PARTICIPATION_SKIPPED reason=NO_PARTICIPANT"
        ));
    }

    @Test
    void eraserDeletionKeepsTheSourceOperandsAndPassesTheOuterUndo() throws Exception {
        final List<String> diagnostics = installBridge();
        final Object action = transformAndInstantiate(ERASER_FIXTURE, eraserFixture());

        action.getClass().getMethod("erase", Object.class, Object.class, Object.class)
            .invoke(action, new Object(), new Object(), Recorder.UNDO);

        assertEquals(List.of(Recorder.ERASER_EDGES), Recorder.removedEdgeLists);
        assertEquals(List.of(Recorder.ERASER_EDGES), Recorder.removedPointLists);
        assertTrue(diagnostics.contains(
            "MESH_MIRROR_DIAG stage=ERASER_DELETE_ACTION_ENTER"
        ));
        assertTrue(diagnostics.contains(
            "MESH_MIRROR_DIAG stage=PARTICIPATION_SKIPPED reason=NO_PARTICIPANT"
        ));
    }

    @Test
    void hostsThatAlreadyDeleteMirrorCounterpartsAreNotTransformed() {
        final MeshMirrorNativeMethodTransformer transformer = new MeshMirrorNativeMethodTransformer(
            MeshMirrorHostProfile.reviewed52And53(), null
        );

        // 5.3.02 carries no linked-deletion selectors, so the action class is not even a target.
        assertEquals(null, transformer.transform(
            null, null, POINT_FIXTURE, null, null, pointFixture()
        ));
    }

    /** The backport is selected by artifact identity, never by anything softer. */
    @Test
    void linkedDeletionSelectorsAreAdmittedOnlyForTheExact5203Artifact() {
        assertTrue(MeshMirrorHostProfile.forArtifact(digest(
            40_805_584L, "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"
        )).orElseThrow().linkedDeletion() != null);

        assertEquals(null, MeshMirrorHostProfile.forArtifact(digest(
            41_922_739L, "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"
        )).orElseThrow().linkedDeletion());

        // Right size, wrong hash, and vice versa: neither is admitted at all.
        assertTrue(MeshMirrorHostProfile.forArtifact(digest(
            40_805_584L, "0000000000000000000000000000000000000000000000000000000000000000"
        )).isEmpty());
        assertTrue(MeshMirrorHostProfile.forArtifact(digest(
            1L, "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"
        )).isEmpty());
    }

    /**
     * On the host that already deletes mirror counterparts, the real action classes must not
     * even be targets, so there is no path by which the behaviour could be applied twice.
     */
    @Test
    void theRealActionClassesAreNotTargetsOnTheNativeHost() {
        final MeshMirrorNativeMethodTransformer transformer = new MeshMirrorNativeMethodTransformer(
            MeshMirrorHostProfile.reviewed52And53(), null
        );

        for (String owner : List.of(REAL_POINT_ACTION, REAL_EDGE_ACTION)) {
            assertEquals(null, transformer.transform(null, null, owner, null, null, stub(owner)));
        }
        assertEquals(MeshMirrorNativeMethodTransformer.Outcome.NONE, transformer.outcome());
    }

    /** On the backported host the same classes are owned, so they are inspected. */
    @Test
    void theRealActionClassesAreTargetsOnTheBackportedHost() {
        for (String owner : List.of(REAL_POINT_ACTION, REAL_EDGE_ACTION)) {
            final MeshMirrorNativeMethodTransformer transformer =
                new MeshMirrorNativeMethodTransformer(MeshMirrorHostProfile.reviewed52(), null);
            transformer.transform(null, null, owner, null, null, stub(owner));
            assertEquals(
                MeshMirrorNativeMethodTransformer.Outcome.TARGET_UNCHANGED,
                transformer.outcome(),
                owner + " must be inspected on the backported host"
            );
        }
    }

    /**
     * If the host moved the deletion call we intercept, matching the enclosing method alone
     * would produce a rewrite that does nothing while reporting success. That must fail closed.
     */
    @Test
    void refusesToRewriteAnActionWhoseDeletionCallIsAbsent() {
        final ClassWriter writer = newClass(POINT_FIXTURE);
        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "b", "(Ljava/lang/Object;)V", null, null
        );
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();

        final MeshMirrorNativeMethodTransformer transformer =
            new MeshMirrorNativeMethodTransformer(profile(), null);

        assertEquals(null, transformer.transform(
            null, null, POINT_FIXTURE, null, null, writer.toByteArray()
        ));
        assertEquals(
            MeshMirrorNativeMethodTransformer.Outcome.LINKED_DELETION_NOT_INJECTED,
            transformer.outcome()
        );
    }

    private static dev.turboism.mapping.verification.HostArtifactDigest digest(
        final long size,
        final String hash
    ) {
        return new dev.turboism.mapping.verification.HostArtifactDigest(size, hash);
    }

    /** A well-formed class with the given name and no method the profile selects. */
    private static byte[] stub(final String owner) {
        final ClassWriter writer = newClass(owner);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> installBridge() {
        final List<String> diagnostics = new ArrayList<>();
        final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
        ui.contributeMirrorAxisAngleControl(new MeshEditUiService.MirrorAxisAngleControl(
            "mesh.mirror-axis.angle", "Angle", "Reset", -180.0f, 180.0f, 0.1f, ignored -> { }
        ));
        NativeMeshMirrorBridge.install(new RuntimeMeshMirrorAxisService(), ui);
        NativeMeshMirrorBridge.mirrorForTesting(new AlwaysEnabledMirror());
        NativeMeshMirrorBridge.diagnostics(diagnostics::add);
        return diagnostics;
    }

    private static Object transformAndInstantiate(final String owner, final byte[] original)
        throws Exception {
        final MeshMirrorNativeMethodTransformer transformer =
            new MeshMirrorNativeMethodTransformer(profile(), null);
        final byte[] transformed = transformer.transform(null, null, owner, null, null, original);
        assertTrue(transformed != null, "the action class must be transformed");
        final ClassLoader loader = new ClassLoader(MeshMirrorLinkedDeletionInjectionTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                if (!name.equals(owner.replace('/', '.'))) return super.findClass(name);
                return defineClass(name, transformed, 0, transformed.length);
            }
        };
        return loader.loadClass(owner.replace('/', '.')).getDeclaredConstructor().newInstance();
    }

    /** Reuses the reviewed 5.2 shape but points the linked-deletion selectors at the fixtures. */
    private static MeshMirrorHostProfile profile() {
        final MeshMirrorHostProfile shared = MeshMirrorHostProfile.reviewed52And53();
        return new MeshMirrorHostProfile(
            shared.meshEditorOwner(), shared.mirrorPointMethod(), shared.mirrorAxisPointMethod(),
            shared.mirrorPointDescriptor(), shared.mirrorHitMethod(), shared.mirrorHitDescriptor(),
            shared.mirrorWidgetOwner(), shared.mirrorWidgetMethod(), shared.mirrorWidgetDescriptor(),
            shared.mirrorAxisDrawOwner(), shared.mirrorAxisDrawMethod(), shared.mirrorAxisDrawDescriptor(),
            null,
            null,
            new MeshMirrorHostProfile.LinkedDeletion(
                POINT_FIXTURE, "b", "(Ljava/lang/Object;)V",
                EDIT_MODE, "delete_exe", "(Ljava/util/List;Ljava/lang/Object;)V",
                EDGE_FIXTURE, "b", "(Ljava/lang/Object;)V",
                RECORDER, "beginUndo", "(Ljava/lang/String;)Ljava/lang/Object;",
                MESH, "removeEdge", "(Ljava/lang/Object;)V",
                ERASER_FIXTURE, "erase",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                RECORDER + "$Handler", "removeEdges",
                "(Ljava/util/List;)Ljava/lang/Object;",
                RECORDER + "$Handler", "removePoints",
                "(Ljava/util/List;Z)Ljava/lang/Object;"
            )
        );
    }

    /** {@code void b(Object pack) { Recorder.editMode().delete_exe(Recorder.sources(), Recorder.undo()); } } */
    private static byte[] pointFixture() {
        final ClassWriter writer = newClass(POINT_FIXTURE);
        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "b", "(Ljava/lang/Object;)V", null, null
        );
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "editMode", "()L" + EDIT_MODE + ";", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "sources", "()Ljava/util/List;", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "undo", "()Ljava/lang/Object;", false);
        method.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, EDIT_MODE, "delete_exe", "(Ljava/util/List;Ljava/lang/Object;)V", false
        );
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * {@code void erase(Object pack, Object event, Object outerUndo) {
     * Recorder.outerUndo(Recorder.handler().removeEdges(Recorder.eraserEdges()));
     * }}
     */
    private static byte[] eraserFixture() {
        final ClassWriter writer = newClass(ERASER_FIXTURE);
        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "erase",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
            null,
            null
        );
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            RECORDER,
            "handler",
            "()L" + RECORDER + "$Handler;",
            false
        );
        method.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            RECORDER,
            "eraserEdges",
            "()Ljava/util/List;",
            false
        );
        method.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            RECORDER + "$Handler",
            "removeEdges",
            "(Ljava/util/List;)Ljava/lang/Object;",
            false
        );
        method.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            RECORDER,
            "attachUndo",
            "(Ljava/lang/Object;Ljava/lang/Object;)V",
            false
        );
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            RECORDER,
            "handler",
            "()L" + RECORDER + "$Handler;",
            false
        );
        method.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            RECORDER,
            "eraserEdges",
            "()Ljava/util/List;",
            false
        );
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            RECORDER + "$Handler",
            "removePoints",
            "(Ljava/util/List;Z)Ljava/lang/Object;",
            false
        );
        method.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            RECORDER,
            "attachUndo",
            "(Ljava/lang/Object;Ljava/lang/Object;)V",
            false
        );
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code void b(Object pack) { Recorder.beginUndo("x"); Recorder.mesh().removeEdge(Recorder.edge()); } } */
    private static byte[] edgeFixture() {
        final ClassWriter writer = newClass(EDGE_FIXTURE);
        final MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "b", "(Ljava/lang/Object;)V", null, null
        );
        method.visitCode();
        method.visitLdcInsn("label");
        method.visitMethodInsn(
            Opcodes.INVOKESTATIC, RECORDER, "beginUndo", "(Ljava/lang/String;)Ljava/lang/Object;", false
        );
        method.visitInsn(Opcodes.POP);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "mesh", "()L" + MESH + ";", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "edge", "()Ljava/lang/Object;", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MESH, "removeEdge", "(Ljava/lang/Object;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter newClass(final String owner) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        return writer;
    }

    public static final class AlwaysEnabledMirror {
        public boolean a() {
            return true;
        }
    }

    public static final class Recorder {
        static final Object POINT = new Object();
        static final List<Object> SOURCES = List.of(List.of(POINT));
        static final Object UNDO = new Object();
        static final Object EDGE = new Object();
        static final List<Object> ERASER_EDGES = List.of(new Object());
        static final List<Object> deletedSources = new ArrayList<>();
        static final List<Object> deletedUndo = new ArrayList<>();
        static final List<Object> removedEdges = new ArrayList<>();
        static final List<Object> removedEdgeLists = new ArrayList<>();
        static final List<Object> removedPointLists = new ArrayList<>();
        private static final EditMode EDIT_MODE_INSTANCE = new EditMode();
        private static final Mesh MESH_INSTANCE = new Mesh();
        private static final Handler HANDLER_INSTANCE = new Handler();

        static void reset() {
            deletedSources.clear();
            deletedUndo.clear();
            removedEdges.clear();
            removedEdgeLists.clear();
            removedPointLists.clear();
        }

        public static EditMode editMode() {
            return EDIT_MODE_INSTANCE;
        }

        public static Mesh mesh() {
            return MESH_INSTANCE;
        }

        public static List<Object> sources() {
            return SOURCES;
        }

        public static Object undo() {
            return UNDO;
        }

        public static Object edge() {
            return EDGE;
        }

        public static Handler handler() {
            return HANDLER_INSTANCE;
        }

        public static List<Object> eraserEdges() {
            return ERASER_EDGES;
        }

        public static void attachUndo(final Object groupUndo, final Object childUndo) {
            // The fixture only needs to preserve the original stack and call order.
        }

        public static Object beginUndo(final String label) {
            return UNDO;
        }

        public static final class EditMode {
            public void delete_exe(final List<?> sources, final Object undo) {
                deletedSources.add(sources);
                deletedUndo.add(undo);
            }
        }

        public static final class Mesh {
            public void removeEdge(final Object edge) {
                removedEdges.add(edge);
            }
        }

        public static final class Handler {
            public Object removeEdges(final List<?> edges) {
                removedEdgeLists.add(edges);
                return new Object();
            }

            public Object removePoints(final List<?> points, final boolean unused) {
                removedPointLists.add(points);
                return new Object();
            }
        }
    }
}
