package dev.turboism.plugin.atlasdalsoo;

import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import dev.turboism.plugin.atlasdalsoo.layout.DalsooPolygonPlanner;
import dev.turboism.plugin.atlasdalsoo.layout.RectPathPolygonPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;
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
    static final String DIALOG_ROTATION_KEY = "dev.turboism.texture-atlas.dialog.rotation";
    static final String DIALOG_LOCK_PRESET_KEY = "dev.turboism.texture-atlas.dialog.lock-preset";
    static final String DIALOG_AUTO_SCALE_KEY = "dev.turboism.texture-atlas.dialog.auto-scale";
    static final String DIALOG_FIXED_SCALE_PERCENT_KEY = "dev.turboism.texture-atlas.dialog.fixed-scale-percent";
    static final String DIALOG_AUTO_SCALE_TOLERANCE_KEY = "dev.turboism.texture-atlas.dialog.auto-scale-tolerance";
    static final String DIALOG_AUTO_SCALE_MAX_TRY_KEY = "dev.turboism.texture-atlas.dialog.auto-scale-max-try";
    static final String DIALOG_KERNEL_KEY = "dev.turboism.texture-atlas.dialog.kernel";
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
                syncDialogState();
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
        final PolygonLayoutSettings confirmed = settings.confirmed();
        if (System.getProperty(DIALOG_ALGORITHM_KEY) == null) {
            System.getProperties().put(DIALOG_ALGORITHM_KEY, ALGORITHM_DALSOO);
        }
        System.getProperties().putIfAbsent(DIALOG_PARALLEL_KEY,
            String.valueOf(confirmed.parallel()));
        System.getProperties().putIfAbsent(DIALOG_ROTATION_KEY,
            confirmed.rotation().name());
        System.getProperties().putIfAbsent(DIALOG_LOCK_PRESET_KEY,
            confirmed.lockPreset().name());
        System.getProperties().putIfAbsent(DIALOG_AUTO_SCALE_KEY,
            String.valueOf(confirmed.automaticScale()));
        System.getProperties().putIfAbsent(DIALOG_FIXED_SCALE_PERCENT_KEY,
            String.valueOf((int) Math.round(confirmed.fixedScale() * 100)));
        System.getProperties().putIfAbsent(DIALOG_AUTO_SCALE_TOLERANCE_KEY,
            String.valueOf((int) Math.round(confirmed.autoScaleTolerance() * 1000)));
        System.getProperties().putIfAbsent(DIALOG_AUTO_SCALE_MAX_TRY_KEY,
            String.valueOf(confirmed.autoScaleMaxTry()));
        System.getProperties().putIfAbsent(DIALOG_KERNEL_KEY,
            confirmed.useAbey() ? "abey" : "dalalah");
    }

    /**
     * Reads the dialog bridge properties (malformed values fall back to the
     * confirmed setting) and persists the merged policy.
     */
    private void syncDialogState() {
        final PolygonLayoutSettings confirmed = settings.confirmed();
        final String kernel = System.getProperty(DIALOG_KERNEL_KEY, "");
        final int tolerancePermille = intProperty(DIALOG_AUTO_SCALE_TOLERANCE_KEY,
            0, 1000, (int) Math.round(confirmed.autoScaleTolerance() * 1000));
        final PolygonLayoutSettings merged = new PolygonLayoutSettings(
            confirmed.backend(),
            enumProperty(DIALOG_ROTATION_KEY, TextureAtlasRotationMode.class,
                confirmed.rotation()),
            confirmed.quality(),
            booleanProperty(DIALOG_AUTO_SCALE_KEY, confirmed.automaticScale()),
            intProperty(DIALOG_FIXED_SCALE_PERCENT_KEY, 1, 800,
                (int) Math.round(confirmed.fixedScale() * 100)) / 100.0,
            "abey".equalsIgnoreCase(kernel) ? true
                : "dalalah".equalsIgnoreCase(kernel) ? false
                    : confirmed.useAbey(),
            booleanProperty(DIALOG_PARALLEL_KEY, confirmed.parallel()),
            enumProperty(DIALOG_LOCK_PRESET_KEY, PolygonLayoutLockPreset.class,
                confirmed.lockPreset()),
            tolerancePermille <= 0
                ? PolygonLayoutSettings.DEFAULT_AUTO_SCALE_TOLERANCE
                : tolerancePermille / 1000.0,
            intProperty(DIALOG_AUTO_SCALE_MAX_TRY_KEY, 0, 64,
                confirmed.autoScaleMaxTry()),
            confirmed.itemPolicies());
        // join so the layout below observes the merged policy
        settings.update(merged).toCompletableFuture().join();
        if (context != null && context.localization().contains(
            "texture-atlas.dalsoo.policy")) {
            context.logger().info(context.localization().format(
                "texture-atlas.dalsoo.policy", merged.rotation().name(),
                merged.lockPreset().name(),
                merged.automaticScale() ? "auto" : String.valueOf(merged.fixedScale()),
                merged.useAbey() ? "abey" : "dalalah",
                String.valueOf(merged.parallel())));
        }
    }

    private static boolean booleanProperty(final String key,
        final boolean fallback) {
        final String value = System.getProperty(key);
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> fallback;
        };
    }

    private static int intProperty(final String key, final int min,
        final int max, final int fallback) {
        final String value = System.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            final int parsed = Integer.parseInt(value.trim());
            return parsed >= min && parsed <= max ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E enumProperty(final String key,
        final Class<E> type, final E fallback) {
        final String value = System.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    /** Persists an externally produced policy update. */
    void updateSettings(final PolygonLayoutSettings value) {
        settings.update(value);
    }

    /** The last confirmed (persisted or defaulted) layout policy. */
    PolygonLayoutSettings confirmedSettings() {
        return settings.confirmed();
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
