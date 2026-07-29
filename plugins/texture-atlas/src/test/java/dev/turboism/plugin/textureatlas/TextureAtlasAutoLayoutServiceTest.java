package dev.turboism.plugin.textureatlas;

import dev.turboism.plugin.textureatlas.layout.PartBucketTextureAtlasPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyStatus;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextureAtlasAutoLayoutServiceTest {

    @Test
    void readsCompleteSnapshotPlansAndSubmitsTheExactResult() {
        final RecordingLayoutService layouts = new RecordingLayoutService();
        final TextureAtlasAutoLayoutService service = new TextureAtlasAutoLayoutService(
            layouts,
            new PartBucketTextureAtlasPlanner()
        );

        final var result = service.applyAutomaticLayout();

        assertEquals(Optional.of(TextureAtlasLayoutApplyStatus.APPLIED), result.status());
        assertEquals(layouts.expectedPlan, layouts.appliedPlan);
        assertEquals(layouts.target, layouts.appliedTarget);
    }

    @Test
    void noActiveAtlasReturnsStructuredUnavailableWithoutApplying() {
        final RecordingLayoutService layouts = new RecordingLayoutService();
        layouts.current = Optional.empty();
        final var result = new TextureAtlasAutoLayoutService(
            layouts, new PartBucketTextureAtlasPlanner()
        ).applyAutomaticLayout();

        assertEquals(
            Optional.of(dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode.CAPABILITY_UNAVAILABLE),
            result.failureCode()
        );
        assertEquals(null, layouts.appliedPlan);
    }

    @Test
    void planningFailureIsStructuredAndApplyFailurePropagates() {
        final RecordingLayoutService impossible = new RecordingLayoutService();
        impossible.current = Optional.of(impossible.snapshot(
            List.of(new TextureAtlasLayoutItem("too-large", 11, 1))
        ));
        final var planFailure = new TextureAtlasAutoLayoutService(
            impossible, new PartBucketTextureAtlasPlanner()
        ).applyAutomaticLayout();
        assertEquals(
            Optional.of(dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode.PLAN_INVALID),
            planFailure.failureCode()
        );

        final RecordingLayoutService rejected = new RecordingLayoutService();
        rejected.applyResult = dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult.failed(
            dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode.TARGET_STALE,
            "stale"
        );
        final var applyFailure = new TextureAtlasAutoLayoutService(
            rejected, new PartBucketTextureAtlasPlanner()
        ).applyAutomaticLayout();
        assertEquals(
            Optional.of(dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode.TARGET_STALE),
            applyFailure.failureCode()
        );
    }

    private static final class RecordingLayoutService implements TextureAtlasLayoutService {
        private final TextureAtlasLayoutTarget target = new TextureAtlasLayoutTarget() { };
        private final TextureAtlasLayoutConstraints constraints =
            new TextureAtlasLayoutConstraints(10, 10, 0, 0, 2, false, false);
        private final List<TextureAtlasLayoutItem> items = List.of(
            new TextureAtlasLayoutItem("texture-b", 4, 4),
            new TextureAtlasLayoutItem("texture-a", 4, 4),
            new TextureAtlasLayoutItem("texture-c", 2, 2)
        );
        private final TextureAtlasLayoutPlan expectedPlan = new TextureAtlasLayoutPlan(
            10,
            10,
            2,
            List.of(
                new TextureAtlasPlacement("texture-a", 0, 0, 0, 4, 4, false),
                new TextureAtlasPlacement("texture-b", 0, 4, 0, 4, 4, false),
                new TextureAtlasPlacement("texture-c", 1, 0, 0, 2, 2, false)
            )
        );
        private TextureAtlasLayoutTarget appliedTarget;
        private TextureAtlasLayoutPlan appliedPlan;
        private Optional<TextureAtlasLayoutSnapshot> current;
        private dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult applyResult =
            dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult.applied();

        private RecordingLayoutService() {
            current = Optional.of(snapshot(items));
        }

        private TextureAtlasLayoutSnapshot snapshot(final List<TextureAtlasLayoutItem> snapshotItems) {
            return new TextureAtlasLayoutSnapshot(
                target, "document-a", "model-a", "atlas-a", constraints,
                snapshotItems, expectedPlan
            );
        }

        @Override
        public Optional<TextureAtlasLayoutSnapshot> current() {
            return current;
        }

        @Override
        public dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult apply(
            final TextureAtlasLayoutTarget target,
            final TextureAtlasLayoutPlan plan
        ) {
            appliedTarget = target;
            appliedPlan = plan;
            return applyResult;
        }
    }
}
