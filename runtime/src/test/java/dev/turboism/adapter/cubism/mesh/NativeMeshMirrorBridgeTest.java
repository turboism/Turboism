package dev.turboism.adapter.cubism.mesh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class NativeMeshMirrorBridgeTest {

    @AfterEach
    void clearBridge() {
        NativeMeshMirrorBridge.uninstall();
    }

    @Test
    void preservesNativeResultsAtZeroAndUsesTheRotatedAxisOtherwise() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        NativeMeshMirrorBridge.install(axis, new RuntimeMeshEditUiService());
        NativeMeshMirrorBridge.attachControl(new javax.swing.JPanel(), new Panel(0.0f, 0.0f));
        final Point original = new Point(9.0f, 9.0f);
        final Point source = new Point(1.0f, 1.0f);

        assertSame(original, NativeMeshMirrorBridge.adjustPoint(original, new State("VERTICAL", 0.0f), source));

        axis.setCurrentAngleDegrees(45.0f);
        axis.observeAxis(0.0f, true, 0.0f, 0.0f);
        final Point reflected = (Point) NativeMeshMirrorBridge.adjustPoint(original, new State("VERTICAL", 0.0f), source);
        assertEquals(-1.0f, reflected.getX(), 0.0001f);
        assertEquals(-1.0f, reflected.getY(), 0.0001f);
        assertEquals(true, NativeMeshMirrorBridge.adjustHit(
            false, new State("VERTICAL", 0.0f), new Point(2.0f, -1.95f), 0.1f
        ));
        axis.setCurrentAngleDegrees(-45.0f);
        // mirrorState is ignored: the cached vertical axis rotated -45° is the line y = x.
        final Point recomputed = (Point) NativeMeshMirrorBridge.adjustAxisPoint(
            original, new State("HORIZONTAL", 2.0f), new Point(3.0f, 1.0f)
        );
        assertEquals(2.0f, recomputed.getX(), 0.0001f);
        assertEquals(2.0f, recomputed.getY(), 0.0001f);
    }

    @Test
    void unboundCallbackPreservesNativeResult() {
        final Point original = new Point(4.0f, 5.0f);

        assertSame(original, NativeMeshMirrorBridge.adjustPoint(original, new State("VERTICAL", 0.0f), original));
        assertEquals(false, NativeMeshMirrorBridge.adjustHit(
            false, new State("VERTICAL", 0.0f), original, 0.1f
        ));
    }

    @Test
    void observesCanvasCenterWhenTheNativePanelIsAttached() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        axis.setCurrentAngleDegrees(90.0f);
        NativeMeshMirrorBridge.install(axis, new RuntimeMeshEditUiService());

        NativeMeshMirrorBridge.attachControl(new javax.swing.JPanel(), new Panel(200.0f, 100.0f));
        final MeshMirrorGeometry.Line line = axis.resolveLine();
        // Canvas center (100, 50) is the pivot; anchor (0, 50) rotated 90° about it: (100, -50).
        assertEquals(100.0f, line.anchor().x(), 0.0001f);
        assertEquals(-50.0f, line.anchor().y(), 0.0001f);
        assertEquals(-1.0f, line.direction().x(), 0.0001f);
        assertEquals(0.0f, line.direction().y(), 0.0001f);
        final Point projected = (Point) NativeMeshMirrorBridge.adjustAxisPoint(
            new Point(9.0f, 9.0f), new State("HORIZONTAL", 25.0f), new Point(120.0f, 30.0f)
        );
        assertEquals(120.0f, projected.getX(), 0.0001f);
        assertEquals(-50.0f, projected.getY(), 0.0001f);
    }

    @Test
    void resolvesCanvasCenterThroughGetterFormPanelChain() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        axis.setCurrentAngleDegrees(90.0f);
        NativeMeshMirrorBridge.install(axis, new RuntimeMeshEditUiService());

        // Every chain link is a Kotlin-style property with a getter and no backing field.
        NativeMeshMirrorBridge.attachControl(new javax.swing.JPanel(), new GetterChain(200.0f, 100.0f));

        final MeshMirrorGeometry.Line line = axis.resolveLine();
        assertEquals(100.0f, line.anchor().x(), 0.0001f);
        assertEquals(-50.0f, line.anchor().y(), 0.0001f);
        assertEquals(-1.0f, line.direction().x(), 0.0001f);
        assertEquals(0.0f, line.direction().y(), 0.0001f);
    }

    @Test
    void replacingTheNativePanelInvalidatesThePreviousPivot() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        axis.setCurrentAngleDegrees(90.0f);
        NativeMeshMirrorBridge.install(axis, new RuntimeMeshEditUiService());
        final Point original = new Point(9.0f, 9.0f);

        NativeMeshMirrorBridge.attachControl(new javax.swing.JPanel(), new Panel(200.0f, 100.0f));
        NativeMeshMirrorBridge.attachControl(new javax.swing.JPanel(), new Object());

        assertSame(original, NativeMeshMirrorBridge.adjustAxisPoint(
            original, new State("HORIZONTAL", 25.0f), new Point(120.0f, 30.0f)
        ));
    }

    @Test
    void samePanelContextReplacementDoesNotReResolvePivotAtOperationTime() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        axis.setCurrentAngleDegrees(90.0f);
        NativeMeshMirrorBridge.install(axis, new RuntimeMeshEditUiService());
        final Panel panel = new Panel(200.0f, 100.0f);
        NativeMeshMirrorBridge.attachControl(new javax.swing.JPanel(), panel);

        panel.toolMode.controller.completePack.currentViewContext = new ViewContext(400.0f, 300.0f);

        // The click path keeps using the pivot captured at attach/draw time.
        final MeshMirrorGeometry.Line line = axis.resolveLine();
        assertEquals(100.0f, line.anchor().x(), 0.0001f);
        assertEquals(-50.0f, line.anchor().y(), 0.0001f);
        final Point projected = (Point) NativeMeshMirrorBridge.adjustAxisPoint(
            new Point(9.0f, 9.0f), new State("HORIZONTAL", 25.0f), new Point(220.0f, 30.0f)
        );
        assertEquals(220.0f, projected.getX(), 0.0001f);
        assertEquals(-50.0f, projected.getY(), 0.0001f);
    }

    @Test
    void clickPathNeverReflectsPanelOrMirrorState() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        axis.setCurrentAngleDegrees(90.0f);
        NativeMeshMirrorBridge.install(axis, new RuntimeMeshEditUiService());
        final Panel panel = new Panel(200.0f, 100.0f);
        NativeMeshMirrorBridge.attachControl(new javax.swing.JPanel(), panel);

        // The panel chain becomes unreadable and the mirrorState cannot reflect at all.
        panel.toolMode.controller.completePack.currentViewContext = null;
        final Point original = new Point(9.0f, 9.0f);

        // Cached vertical axis: anchor rotate((0, 50), (100, 50), 90°) = (100, -50), dir (-1, 0).
        final Point reflected = (Point) NativeMeshMirrorBridge.adjustPoint(
            original, new Object(), new Point(1.0f, 1.0f)
        );
        assertEquals(1.0f, reflected.getX(), 0.0001f);
        assertEquals(-101.0f, reflected.getY(), 0.0001f);
        assertEquals(true, NativeMeshMirrorBridge.adjustHit(
            false, new Object(), new Point(1.0f, -50.05f), 0.1f
        ));
    }

    @Test
    void repeatedAttachForTheSamePanelDoesNotDuplicateTheRuntimeAttachment() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
        NativeMeshMirrorBridge.install(axis, ui);
        final Panel panel = new Panel(200.0f, 100.0f);
        final javax.swing.JPanel widget = new javax.swing.JPanel();

        NativeMeshMirrorBridge.attachControl(widget, panel);
        NativeMeshMirrorBridge.attachControl(widget, panel);

        assertEquals(null, ui.nativeAttachment());
    }

    @Test
    void explicitHostContextClearMakesOperationsFailClosed() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        axis.setCurrentAngleDegrees(90.0f);
        NativeMeshMirrorBridge.install(axis, new RuntimeMeshEditUiService());
        NativeMeshMirrorBridge.attachControl(new javax.swing.JPanel(), new Panel(200.0f, 100.0f));
        NativeMeshMirrorBridge.clearHostContext();
        final Point original = new Point(9.0f, 9.0f);

        assertSame(original, NativeMeshMirrorBridge.adjustAxisPoint(
            original, new State("HORIZONTAL", 25.0f), new Point(120.0f, 30.0f)
        ));
    }

    private static void assertProjectedX(final Panel panel, final float expected) {
        final Point projected = (Point) NativeMeshMirrorBridge.adjustAxisPoint(
            new Point(9.0f, 9.0f), new State("HORIZONTAL", 25.0f), new Point(expected + 20.0f, 30.0f)
        );
        assertEquals(expected, projected.getX(), 0.0001f);
    }

    public static final class Panel {
        public final ToolMode toolMode;
        public Panel(final float width, final float height) { toolMode = new ToolMode(width, height); }
    }

    public static final class ToolMode {
        final Controller controller;
        ToolMode(final float width, final float height) { controller = new Controller(width, height); }
        public Controller getCtrl$cubism() { return controller; }
    }

    public static final class Controller {
        public final CompletePack completePack;
        Controller(final float width, final float height) { completePack = new CompletePack(width, height); }
    }

    public static final class CompletePack {
        public ViewContext currentViewContext;
        CompletePack(final float width, final float height) { currentViewContext = new ViewContext(width, height); }
    }

    public static final class ViewContext {
        public EditMode currentEditMode;
        ViewContext(final float width, final float height) { currentEditMode = new EditMode(width, height); }
    }

    public static final class EditMode {
        public Model currentModel;
        EditMode(final float width, final float height) { currentModel = new Model(width, height); }
    }

    public static final class Model {
        public Source source;
        Model(final float width, final float height) { source = new Source(width, height); }
    }

    public static final class Source {
        public Canvas canvas;
        Source(final float width, final float height) { canvas = new Canvas(width, height); }
    }

    public record Canvas(float width, float height) {
        public float getPixelWidth() { return width; }
        public float getPixelHeight() { return height; }
    }

    /** Getter-only panel chain: every link is a Kotlin-style property (no backing field). */
    public static final class GetterChain {
        private final Canvas canvas;

        public GetterChain(final float width, final float height) { canvas = new Canvas(width, height); }

        public GetterChain getToolMode() { return this; }

        public GetterChain getCtrl$cubism() { return this; }

        public GetterChain getCompletePack() { return this; }

        public GetterChain getCurrentViewContext() { return this; }

        public GetterChain getCurrentEditMode() { return this; }

        public GetterChain getCurrentModel() { return this; }

        public GetterChain getSource() { return this; }

        public Canvas getCanvas() { return canvas; }
    }

    public static final class Point {
        private final float x;
        private final float y;

        public Point(final float x, final float y) {
            this.x = x;
            this.y = y;
        }

        public float getX() { return x; }
        public float getY() { return y; }
    }

    public record State(String orientation, float axisValue) {
        public Object b() { return orientation; }
        public float c() { return axisValue; }
    }
}
