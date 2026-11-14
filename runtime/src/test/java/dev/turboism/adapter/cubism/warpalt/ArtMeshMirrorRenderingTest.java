package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.MouseEvent;
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

    /**
     * A double-click that resets the axis must consume the whole gesture.
     *
     * <p>The operator double-clicks the axis centre to reset it. The press that resets the axis is
     * consumed, but the matching release used to fall through to the native dispatcher, which then
     * started its own mesh-edit gesture on the release. Resetting the axis must never leak a native
     * mesh gesture.</p>
     */
    @Test
    void doubleClickResetConsumesTheMatchingRelease() throws Exception {
        try (var loader = host()) {
            Object view = loader.loadClass("com.live2d.cubism.view.context.CEViewContext_ModelingView")
                    .getConstructor()
                    .newInstance();
            NativeWarpAltMirrorBridge.install(true);
            try (var participation = NativeArtMeshSymmetryBridge.service().participate()) {
                NativeArtMeshSymmetryBridge.service().setEnabled(true);
                // The fixture axis pivot (10000010, 10000010) projects to the component centre.
                Object centre = mouse(loader, MouseEvent.MOUSE_PRESSED, 500, 400, 2);
                assertTrue(
                        NativeArtMeshSymmetryBridge.input(view, event(loader, centre)),
                        "the second press of the double-click must reset the axis");
                Object release = mouse(loader, MouseEvent.MOUSE_RELEASED, 500, 400, 2);
                assertTrue(
                        NativeArtMeshSymmetryBridge.input(view, event(loader, release)),
                        "the release of the reset gesture must be consumed, not passed to the host");
                // The axis is rebuilt on the next press, so the bridge keeps owning the gesture.
                Object press = mouse(loader, MouseEvent.MOUSE_PRESSED, 500, 400, 1);
                assertTrue(
                        NativeArtMeshSymmetryBridge.input(view, event(loader, press)),
                        "the axis must still own the next press after a reset");
            }
        }
    }

    @Test
    void hoverSkipsUnprojectionAndCachedAngleLabelAvoidsTranslationTextureReupload() throws Exception {
        try (var loader = hostWithImageLabel()) {
            Object view = loader.loadClass("com.live2d.cubism.view.context.CEViewContext_ModelingView")
                    .getConstructor()
                    .newInstance();
            Object camera = view.getClass().getField("camera").get(view);
            Class<?> textureClass = loader.loadClass("com.live2d.graphics3d.texture.GTexture");
            NativeWarpAltMirrorBridge.install(true);
            try (var participation = NativeArtMeshSymmetryBridge.service().participate()) {
                NativeArtMeshSymmetryBridge.service().setEnabled(true);
                NativeArtMeshSymmetryBridge.draw(view);
                assertEquals(1, textureClass.getField("reinitCalls").getInt(null));
                assertEquals(
                        "LINEAR",
                        String.valueOf(textureClass.getField("lastMinFilter").get(null)));

                // Hovering must not inverse-project cursor coordinates.
                NativeArtMeshSymmetryBridge.input(
                        view, event(loader, mouse(loader, MouseEvent.MOUSE_MOVED, 510, 410, 0)));
                assertEquals(0, camera.getClass().getField("unprojects").getInt(camera));

                // Translating the axis moves the label without re-uploading its texture.
                assertTrue(NativeArtMeshSymmetryBridge.input(
                        view, event(loader, mouse(loader, MouseEvent.MOUSE_PRESSED, 500, 400, 1))));
                assertTrue(NativeArtMeshSymmetryBridge.input(
                        view, event(loader, mouse(loader, MouseEvent.MOUSE_DRAGGED, 520, 415, 1))));
                NativeArtMeshSymmetryBridge.draw(view);
                assertEquals(1, textureClass.getField("reinitCalls").getInt(null));
                assertTrue(NativeArtMeshSymmetryBridge.input(
                        view, event(loader, mouse(loader, MouseEvent.MOUSE_RELEASED, 520, 415, 1))));
            }
        }
    }

    @Test
    void pointDragMirrorsWithoutRepeatedSelectionPollingAndUpdatesForm() throws Exception {
        try (var loader = hostForPointDrag()) {
            Object view = loader.loadClass("com.live2d.cubism.view.context.CEViewContext_ModelingView")
                    .getConstructor()
                    .newInstance();
            Object edit = view.getClass().getField("edit").get(view);
            Object selector = edit.getClass().getField("selector").get(edit);
            Object model = edit.getClass().getField("model").get(edit);
            Object mesh = model.getClass().getMethod("getArtMesh", String.class).invoke(model, "one");
            Object form = mesh.getClass().getMethod("getCalculatedForm").invoke(mesh);
            float[] positions = {-10f, 0f, 10f, 0f, 0f, 20f};
            form.getClass().getField("positions").set(form, positions);
            Object meshSource = mesh.getClass().getMethod("getSource").invoke(mesh);
            Object coord = loader.loadClass("com.live2d.doc.CoordType")
                    .getConstructor()
                    .newInstance();
            Class<?> baseType = loader.loadClass("com.live2d.cubism.doc.model.drawable.artMesh.MeshPointRef");
            Class<?> exType = loader.loadClass("com.live2d.cubism.doc.model.drawable.artMesh.MeshPointRefEx");
            Class<?> transformType = loader.loadClass("com.live2d.doc.selection.ITransformBetweenLocalAndCanvas");
            Class<?> vectorType = loader.loadClass("com.live2d.graphics3d.type.GVector2");
            Object nonlinearTransform = java.lang.reflect.Proxy.newProxyInstance(
                    loader, new Class<?>[] {transformType}, (proxy, method, args) -> {
                        float x = (float) vectorType.getMethod("getX").invoke(args[0]);
                        float y = (float) vectorType.getMethod("getY").invoke(args[0]);
                        if (method.getName().equals("transformLocalToCanvas")) {
                            return vectorType
                                    .getConstructor(float.class, float.class)
                                    .newInstance(x * 2f, y);
                        }
                        if (method.getName().equals("transformCanvasToLocal")) {
                            return vectorType
                                    .getConstructor(float.class, float.class)
                                    .newInstance(x * 0.5f, y);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            Object base0 = baseType.getConstructor(
                            meshSource.getClass(),
                            int.class,
                            float[].class,
                            int.class,
                            coord.getClass(),
                            form.getClass())
                    .newInstance(meshSource, 0, positions, 0, coord, form);
            Object base1 = baseType.getConstructor(
                            meshSource.getClass(),
                            int.class,
                            float[].class,
                            int.class,
                            coord.getClass(),
                            form.getClass())
                    .newInstance(meshSource, 1, positions, 0, coord, form);
            Object ref0 = exType.getConstructor(baseType, transformType, form.getClass())
                    .newInstance(base0, nonlinearTransform, form);
            Object ref1 = exType.getConstructor(baseType, transformType, form.getClass())
                    .newInstance(base1, nonlinearTransform, form);

            NativeWarpAltMirrorBridge.install(true);
            try (var participation = NativeArtMeshSymmetryBridge.service().participate()) {
                NativeArtMeshSymmetryBridge.service().setEnabled(true);
                // Press on vertex 0 side (x=480, away from axis at x=500 -> point drag, not axis drag).
                assertFalse(NativeArtMeshSymmetryBridge.input(
                        view, event(loader, mouse(loader, MouseEvent.MOUSE_PRESSED, 480, 400, 1))));
                int pollsAfterPress =
                        selector.getClass().getField("selectedCalls").getInt(selector);
                assertFalse(NativeArtMeshSymmetryBridge.input(
                        view, event(loader, mouse(loader, MouseEvent.MOUSE_DRAGGED, 470, 410, 1))));
                assertEquals(
                        pollsAfterPress,
                        selector.getClass().getField("selectedCalls").getInt(selector),
                        "point drag must not re-poll selected meshes on MOUSE_DRAGGED");

                // Move vertex 0 to canvas (-24, 6) with weight 0.5: local (-10, 0) -> (-12, 6), blended (-11, 3),
                // canvas (-22, 3), delta (-2, 3) reflected across vertical axis at x=0 -> (+2, 3),
                // counterpart canvas (20, 0) + (2, 3) = (22, 3) -> local (11, 3).
                Object target =
                        vectorType.getConstructor(float.class, float.class).newInstance(-24f, 6f);
                com.live2d.cubism.view.context.action.U$b.writeArtMesh(ref0, target, 0.5f);
                assertEquals(-11f, positions[0], 1e-5f);
                assertEquals(3f, positions[1], 1e-5f);
                assertEquals(11f, positions[2], 1e-5f);
                assertEquals(3f, positions[3], 1e-5f);
                assertEquals(2, form.getClass().getField("updatedCalls").getInt(form));

                // Second call on counterpart in the same tick must be skipped as already mirrored.
                com.live2d.cubism.view.context.action.U$b.writeArtMesh(ref1, target, 0.5f);
                assertEquals(11f, positions[2], 1e-5f);
                assertEquals(3f, positions[3], 1e-5f);

                // Starting a new tick clears per-tick deduplication so subsequent drags apply again.
                Object viewContextClass = loader.loadClass("com.live2d.cubism.view.context.CEViewContext");
                Object manager = loader.loadClass("com.live2d.cubism.view.context.actionManager.CEActionManager")
                        .getConstructor((Class<?>) viewContextClass)
                        .newInstance(view);
                NativeWarpAltMirrorBridge.diagMouseAction(manager, null);
                Object nextTarget =
                        vectorType.getConstructor(float.class, float.class).newInstance(-26f, 6f);
                com.live2d.cubism.view.context.action.U$b.writeArtMesh(ref0, nextTarget, 1.0f);
                assertEquals(-13f, positions[0], 1e-5f);
                assertEquals(6f, positions[1], 1e-5f);
                assertEquals(13f, positions[2], 1e-5f);
                assertEquals(6f, positions[3], 1e-5f);

                // Weight mirroring must resolve getArtMesh(CDrawableId) rather than casting CDrawableId to
                // CDrawableGuid.
                Object pointSelector = loader.loadClass("com.live2d.doc.selection.PointSelector")
                        .getConstructor()
                        .newInstance();
                NativeArtMeshSymmetryBridge.weight(pointSelector, base0, 0.75f, false, true);
                assertEquals(
                        1, pointSelector.getClass().getField("setWeightCalls").getInt(pointSelector));
                assertEquals(
                        0.75f, pointSelector.getClass().getField("lastWeight").getFloat(pointSelector), 1e-5f);
            }
        }
    }

    private URLClassLoader hostWithImageLabel() throws Exception {
        var files = new ArrayList<String>();
        source(files, "com.live2d.graphics3d.type.GVector2", """
            public class GVector2 {float x,y; public GVector2(float x,float y){this.x=x;this.y=y;}
            public float getX(){return x;} public float getY(){return y;}}
            """);
        source(files, "com.live2d.graphics3d.type.GVector3", """
            public class GVector3 {public float x,y,z; public void set(float x,float y,float z){this.x=x;this.y=y;this.z=z;}}
            """);
        source(files, "com.live2d.graphics3d.type.GRectF", """
            public class GRectF {public GRectF(float x,float y,float w,float h){}}
            """);
        source(files, "com.live2d.type.CPoint", """
            public class CPoint {public int x,y;public CPoint(int x,int y){this.x=x;this.y=y;}}
            """);
        source(files, "com.live2d.type.CColor", "public class CColor {public CColor(int r,int g,int b){}}");
        source(files, "com.live2d.type.CFont", "public class CFont {public CFont(int size){}}");
        source(files, "com.live2d.type.CAffine", "public class CAffine {}");
        source(files, "com.live2d.graphics.CImageResource", """
            public class CImageResource {public CImageResource(java.awt.image.BufferedImage i,boolean u){}}
            """);
        source(
                files,
                "com.live2d.graphics3d.texture.MinFilter",
                "public enum MinFilter { NEAREST, LINEAR, LINEAR_MIPMAP_LINEAR }");
        source(files, "com.live2d.graphics3d.texture.MagFilter", "public enum MagFilter { NEAREST, LINEAR }");
        source(files, "com.live2d.graphics3d.texture.GTexture", """
            public class GTexture {
                public static int reinitCalls;
                public static Object lastMinFilter;
                public static class FilterMode {
                    public void setMinFilter(MinFilter f){lastMinFilter=f;}
                    public void setMagFilter(MagFilter f){}
                }
                private final FilterMode mode=new FilterMode();
                public FilterMode getFilterMode(){return mode;}
                public void setPreload(boolean p){}
                public void reinitTexture(){reinitCalls++;}
            }
            """);
        source(files, "com.live2d.graphics3d.texture.GTexture2D", """
            public class GTexture2D extends GTexture {
                public GTexture2D(com.live2d.graphics.CImageResource r, com.live2d.type.CAffine a, boolean p){}
            }
            """);
        source(files, "com.live2d.graphics3d.component.a.c", "public class c {}");
        source(
                files,
                "com.live2d.graphics3d.component.a.c$b",
                "public class c$b extends c {public static final c$b a=new c$b();}");
        source(files, "com.live2d.graphics3d.component.GTransform", """
            import com.live2d.graphics3d.type.GVector3;
            public class GTransform {private final GVector3 p=new GVector3(); public GVector3 getPosition(){return p;}}
            """);
        source(files, "com.live2d.graphics3d.component.GMeshRenderer", """
            public class GMeshRenderer {
                public void setSortingLayer(com.live2d.graphics3d.component.a.c l){}
                public void setOrderInLayer(int o){}
            }
            """);
        source(files, "com.live2d.graphics3d.entity.Entities", """
            public class Entities {
                public void add(com.live2d.graphics3d.entity.GEntity e, int i){e.parent=this;}
            }
            """);
        source(files, "com.live2d.graphics3d.entity.GEntity", """
            import com.live2d.graphics3d.component.GTransform;
            public class GEntity {
                public Object parent;
                public boolean enabled;
                private final GTransform t=new GTransform();
                private final Entities c=new Entities();
                public GTransform getTransform(){return t;}
                public GEntity getParentEntity(){return null;}
                public Entities getChildren(){return c;}
                public void setEnabled(boolean e){enabled=e;}
            }
            """);
        source(files, "com.live2d.graphics3d.geometry.GImageEntity", """
            import com.live2d.graphics3d.component.GMeshRenderer;
            import com.live2d.graphics3d.texture.GTexture;
            import com.live2d.graphics3d.type.GRectF;
            public class GImageEntity extends com.live2d.graphics3d.entity.GEntity {
                private final GMeshRenderer r=new GMeshRenderer();
                public GImageEntity(String n, GTexture t, boolean f, GRectF b){}
                public GMeshRenderer getMeshRenderer(){return r;}
            }
            """);
        source(files, "com.live2d.graphics3d.sceneGraph.GSceneGraph", """
            import com.live2d.graphics3d.entity.GEntity;
            public class GSceneGraph extends GEntity {
                private final GEntity comp=new GEntity();
                public GEntity getObjectsOnComponent(){return comp;}
                public void setVolatile(GEntity e){e.setEnabled(true);}
            }
            """);
        source(files, "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource", "public class CArtMeshSource {}");
        source(files, "com.live2d.cubism.doc.modeling.CModelingEditMode_Main", """
            public class CModelingEditMode_Main {
                public final Selector selector=new Selector(); public final Model model=new Model();
                public Selector getSelector(){return selector;}public Model getCurrentModel(){return model;}
                public static class Selector {public java.util.List<Selection> getSelected(){return java.util.List.of(new Selection());}}
                public static class Selection {
                    public Object getSource(){return new com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource();}
                    public String getGuid(){return "one";}}
                public static class Model {final Object source=new Object();
                    public Object getSource(){return source;}public Mesh getArtMesh(String id){return new Mesh();}}
                public static class Mesh {public Form getCalculatedForm(){return new Form();}}
                public static class Form {public float[] getPositions(){return new float[]{10000000,10000000,10000020,10000020};}}
            }
            """);
        source(files, "com.live2d.cubism.view.context.CEViewContext_ModelingView", """
            import com.live2d.graphics3d.sceneGraph.GSceneGraph;
            import com.live2d.graphics3d.type.GVector2;
            import com.live2d.type.*;
            public class CEViewContext_ModelingView {
                public final com.live2d.cubism.doc.modeling.CModelingEditMode_Main edit=new com.live2d.cubism.doc.modeling.CModelingEditMode_Main();
                public final Camera camera=new Camera();public final Drawer drawer=new Drawer();
                public final GSceneGraph scene=new GSceneGraph();
                public Object getCurrentEditMode(){return edit;}public Camera getCameraManager(){return camera;}
                public int repaints; public void repaintCanvas(){repaints++;}
                public static class NativeMouse extends java.awt.event.MouseEvent {
                    public NativeMouse(int id,int x,int y,int clicks){
                        super(new java.awt.Canvas(),id,1L,0,x,y,clicks,false,java.awt.event.MouseEvent.BUTTON1);}}
                public static class NativeEvent {
                    public final NativeMouse mouse; public final CPoint point;
                    public NativeEvent(NativeMouse m){mouse=m;point=new CPoint(m.getX(),m.getY());}
                    public NativeMouse f(){return mouse;} public CPoint K(){return point;}}
                public Render getRenderSystem(){return new Render();}public GSceneGraph getSceneGraph(){return scene;}
                public class Render {public Drawer b(){return drawer;}}
                public static class Rect {public int getX(){return 0;}public int getY(){return 0;}public int getWidth(){return 1000;}public int getHeight(){return 800;}}
                public static class Camera {public float zoom=1; public int unprojects;
                    public boolean isSafeEditOnPlaneXY(){return true;}public Rect getComponentRect(){return new Rect();}
                    public GVector2 documentToComponent(GVector2 p){return new GVector2(500+(p.getX()-10000010)*zoom,400+(p.getY()-10000010)*zoom);}
                    public GVector2 componentToDocument(CPoint p){unprojects++;return new GVector2((p.x-500)/zoom+10000010,(p.y-400)/zoom+10000010);}}
                public static class Drawer {
                    public void a(Object ignored){}public void a(int order){}
                    public void a(GVector2 a,GVector2 b,CColor c,float width,float z){}
                    public void a(String s,CFont f,float x,float y,float z,float size,CColor c){}}
            }
            """);
        Path classes = Files.createDirectories(root.resolve("label-classes"));
        var args = new ArrayList<>(List.of("-d", classes.toString()));
        args.addAll(files);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static Object mouse(final ClassLoader loader, final int id, final int x, final int y, final int clickCount)
            throws Exception {
        return loader.loadClass("com.live2d.cubism.view.context.CEViewContext_ModelingView$NativeMouse")
                .getConstructor(int.class, int.class, int.class, int.class)
                .newInstance(id, x, y, clickCount);
    }

    private static Object event(final ClassLoader loader, final Object mouse) throws Exception {
        final Class<?> type = loader.loadClass("com.live2d.cubism.view.context.CEViewContext_ModelingView$NativeEvent");
        final Class<?> mouseType =
                loader.loadClass("com.live2d.cubism.view.context.CEViewContext_ModelingView$NativeMouse");
        return type.getConstructor(mouseType).newInstance(mouse);
    }

    private URLClassLoader hostForPointDrag() throws Exception {
        var files = new ArrayList<String>();
        source(files, "com.live2d.graphics3d.type.GVector2", """
            public class GVector2 {float x,y; public GVector2(float x,float y){this.x=x;this.y=y;}
            public float getX(){return x;} public float getY(){return y;}
            public void setX(float x){this.x=x;} public void setY(float y){this.y=y;}}
            """);
        source(files, "com.live2d.type.CPoint", """
            public class CPoint {public int x,y;public CPoint(int x,int y){this.x=x;this.y=y;}}
            """);
        source(files, "com.live2d.type.CColor", "public class CColor {public CColor(int r,int g,int b){}}");
        source(files, "com.live2d.type.CFont", "public class CFont {public CFont(int size){}}");
        source(files, "com.live2d.doc.CoordType", "public class CoordType {}");
        source(files, "com.live2d.doc.selection.ITransformBetweenLocalAndCanvas", """
            import com.live2d.graphics3d.type.GVector2;
            public interface ITransformBetweenLocalAndCanvas {
                GVector2 transformLocalToCanvas(GVector2 src, GVector2 dst, int index);
                GVector2 transformCanvasToLocal(GVector2 src, GVector2 dst, int index);
            }
            """);
        source(files, "com.live2d.type.CDrawableGuid", "public class CDrawableGuid {}");
        source(files, "com.live2d.type.CDeformerGuid", "public class CDeformerGuid {}");
        source(files, "com.live2d.cubism.doc.model.id.CDrawableId", "public class CDrawableId {}");
        source(files, "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource", """
            import com.live2d.cubism.doc.model.id.CDrawableId;
            public class CArtMeshSource {
                private final CDrawableId id = new CDrawableId();
                public CDrawableId getId() { return id; }
            }
            """);
        source(files, "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm", """
            import com.live2d.doc.selection.ITransformBetweenLocalAndCanvas;
            public class CArtMeshForm {
                public float[] positions = new float[] {-10f, 0f, 10f, 0f};
                public int updatedCalls;
                public float[] getPositions() { return positions; }
                public void setUpdated() { updatedCalls++; }
                public MeshPointRefEx getCompatiblePointRefEx(
                        MeshPointRef ref, ITransformBetweenLocalAndCanvas transform) {
                    return new MeshPointRefEx(ref, transform, this);
                }
            }
            """);
        source(files, "com.live2d.cubism.doc.model.drawable.artMesh.MeshPointRef", """
            import com.live2d.doc.CoordType;
            import com.live2d.graphics3d.type.GVector2;
            public class MeshPointRef {
                private final CArtMeshSource source;
                private final int index;
                private final float[] positions;
                private final int step;
                private final CoordType coord;
                private final CArtMeshForm form;
                public MeshPointRef(CArtMeshSource s, int i, float[] p, int st, CoordType c, CArtMeshForm f) {
                    source=s; index=i; positions=p; step=st; coord=c; form=f;
                }
                public int a() { return index; }
                public CoordType c() { return coord; }
                public CArtMeshSource d() { return source; }
                public CArtMeshForm g() { return form; }
                public float[] h() { return positions; }
                public int i() { return step; }
                public GVector2 getPos() { return new GVector2(positions[index * 2], positions[index * 2 + 1]); }
                public void moveToOnLocal(GVector2 target, float weight) {
                    positions[index * 2] = positions[index * 2] * (1f - weight) + target.getX() * weight;
                    positions[index * 2 + 1] = positions[index * 2 + 1] * (1f - weight) + target.getY() * weight;
                    if (form != null) form.setUpdated();
                }
            }
            """);
        source(files, "com.live2d.cubism.doc.model.drawable.artMesh.MeshPointRefEx", """
            import com.live2d.doc.selection.ITransformBetweenLocalAndCanvas;
            import com.live2d.graphics3d.type.GVector2;
            public class MeshPointRefEx extends MeshPointRef {
                private final ITransformBetweenLocalAndCanvas transformBetweenLocalAndCanvas;
                public MeshPointRefEx(MeshPointRef base, ITransformBetweenLocalAndCanvas t, CArtMeshForm f) {
                    super(base.d(), base.a(), base.h(), base.i(), base.c(), f);
                    transformBetweenLocalAndCanvas = t;
                }
                public GVector2 e() {
                    GVector2 out = transformBetweenLocalAndCanvas == null
                            ? null : transformBetweenLocalAndCanvas.transformLocalToCanvas(getPos(), null, a());
                    return out != null ? out : getPos();
                }
                public void a(GVector2 target, float weight) {
                    GVector2 local = transformBetweenLocalAndCanvas == null
                            ? null : transformBetweenLocalAndCanvas.transformCanvasToLocal(target, null, a());
                    moveToOnLocal(local != null ? local : target, weight);
                }
            }
            """);
        source(files, "com.live2d.cubism.doc.modeling.CModelingEditMode_Main", """
            import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm;
            import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource;
            public class CModelingEditMode_Main {
                public final Selector selector = new Selector();
                public final Model model = new Model();
                public Selector getSelector() { return selector; }
                public Model getCurrentModel() { return model; }
                public static class Selector {
                    public int selectedCalls;
                    public java.util.List<Selection> getSelected() {
                        selectedCalls++;
                        return java.util.List.of(new Selection());
                    }
                }
                public static class Selection {
                    public Object getSource() { return new CArtMeshSource(); }
                    public String getGuid() { return "one"; }
                }
                public static class Model {
                    final Object source = new Object();
                    final Mesh mesh = new Mesh();
                    final com.live2d.doc.selection.ITransformBetweenLocalAndCanvas identity =
                            new com.live2d.doc.selection.ITransformBetweenLocalAndCanvas() {
                                public com.live2d.graphics3d.type.GVector2 transformLocalToCanvas(
                                        com.live2d.graphics3d.type.GVector2 s,
                                        com.live2d.graphics3d.type.GVector2 d,
                                        int i) {
                                    return s;
                                }
                                public com.live2d.graphics3d.type.GVector2 transformCanvasToLocal(
                                        com.live2d.graphics3d.type.GVector2 s,
                                        com.live2d.graphics3d.type.GVector2 d,
                                        int i) {
                                    return s;
                                }
                            };
                    public Object getSource() { return source; }
                    public Mesh getArtMesh(com.live2d.type.CDrawableGuid guid) { return mesh; }
                    public Mesh getArtMesh(com.live2d.cubism.doc.model.id.CDrawableId id) { return mesh; }
                    public Mesh getArtMesh(String id) { return mesh; }
                    public com.live2d.doc.selection.ITransformBetweenLocalAndCanvas getDeformerTransform(
                            com.live2d.type.CDeformerGuid guid) {
                        return identity;
                    }
                }
                public static class Mesh {
                    private final CArtMeshSource source = new CArtMeshSource();
                    private final CArtMeshForm form = new CArtMeshForm();
                    private final com.live2d.type.CDeformerGuid deformerGuid = new com.live2d.type.CDeformerGuid();
                    public CArtMeshSource getSource() { return source; }
                    public CArtMeshForm getCalculatedForm() { return form; }
                    public com.live2d.type.CDeformerGuid getTargetDeformerGuid() { return deformerGuid; }
                }
            }
            """);
        source(files, "com.live2d.doc.selection.PointSelector", """
            public class PointSelector {
                public int setWeightCalls;
                public float lastWeight;
                public Object getCompatible(Object ref) { return ref; }
                public void setWeight(Object ref, float w) { setWeightCalls++; lastWeight = w; }
                public boolean add(Object ref, float w, boolean reorder) { return true; }
            }
            """);
        source(files, "com.live2d.cubism.view.context.CEViewContext", "public class CEViewContext {}");
        source(files, "com.live2d.cubism.view.context.actionManager.CEActionManager", """
            import com.live2d.cubism.view.context.CEViewContext;
            public class CEActionManager {
                private final CEViewContext viewContext;
                public CEActionManager(CEViewContext viewContext) { this.viewContext = viewContext; }
                public final CEViewContext getViewContext() { return viewContext; }
            }
            """);
        source(files, "com.live2d.cubism.view.context.CEViewContext_ModelingView", """
            import com.live2d.graphics3d.type.GVector2;
            import com.live2d.type.*;
            public class CEViewContext_ModelingView extends com.live2d.cubism.view.context.CEViewContext {
                public final com.live2d.cubism.doc.modeling.CModelingEditMode_Main edit =
                        new com.live2d.cubism.doc.modeling.CModelingEditMode_Main();
                public final Camera camera = new Camera();
                public final Drawer drawer = new Drawer();
                public Object getCurrentEditMode() { return edit; }
                public Camera getCameraManager() { return camera; }
                public void repaintCanvas() {}
                public static class NativeMouse extends java.awt.event.MouseEvent {
                    public NativeMouse(int id, int x, int y, int clicks) {
                        super(new java.awt.Canvas(), id, 1L, 0, x, y, clicks, false, java.awt.event.MouseEvent.BUTTON1);
                    }
                }
                public static class NativeEvent {
                    public final NativeMouse mouse;
                    public final CPoint point;
                    public NativeEvent(NativeMouse m) { mouse = m; point = new CPoint(m.getX(), m.getY()); }
                    public NativeMouse f() { return mouse; }
                    public CPoint K() { return point; }
                }
                public Render getRenderSystem() { return new Render(); }
                public Scene getSceneGraph() { return new Scene(); }
                public class Render { public Drawer b() { return drawer; } }
                public static class Scene { public Object getObjectsOnComponent() { return this; } }
                public static class Rect {
                    public int getX() { return 0; }
                    public int getY() { return 0; }
                    public int getWidth() { return 1000; }
                    public int getHeight() { return 800; }
                }
                public static class Camera {
                    public boolean isSafeEditOnPlaneXY() { return true; }
                    public Rect getComponentRect() { return new Rect(); }
                    public GVector2 documentToComponent(GVector2 p) {
                        return new GVector2(500 + p.getX(), 400 + p.getY());
                    }
                    public GVector2 componentToDocument(CPoint p) {
                        return new GVector2(p.x - 500, p.y - 400);
                    }
                }
                public static class Drawer {
                    public void a(Object ignored) {}
                    public void a(int order) {}
                    public void a(GVector2 a, GVector2 b, CColor c, float width, float z) {}
                    public void a(String s, CFont f, float x, float y, float z, float size, CColor c) {}
                }
            }
            """);
        Path classes = Files.createDirectories(root.resolve("point-drag-classes"));
        var args = new ArrayList<>(List.of("-d", classes.toString()));
        args.addAll(files);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
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
                public int repaints; public void repaintCanvas(){repaints++;}
                public static class NativeMouse extends java.awt.event.MouseEvent {
                    public NativeMouse(int id,int x,int y,int clicks){
                        super(new java.awt.Canvas(),id,1L,0,x,y,clicks,false,java.awt.event.MouseEvent.BUTTON1);}}
                public static class NativeEvent {
                    public final NativeMouse mouse; public final CPoint point;
                    public NativeEvent(NativeMouse m){mouse=m;point=new CPoint(m.getX(),m.getY());}
                    public NativeMouse f(){return mouse;} public CPoint K(){return point;}}
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
