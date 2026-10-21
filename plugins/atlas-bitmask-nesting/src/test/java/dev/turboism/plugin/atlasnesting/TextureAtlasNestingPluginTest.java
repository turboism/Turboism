package dev.turboism.plugin.atlasnesting;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.textureatlas.*;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.test.plugin.FakePluginContext;
import java.lang.reflect.Proxy;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextureAtlasNestingPluginTest {
    @Test
    void registrationLeavesSelectionAloneAndScopeCloseRemovesIt() throws Exception {
        final var registry = new Registry();
        final var facade = (CubismFacade) Proxy.newProxyInstance(
                CubismFacade.class.getClassLoader(), new Class<?>[] {CubismFacade.class}, (proxy, method, args) -> {
                    if (method.getName().equals("textureAtlasAlgorithms")) return registry;
                    if (method.getName().equals("textureAtlasPolygonLayouts")) return polygonService(true);
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
        assertFalse(algorithm.planner().supportsCompleteAtlas());
        assertTrue(registry.selection().isNative());
        assertInstanceOf(TextureAtlasPolygonLayoutPlanner.class, algorithm.planner());
        // The production facade scopes registrations; verify its cleanup callback works.
        registry.registration.close();
        assertTrue(registry.algorithms().isEmpty());
    }

    @Test
    void rectangleAdapterHonorsFixedScaleAndExplicitlyRejectsCompleteAtlas() {
        final var plugin = new TextureAtlasNestingPlugin();
        plugin.init(FakePluginContext.builder()
                .localization(FakePluginContext.passThroughLocalization())
                .build());
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

    @ParameterizedTest
    @ValueSource(strings = {"en", "ja", "ko", "zh-Hans", "zh-Hant"})
    void resolvesLocalizedNameAndRejectionsWithoutLeakingKeys(String language) {
        final var registry = new Registry();
        final var catalog = catalog(Locale.forLanguageTag(language));
        final var plugin = localizedPlugin(registry, catalog, true);
        plugin.enable();
        final var planner = (TextureAtlasPolygonLayoutPlanner)
                registry.find("bitmask-nesting").orElseThrow().planner();
        assertEquals(
                catalog.text("texture-atlas.algorithm.bitmask-nesting"),
                registry.find("bitmask-nesting").orElseThrow().displayName());
        assertFalse(catalog.text("plugin.name").startsWith("plugin."));
        final var scopeError = assertThrows(
                UnsupportedOperationException.class,
                () -> planner.plan(List.of(), new TextureAtlasLayoutConstraints(100, 100, 3, 6, 2, false, false)));
        assertEquals(catalog.text("texture-atlas.nesting.error.current-page-only"), scopeError.getMessage());
        final var duplicate =
                List.of(new TextureAtlasLayoutItem("part-A", 5, 5), new TextureAtlasLayoutItem("part-A", 5, 5));
        final var shapeError = assertThrows(
                IllegalArgumentException.class,
                () -> planner.plan(duplicate, TextureAtlasLayoutConstraints.currentPage(100, 100, 3, true, 1)));
        assertEquals(catalog.format("texture-atlas.nesting.error.duplicate-id", "part-A"), shapeError.getMessage());
        assertNotNull(shapeError.getCause());
        final var legacyRegistry = new Registry();
        final var legacy = localizedPlugin(legacyRegistry, catalog, false);
        legacy.enable();
        final var legacyPlanner =
                legacyRegistry.find("bitmask-nesting").orElseThrow().planner();
        assertFalse(legacyPlanner instanceof TextureAtlasPolygonLayoutPlanner);
        final var legacyPlan = legacyPlanner.plan(
                List.of(new TextureAtlasLayoutItem("a", 20, 30)),
                TextureAtlasLayoutConstraints.currentPage(100, 100, 3, true, .75));
        assertEquals(.75, legacyPlan.scale());
        assertEquals(1, legacyPlan.placements().size());
    }

    private static TextureAtlasNestingPlugin localizedPlugin(
            Registry registry, PluginLocalization catalog, boolean available) {
        final var facade = (CubismFacade) Proxy.newProxyInstance(
                CubismFacade.class.getClassLoader(),
                new Class<?>[] {CubismFacade.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "textureAtlasAlgorithms" -> registry;
                    case "textureAtlasPolygonLayouts" -> polygonService(available);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final var plugin = new TextureAtlasNestingPlugin();
        plugin.init(
                FakePluginContext.builder().cubism(facade).localization(catalog).build());
        return plugin;
    }

    private static TextureAtlasPolygonLayoutService polygonService(boolean available) {
        return (TextureAtlasPolygonLayoutService) Proxy.newProxyInstance(
                TextureAtlasPolygonLayoutService.class.getClassLoader(),
                new Class<?>[] {TextureAtlasPolygonLayoutService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isAvailable")) return available;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static PluginLocalization catalog(Locale locale) {
        final var bundle = ResourceBundle.getBundle("META-INF/turboism/i18n/messages", locale);
        return new PluginLocalization() {
            public Locale locale() {
                return locale;
            }

            public String text(String key) {
                return bundle.getString(key);
            }

            public String format(String key, Object... args) {
                return new MessageFormat(text(key), locale).format(args);
            }

            public boolean contains(String key) {
                return bundle.containsKey(key);
            }
        };
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
