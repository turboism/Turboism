package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.model.Point2;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class NativeMeshVertexProjectorTest {
    @Test
    void mapsStagedVerticesThroughTheCurrentFormBeforeZoomAndPanWithoutChangingIndices() {
        final Fixture fixture = new Fixture();
        assertEquals(
                List.of(
                        new Point2(60, 90),
                        new Point2(140, 90),
                        new Point2(140, 210),
                        new Point2(60, 210),
                        new Point2(100, 150),
                        new Point2(180, 330)),
                fixture.project());
        assertEquals(1, fixture.model.reads);
        // The editable mesh has added vertices; mapping uses the authored source triangles.
        assertEquals(6, fixture.mesh.count());
        assertEquals(4, fixture.source.positions.length / 2);
    }

    @Test
    void overlayCoveringDisplayedVertexSelectsItsIndexInsteadOfTheRawVertexAtTheSamePixel() throws Exception {
        final Fixture fixture = new Fixture();
        final List<List<Integer>> commits = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            final JPanel view = new JPanel(null);
            view.setSize(400, 400);
            final RuntimeSelectionBrush brush = new RuntimeSelectionBrush(new RuntimeSelectionBrush.Host() {
                public JComponent component() {
                    return view;
                }

                public List<Point2> projectedVertices() {
                    return fixture.project();
                }

                public void commitSelection(final List<Integer> indices, final SelectionMode mode) {
                    commits.add(indices);
                }

                public SelectionMode selectionMode(boolean shift, boolean control, boolean alt) {
                    return SelectionMode.REPLACE;
                }

                public boolean revalidate() {
                    return true;
                }

                public void deactivate() {}
            });
            try {
                brush.install();
                brush.setRadiusPixels(8);
                final JComponent overlay = brush.overlayForTests();
                overlay.dispatchEvent(new MouseEvent(
                        overlay,
                        MouseEvent.MOUSE_PRESSED,
                        1L,
                        InputEvent.BUTTON1_DOWN_MASK,
                        60,
                        90,
                        1,
                        false,
                        MouseEvent.BUTTON1));
                assertTrue(brush.previewVisibleForTests());
                assertTrue(commits.isEmpty());
                overlay.dispatchEvent(new MouseEvent(
                        overlay, MouseEvent.MOUSE_RELEASED, 2L, 0, 60, 90, 1, false, MouseEvent.BUTTON1));
                // Raw index 5 projects to this pixel; displayed index 0 is actually beneath the circle.
                assertEquals(List.of(List.of(0)), commits);
            } finally {
                brush.close();
            }
        });
    }

    @Test
    void sourceAndImageModesUseIdentityAndDoNotReadCalculatedForms() {
        final Fixture fixture = new Fixture();
        for (Mode mode : List.of(Mode.SOURCE, Mode.IMAGE)) {
            fixture.view.mode = mode;
            assertEquals(new Point2(0, 10), fixture.project().get(0));
        }
        assertEquals(0, fixture.model.reads);
    }

    @Test
    void readsModeAndCalculatedFormAgainAfterAViewChange() {
        final Fixture fixture = new Fixture();
        assertEquals(new Point2(60, 90), fixture.project().get(0));
        fixture.instance.form.positions = fixture.source.positions.clone();
        assertEquals(new Point2(0, 10), fixture.project().get(0));
        fixture.view.mode = Mode.IMAGE;
        fixture.instance.form.positions = null;
        assertEquals(new Point2(0, 10), fixture.project().get(0));
        assertEquals(2, fixture.model.reads);
    }

    @Test
    void rejectsUnknownModesAndAnInstanceFromAnotherSource() {
        final Fixture fixture = new Fixture();
        fixture.view.mode = new Mode();
        assertThrows(IllegalStateException.class, fixture::project);
        assertEquals(0, fixture.camera.calls);
        fixture.view.mode = Mode.POSED;
        fixture.instance.source = new Source();
        assertThrows(IllegalStateException.class, fixture::project);
        assertEquals(0, fixture.camera.calls);
    }

    @Test
    void rejectsMalformedGeometryBeforeCameraProjection() {
        final Fixture fixture = new Fixture();
        fixture.instance.form.positions = new float[] {1, 2};
        assertThrows(IllegalStateException.class, fixture::project);
        fixture.instance.form.positions = fixture.source.positions.clone();
        fixture.source.indices = new int[] {0, 1, 99};
        assertThrows(IllegalStateException.class, fixture::project);
        fixture.view.mode = Mode.IMAGE;
        fixture.mesh.positions[1] = Float.NaN;
        assertThrows(IllegalStateException.class, fixture::project);
        assertEquals(0, fixture.camera.calls);
    }

    private static VerifiedMemberResolver resolver() {
        final List<StaticSelector> selectors = List.of(
                method(
                        MeshToolSessionSelectorContract.EDITABLE_MESH_GL_POSITIONS,
                        Mesh.class,
                        "positions",
                        float[].class),
                method(MeshToolSessionSelectorContract.EDITABLE_MESH_POINT_COUNT, Mesh.class, "count", int.class),
                method("cubism.editor-model.modeling-view.current-view-mode", View.class, "mode", Mode.class),
                field("cubism.editor-model.modeling-view.mode-current-form", "POSED"),
                field("cubism.editor-model.modeling-view.mode-source", "SOURCE"),
                field("cubism.editor-model.modeling-view.mode-image", "IMAGE"),
                method("cubism.editor-model.modeling-view.model", View.class, "model", Model.class),
                method("cubism.editor-model.model.get-object", Model.class, "getObject", Instance.class, String.class),
                method(MeshToolSessionSelectorContract.ARTMESH_SOURCE_ID, Source.class, "id", String.class),
                StaticSelector.classSelector("cubism.editor-model.art-mesh.class", name(Instance.class)),
                method("cubism.editor-model.art-mesh.source", Instance.class, "source", Source.class),
                method("cubism.editor-model.art-mesh-source.positions", Source.class, "positions", float[].class),
                method("cubism.editor-model.art-mesh-source.indices", Source.class, "indices", int[].class),
                method(
                        "cubism.editor-model.parameter-controllable.calculated-form",
                        Instance.class,
                        "form",
                        Form.class),
                method("cubism.editor-model.art-mesh-form.positions", Form.class, "positions", float[].class),
                StaticSelector.constructor(
                        "cubism.editor-model.mesh-coordinate-converter.create",
                        name(Converter.class),
                        "([F[F[II)V",
                        StaticSelector.ACCESS_PUBLIC),
                method(
                        "cubism.editor-model.mesh-coordinate-converter.transform",
                        Converter.class,
                        "transform",
                        float[].class,
                        float[].class,
                        float[].class),
                StaticSelector.constructor(
                        MeshToolSessionSelectorContract.VECTOR_CREATE,
                        name(Vector.class),
                        "(FF)V",
                        StaticSelector.ACCESS_PUBLIC),
                method(
                        MeshToolSessionSelectorContract.CAMERA_DOCUMENT_TO_COMPONENT,
                        Camera.class,
                        "project",
                        Vector.class,
                        Vector.class),
                method(MeshToolSessionSelectorContract.VECTOR_X, Vector.class, "x", float.class),
                method(MeshToolSessionSelectorContract.VECTOR_Y, Vector.class, "y", float.class));
        return TestVerifiedResolvers.create(
                MeshToolSessionSelectorContract.ADAPTER_SLICE_ID,
                Set.of(MeshToolSessionSelectorContract.CAPABILITY_ID),
                selectors,
                NativeMeshVertexProjectorTest.class.getClassLoader());
    }

    private static StaticSelector method(
            String alias, Class<?> owner, String member, Class<?> result, Class<?>... args) {
        return StaticSelector.method(
                alias,
                name(owner),
                member,
                MethodType.methodType(result, args).toMethodDescriptorString(),
                StaticSelector.ACCESS_PUBLIC);
    }

    private static StaticSelector field(String alias, String member) {
        return StaticSelector.field(
                alias,
                name(Mode.class),
                member,
                "L" + name(Mode.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC);
    }

    private static String name(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static final class Fixture {
        final Source source = new Source();
        final Mesh mesh = new Mesh();
        final Instance instance = new Instance(source);
        final Model model = new Model(instance);
        final View view = new View(model);
        final Camera camera = new Camera();
        final VerifiedMemberResolver resolver = resolver();
        final MeshEditSessionIdentity identity = new MeshEditSessionIdentity(new MeshToolSessionResolver.Snapshot(
                new Object(),
                new Object(),
                new Object(),
                source,
                mesh,
                new Object(),
                new Object(),
                view,
                new Object(),
                new JPanel(),
                camera,
                new Object(),
                new Object(),
                new Object()));

        List<Point2> project() {
            return NativeMeshVertexProjector.project(resolver, identity);
        }
    }

    public static final class Mode {
        public static final Mode POSED = new Mode();
        public static final Mode SOURCE = new Mode();
        public static final Mode IMAGE = new Mode();
    }

    public static final class Source {
        float[] positions = {10, 20, 30, 20, 30, 40, 10, 40};
        int[] indices = {0, 1, 2, 0, 2, 3};

        public String id() {
            return "mesh";
        }

        public float[] positions() {
            return positions;
        }

        public int[] indices() {
            return indices;
        }
    }

    public static final class Mesh {
        float[] positions = {10, 20, 30, 20, 30, 40, 10, 40, 20, 30, 40, 60};

        public float[] positions() {
            return positions;
        }

        public int count() {
            return positions.length / 2;
        }
    }

    public static final class Form {
        float[] positions = {40, 60, 80, 60, 80, 120, 40, 120};

        public float[] positions() {
            return positions;
        }
    }

    public static final class Instance {
        Source source;
        final Form form = new Form();

        Instance(Source source) {
            this.source = source;
        }

        public Source source() {
            return source;
        }

        public Form form() {
            return form;
        }
    }

    public static final class Model {
        final Instance instance;
        int reads;

        Model(Instance instance) {
            this.instance = instance;
        }

        public Instance getObject(String id) {
            reads++;
            assertEquals("mesh", id);
            return instance;
        }
    }

    public static final class View {
        final Model model;
        Mode mode = Mode.POSED;

        View(Model model) {
            this.model = model;
        }

        public Model model() {
            return model;
        }

        public Mode mode() {
            return mode;
        }
    }

    /** Fixture for the native triangle converter; live-JAR validation covers its implementation. */
    public static final class Converter {
        final float[] source;
        final float[] current;

        public Converter(float[] source, float[] current, int[] indices, int dimensions) {
            assertEquals(2, dimensions);
            assertEquals(6, indices.length);
            this.source = source;
            this.current = current;
        }

        public float[] transform(float[] input, float[] output) {
            assertEquals(input.length, output.length);
            for (int i = 0; i < input.length; i += 2) {
                output[i] = current[0] + (input[i] - source[0]) * (current[2] - current[0]) / (source[2] - source[0]);
                output[i + 1] =
                        current[1] + (input[i + 1] - source[1]) * (current[5] - current[1]) / (source[5] - source[1]);
            }
            return output;
        }
    }

    public static final class Vector {
        final float x;
        final float y;

        public Vector(float x, float y) {
            this.x = x;
            this.y = y;
        }

        public float x() {
            return x;
        }

        public float y() {
            return y;
        }
    }

    public static final class Camera {
        int calls;

        public Vector project(Vector point) {
            calls++;
            return new Vector(point.x * 2 - 20, point.y * 2 - 30);
        }
    }
}
