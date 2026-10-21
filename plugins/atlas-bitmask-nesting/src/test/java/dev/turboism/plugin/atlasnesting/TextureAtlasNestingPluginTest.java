package dev.turboism.plugin.atlasnesting;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.textureatlas.*;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.test.plugin.FakePluginContext;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TextureAtlasNestingPluginTest {
    @Test
    void registrationLeavesSelectionAloneAndScopeCloseRemovesIt() throws Exception {
        final var registry = new Registry();
        final var facade = (CubismFacade) Proxy.newProxyInstance(
                CubismFacade.class.getClassLoader(), new Class<?>[] {CubismFacade.class}, (proxy, method, args) -> {
                    if (method.getName().equals("textureAtlasAlgorithms")) return registry;
                    throw new UnsupportedOperationException(method.getName());
                });
        final var context = FakePluginContext.builder()
                .cubism(facade)
                .localization(FakePluginContext.passThroughLocalization())
                .build();
        final var plugin = new TextureAtlasNestingPlugin();
        assertThrows(IllegalStateException.class, plugin::enable);
        plugin.init(context);
        plugin.enable();
        final var algorithm = registry.find("bitmask-nesting").orElseThrow();
        assertFalse(algorithm.supportsParallel());
        assertFalse(algorithm.supportsPolygonOptions());
        assertTrue(registry.selection().isNative());
        assertInstanceOf(TextureAtlasPolygonLayoutPlanner.class, algorithm.planner());
        // The production facade scopes registrations; verify its cleanup callback works.
        registry.registration.close();
        assertTrue(registry.algorithms().isEmpty());
    }

    @Test
    void rectangleAdapterHonorsFixedScaleAndExplicitlyRejectsCompleteAtlas() {
        final var plugin = new TextureAtlasNestingPlugin();
        final var planner = plugin.new Planner();
        final var items = List.of(new TextureAtlasLayoutItem("a", 25, 35), new TextureAtlasLayoutItem("b", 25, 35));
        final var plan = planner.plan(items, TextureAtlasLayoutConstraints.currentPage(100, 100, 3, true, .75));
        assertEquals(.75, plan.scale());
        assertEquals(2, plan.placements().size());
        assertEquals(1, plan.pageCount());
        assertThrows(
                UnsupportedOperationException.class,
                () -> planner.plan(items, new TextureAtlasLayoutConstraints(100, 100, 3, 6, 2, false, false)));
    }

    private static final class Registry implements TextureAtlasLayoutAlgorithmRegistry {
        final Map<String, TextureAtlasLayoutAlgorithm> algorithms = new LinkedHashMap<>();
        Registration registration;

        @Override
        public Registration register(TextureAtlasLayoutAlgorithm algorithm) {
            algorithms.put(algorithm.id(), algorithm);
            registration = () -> algorithms.remove(algorithm.id());
            return registration;
        }

        @Override
        public Optional<TextureAtlasLayoutAlgorithm> find(String id) {
            return Optional.ofNullable(algorithms.get(id));
        }

        @Override
        public List<TextureAtlasLayoutAlgorithm> algorithms() {
            return List.copyOf(algorithms.values());
        }

        @Override
        public TextureAtlasLayoutSelection selection() {
            return TextureAtlasLayoutSelection.nativeDefault();
        }

        @Override
        public void select(TextureAtlasLayoutSelection ignored) {
            fail("Registration must not select");
        }
    }
}
