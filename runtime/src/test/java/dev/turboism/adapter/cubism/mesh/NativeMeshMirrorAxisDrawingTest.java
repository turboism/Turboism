package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.MeshEditContributionSurface.MirrorAxisAngleControl;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises both draw bridges with a synthetic host camera, viewport and native line drawer. */
final class NativeMeshMirrorAxisDrawingTest {
    @TempDir
    Path root;

    @AfterEach
    void uninstall() {
        NativeMeshMirrorBridge.uninstall();
    }

    @Test
    void rotatedAxisCoversTheViewportAfterZoomAndPan() throws Exception {
        try (URLClassLoader loader = host()) {
            final Object draw =
                    loader.loadClass("fixture.Draw").getConstructor().newInstance();
            final Object view = draw.getClass().getMethod("a").invoke(draw);
            final Object drawer = draw.getClass().getMethod("drawer").invoke(draw);
            final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
            NativeMeshMirrorBridge.install(axis, new RuntimeMeshEditUiService());
            NativeMeshMirrorBridge.attachControl(new Object(), new NativeMeshMirrorBridgeTest.Panel(2000, 1000));
            for (float zoom : new float[] {0.05f, 1, 20}) {
                for (float pan : new float[] {-3000, 0, 3000}) {
                    view.getClass().getField("zoom").setFloat(view, zoom);
                    view.getClass().getField("pan").setFloat(view, pan);
                    for (boolean vertical : new boolean[] {false, true}) {
                        for (int angle : new int[] {1, 45, 89, 90, 135, 179}) {
                            axis.setCurrentAngleDegrees(angle);
                            assertTrue(NativeMeshMirrorBridge.drawAxis(draw, 0, vertical, 1, "axis"));
                            assertCoverage(axis.resolveLine(), drawer, zoom, pan);
                        }
                    }
                }
            }
            // The legacy bridge must preserve the default-argument flags and style values.
            final Class<?> vector = loader.loadClass("com.live2d.graphics3d.type.GVector2");
            final Object start = vector.getConstructor(float.class, float.class).newInstance(0, -540);
            final Object end = vector.getConstructor(float.class, float.class).newInstance(0, 540);
            axis.setCurrentAngleDegrees(90);
            NativeMeshMirrorBridge.legacyAxisDraw(drawer, start, end, "legacy", 2, 3, 7, null);
            assertEquals("legacy", drawer.getClass().getField("color").get(drawer));
            assertEquals(7, drawer.getClass().getField("flags").get(drawer));
            // The pivot is off screen: the old half-height radius stopped at x=460.
            final Object renderedStart = drawer.getClass().getField("start").get(drawer);
            final Object renderedEnd = drawer.getClass().getField("end").get(drawer);
            final float x1 = ((Number) vector.getMethod("getX").invoke(renderedStart)).floatValue();
            final float x2 = ((Number) vector.getMethod("getX").invoke(renderedEnd)).floatValue();
            assertTrue(Math.min(x1, x2) <= 0 && Math.max(x1, x2) >= 0);
        }
    }

    @Test
    void canvasHandlesMoveTheNativePositionAndRotateAroundTheMovedCenter() throws Exception {
        try (URLClassLoader loader = host()) {
            final Object draw =
                    loader.loadClass("fixture.Draw").getConstructor().newInstance();
            final Object view = draw.getClass().getMethod("a").invoke(draw);
            final Object drawer = draw.getClass().getMethod("drawer").invoke(draw);
            final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
            final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
            ui.contributeMirrorAxisAngleControl(
                    new MirrorAxisAngleControl("angle", "Angle", "", -180, 180, 0.1f, axis::setCurrentAngleDegrees));
            final GesturePanel panel = new GesturePanel();
            final GestureMirror mirror = new GestureMirror();
            NativeMeshMirrorBridge.install(axis, ui);
            NativeMeshMirrorBridge.attachControl(new Object(), panel);
            NativeMeshMirrorBridge.mirrorForTesting(mirror);

            assertTrue(NativeMeshMirrorBridge.drawAxis(draw, 200, true, 1, "axis"));
            assertEquals(9, drawer.getClass().getField("calls").getInt(drawer));
            assertFalse(mouse(view, MouseEvent.MOUSE_PRESSED, 300, 410));
            assertTrue(mouse(view, MouseEvent.MOUSE_PRESSED, 200, 400));
            assertTrue(mouse(view, MouseEvent.MOUSE_DRAGGED, 220, 410));
            assertTrue(mouse(view, MouseEvent.MOUSE_RELEASED, 220, 410));
            assertEquals(220, mirror.c(), 0.001f);
            assertEquals(220, panel.getAxisSlidable().getValue(), 0.001f);

            assertTrue(NativeMeshMirrorBridge.drawAxis(draw, mirror.c(), true, 1, "axis"));
            assertTrue(mouse(view, MouseEvent.MOUSE_PRESSED, 220, 474));
            assertTrue(mouse(view, MouseEvent.MOUSE_DRAGGED, 284, 410));
            assertTrue(mouse(view, MouseEvent.MOUSE_RELEASED, 284, 410));
            assertEquals(-90, axis.currentAngleDegrees(), 0.001f);
            assertEquals(new MeshMirrorGeometry.Point(220, 410), axis.handleCenter(axis.guideLine()));
            assertEquals(
                    410,
                    MeshMirrorGeometry.project(axis.resolveLine(), 300, 500).y(),
                    0.001f);

            mirror.enabled = false;
            assertFalse(mouse(view, MouseEvent.MOUSE_PRESSED, 220, 410));
        }
    }

