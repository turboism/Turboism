package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Offline proof that the Auto Mesh write speaks the reviewed Editor contract of every build.
 *
 * <p>The writer reproduces the Editor's own generator: a fresh editable mesh, canvas points and
 * triangle edges, the copy into the mesh the edit mode holds, the source verification and the
 * topology notifications. Each of those steps names a host member, so a build that dropped or moved
 * one would make the write refuse instead of producing the Editor's result. This case resolves every
 * named member against the reviewed artifact archive, so the contract fails here rather than inside
 * a licensed host.</p>
 *
 * <p>The table is written from the reviewed bytecode and stays independent of the runtime constants,
 * so a wrong constant cannot hide behind a matching assertion. Without the archive every case is
 * skipped with an explicit assumption message.</p>
 */
class AutoMeshWriteContractTest {

    private static final String NO_ARCHIVE_MESSAGE = "reviewed Cubism artifact archive is not configured; set "
            + "TURBOISM_CUBISM_REF to a directory holding <version>/Live2D_Cubism.jar";

    private static final String MESH = "com.live2d.graphics3d.editableMesh.GEditableMesh2";
    private static final String MESH_GUID = "com.live2d.type.GEditableMeshGuid";
    private static final String POINT_TYPE = "com.live2d.graphics3d.editableMesh.MPoint2$PointType";
    private static final String EDGE_TYPE = "com.live2d.graphics3d.editableMesh.MEdge$EdgeType";
    private static final String COPYABLE = "com.live2d.core.ICopyable";
    private static final String COPY_BRIDGE = "com.live2d.core.ICopyable$b";
    private static final String COPY_CONTEXT = "com.live2d.core.a";
    private static final String VERIFIABLE = "com.live2d.util.verify.IVerifiable";
    private static final String VERIFY_CONTEXT = "com.live2d.util.verify.a";
    private static final String VERIFY_BRIDGE = "com.live2d.util.verify.IVerifiable$a";
    private static final String ART_MESH_SOURCE = "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource";
    private static final String ART_MESH_HANDLER = "com.live2d.cubism.doc.model.drawable.artMesh.ArtMeshHandler";
    private static final String MODEL_SOURCE = "com.live2d.cubism.doc.model.CModelSource";
    private static final String MODEL = "com.live2d.cubism.doc.model.CModel";
    private static final String TOPOLOGY =
            "com.live2d.cubism.doc.model.extension.structureObserver.CTopologyObserverExtension";
    private static final String PACK = "com.live2d.cubism.pack.CECompletePack";
    private static final String UPDATE_MANAGER = "com.live2d.cubism.CEUpdateManager";
    private static final String UPDATE_TARGET = "com.live2d.cubism.CEUpdateManager$b";
    private static final String DOCUMENT = "com.live2d.cubism.doc.modeling.CModelingDocument";
    private static final String MESH_EDIT_MODE = "com.live2d.cubism.doc.modeling.CModelingEditMode_MeshEditor";
    private static final String MODELING_MODE = "com.live2d.cubism.doc.modeling.CModelingEditMode_Main";
    private static final String EDIT_MODE = "com.live2d.doc.IEditMode";
    private static final String SELECTOR = "com.live2d.doc.selection.ISelector";
    private static final String EDIT_DATA = "com.live2d.cubism.doc.modeling.CModelingEditMode_MeshEditor$b";
    private static final String MESH_GENERATOR =
            "com.live2d.cubism.doc.model.extension.meshGenerator.CMeshGeneratorExtension";
    private static final String EDITABLE_MESH_EXTENSION =
            "com.live2d.cubism.doc.model.extension.editableMesh.CEditableMeshExtension";
    private static final String GROUP_UNDO = "com.live2d.undo.GroupUndo";
    private static final String UNDOABLE = "com.live2d.undo.ACUndoable";
    private static final String FUNCTION1 = "kotlin.jvm.functions.Function1";
    private static final String STRING = "java.lang.String";
    private static final String LIST = "java.util.List";
    private static final String OBJECT = "java.lang.Object";
    private static final String BOOLEAN = "boolean";
    private static final String FLOAT = "float";
    private static final String INT = "int";
    private static final String LONG = "long";
    private static final String CONSTRUCTOR = "<init>";

