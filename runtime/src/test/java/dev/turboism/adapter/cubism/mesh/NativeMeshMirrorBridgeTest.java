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
        final Point recomputed = (Point) NativeMeshMirrorBridge.adjustAxisPoint(
            original, new State("HORIZONTAL", 2.0f), source
        );
        assertEquals(1.0f, recomputed.getX(), 0.0001f);
        assertEquals(1.0f, recomputed.getY(), 0.0001f);
    }

    @Test
    void observesCanvasCenterWhenTheNativePanelIsAttached() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        axis.setCurrentAngleDegrees(90.0f);
        NativeMeshMirrorBridge.install(axis, new RuntimeMeshEditUiService());

        NativeMeshMirrorBridge.attachControl(new javax.swing.JPanel(), new Panel(200.0f, 100.0f));
        final Point projected = (Point) NativeMeshMirrorBridge.adjustAxisPoint(
            new Point(9.0f, 9.0f), new State("HORIZONTAL", 25.0f), new Point(120.0f, 30.0f)
        );

        assertEquals(100.0f, projected.getX(), 0.0001f);
        assertEquals(30.0f, projected.getY(), 0.0001f);
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
    void samePanelViewEditModelSourceAndCanvasReplacementsAreResolvedAtOperationTime() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        axis.setCurrentAngleDegrees(90.0f);
        NativeMeshMirrorBridge.install(axis, new RuntimeMeshEditUiService());
        final Panel panel = new Panel(200.0f, 100.0f);
        NativeMeshMirrorBridge.attachControl(new javax.swing.JPanel(), panel);

        panel.toolMode.controller.completePack.currentViewContext = new ViewContext(400.0f, 300.0f);
        final Point projected = (Point) NativeMeshMirrorBridge.adjustAxisPoint(
            new Point(9.0f, 9.0f), new State("HORIZONTAL", 25.0f), new Point(220.0f, 30.0f)
        );

        assertEquals(200.0f, projected.getX(), 0.0001f);
        assertEquals(30.0f, projected.getY(), 0.0001f);

        panel.toolMode.controller.completePack.currentViewContext.currentEditMode = new EditMode(600.0f, 400.0f);
        assertProjectedX(panel, 300.0f);
        panel.toolMode.controller.completePack.currentViewContext.currentEditMode.currentModel = new Model(800.0f, 500.0f);
        assertProjectedX(panel, 400.0f);
        panel.toolMode.controller.completePack.currentViewContext.currentEditMode.currentModel.source = new Source(1000.0f, 600.0f);
        assertProjectedX(panel, 500.0f);
        panel.toolMode.controller.completePack.currentViewContext.currentEditMode.currentModel.source.canvas = new Canvas(1200.0f, 700.0f);
        assertProjectedX(panel, 600.0f);
    }

    @Test
    void unreadableSamePanelContextFallsBackForPointProjectionAndHit() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        axis.setCurrentAngleDegrees(45.0f);
        NativeMeshMirrorBridge.install(axis, new RuntimeMeshEditUiService());
        final Panel panel = new Panel(200.0f, 100.0f);
        NativeMeshMirrorBridge.attachControl(new javax.swing.JPanel(), panel);
        panel.toolMode.controller.completePack.currentViewContext = null;
        final Point original = new Point(9.0f, 9.0f);

        assertSame(original, NativeMeshMirrorBridge.adjustPoint(
            original, new State("VERTICAL", 0.0f), new Point(1.0f, 1.0f)
        ));
        assertSame(original, NativeMeshMirrorBridge.adjustAxisPoint(
            original, new State("HORIZONTAL", 25.0f), new Point(120.0f, 30.0f)
        ));
        assertEquals(false, NativeMeshMirrorBridge.adjustHit(
            false, new State("VERTICAL", 0.0f), new Point(2.0f, -1.95f), 0.1f
        ));
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
