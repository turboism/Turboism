package dev.turboism.plugin.atlasfolders;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.plugin.atlasdalsoo.TextureAtlasDalsooPlugin;
import dev.turboism.plugin.atlasfolders.layout.FolderTextureAtlasPlanner;
import dev.turboism.plugin.atlasfolders.layout.FolderTextureAtlasPlanner.Folder;
import dev.turboism.plugin.atlasnesting.TextureAtlasNestingPlugin;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.textureatlas.*;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.test.plugin.FakePluginContext;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises real plugin registration, selector availability and folder packing together. */
class FolderAtlasAlgorithmIntegrationTest {
    private static final String BRIDGE_PREFIX = "dev.turboism.texture-atlas.";
    private final Map<String, TextureAtlasLayoutAlgorithm> algorithms = new LinkedHashMap<>();
    private final Map<String, Object> savedProperties = new HashMap<>();
    private final TextureAtlasDalsooPlugin dalsoo = new TextureAtlasDalsooPlugin();
    private final TextureAtlasNestingPlugin bitmask = new TextureAtlasNestingPlugin();
    private FakePluginContext context;

    @BeforeEach
    void registerPlugins() {
        System.getProperties().forEach((key, value) -> {
            if (key.toString().startsWith(BRIDGE_PREFIX)) savedProperties.put(key.toString(), value);
        });
        var registry = (TextureAtlasLayoutAlgorithmRegistry) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {TextureAtlasLayoutAlgorithmRegistry.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "register" -> {
                        var algorithm = (TextureAtlasLayoutAlgorithm) args[0];
                        algorithms.put(algorithm.id(), algorithm);
                        yield (Registration) () -> algorithms.remove(algorithm.id());
                    }
                    case "algorithms" -> List.copyOf(algorithms.values());
                    case "find" -> Optional.ofNullable(algorithms.get(args[0]));
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var facade = (CubismFacade) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {CubismFacade.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "textureAtlasAlgorithms" -> registry;
                    case "textureAtlasPolygonLayouts" -> TextureAtlasPolygonLayoutService.unavailable();
                    case "textureAtlasLayouts" -> TextureAtlasLayoutService.unavailable();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        context = FakePluginContext.builder()
                .cubism(facade)
                .localization(FakePluginContext.passThroughLocalization())
                .build();
        bitmask.init(context);
        bitmask.enable();
        dalsoo.init(context);
        dalsoo.enable();
    }

    @AfterEach
    void cleanupPluginsAndDialogBridge() throws Exception {
        dalsoo.shutdown();
        bitmask.shutdown();
        if (context != null) context.disposableScope().close();
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith(BRIDGE_PREFIX));
        savedProperties.forEach((key, value) -> System.getProperties().put(key, value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bitmask-nesting", "dalsoo"})
    void registeredAlgorithmsAreSelectableAndSplitOverflowWithoutScaling(String id) {
        assertTrue(FolderAtlasDialog.algorithmOptions(context.localization(), List.copyOf(algorithms.values())).stream()
                .anyMatch(option -> option.id().equals(id) && option.available()));
        var planner = new FolderTextureAtlasPlanner(algorithms.get(id).planner(), false);
        var tree = new Folder(
                "root",
                List.of(),
                List.of(
                        new Folder("head", List.of(item("a", 90), item("b", 90)), List.of()),
                        new Folder("body", List.of(item("c", 40)), List.of())));
        var plan = planner.plan(tree, 128, 8, 3, 6);
        assertEquals(List.of("001 head", "002 head", "003 body"), plan.pageNames());
        assertEquals(
                Set.of("a", "b", "c"),
                new HashSet<>(plan.placements().stream()
                        .map(TextureAtlasPlacement::textureId)
                        .toList()));
        assertEquals(1D, plan.scale());
        for (var p : plan.placements()) {
            assertFalse(p.rotated());
            assertEquals(p.textureId().equals("c") ? 40 : 90, p.width());
            assertTrue(p.x() >= 3 && p.y() >= 3);
            assertTrue(p.x() + p.width() <= 125 && p.y() + p.height() <= 125);
        }
        assertThrows(IllegalArgumentException.class, () -> planner.plan(tree, 128, 2, 3, 6));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bitmask-nesting", "dalsoo"})
    void completeRequestsPlaceEveryImageOrReportTypedBudgetFailure(String id) {
        var planner = algorithms.get(id).planner();
        var items = List.of(item("a", 90), item("b", 90));
        var result = planner.plan(items, new TextureAtlasLayoutConstraints(128, 128, 3, 6, 2, false, false));
        assertEquals(2, result.pageCount());
        assertEquals(2, result.placements().size());
        var failure = assertThrows(
                TextureAtlasPackingException.class,
                () -> planner.plan(items, new TextureAtlasLayoutConstraints(128, 128, 3, 6, 1, false, false)));
        assertEquals(TextureAtlasPackingException.Reason.PAGE_BUDGET_EXHAUSTED, failure.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bitmask-nesting", "dalsoo"})
    void automaticPagesRetainAllItemsAndRespectSpacing(String id) {
        var planner = new FolderTextureAtlasPlanner(algorithms.get(id).planner());
        var tree = new Folder(
                "root",
                List.of(),
                List.of(
                        new Folder("head", List.of(item("a", 20), item("b", 20)), List.of()),
                        new Folder("body", List.of(item("c", 90)), List.of())));
        var result = planner.plan(tree, 128, 8, 3, 6);
        assertEquals(1, result.pageCount());
        assertEquals(new TextureAtlasPageSize(128, 128), result.pageSize(0));
        var first = result.placements().stream().filter(p -> p.pageIndex() == 0).toList();
        assertEquals(3, first.size());
        var a = first.get(0);
        var b = first.get(1);
        assertTrue(a.x() + a.width() + 6 <= b.x()
                || b.x() + b.width() + 6 <= a.x()
                || a.y() + a.height() + 6 <= b.y()
                || b.y() + b.height() + 6 <= a.y());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bitmask-nesting", "dalsoo"})
    void completeFolderLayoutUsesContoursInsteadOfImageBoundingBoxes(String id) {
        var a = new TextureAtlasLayoutItem("a", 128, 128, TextureAtlasOutline.rect(20, 20));
        var b = new TextureAtlasLayoutItem("b", 128, 128, TextureAtlasOutline.of(new double[][] {
            {70, 70}, {90, 70}, {90, 90}, {70, 90}
        }));
        var tree = new Folder(
                "root",
                List.of(),
                List.of(new Folder("left", List.of(a), List.of()), new Folder("right", List.of(b), List.of())));
        var result = new FolderTextureAtlasPlanner(algorithms.get(id).planner()).plan(tree, 64, 1, 3, 6);
        assertEquals(1, result.pageCount());
        assertTrue(result.placements().isEmpty());
        assertEquals(2, result.polygonPages().get(0).placements().size());
        assertTrue(result.polygonPages().get(0).placements().stream()
                .allMatch(p -> p.scale() == 1D && p.angleDeg() == 0D));
        assertTrue(result.polygonPages().get(0).placementFor("b").orElseThrow().x() < 0);
    }

    private static TextureAtlasLayoutItem item(String id, int edge) {
        return new TextureAtlasLayoutItem(id, edge, edge);
    }
}
