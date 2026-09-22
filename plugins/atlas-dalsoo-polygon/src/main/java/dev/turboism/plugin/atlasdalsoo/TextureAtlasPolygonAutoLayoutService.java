package dev.turboism.plugin.atlasdalsoo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.turboism.plugin.atlasdalsoo.layout.DalsooPolygonPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutlineSource;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutSnapshot;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan;

/**
 * Runs the polygon packing path for the native automatic-layout entry:
 * read outlines -> apply configured per-item policies -> plan -> validate+apply.
 */
final class TextureAtlasPolygonAutoLayoutService {

    private final TextureAtlasPolygonLayoutService layouts;
    private final Supplier<PolygonLayoutSettings> settings;
    private final Consumer<String> logger;

    TextureAtlasPolygonAutoLayoutService(final TextureAtlasPolygonLayoutService layouts,
        final Supplier<PolygonLayoutSettings> settings, final Consumer<String> logger) {
        this.layouts = Objects.requireNonNull(layouts, "layouts");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = logger == null ? m -> { } : logger;
    }

    TextureAtlasLayoutApplyResult applyAutomaticLayout(final boolean parallel) {
        final Optional<TextureAtlasPolygonLayoutSnapshot> maybeSnapshot;
        try {
            maybeSnapshot = layouts.currentPolygon();
        } catch (UnsupportedOperationException unavailable) {
            return TextureAtlasLayoutApplyResult.failed(
                TextureAtlasLayoutFailureCode.CAPABILITY_UNAVAILABLE,
                "Polygon layout service is not installed for this host.");
        }
        if (maybeSnapshot.isEmpty()) {
            return TextureAtlasLayoutApplyResult.failed(
                TextureAtlasLayoutFailureCode.CAPABILITY_UNAVAILABLE,
                "No active texture atlas page is available.");
        }
        final TextureAtlasPolygonLayoutSnapshot snapshot = maybeSnapshot.get();
        final PolygonLayoutSettings policy = settings.get();
        final List<TextureAtlasPolygonItem> items = new ArrayList<>(snapshot.items().size());
        for (final TextureAtlasPolygonItem item : snapshot.items()) {
            final TextureAtlasItemLayoutPolicy itemPolicy =
                policy.policyFor(snapshot.modelId(), item.textureId());
            items.add(new TextureAtlasPolygonItem(item.textureId(), item.width(),
                item.height(), item.outline(), itemPolicy, item.outlineSource(),
                item.currentMatrix(), item.currentlyPlaced()));
        }
        // Exact-host diagnostics: outline provenance is the contour-extraction
        // evidence reviewers read from the plugin log.
        int drawDataShapes = 0;
        int boundsFallback = 0;
        int ringTotal = 0;
        int vertexTotal = 0;
        for (final TextureAtlasPolygonItem item : items) {
            if (item.outlineSource() == TextureAtlasOutlineSource.DRAW_DATA_SHAPES) {
                drawDataShapes++;
            } else {
                boundsFallback++;
            }
            ringTotal += item.outline().rings().size();
            for (final double[][] ring : item.outline().rings()) {
                vertexTotal += ring.length;
            }
        }
        logger.accept("dalsoo snapshot: items=" + items.size()
            + " outlineDrawDataShapes=" + drawDataShapes
            + " outlineBoundsFallback=" + boundsFallback
            + " rings=" + ringTotal + " vertices=" + vertexTotal
            + " page=" + snapshot.constraints().pageWidth() + "x"
            + snapshot.constraints().pageHeight()
            + " rotationMode=" + snapshot.constraints().rotationMode()
            + " requestedScale=" + snapshot.constraints().requestedScale()
            + " state=" + stateDigest(items));
        // the session's issued bounds win: the host dialog asked for
        // rotationMode/requestedScale, so a wider plugin rotation degrades
        // honestly and a fixed session scale is honored exactly
        var rotation = policy.rotation();
        if (rotation.ordinal() > snapshot.constraints().rotationMode().ordinal()) {
            logger.accept("dalsoo rotation " + rotation + " degraded to "
                + snapshot.constraints().rotationMode() + " by the issued session");
            rotation = snapshot.constraints().rotationMode();
        }
        final double requestedScale = snapshot.constraints().requestedScale() > 0
            ? snapshot.constraints().requestedScale()
            : (policy.automaticScale() ? 0 : policy.fixedScale());
        final var constraints = new TextureAtlasPolygonConstraints(
            snapshot.constraints().pageWidth(), snapshot.constraints().pageHeight(),
            snapshot.constraints().margin(),
            rotation,
            requestedScale,
            policy.backend() == TextureAtlasLayoutBackend.HOST_NATIVE
                ? TextureAtlasLayoutBackend.DALSOO_POLYGON : policy.backend(),
            policy.quality());
        final TextureAtlasPolygonPlan plan;
        try {
            plan = new DalsooPolygonPlanner().plan(items, constraints, parallel);
        } catch (RuntimeException failure) {
            return TextureAtlasLayoutApplyResult.failed(
                TextureAtlasLayoutFailureCode.PLAN_INVALID,
                "Polygon planner failed: " + failure.getMessage());
        }
        logger.accept("dalsoo plan: placements=" + plan.placements().size()
            + " overflow=" + plan.overflowTextureIds().size()
            + " scale=" + plan.scale() + " " + plan.diagnostics());
        final TextureAtlasLayoutApplyResult result = layouts.apply(snapshot.target(), plan);
        // Post-apply readback is live (the session reads issued transforms per
        // call), so a second snapshot inside the same invocation yields the
        // post-write state digest.
        if (result.status().isPresent()) {
            try {
                layouts.currentPolygon().ifPresent(post ->
                    logger.accept("dalsoo applied: state=" + stateDigest(post.items())
                        + " status=" + result.status().orElseThrow()));
            } catch (RuntimeException reread) {
                logger.accept("dalsoo applied: state=unreadable"
                    + " status=" + result.status().orElseThrow());
            }
        }
        return result;
    }

    /**
     * Stable digest of the issued per-item placement state (texture id, placed
     * flag, current matrix). Exact-host diagnostics let a probe prove
     * undo/redo/persistence by comparing the digest across invocations without
     * relying on version-specific host internals.
     */
    private static String stateDigest(final List<TextureAtlasPolygonItem> items) {
        final StringBuilder canonical = new StringBuilder();
        items.stream()
            .sorted(java.util.Comparator.comparing(TextureAtlasPolygonItem::textureId))
            .forEach(item -> {
                canonical.append(item.textureId()).append('|')
                    .append(item.currentlyPlaced() ? '1' : '0');
                final double[] matrix = item.currentMatrix();
                if (matrix != null) {
                    for (final double v : matrix) {
                        canonical.append('|').append(v);
                    }
                }
                canonical.append('\n');
            });
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)))
                .substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException failure) {
            return "sha-unavailable";
        }
    }
}
