package dev.turboism.plugin.atlasdalsoo;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import dev.turboism.plugin.atlasdalsoo.layout.DalsooPolygonPlanner;
import dev.turboism.plugin.atlasdalsoo.layout.RectPathPolygonPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

/**
 * Polygon-aware texture-atlas packing on the ported MIT Dalsoo kernel.
 *
 * <p>Registers the {@code dalsoo} algorithm (polygon packing) in the shared
 * algorithm registry and takes the native automatic-layout callback when
 * available. The polygon path reads real item outlines through
 * {@code textureAtlasPolygonLayouts()}; when another plugin owns the callback the
 * {@code dalsoo} algorithm still works through the rectangle dialog path with
 * bounding-box outlines, which is reported honestly as a degraded mode.</p>
 */
public final class TextureAtlasDalsooPlugin implements TurboismPlugin {

    static final String NATIVE_AUTO_LAYOUT_CALLBACK_KEY =
        "dev.turboism.texture-atlas.auto-layout.callback";
    static final String DIALOG_ALGORITHM_KEY = "dev.turboism.texture-atlas.dialog.algorithm";
    static final String DIALOG_PARALLEL_KEY = "dev.turboism.texture-atlas.dialog.parallel";
    static final String ALGORITHM_DALSOO = "dalsoo";

    private PluginContext context;
    private boolean enabled;
    private final PolygonLayoutSettingsBinding settings = new PolygonLayoutSettingsBinding();
    private final BooleanSupplier nativeAutoLayoutCallback = this::applyFromNativeEntry;
    private TextureAtlasPolygonAutoLayoutService polygonService;
    private RectAutoLayoutDelegate rectDelegate;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        if (!settings.init(context.config()).toCompletableFuture().join()) {
            throw new IllegalStateException(
                "Atlas Dalsoo configuration schema registration failed.");
        }
        context.logger().info("Atlas Dalsoo polygon packing initialized");
    }

    @Override
    public void enable() {
        requireContext();
        if (!settings.enable().toCompletableFuture().join()) {
            throw new IllegalStateException(
                "Atlas Dalsoo configuration could not be loaded.");
        }
        enabled = true;
        registerAlgorithm();
        composeServices();
        System.getProperties().putIfAbsent(NATIVE_AUTO_LAYOUT_CALLBACK_KEY,
            nativeAutoLayoutCallback);
        publishDialogState();
        context.logger().info("Atlas Dalsoo polygon packing enabled");
    }

    private void registerAlgorithm() {
        try {
            final TextureAtlasLayoutAlgorithmRegistry registry =
                context.cubism().textureAtlasAlgorithms();
            context.disposableScope().register(registry.register(
                new TextureAtlasLayoutAlgorithm(
                    ALGORITHM_DALSOO,
                    context.localization().text("texture-atlas.algorithm.dalsoo"),
                    true,
                    new RectPathPolygonPlanner(() -> settings.confirmed())
                )
            ));
        } catch (Throwable failure) {
            context.logger().warn("Atlas Dalsoo algorithm registration failed safely: "
                + failure);
        }
    }

    private void composeServices() {
        polygonService = new TextureAtlasPolygonAutoLayoutService(
            context.cubism().textureAtlasPolygonLayouts(),
            () -> settings.confirmed(),
            message -> context.logger().info(message));
        rectDelegate = new RectAutoLayoutDelegate(context.cubism().textureAtlasLayouts());
    }

    private boolean applyFromNativeEntry() {
        try {
            final String algorithm = System.getProperty(DIALOG_ALGORITHM_KEY,
                ALGORITHM_DALSOO);
            final boolean parallel = "true".equals(
                System.getProperty(DIALOG_PARALLEL_KEY, "false"));
            if (ALGORITHM_DALSOO.equals(algorithm)) {
                final TextureAtlasLayoutApplyResult result =
                    polygonService.applyAutomaticLayout(parallel);
                if (result.status().isPresent()) {
                    context.logger().info("Atlas Dalsoo automatic-layout status="
                        + result.status().orElseThrow());
                    return true;
                }
                context.logger().warn("Atlas Dalsoo automatic-layout failureCode="
                    + result.failureCode().orElseThrow());
                return false;
            }
            // another algorithm selected through the shared dialog: run its rect
            // planner if one is registered, else delegate to the host
            final var planner = context.cubism().textureAtlasAlgorithms()
                .find(algorithm)
                .map(TextureAtlasLayoutAlgorithm::planner)
                .orElse(null);
            if (planner == null) {
                return false;
            }
            return rectDelegate.applyAutomaticLayout(planner, parallel)
                .status().isPresent();
        } catch (RuntimeException | Error failure) {
            if (context != null) {
                context.logger().error(
                    "Atlas Dalsoo native automatic-layout entry failed safely.", failure);
            }
            return false;
        }
    }

    private void publishDialogState() {
        if (System.getProperty(DIALOG_ALGORITHM_KEY) == null) {
            System.getProperties().put(DIALOG_ALGORITHM_KEY, ALGORITHM_DALSOO);
        }
        System.getProperties().putIfAbsent(DIALOG_PARALLEL_KEY,
            String.valueOf(settings.confirmed().parallel()));
    }

    @Override
    public void disable() {
        removeNativeCallback();
        enabled = false;
        settings.disable();
    }

    @Override
    public void shutdown() {
        removeNativeCallback();
        enabled = false;
        settings.shutdown();
        context = null;
        polygonService = null;
        rectDelegate = null;
    }

    private void removeNativeCallback() {
        final Object value = System.getProperties().get(NATIVE_AUTO_LAYOUT_CALLBACK_KEY);
        if (value == nativeAutoLayoutCallback) {
            System.getProperties().remove(NATIVE_AUTO_LAYOUT_CALLBACK_KEY);
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException(
                "Atlas Dalsoo plugin must be initialized before enable.");
        }
    }
}