    @Test
    void centersSelectedTransformedMeshesAndReflectsNativePointSubclassesAfterRotation() throws Exception {
        try (URLClassLoader loader = host()) {
            final Object draw =
                    loader.loadClass("fixture.Draw").getConstructor().newInstance();
            final Object view = draw.getClass().getMethod("a").invoke(draw);
            view.getClass().getMethod("selectMeshes").invoke(view);
            final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
            final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
            ui.contributeMirrorAxisAngleControl(
                    new MirrorAxisAngleControl("angle", "Angle", "", -180, 180, 0.1f, axis::setCurrentAngleDegrees));
            final GestureMirror mirror = new GestureMirror();
            NativeMeshMirrorBridge.install(axis, ui);
            NativeMeshMirrorBridge.attachControl(new Object(), new GesturePanel());
            NativeMeshMirrorBridge.mirrorForTesting(mirror);

            assertTrue(NativeMeshMirrorBridge.drawAxis(draw, 200, true, 1, "axis"));
            javax.swing.SwingUtilities.invokeAndWait(() -> {});
            assertEquals(new MeshMirrorGeometry.Point(340, 230), axis.handleCenter(axis.guideLine()));
            assertEquals(340, mirror.c());
            assertTrue(mouse(view, MouseEvent.MOUSE_PRESSED, 340, 294));
            assertTrue(mouse(view, MouseEvent.MOUSE_DRAGGED, 404, 230));
            assertTrue(mouse(view, MouseEvent.MOUSE_RELEASED, 404, 230));
            assertTrue(NativeMeshMirrorBridge.drawAxis(draw, mirror.c(), true, 1, "axis"));

            final Object source =
                    loader.loadClass("fixture.Draw$PointRef").getConstructor().newInstance();
            final Class<?> vector = loader.loadClass("com.live2d.graphics3d.type.GVector2");
            final Object original =
                    vector.getConstructor(float.class, float.class).newInstance(330f, 280f);
            final Object reflected = NativeMeshMirrorBridge.adjustPoint(original, mirror, source);
            final Object projected = NativeMeshMirrorBridge.adjustAxisPoint(original, mirror, source);
            assertEquals(350f, vector.getMethod("getX").invoke(reflected));
            assertEquals(180f, vector.getMethod("getY").invoke(reflected));
            assertEquals(230f, vector.getMethod("getY").invoke(projected));
        }
    }

    private static boolean mouse(final Object view, final int id, final int x, final int y) throws Exception {
        final MouseEvent raw = new MouseEvent(
                new JPanel(),
                id,
                0,
                0,
                x,
                y,
                1,
                false,
                id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1);
        final Object event = view.getClass()
                .getClassLoader()
                .loadClass("fixture.Draw$Input")
                .getConstructor(MouseEvent.class)
                .newInstance(raw);
        return NativeMeshMirrorBridge.axisInput(view, event);
    }

    public static final class GesturePanel {
        public final NativeMeshMirrorBridgeTest.ToolMode toolMode =
                new NativeMeshMirrorBridgeTest.Panel(800, 800).toolMode;
        private final Position position = new Position();

        public Position getAxisSlidable() {
            return position;
        }
    }

    public static final class Position {
        private float value = 200;

        public void setValue(final float next) {
            value = next;
        }

        public float getValue() {
            return value;
        }
    }

    public static final class GestureMirror {
        private float position = 200;
        private boolean enabled = true;

        public float c() {
            return position;
        }

        public void a(final float next) {
            position = next;
        }

        public boolean a(final Object editMode) {
            return enabled;
        }
    }

