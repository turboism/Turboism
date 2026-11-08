package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * Pins the element type of the selection list both guarded funnels receive.
 *
 * <p>The target projection reads one accessor per element type, so a build that changed the element
 * type would silently resolve nothing and quietly keep the native algorithm. This test reads the
 * generic signature of each reviewed funnel and asserts the exact element type the projection
 * expects, which makes such a drift a build failure instead of a silent fallback.</p>
 *
 * <p>Without the artifact archive every case is skipped with an explicit assumption message.</p>
 */
class AutoMeshFunnelElementTypeTest {

    private static final String NO_ARCHIVE_MESSAGE = "reviewed Cubism artifact archive is not configured; set "
            + "TURBOISM_CUBISM_REF to a directory holding <version>/Live2D_Cubism.jar";
    private static final String AUTO_MESH_UTIL = "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/AutoMeshUtil";
    private static final String AUTO_MESH_UTIL_IN_MESH_EDITOR =
            "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/AutoMeshUtil_InMeshEditor";
    private static final String MODELING = "updateMeshFromInspector_inModelingMode";
    private static final String MESH_EDITOR = "updateMeshFromInspector_inMeshEditorMode";

    /** The element type the modeling-mode projection reads through the extension base class. */
    private static final String MODELING_ELEMENT =
            "com.live2d.cubism.doc.model.extension.meshGenerator.CMeshGeneratorExtension";

    /** The element type the mesh-editor projection reads through its own ArtMesh-source accessor. */
    private static final String MESH_EDITOR_ELEMENT = "com.live2d.cubism.doc.modeling.CModelingEditMode_MeshEditor$b";

    @Test
    void everyReviewedFunnelCarriesTheExpectedSelectionElementType() throws Exception {
        final Path root = AutoMeshFunnelTestSupport.refRoot();
        assumeTrue(root != null, NO_ARCHIVE_MESSAGE);
        final Map<String, String> modelingElements = new LinkedHashMap<>();
        final Map<String, String> meshEditorElements = new LinkedHashMap<>();
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            final Path jar = AutoMeshFunnelTestSupport.jar(root, version);
            if (jar == null) {
                continue;
            }
            modelingElements.put(version, elementType(jar, AUTO_MESH_UTIL, MODELING));
            meshEditorElements.put(version, elementType(jar, AUTO_MESH_UTIL_IN_MESH_EDITOR, MESH_EDITOR));
        }
        assumeTrue(!modelingElements.isEmpty(), NO_ARCHIVE_MESSAGE);

        for (final Map.Entry<String, String> entry : modelingElements.entrySet()) {
            assertEquals(
                    MODELING_ELEMENT,
                    entry.getValue(),
                    "the modeling-mode selection element of " + entry.getKey() + " must stay resolvable");
        }
        for (final Map.Entry<String, String> entry : meshEditorElements.entrySet()) {
            assertEquals(
                    MESH_EDITOR_ELEMENT,
                    entry.getValue(),
                    "the mesh-editor selection element of " + entry.getKey() + " must stay resolvable");
        }
        assertEquals(
                AutoMeshHostProfile.supportedHostVersions().size(),
                modelingElements.size(),
                "every reviewed release must report its modeling-mode element type");
        assertEquals(
                AutoMeshHostProfile.supportedHostVersions().size(),
                meshEditorElements.size(),
                "every reviewed release must report its mesh-editor element type");
    }

    /**
     * Returns the element type of the funnel's single generic {@code List} parameter.
     *
     * <p>The generic signature is the only place the element type is named: the descriptor itself
     * erases it to {@code java.util.List}, so an erasure-only check would accept any element.</p>
     */
    private static String elementType(final Path jar, final String owner, final String methodName) throws IOException {
        final byte[] bytes = AutoMeshFunnelTestSupport.classBytes(jar, owner);
        final String[] signature = new String[1];
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String genericSignature,
                                    final String[] exceptions) {
                                if (methodName.equals(name) && genericSignature != null) {
                                    signature[0] = genericSignature;
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        assertNotNull(signature[0], "the reviewed funnel must carry a generic signature: " + methodName);
        final List<String> elements = listElementTypes(signature[0]);
        assertEquals(1, elements.size(), "the funnel must declare exactly one selection list: " + signature[0]);
        return elements.get(0);
    }

    /** Collects the element type of every generic {@code List} parameter, in declaration order. */
    private static List<String> listElementTypes(final String signature) {
        final List<String> elements = new java.util.ArrayList<>();
        new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
            private boolean listParameter;

            @Override
            public SignatureVisitor visitParameterType() {
                listParameter = false;
                return this;
            }

            @Override
            public void visitClassType(final String name) {
                if ("java/util/List".equals(name)) {
                    listParameter = true;
                }
            }

            @Override
            public SignatureVisitor visitTypeArgument(final char wildcard) {
                if (!listParameter) {
                    return this;
                }
                return new SignatureVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitClassType(final String element) {
                        elements.add(Type.getObjectType(element).getClassName());
                    }
                };
            }
        });
        return List.copyOf(elements);
    }
}
