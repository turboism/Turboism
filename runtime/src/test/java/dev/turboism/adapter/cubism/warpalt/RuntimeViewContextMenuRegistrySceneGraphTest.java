package dev.turboism.adapter.cubism.warpalt;

import com.live2d.graphics3d.sceneGraph.GSceneGraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeViewContextMenuRegistrySceneGraphTest {

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
        final IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> RuntimeViewContextMenuRegistry.sceneGraphOf(new StripMissing()));
        assertTrue(failure.getMessage().contains("not found"));
    }

    @Test
    void ambiguousAccessorFailsClosed() {
        final IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> RuntimeViewContextMenuRegistry.sceneGraphOf(new StripAmbiguous()));
        assertTrue(failure.getMessage().contains("ambiguous"));
    }
}
