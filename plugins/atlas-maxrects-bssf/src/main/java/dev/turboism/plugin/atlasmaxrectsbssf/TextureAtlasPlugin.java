package dev.turboism.plugin.atlasmaxrectsbssf;

import dev.turboism.plugin.atlasmaxrectsbssf.layout.CurrentPageTextureAtlasPlanner;
import dev.turboism.plugin.atlasmaxrectsbssf.layout.MaxRectsBssfTextureAtlasPlanner;
import dev.turboism.plugin.atlasmaxrectsbssf.layout.PartBucketTextureAtlasPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlanner;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.List;
import java.util.Objects;

/**
 * Registers the MaxRects-BSSF packing algorithm with the runtime-owned texture-atlas
 * algorithm registry. The runtime owns algorithm selection, the native automatic-layout
 * entry, and plan application; this plugin only contributes planners and its own
 * layout-mode configuration.
 */
public final class TextureAtlasPlugin implements TurboismPlugin {

    static final String ALGORITHM_MAXRECTS = "maxrects";
    static final String ALGORITHM_NATIVE = "native";

    private PluginContext context;
    private boolean enabled;
    private final TextureAtlasSettingsBinding settings;

    public TextureAtlasPlugin() {
        this(new TextureAtlasSettingsBinding());
    }

    TextureAtlasPlugin(final TextureAtlasSettingsBinding settings) {
        this.settings = settings;
    }

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        if (!settings.init(context.config()).toCompletableFuture().join()) {
            throw new IllegalStateException("Texture Atlas configuration schema registration failed.");
        }
        context.logger().info("Texture Atlas plugin initialized");
    }

    @Override
    public void enable() {
        requireContext();
        if (!settings.enable().toCompletableFuture().join()) {
            throw new IllegalStateException("Texture Atlas configuration could not be loaded.");
        }
        enabled = true;
        registerAlgorithms();
        applyLegacySelection();
        context.logger().info("Texture Atlas automatic layout uses current-page scope; layout-mode="
            + settings.confirmed().layoutMode() + " applies only to explicit complete-atlas SDK requests.");
    }

    /** Registers this plugin's planner with the framework registry. */
    private void registerAlgorithms() {
        try {
            final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry registry =
                context.cubism().textureAtlasAlgorithms();
            // The facade binds the registration to this plugin's disposable scope and
            // waits for in-flight dispatches during teardown, so no explicit scope tie
            // (or close) is required here.
            registry.register(
                new TextureAtlasLayoutAlgorithm(
                    ALGORITHM_MAXRECTS,
                    context.localization().text("texture-atlas.algorithm.maxrects"),
                    true,
                    new TextureAtlasLayoutPlanner() {
                        @Override
                        public TextureAtlasLayoutPlan plan(
                            final List<TextureAtlasLayoutItem> items,
                            final TextureAtlasLayoutConstraints constraints
                        ) {
                            return plan(items, constraints, parallelPreference());
                        }

                        @Override
                        public TextureAtlasLayoutPlan plan(
                            final List<TextureAtlasLayoutItem> items,
                            final TextureAtlasLayoutConstraints constraints,
                            final boolean parallel
                        ) {
                            if (constraints.singlePageOptions() != null) {
                                return new CurrentPageTextureAtlasPlanner()
                                    .plan(items, constraints, parallel);
                            }
                            // Keep explicit complete-atlas SDK consumers separate from native current-page layout.
                            return settings.confirmed().layoutMode() == TextureAtlasLayoutMode.PART_BUCKET
                                ? new PartBucketTextureAtlasPlanner().plan(items, constraints)
                                : new MaxRectsBssfTextureAtlasPlanner().plan(items, constraints, parallel);
                        }
                    }
                )
            );
        } catch (Throwable failure) {
            context.logger().warn("Texture Atlas algorithm registration failed safely: " + failure);
        }
    }

    /**
     * Hands a pre-v4 persisted algorithm/parallel preference to the runtime-owned
     * selection exactly once and atomically: any explicit selection already made —
     * including the explicit native choice — wins over the migrated value, and a
     * concurrent explicit select can never be overwritten by the hand-off.
     */
    private void applyLegacySelection() {
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection legacy =
            settings.consumeLegacySelection();
        if (legacy == null) {
            return;
        }
        try {
            final var registry = context.cubism().textureAtlasAlgorithms();
            if (registry.selectIfUnset(legacy)) {
                context.logger().info(
                    "Texture Atlas migrated the persisted layout selection to runtime state: "
                        + legacy.algorithmId()
                );
            }
        } catch (RuntimeException failure) {
            context.logger().warn(
                "Texture Atlas legacy selection migration skipped safely: " + failure
            );
        }
    }

    /**
     * The runtime-owned parallel preference for explicit two-argument planner calls;
     * native dispatch supplies the flag directly through the three-argument overload.
     */
    private boolean parallelPreference() {
        try {
            final PluginContext current = context;
            return current != null
                && current.cubism().textureAtlasAlgorithms().selection().parallel();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    @Override
    public void disable() {
        enabled = false;
        settings.disable();
    }

    @Override
    public void shutdown() {
        enabled = false;
        settings.shutdown();
        context = null;
    }

    boolean isEnabled() {
        return enabled;
    }

    TextureAtlasSettings settings() {
        requireContext();
        return settings.confirmed();
    }

    boolean updateSettings(final TextureAtlasSettings value) {
        requireContext();
        return settings.update(value).toCompletableFuture().join();
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("Texture Atlas plugin must be initialized before enable.");
        }
    }
}