    private static void assertCoverage(
            final MeshMirrorGeometry.Line line, final Object drawer, final float zoom, final float pan)
            throws Exception {
        final Object end = drawer.getClass().getField("end").get(drawer);
        final float x = ((Number) end.getClass().getMethod("getX").invoke(end)).floatValue();
        final float y = ((Number) end.getClass().getMethod("getY").invoke(end)).floatValue();
        final double radius =
                Math.hypot(x - line.anchor().x(), y - line.anchor().y());
        for (float cornerX : new float[] {pan, pan + 1920 / zoom}) {
            for (float cornerY : new float[] {pan, pan + 1080 / zoom}) {
                final double projection = (cornerX - line.anchor().x())
                                * line.direction().x()
                        + (cornerY - line.anchor().y()) * line.direction().y();
                assertTrue(
                        Math.abs(projection) <= radius + 0.02,
                        "axis stops inside viewport: zoom=" + zoom + " pan=" + pan);
            }
        }
    }

    private URLClassLoader host() throws Exception {
        final List<String> files = new ArrayList<>();
        source(files, "com/live2d/type/CPoint.java", """
                package com.live2d.type;
                public record CPoint(int x, int y) { public int getX(){return x;} public int getY(){return y;} }
                """);
        source(files, "com/live2d/graphics3d/type/GVector2.java", """
                package com.live2d.graphics3d.type;
                public class GVector2 {
                    private final float x, y;
                    public GVector2(float x,float y){this.x=x;this.y=y;}
                    public float getX(){return x;} public float getY(){return y;}
                }
                """);
        source(files, "com/live2d/graphics3d/component/a/c.java", """
                package com.live2d.graphics3d.component.a;
                public class c { public enum b { a } }
                """);
        source(files, "fixture/Draw.java", """
                package fixture;
                import com.live2d.type.CPoint;
                import com.live2d.graphics3d.type.GVector2;
                import java.awt.event.MouseEvent;
                import java.util.List;
                public class Draw {
                    final View view=new View(); final Drawer drawer=new Drawer();
                    public View a(){return view;} public Pack b(){return new Pack();}
                    public Drawer drawer(){return drawer;}
                    public class View {
                        public float zoom=1,pan=0;
                        public Render getRenderSystem(){return new Render();}
                        public Scene getSceneGraph(){return new Scene();}
                        public Area getViewArea(){return new Area();}
                        public View getCameraManager(){return this;}
                        public View getCurrentEditMode(){return this;}
                        private List<Entry> editData = List.of();
                        public List<Entry> getEditDataList(){return editData;}
                        public FramePack createActionPack(Input event,CPoint point){return new FramePack();}
                        public void selectMeshes(){
                            editData = List.of(new Entry(new Mesh(new float[] {10, 20, 30, 70}, 300)),
                                               new Entry(new Mesh(new float[] {10, 30, 30, 80}, 340)));
                        }
                        public boolean isSafeEditOnPlaneXY(){return true;}
                        public void repaintCanvas(){}
                        public GVector2 componentToDocument(CPoint p){return new GVector2(p.getX()/zoom+pan,p.getY()/zoom+pan);}
                        public GVector2 documentToComponent(GVector2 p){
                            return new GVector2((p.getX()-pan)*zoom,(p.getY()-pan)*zoom);
                        }
                    }
                    public record Input(MouseEvent f) {
                        public CPoint K(){return new CPoint(f.getX(), f.getY());}
                    }
                    public static final class PointRef extends GVector2 {
                        public PointRef(){super(350,280);}
                    }
                    public record Entry(Mesh b) {}
                    public record Mesh(float[] positions,int offset) {
                        public float[] getGlPositions(){return positions;}
                    }
                    public record Frame(int offset) {
                        public GVector2 b(GVector2 point){return new GVector2(point.getX()+offset, point.getY()+180);}
                    }
                    public static class FramePack {public Frame a(Mesh mesh){return new Frame(mesh.offset());}}
                    public class Render {public Drawer b(){return drawer;}}
                    public static class Scene {public Object getObjectsOnCanvas(){return this;}}
                    public static class Area {public Rect getViewAreaViewport(){return new Rect();}}
                    public static class Rect {public int getX(){return 0;} public int getY(){return 0;}
                        public int getW(){return 1920;} public int getH(){return 1080;}}
                    public static class Pack {public Panel getMainViewPanel(){return new Panel();}}
                    public static class Panel {public Panel getRect(){return this;} public int getH(){return 0;}}
                    public static class Drawer {
                        public GVector2 start,end; public Object color; public int flags, calls;
                        public void a(Object ignored){}
                        public void a(GVector2 s,GVector2 e,Object c,float width,float other){
                            start=s;end=e;color=c;calls++;
                        }
                        public static void a(Drawer d,GVector2 s,GVector2 e,Object c,float f,float second,int flags,Object defaults){
                            d.a(s,e,c,f,second); d.flags=flags;
                        }
                    }
                }
                """);
        final Path classes = Files.createDirectories(root.resolve("classes"));
        final List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        args.addAll(files);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        // Keep the synthetic host independent of other tests' native class stubs.
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private void source(final List<String> files, final String name, final String content) throws Exception {
        final Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        files.add(path.toString());
    }
}
