package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.*;

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

/** Draws through the real bridge with a counted host camera and primitive collector. */
final class ArtMeshMirrorRenderingTest {
    @TempDir
    Path root;

    @AfterEach
    void cleanup() {
        NativeWarpAltMirrorBridge.uninstall();
    }

    @Test
    void boundedDrawWorkAndAxisSurvivesZoomAndSelectionChanges() throws Exception {
        try (var loader = host()) {
            Object view = loader.loadClass("com.live2d.cubism.view.context.CEViewContext_ModelingView")
                    .getConstructor()
                    .newInstance();
            Object camera = view.getClass().getField("camera").get(view);
            Object drawer = view.getClass().getField("drawer").get(view);
            NativeWarpAltMirrorBridge.install(true);
            try (var participation = NativeArtMeshSymmetryBridge.service().participate()) {
                NativeArtMeshSymmetryBridge.service().setEnabled(true);
                // A large model coordinate and broad zoom range catch cancellation when a
                // projected one-unit direction collapses to zero after zooming out.
                var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
                for (float zoom : new float[] {1, .2f, .01f, .00001f, 2, 50, 1}) {
                    camera.getClass().getField("zoom").setFloat(camera, zoom);
                    drawer.getClass().getField("calls").setInt(drawer, 0);
                    NativeArtMeshSymmetryBridge.draw(view);
                    int calls = drawer.getClass().getField("calls").getInt(drawer);
                    checks.add(() -> assertTrue(calls > 0, "axis disappeared at zoom=" + zoom));
                    checks.add(() -> assertTrue(calls <= 26, "per-frame primitive budget exceeded: " + calls));
                    int inversions = camera.getClass().getField("unprojects").getInt(camera);
                    checks.add(() ->
                            assertEquals(0, inversions, "drawing controls must not inverse-project every fill stroke"));
                }
                assertAll(checks);
                Object edit = view.getClass().getField("edit").get(view);
                Object selector = edit.getClass().getField("selector").get(edit);
                var states = NativeArtMeshSymmetryBridge.class.getDeclaredField("STATES");
                states.setAccessible(true);
                Object state = ((java.util.Map<?, ?>) states.get(null))
                        .values()
                        .iterator()
                        .next();
                var axisField = state.getClass().getDeclaredField("axis");
                axisField.setAccessible(true);
                ArtMeshMirrorAxis first = (ArtMeshMirrorAxis) axisField.get(state);
                first.translate(new ArtMeshMirrorAxis.Point(13, 17));
                NativeArtMeshSymmetryBridge.draw(view);
                assertSame(first, axisField.get(state), "repaint must preserve the current mesh axis");
                selector.getClass().getField("second").setBoolean(selector, true);
                NativeArtMeshSymmetryBridge.draw(view);
                ArtMeshMirrorAxis second = (ArtMeshMirrorAxis) axisField.get(state);
                assertNotSame(first, second);
                assertEquals(new ArtMeshMirrorAxis.Point(10000030, 10000030), second.pivot());
            }
        }
    }

    @Test
    void rotatedAxisMatchesNearbyVerticesWithinNativeScreenTolerance() throws Exception {
        try (var loader = host()) {
            Object view = loader.loadClass("com.live2d.cubism.view.context.CEViewContext_ModelingView")
                    .getConstructor()
                    .newInstance();
            Object camera = view.getClass().getField("camera").get(view);
            var pivot = new ArtMeshMirrorAxis.Point(10000010, 10000010);
            var axis = new ArtMeshMirrorAxis(pivot, false);
            axis.rotate(new ArtMeshMirrorAxis.Point(pivot.x() + 14, pivot.y() + 100));
            var points = new ArtMeshMirrorAxis.Point[] {
                new ArtMeshMirrorAxis.Point(pivot.x() - 10, pivot.y() - 10),
                new ArtMeshMirrorAxis.Point(pivot.x() + 10, pivot.y() - 10),
                new ArtMeshMirrorAxis.Point(pivot.x() - 10, pivot.y() + 10),
                new ArtMeshMirrorAxis.Point(pivot.x() + 10, pivot.y() + 10)
            };
            for (float zoom : new float[] {1, 2, .2f}) {
                camera.getClass().getField("zoom").setFloat(camera, zoom);
                assertArrayEquals(
                        new int[] {1, 0, 3, 2},
                        NativeArtMeshSymmetryBridge.pairForView(view, axis, points),
                        "zoom=" + zoom);
            }
            camera.getClass().getField("zoom").setFloat(camera, 20);
            assertArrayEquals(
                    new int[] {-1, -1, -1, -1},
                    NativeArtMeshSymmetryBridge.pairForView(view, axis, points),
                    "Do not link distant vertices outside the native screen tolerance");
        }
    }

