package dev.turboism.plugin.textureatlas;

import dev.turboism.plugin.textureatlas.layout.TextureAtlasPackingException;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot;

import java.util.Objects;
import java.util.Optional;

/** Plugin-owned automatic layout workflow; runtime/provider own no packing policy. */
public final class TextureAtlasAutoLayoutService {

    private final TextureAtlasLayoutService layouts;
    private final TextureAtlasLayoutPlanner planner;
    private final LifecycleLease lifecycle;
    private final java.util.function.Consumer<String> log;

    public TextureAtlasAutoLayoutService(
        final TextureAtlasLayoutService layouts,
        final TextureAtlasLayoutPlanner planner
    ) {
        this(layouts, planner, LifecycleLease.alwaysActive(), ignored -> { });
    }

    public TextureAtlasAutoLayoutService(
        final TextureAtlasLayoutService layouts,
        final dev.turboism.plugin.textureatlas.layout.PartBucketTextureAtlasPlanner planner
    ) {
        this(layouts, planner::plan, LifecycleLease.alwaysActive());
    }

    TextureAtlasAutoLayoutService(
        final TextureAtlasLayoutService layouts,
        final TextureAtlasLayoutPlanner planner,
        final LifecycleLease lifecycle
    ) {
        this(layouts, planner, lifecycle, ignored -> { });
    }

    TextureAtlasAutoLayoutService(
        final TextureAtlasLayoutService layouts,
        final TextureAtlasLayoutPlanner planner,
        final LifecycleLease lifecycle,
        final java.util.function.Consumer<String> log
    ) {
        this.layouts = Objects.requireNonNull(layouts, "layouts");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.log = Objects.requireNonNull(log, "log");
    }

    public TextureAtlasLayoutApplyResult applyAutomaticLayout() {
        if (!lifecycle.isActive()) {
            return TextureAtlasLayoutApplyResult.failed(
                TextureAtlasLayoutFailureCode.RUNTIME_CLOSED,
                "Texture atlas automatic layout is unavailable while the plugin is disabled."
            );
        }
        final Optional<TextureAtlasLayoutSnapshot> snapshot = layouts.current();
        if (snapshot.isEmpty()) {
            return TextureAtlasLayoutApplyResult.failed(
                TextureAtlasLayoutFailureCode.CAPABILITY_UNAVAILABLE,
                "No active texture atlas is available."
            );
        }
        final TextureAtlasLayoutSnapshot current = snapshot.orElseThrow();
        try {
            final TextureAtlasLayoutPlan plan = planner.plan(current.items(), current.constraints());
            log.accept(
                "Texture Atlas native automatic-layout plan items=" + current.items().size()
                    + " page=" + plan.pageWidth() + "x" + plan.pageHeight()
                    + " pages=" + plan.pageCount()
                    + " placements=" + plan.placements().size()
            );
            return layouts.apply(current.target(), plan);
        } catch (TextureAtlasPackingException exception) {
            return TextureAtlasLayoutApplyResult.failed(
                TextureAtlasLayoutFailureCode.PLAN_INVALID,
                "Automatic texture atlas layout could not produce a valid plan."
            );
        }
    }

    static final class LifecycleLease {
        private boolean active;
        private boolean closed;

        static LifecycleLease alwaysActive() {
            final LifecycleLease lease = new LifecycleLease();
            lease.activate();
            return lease;
        }

        synchronized void activate() {
            if (closed) throw new IllegalStateException("Texture atlas plugin lifecycle is closed.");
            active = true;
        }

        synchronized void deactivate() {
            active = false;
        }

        synchronized void close() {
            active = false;
            closed = true;
        }

        synchronized boolean isActive() {
            return active && !closed;
        }
    }
}
