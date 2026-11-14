package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.live2d.graphics3d.sceneGraph.GSceneGraph;
import dev.turboism.sdk.ui.resource.UiRasterImage;
import dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry.StateButtonContribution;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RuntimeViewContextMenuRegistrySceneGraphTest {

    @Test
    void convertsSdkPixelsWithoutLosingAlphaOrRowOrder() {
        final int[] pixels = {0x80ff0000, 0xff00ff00, 0x00123456, 0xff0000ff};
        final UiRasterImage source = new UiRasterImage(2, 2, pixels);
        final var rendered = RuntimeViewContextMenuRegistry.rasterImage(source);
        assertEquals(2, rendered.getWidth());
        assertEquals(2, rendered.getHeight());
        assertArrayEquals(pixels, rendered.getRGB(0, 0, 2, 2, null, 0, 2));
        rendered.setRGB(0, 0, 0);
        assertArrayEquals(pixels, source.argb());
    }

    /** 5.2.03 shape: the scene-graph accessor is {@code d()}. */
    public static final class Strip5203 {
        private final GSceneGraph graph = new GSceneGraph();

        public GSceneGraph d() {
            return graph;
        }

        public boolean e() {
            return true;
        }
    }

    /** 5.3.x shape: the scene-graph accessor is {@code e()}. */
    public static final class Strip53 {
        private final GSceneGraph graph = new GSceneGraph();

        public boolean d() {
            return false;
        }

        public GSceneGraph e() {
            return graph;
        }
    }

    /** No no-arg method returns the scene-graph type. */
    public static final class StripMissing {
        public boolean d() {
            return true;
        }
    }

    /** Two no-arg candidates: must fail closed rather than pick one. */
    public static final class StripAmbiguous {
        public GSceneGraph d() {
            return new GSceneGraph();
        }

        public GSceneGraph e() {
            return new GSceneGraph();
        }
    }

    public static final class MockStripH {
        public final List<Object> H = new ArrayList<>();
    }

    /** 5.0–5.2 shape: E is left toolbar list, H is right view cluster list. */
    public static final class MockStrip50To52 {
        public final List<Object> E = new ArrayList<>();
        public final List<Object> H = new ArrayList<>();
    }

    /** 5.3.x shape: E is GSimpleLinesEntity (non-List), H is left toolbar list. */
    public static final class MockStrip53 {
        public final Object E = new Object();
        public final List<Object> H = new ArrayList<>();
    }

    public static final class MockChildren {
        final List<Object> list = new ArrayList<>();

        public void remove(final MockEntity entity) {
            list.remove(entity);
        }

        public List<Object> getList() {
            return list;
        }
    }

    public static final class MockParent {
        final MockChildren children = new MockChildren();

        public MockChildren getChildren() {
            return children;
        }
    }

    public static final class MockEntity {
        boolean buttonEnabled = true;
        boolean enabled = true;
        boolean disposed = false;
        MockParent parent;

        public void setButtonEnabled(final boolean v) {
            this.buttonEnabled = v;
        }

        public void setEnabled(final boolean v) {
            this.enabled = v;
        }

        public void dispose() {
            this.disposed = true;
        }

        public MockParent getParentEntity() {
            return parent;
        }
    }

    @Test
    void resolvesThe5203AccessorByReturnType() throws Exception {
        final Strip5203 strip = new Strip5203();
        assertSame(strip.graph, RuntimeViewContextMenuRegistry.sceneGraphOf(strip));
    }

    @Test
    void resolvesThe53xAccessorByReturnType() throws Exception {
        final Strip53 strip = new Strip53();
        assertSame(strip.graph, RuntimeViewContextMenuRegistry.sceneGraphOf(strip));
    }

    @Test
    void missingAccessorFailsClosed() {
        final IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> RuntimeViewContextMenuRegistry.sceneGraphOf(new StripMissing()));
        assertTrue(failure.getMessage().contains("not found"));
    }

    @Test
    void ambiguousAccessorFailsClosed() {
        final IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> RuntimeViewContextMenuRegistry.sceneGraphOf(new StripAmbiguous()));
        assertTrue(failure.getMessage().contains("ambiguous"));
    }

    @Test
    void generatesUniqueEntityNamesForDistinctContributions() {
        final String warp0 = RuntimeViewContextMenuRegistry.entityNameFor("warp-deformer-alt-symmetry.axis", 0);
        final String warp1 = RuntimeViewContextMenuRegistry.entityNameFor("warp-deformer-alt-symmetry.axis", 1);
        final String artMesh0 = RuntimeViewContextMenuRegistry.entityNameFor("artmesh-mirror.toggle", 0);
        final String artMesh1 = RuntimeViewContextMenuRegistry.entityNameFor("artmesh-mirror.toggle", 1);

        assertEquals("warpAltMirrorAxis0", warp0);
        assertEquals("warpAltMirrorAxis1", warp1);
        assertEquals("artMeshMirrorToggle0", artMesh0);
        assertEquals("artMeshMirrorToggle1", artMesh1);
        assertNotEquals(warp0, artMesh0);
        assertNotEquals(warp1, artMesh1);
    }

    @Test
    void removeButtonDisablesDetachesAndDisposes() {
        final MockEntity entity = new MockEntity();
        final MockParent parent = new MockParent();
        entity.parent = parent;
        parent.children.list.add(entity);

        RuntimeViewContextMenuRegistry.removeButton(null, entity);

        assertFalse(entity.buttonEnabled);
        assertFalse(entity.enabled);
        assertTrue(entity.disposed);
        assertFalse(parent.children.list.contains(entity));
    }

    @Test
    void indexOfInGroupFindsCorrectSeat() {
        final MockStripH strip = new MockStripH();
        final Object b1 = new Object();
        final Object b2 = new Object();
        strip.H.add(b1);
        strip.H.add(b2);

        assertEquals(0, RuntimeViewContextMenuRegistry.indexOfInGroup(strip, b1));
        assertEquals(1, RuntimeViewContextMenuRegistry.indexOfInGroup(strip, b2));
        assertEquals(-1, RuntimeViewContextMenuRegistry.indexOfInGroup(strip, new Object()));
    }

    @Test
    void insertsContributedButtonsByOrderNotRegistrationOrder() {
        final List<Object> group = new ArrayList<>(List.of("native-1", "native-2", "artmesh", "warp"));
        final RuntimeViewContextMenuRegistry.OrderedContribution artMesh = contribution("artmesh-mirror.toggle", 10);
        final RuntimeViewContextMenuRegistry.OrderedContribution warp =
                contribution("warp-deformer-alt-symmetry.axis", 20);

        assertEquals(
                2,
                RuntimeViewContextMenuRegistry.insertionIndex(
                        group, List.of("artmesh", "warp"), List.of(artMesh, warp), contribution("first", 5)));
        assertEquals(
                3,
                RuntimeViewContextMenuRegistry.insertionIndex(
                        group, List.of("artmesh", "warp"), List.of(artMesh, warp), contribution("middle", 15)));
        assertEquals(
                4,
                RuntimeViewContextMenuRegistry.insertionIndex(
                        group, List.of("artmesh", "warp"), List.of(artMesh, warp), contribution("last", 25)));
    }

    @Test
    void equalOrdersUseContributionIdAsTheStableTieBreaker() {
        final List<Object> group = new ArrayList<>(List.of("native", "z"));
        final RuntimeViewContextMenuRegistry.OrderedContribution z = contribution("z", 10);

        assertEquals(
                1,
                RuntimeViewContextMenuRegistry.insertionIndex(group, List.of("z"), List.of(z), contribution("a", 10)));
        assertEquals(
                2,
                RuntimeViewContextMenuRegistry.insertionIndex(group, List.of("z"), List.of(z), contribution("zz", 10)));
    }

    @Test
    void firstContributionFollowsNativeEntries() {
        final List<Object> group = new ArrayList<>(List.of("native-1", "native-2"));

        assertEquals(
                2, RuntimeViewContextMenuRegistry.insertionIndex(group, List.of(), List.of(), contribution("only", 0)));
    }

    private static RuntimeViewContextMenuRegistry.OrderedContribution contribution(final String id, final int order) {
        return new RuntimeViewContextMenuRegistry.OrderedContribution(
                new StateButtonContribution(
                        id, Map.of(0, new UiRasterImage(1, 1, new int[] {0xff000000})), 0, ignored -> {}),
                order);
    }

    @Test
    void groupFieldResolvesEOn50To52AndHOn53() throws Exception {
        assertEquals(
                "E",
                RuntimeViewContextMenuRegistry.groupField(MockStrip50To52.class).getName());
        assertEquals(
                "H",
                RuntimeViewContextMenuRegistry.groupField(MockStrip53.class).getName());
    }

    @Test
    void leftToolbarOperationsOn50To52OperateOnEWithoutPollutingH() throws Exception {
        final MockStrip50To52 strip = new MockStrip50To52();
        final Object b1 = new Object();
        final Object b2 = new Object();
        final Object unrelated = new Object();
        strip.E.add(b1);
        strip.E.add(b2);
        strip.H.add(unrelated);

        assertEquals(0, RuntimeViewContextMenuRegistry.indexOfInGroup(strip, b1));
        assertEquals(1, RuntimeViewContextMenuRegistry.indexOfInGroup(strip, b2));
        assertEquals(-1, RuntimeViewContextMenuRegistry.indexOfInGroup(strip, unrelated));

        RuntimeViewContextMenuRegistry.removeFromGroup(strip, b1);
        assertEquals(-1, RuntimeViewContextMenuRegistry.indexOfInGroup(strip, b1));
        assertEquals(0, RuntimeViewContextMenuRegistry.indexOfInGroup(strip, b2));
        assertEquals(1, strip.H.size());
        assertSame(unrelated, strip.H.get(0));
    }

    @Test
    void leftToolbarOperationsOn53OperateOnH() throws Exception {
        final MockStrip53 strip = new MockStrip53();
        final Object b1 = new Object();
        final Object b2 = new Object();
        strip.H.add(b1);
        strip.H.add(b2);

        assertEquals(0, RuntimeViewContextMenuRegistry.indexOfInGroup(strip, b1));
        assertEquals(1, RuntimeViewContextMenuRegistry.indexOfInGroup(strip, b2));

        RuntimeViewContextMenuRegistry.removeFromGroup(strip, b1);
        assertEquals(-1, RuntimeViewContextMenuRegistry.indexOfInGroup(strip, b1));
        assertEquals(0, RuntimeViewContextMenuRegistry.indexOfInGroup(strip, b2));
    }

    @Test
    void remountsContributedButtonsOnNewStripAndClearedGroupWhilePreservingState(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path tempDir) throws Exception {
        final java.nio.file.Path classes = compileMockStripHost(tempDir);
        try (var loader = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            final Class<?> stripClass = Class.forName("com.live2d.cubism.view.context.a.b", false, loader);
            final Object strip1 = stripClass.getConstructor().newInstance();
            final Object strip2 = stripClass.getConstructor().newInstance();

            final RuntimeViewContextMenuRegistry registry = new RuntimeViewContextMenuRegistry();
            final UiRasterImage off = new UiRasterImage(1, 1, new int[] {0xff111111});
            final UiRasterImage on = new UiRasterImage(1, 1, new int[] {0xff222222});
            registry.contributeStateButtons(
                    new StateButtonContribution("artmesh-mirror.toggle", Map.of(0, off, 1, on), 0, ignored -> {}), 10);
            registry.contributeStateButtons(
                    new StateButtonContribution(
                            "warp-deformer-alt-symmetry.axis", Map.of(0, off, 1, on), 0, ignored -> {}),
                    20);

            registry.mount(strip1);
            @SuppressWarnings("unchecked")
            final List<Object> group1 =
                    (List<Object>) stripClass.getDeclaredField("H").get(strip1);
            assertEquals(3, group1.size(), "native item + 2 contributed buttons on first strip");

            registry.updateButtonState("artmesh-mirror.toggle", 1);
            assertEquals(3, group1.size());
            assertTrue((Boolean)
                    group1.get(1).getClass().getMethod("isButtonSelected").invoke(group1.get(1)));

            // Simulate closing first model and opening a new model with a fresh strip instance.
            registry.mount(strip2);
            @SuppressWarnings("unchecked")
            final List<Object> group2 =
                    (List<Object>) stripClass.getDeclaredField("H").get(strip2);
            assertEquals(3, group2.size(), "contributed buttons must remount on new strip");
            assertEquals("artMeshMirrorToggle1", group2.get(1).toString());
            assertEquals("warpAltMirrorAxis0", group2.get(2).toString());
            assertTrue((Boolean)
                    group2.get(1).getClass().getMethod("isButtonSelected").invoke(group2.get(1)));
            assertFalse((Boolean)
                    group2.get(2).getClass().getMethod("isButtonSelected").invoke(group2.get(2)));

            // Simulate same strip instance rebuilt after group H was cleared to native entries only.
            final Object nativeEntry = group2.get(0);
            group2.clear();
            group2.add(nativeEntry);
            registry.mount(strip2);
            assertEquals(3, group2.size(), "contributed buttons must remount when group H is cleared");
            assertEquals("artMeshMirrorToggle1", group2.get(1).toString());
            assertEquals("warpAltMirrorAxis0", group2.get(2).toString());
        }
    }

    private static java.nio.file.Path compileMockStripHost(final java.nio.file.Path root) throws Exception {
        final java.nio.file.Path src = root.resolve("src");
        final java.nio.file.Path out = root.resolve("out");
        java.nio.file.Files.createDirectories(out);
        writeJava(
                src,
                "com.live2d.graphics3d.type.GRectF",
                "package com.live2d.graphics3d.type; public class GRectF { public final float w, h; public GRectF(float x, float y, float w, float h) { this.w = w; this.h = h; } public float getWidth() { return w; } public float getHeight() { return h; } }");
        writeJava(
                src,
                "com.live2d.graphics3d.entity.GEntity",
                "package com.live2d.graphics3d.entity; public class GEntity { public GEntity parent; public final ChildList children = new ChildList(); public ChildList getChildren() { return children; } public GEntity getParentEntity() { return parent; } public static class ChildList { public final java.util.List<Object> list = new java.util.ArrayList<>(); public void add(GEntity e, int idx) { e.parent = null; list.add(e); } public void remove(GEntity e) { list.remove(e); } public java.util.List<Object> getList() { return list; } } }");
        writeJava(
                src,
                "com.live2d.graphics3d.sceneGraph.GSceneGraph",
                "package com.live2d.graphics3d.sceneGraph; import com.live2d.graphics3d.entity.GEntity; public class GSceneGraph { public final GEntity objects = new GEntity(); public GEntity getObjectsOnComponent() { return objects; } }");
        writeJava(
                src,
                "com.live2d.graphics.CImageResource",
                "package com.live2d.graphics; public class CImageResource { public CImageResource(java.awt.image.BufferedImage img, boolean flag) {} }");
        writeJava(src, "com.live2d.type.CFont", "package com.live2d.type; public class CFont {}");
        writeJava(
                src,
                "kotlin.jvm.functions.Function3",
                "package kotlin.jvm.functions; public interface Function3 { Object invoke(Object a, Object b, Object c); }");
        writeJava(
                src,
                "com.live2d.cubism.view.context.guiEntity.q",
                "package com.live2d.cubism.view.context.guiEntity; import com.live2d.graphics.CImageResource; public class q { public q(CImageResource a, CImageResource b, CImageResource c, CImageResource d, CImageResource e, CImageResource f, CImageResource g) {} }");
        writeJava(
                src,
                "com.live2d.cubism.view.context.guiEntity.GToggleIconButtonEntity",
                "package com.live2d.cubism.view.context.guiEntity; import com.live2d.graphics3d.entity.GEntity; import com.live2d.graphics3d.type.GRectF; import kotlin.jvm.functions.Function3; public class GToggleIconButtonEntity extends GEntity { private final String name; private GRectF rect; private boolean buttonEnabled; private boolean buttonSelected; public GToggleIconButtonEntity(String name) { this.name = name; } public void setOnAction(Function3 fn) {} public void setButtonEnabled(boolean v) { buttonEnabled = v; } public void setEnabled(boolean v) {} public void setButtonSelected(boolean v) { buttonSelected = v; } public boolean isButtonSelected() { return buttonSelected; } public void setSelected(GEntity e) {} public GRectF getRectOnComponent() { return rect; } public void setBoundsOnComponent(GRectF r, float s) { rect = r; } public void dispose() {} @Override public String toString() { return name; } }");
        writeJava(
                src,
                "com.live2d.cubism.view.context.a.b",
                "package com.live2d.cubism.view.context.a; import com.live2d.cubism.view.context.guiEntity.*; import com.live2d.graphics3d.sceneGraph.GSceneGraph; import com.live2d.graphics3d.type.GRectF; import com.live2d.type.CFont; public class b { public final java.util.List<Object> H = new java.util.ArrayList<>(); private final GSceneGraph graph = new GSceneGraph(); public b() { H.add(\"native-0\"); } public GSceneGraph e() { return graph; } private static GToggleIconButtonEntity a(b strip, String name, CFont f, GRectF r, boolean b1, boolean b2, q icons, int sz, Object o) { return new GToggleIconButtonEntity(name); } }");

        final javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        final List<String> sources = new ArrayList<>();
        try (var walk = java.nio.file.Files.walk(src)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toString()));
        }
        final List<String> args = new ArrayList<>(List.of("-d", out.toString()));
        args.addAll(sources);
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)));
        return out;
    }

    private static void writeJava(final java.nio.file.Path root, final String fqn, final String body) throws Exception {
        final java.nio.file.Path file = root.resolve(fqn.replace('.', '/') + ".java");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, body);
    }
}