    /** Every member the write names, in the shape the reviewed bytecode declares it. */
    private static final List<Member> MEMBERS = List.of(
            // The fresh editable mesh and its geometry primitives.
            new Member(MESH, CONSTRUCTOR, List.of(MESH_GUID)),
            new Member(MESH, "getMeshGuid", List.of()),
            new Member(MESH, "addPoint", List.of(FLOAT, FLOAT, POINT_TYPE, LONG)),
            new Member(MESH, "addEdge", List.of(INT, INT, EDGE_TYPE, BOOLEAN, BOOLEAN)),
            new Member(MESH, "getEdges", List.of()),
            new Member(MESH, "setPositionUpdated", List.of()),
            new Member(MESH, "setEdgeUpdated", List.of()),
            new Member(MESH, "reinit", List.of(COPYABLE, COPY_CONTEXT)),
            // The Editor's copy of the fresh mesh into the live one.
            new Member(COPY_BRIDGE, "a", List.of(COPYABLE, COPYABLE, COPY_CONTEXT, INT, OBJECT)),
            // Source verification, the call the Editor makes before and after the copy.
            new Member(VERIFY_BRIDGE, "a", List.of(VERIFIABLE, BOOLEAN, VERIFY_CONTEXT, INT, OBJECT)),
            new Member(ART_MESH_SOURCE, "verify", List.of(BOOLEAN, VERIFY_CONTEXT)),
            new Member(ART_MESH_SOURCE, "recover", List.of()),
            new Member(ART_MESH_SOURCE, "setMeshUpdatedFlag__testImpl", List.of()),
            new Member(ART_MESH_SOURCE, "getHandler", List.of()),
            new Member(ART_MESH_SOURCE, "getModelSource", List.of()),
            new Member(ART_MESH_SOURCE, "getExtensions", List.of()),
            // The topology notifications the Editor fires around its own write.
            new Member(TOPOLOGY, "fireTopologyWillChange", List.of(MODEL)),
            new Member(TOPOLOGY, "fireTopologyChanged", List.of(MODEL, BOOLEAN)),
            new Member(MODEL_SOURCE, "getCurrentInstance", List.of()),
            // The edit mode the write opens, and the canvas it repaints.
            new Member(PACK, "getCurrentDoc", List.of()),
            new Member(PACK, "getUpdateManager", List.of()),
            new Member(UPDATE_MANAGER, "repaintCanvas$default", List.of(UPDATE_MANAGER, UPDATE_TARGET, INT, OBJECT)),
            new Member(DOCUMENT, "getEditMode_meshEdit", List.of()),
            new Member(DOCUMENT, "getEditMode_modeling", List.of()),
            new Member(DOCUMENT, "getCurrentEditMode", List.of()),
            new Member(DOCUMENT, "getSelector", List.of()),
            new Member(SELECTOR, "clearSubSelection", List.of()),
            new Member(EDIT_MODE, "beginEdit", List.of(STRING)),
            new Member(EDIT_MODE, "endEdit", List.of(BOOLEAN, FUNCTION1)),
            new Member(MESH_EDIT_MODE, "getEditDataList", List.of()),
            new Member(MODELING_MODE, "beginEdit", List.of(STRING)),
            // The two Undo snapshots the funnels take.
            new Member(ART_MESH_HANDLER, "a", List.of(STRING)),
            new Member(GROUP_UNDO, "plusAssign", List.of(UNDOABLE)),
            new Member(GROUP_UNDO, "addEdit", List.of(UNDOABLE, BOOLEAN)),
            // The editable mesh each funnel delivers.
            new Member(EDIT_DATA, "a", List.of()),
            new Member(EDIT_DATA, "b", List.of()),
            new Member(EDIT_DATA, "c", List.of()),
            new Member(MESH_GENERATOR, "getOwner", List.of()),
            new Member(EDITABLE_MESH_EXTENSION, "getEditableMesh", List.of()),
            new Member(EDITABLE_MESH_EXTENSION, "getOwner", List.of()));

