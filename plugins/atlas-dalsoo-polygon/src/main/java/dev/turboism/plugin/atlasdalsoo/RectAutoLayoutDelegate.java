package dev.turboism.plugin.atlasdalsoo;

import java.util.Objects;
import java.util.Optional;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot;

/**
 * Rectangle-path delegation for the shared native automatic-layout callback:
 * when this plugin owns the callback but the dialog selected a different
 * algorithm, its registered rectangle planner still runs correctly.
 */
final class RectAutoLayoutDelegate {

    private final TextureAtlasLayoutService layouts;

    RectAutoLayoutDelegate(final TextureAtlasLayoutService layouts) {
        this.layouts = Objects.requireNonNull(layouts, "layouts");
    }

    TextureAtlasLayoutApplyResult applyAutomaticLayout(
        final TextureAtlasLayoutPlanner planner, final boolean parallel) {
        final Optional<TextureAtlasLayoutSnapshot> snapshot;
        try {
            snapshot = layouts.current();
        } catch (UnsupportedOperationException unavailable) {
            return TextureAtlasLayoutApplyResult.failed(
                TextureAtlasLayoutFailureCode.CAPABILITY_UNAVAILABLE,
                "Layout service is not installed for this host.");
        }
        if (snapshot.isEmpty()) {
            return TextureAtlasLayoutApplyResult.failed(
                TextureAtlasLayoutFailureCode.CAPABILITY_UNAVAILABLE,
                "No active texture atlas page is available.");
        }
        final TextureAtlasLayoutSnapshot state = snapshot.get();
        final TextureAtlasLayoutPlan plan = planner.plan(state.items(),
            state.constraints(), parallel);
        return layouts.apply(state.target(), plan);
    }
}