    private URLClassLoader host() throws Exception {
        var files = new ArrayList<String>();
        source(files, "com.live2d.graphics3d.type.GVector2", """
            public class GVector2 {float x,y; public GVector2(float x,float y){this.x=x;this.y=y;}
            public float getX(){return x;} public float getY(){return y;}}
            """);
        source(files, "com.live2d.type.CPoint", """
            public class CPoint {public int x,y;public CPoint(int x,int y){this.x=x;this.y=y;}}
            """);
        source(files, "com.live2d.type.CColor", "public class CColor {public CColor(int r,int g,int b){}}");
        source(files, "com.live2d.type.CFont", "public class CFont {public CFont(int size){}}");
        source(
                files,
                "com.live2d.graphics3d.component.a.c$b",
                "public class c$b {public static final c$b a=new c$b();}");
        source(files, "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource", "public class CArtMeshSource {}");
        source(files, "com.live2d.cubism.doc.modeling.CModelingEditMode_Main", """
            public class CModelingEditMode_Main {
                public final Selector selector=new Selector(); public final Model model=new Model();
                public Selector getSelector(){return selector;}public Model getCurrentModel(){return model;}
                public static class Selector {public boolean second;
                    public java.util.List<Selection> getSelected(){return java.util.List.of(new Selection(second));}}
                public static class Selection {boolean second;Selection(boolean s){second=s;}
                    public Object getSource(){return new com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource();}
                    public String getGuid(){return second?"two":"one";}}
                public static class Model {final Object source=new Object();
                    public Object getSource(){return source;}public Mesh getArtMesh(String id){return new Mesh(id.equals("two"));}}
                public static class Mesh {boolean second;Mesh(boolean s){second=s;}
                    public Form getCalculatedForm(){return new Form(second);}}
                public static class Form {boolean second;Form(boolean s){second=s;}
                    public float[] getPositions(){float x=second?10000020:10000000;return new float[]{x,x,x+20,x+20};}}
            }
            """);
        source(files, "com.live2d.cubism.view.context.CEViewContext_ModelingView", """
            import com.live2d.graphics3d.type.GVector2;
            import com.live2d.type.*;
            public class CEViewContext_ModelingView {
                public final com.live2d.cubism.doc.modeling.CModelingEditMode_Main edit=new com.live2d.cubism.doc.modeling.CModelingEditMode_Main();
                public final Camera camera=new Camera();public final Drawer drawer=new Drawer();
                public Object getCurrentEditMode(){return edit;}public Camera getCameraManager(){return camera;}
                public Render getRenderSystem(){return new Render();}public Scene getSceneGraph(){return new Scene();}
                public class Render {public Drawer b(){return drawer;}}
                public static class Scene {public Object getObjectsOnCanvas(){return this;}public Object getObjectsOnComponent(){return this;}}
                public static class Rect {public int getX(){return 0;}public int getY(){return 0;}public int getWidth(){return 1000;}public int getHeight(){return 800;}}
                public static class Camera {public float zoom=1; public int unprojects;
                    public boolean isSafeEditOnPlaneXY(){return true;}public Rect getComponentRect(){return new Rect();}
                    public GVector2 documentToComponent(GVector2 p){return new GVector2(500+(p.getX()-10000010)*zoom,400+(p.getY()-10000010)*zoom);}
                    public GVector2 componentToDocument(CPoint p){unprojects++;return new GVector2((p.x-500)/zoom+10000010,(p.y-400)/zoom+10000010);}}
                public static class Drawer {public int calls;
                    public void a(Object ignored){}public void a(int order){}
                    public void a(GVector2 a,GVector2 b,CColor c,float width,float z){calls++;}
                    public void a(float a,float b,float c,float d,float e,float f,float g,float h,float i,CColor color){calls++;}
                    public void a(String s,CFont f,float x,float y,float z,float size,CColor c){calls++;}}
            }
            """);
        Path classes = Files.createDirectories(root.resolve("classes"));
        var args = new ArrayList<>(List.of("-d", classes.toString()));
        args.addAll(files);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private void source(List<String> files, String type, String body) throws Exception {
        int dot = type.lastIndexOf('.');
        Path path = root.resolve(type.replace('.', '/') + ".java");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "package " + type.substring(0, dot) + ";\n" + body);
        files.add(path.toString());
    }
}