    @Test
    void everyReviewedArtifactCarriesTheMembersTheWriteNames() throws Exception {
        final Path root = AutoMeshFunnelTestSupport.refRoot();
        assumeTrue(root != null, NO_ARCHIVE_MESSAGE);
        final List<String> unresolved = new ArrayList<>();
        int inspected = 0;
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            final Path jar = AutoMeshFunnelTestSupport.jar(root, version);
            if (jar == null) {
                continue;
            }
            inspected++;
            try (HostLoader loader = new HostLoader(jar)) {
                for (final Member member : MEMBERS) {
                    try {
                        loader.resolve(member);
                    } catch (ReflectiveOperationException | LinkageError failure) {
                        unresolved.add(version + " " + member.signature() + " -> " + failure);
                    }
                }
            }
        }
        assertTrue(inspected > 0, NO_ARCHIVE_MESSAGE);
        assertEquals(List.of(), unresolved, "every reviewed artifact must carry the write contract");
    }

    /** One reflective member: owner class, member name and exact parameter type names. */
    private record Member(String owner, String name, List<String> parameterTypes) {

        private String signature() {
            return owner + "#" + name + "(" + String.join(", ", parameterTypes) + ")";
        }
    }

    /**
     * Loader that keeps the reviewed artifact separate from the harness.
     *
     * <p>The parent answers for the JDK and the Kotlin runtime the artifact links against, so a
     * member that names a Kotlin type still resolves while every Editor type comes from the jar.</p>
     */
    private static final class HostLoader extends URLClassLoader {

        private HostLoader(final Path jar) throws Exception {
            super(urlsOf(jar.getParent()), AutoMeshWriteContractTest.class.getClassLoader());
        }

        private static URL[] urlsOf(final Path directory) throws Exception {
            final List<URL> urls = new ArrayList<>();
            try (var paths = java.nio.file.Files.list(directory)) {
                for (final Path candidate :
                        paths.filter(entry -> entry.toString().endsWith(".jar")).toList()) {
                    urls.add(candidate.toUri().toURL());
                }
            }
            return urls.toArray(URL[]::new);
        }

        /**
         * Loads one class artifact first.
         *
         * <p>The harness declares stand-ins for a few Cubism types, so a parent-first load would
         * shadow the artifact's own class with a stand-in that carries a different surface. The
         * artifact therefore answers first, and the harness answers only for the classes it does
         * not carry.</p>
         */
        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException absent) {
                        loaded = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private void resolve(final Member member) throws ReflectiveOperationException {
            final List<Class<?>> parameters = new ArrayList<>();
            for (final String parameter : member.parameterTypes()) {
                parameters.add(primitive(parameter).orElseGet(() -> load(parameter)));
            }
            final Class<?> owner = Class.forName(member.owner(), false, this);
            final Class<?>[] types = parameters.toArray(new Class<?>[0]);
            if (CONSTRUCTOR.equals(member.name())) {
                owner.getConstructor(types);
                return;
            }
            final Method resolved = owner.getMethod(member.name(), types);
            assertTrue(
                    resolved.getDeclaringClass().isAssignableFrom(owner),
                    member.signature() + " is declared by "
                            + resolved.getDeclaringClass().getName());
        }

        private Class<?> load(final String name) {
            try {
                return Class.forName(name, false, this);
            } catch (ClassNotFoundException failure) {
                throw new IllegalStateException("member parameter type is missing: " + name, failure);
            }
        }

        private static java.util.Optional<Class<?>> primitive(final String name) {
            return switch (name) {
                case INT -> java.util.Optional.of(int.class);
                case BOOLEAN -> java.util.Optional.of(boolean.class);
                case FLOAT -> java.util.Optional.of(float.class);
                case LONG -> java.util.Optional.of(long.class);
                default -> java.util.Optional.empty();
            };
        }
    }
}
