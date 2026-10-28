package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
                public record GVector2(float x,float y) { public float getX(){return x;} public float getY(){return y;} }
                """);
        source(files, "com/live2d/graphics3d/component/a/c.java", """
                package com.live2d.graphics3d.component.a;
                public class c { public enum b { a } }
                """);
        source(files, "fixture/Draw.java", """
                package fixture;
                import com.live2d.type.CPoint;
                import com.live2d.graphics3d.type.GVector2;
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
                        public GVector2 componentToDocument(CPoint p){return new GVector2(p.getX()/zoom+pan,p.getY()/zoom+pan);}
                    }
                    public class Render {public Drawer b(){return drawer;}}
                    public static class Scene {public Object getObjectsOnCanvas(){return this;}}
                    public static class Area {public Rect getViewAreaViewport(){return new Rect();}}
                    public static class Rect {public int getX(){return 0;} public int getY(){return 0;}
                        public int getW(){return 1920;} public int getH(){return 1080;}}
                    public static class Pack {public Panel getMainViewPanel(){return new Panel();}}
                    public static class Panel {public Panel getRect(){return this;} public int getH(){return 0;}}
                    public static class Drawer {
                        public GVector2 start,end; public Object color; public int flags;
                        public void a(Object ignored){}
                        public void a(GVector2 s,GVector2 e,Object c,float width,float other){start=s;end=e;color=c;}
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
