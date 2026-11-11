package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.profile.CubismAutoMeshUiProfile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Offline proof that the route the selector row calls is the route the Editor calls itself.
 *
 * <p>The Editor re-places points as soon as its own preset changes: the mesh-editor popup resolves
 * the current edit data and calls the guarded funnel, and the modeling popups do the same through
 * their own container. The plugin selector must use those routes instead of growing a second
 * placement pipeline, so this case reads the reviewed popup bytecode and requires exactly that
 * call. A build that moved placement elsewhere fails here instead of silently ignoring the
 * selector.</p>
 *
 * <p>Without the artifact archive every case is skipped with an explicit assumption message.</p>
 */
class AutoMeshLiveRouteTest {

    private static final String NO_ARCHIVE_MESSAGE = "reviewed Cubism artifact archive is not configured; set "
            + "TURBOISM_CUBISM_REF to a directory holding <version>/Live2D_Cubism.jar";

    /** Owner internal name of the mesh-editor popup container. */
    private static final String MESH_EDITOR_POPUP_OWNER =
            "com/live2d/cubism/doc/model/extension/meshGenerator/ui/AutoMeshGenerate_PopupType_InMeshEditor";

    /** The guarded funnel the mesh-editor popup calls. */
    private static final String MESH_EDITOR_FUNNEL =
            "INVOKEVIRTUAL com/live2d/cubism/view/palette/tool/toolMode/meshEditor/AutoMeshUtil_InMeshEditor"
                    + ".updateMeshFromInspector_inMeshEditorMode"
                    + "(Lcom/live2d/cubism/pack/CECompletePack;Lcom/live2d/ui/event/z;Ljava/util/List;)V";

    /** Descriptor of the container entry the selector row resolves and calls. */
    private static final String ROUTE_DESCRIPTOR =
            "(L" + MESH_EDITOR_POPUP_OWNER + "$AutoGenerateMeshDialog;Lcom/live2d/ui/event/z;)V";

    @Test
    void theMeshEditorPopupPlacesThroughTheGuardedFunnel() throws Exception {
        final Path root = AutoMeshFunnelTestSupport.refRoot();
        assumeTrue(root != null, NO_ARCHIVE_MESSAGE);
        final List<String> problems = new ArrayList<>();
        int inspected = 0;
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            final Path jar = AutoMeshFunnelTestSupport.jar(root, version);
            if (jar == null) {
                continue;
            }
            inspected++;
            final byte[] bytes = AutoMeshFunnelTestSupport.classBytes(jar, MESH_EDITOR_POPUP_OWNER);
            final List<String> route = invocationsByDescriptor(bytes).get(ROUTE_DESCRIPTOR);
            if (route == null) {
                problems.add(version + " declares no container entry");
            } else if (!route.contains(MESH_EDITOR_FUNNEL)) {
                problems.add(version + " does not place through the guarded funnel");
            }
        }
        assertTrue(inspected > 0, NO_ARCHIVE_MESSAGE);
        assertEquals(List.of(), problems, "the selector row must call the Editor's own placement route");
    }

    /**
     * Collects every method invocation of one class, keyed by the method's own descriptor.
     *
     * <p>The popup keeps its placement entry next to unrelated members whose names collide, so the
     * descriptor is what identifies the entry; the calls inside it are the evidence.</p>
     */
    private static Map<String, List<String>> invocationsByDescriptor(final byte[] bytes) {
        final Map<String, List<String>> calls = new LinkedHashMap<>();
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
                                final List<String> invocations = new ArrayList<>();
                                calls.put(descriptor, invocations);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String method,
                                            final String callDescriptor,
                                            final boolean isInterface) {
                                        invocations.add((opcode == Opcodes.INVOKESTATIC
                                                        ? "INVOKESTATIC "
                                                        : opcode == Opcodes.INVOKESPECIAL
                                                                ? "INVOKESPECIAL "
                                                                : "INVOKEVIRTUAL ")
                                                + owner + "." + method + callDescriptor);
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls;
    }

    @Test
    void theMeshEditorPopupContainerIsTheReviewedWidgetTheRowDecorates() {
        assertEquals(
                MESH_EDITOR_POPUP_OWNER + "$AutoGenerateMeshDialog",
                CubismAutoMeshUiProfile.MESH_EDITOR_POPUP_TYPE.replace('.', '/'),
                "the row decorates the container the live route belongs to");
    }
}
