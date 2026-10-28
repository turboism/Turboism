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
import java.util.ArrayList;
import java.util.List;
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
}
