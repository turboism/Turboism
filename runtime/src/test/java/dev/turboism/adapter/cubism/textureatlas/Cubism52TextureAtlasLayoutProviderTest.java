package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class Cubism52TextureAtlasLayoutProviderTest {

    @Test
    void translatesACompletePlanThroughTheExact52ConstructionPath() {
        final Fixture fixture = new Fixture();
        final Cubism52TextureAtlasLayoutProvider provider = new Cubism52TextureAtlasLayoutProvider(
            resolver(),
            fixture
        );
        final TextureAtlasAuthoringState expected = fixture.state();
        final TextureAtlasLayoutPlan plan = new TextureAtlasLayoutPlan(
            1024,
            1024,
            2,
            List.of(
                new TextureAtlasPlacement("tex-a", 0, 16, 24, 120, 80, false),
                new TextureAtlasPlacement("tex-b", 1, 40, 48, 64, 96, false)
            )
        );

        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.APPLIED, provider.apply(expected, plan));
        assertEquals(List.of("page-0", "page-1"), fixture.stagedPages);
        assertEquals(List.of(
            new Placement("page-0", fixture.items.get(0), 16, 24),
            new Placement("page-1", fixture.items.get(1), 40, 48)
        ), fixture.stagedPlacements);
        assertSame(expected, fixture.appliedExpected);
        assertEquals(plan, fixture.appliedPlan);
    }

    private VerifiedMemberResolver resolver() {
        return TestVerifiedResolvers.create(
            "5.2.0",
            Cubism52TextureAtlasLayoutProvider.ADAPTER_SLICE_ID,
            Set.of(Cubism52TextureAtlasLayoutProvider.CAPABILITY_ID),
            List.of(
                StaticSelector.method(
                    Cubism52TextureAtlasLayoutProvider.CREATE_PAGE_ALIAS,
                    Fixture.class.getName().replace('.', '/'),
                    "createPage",
                    "(Ljava/lang/String;II)Ljava/lang/Object;",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    Cubism52TextureAtlasLayoutProvider.PLACE_IMAGE_ALIAS,
                    Fixture.class.getName().replace('.', '/'),
                    "placeImage",
                    "(Ljava/lang/Object;Ljava/lang/Object;II)V",
                    StaticSelector.ACCESS_PUBLIC
                )
            ),
            getClass().getClassLoader()
        );
    }

    public static final class Fixture implements Cubism52TextureAtlasLayoutProvider.HostAccess {
        private final List<Object> items = List.of(new Object(), new Object());
        private final List<String> stagedPages = new ArrayList<>();
        private final List<Placement> stagedPlacements = new ArrayList<>();
        private TextureAtlasAuthoringState appliedExpected;
        private TextureAtlasLayoutPlan appliedPlan;

        @Override
        public TextureAtlasAuthoringState current() {
            return state();
        }

        @Override
        public Object item(final String textureId) {
            return items.get(textureId.equals("tex-a") ? 0 : 1);
        }

        public Object createPage(final String name, final int width, final int height) {
            assertEquals(1024, width);
            assertEquals(1024, height);
            stagedPages.add(name);
            return name;
        }

        public void placeImage(final Object page, final Object image, final int x, final int y) {
            stagedPlacements.add(new Placement((String) page, image, x, y));
        }

        @Override
        public void apply(
            final TextureAtlasAuthoringState expected,
            final TextureAtlasLayoutPlan plan,
            final List<Object> pages
        ) {
            appliedExpected = expected;
            appliedPlan = plan;
            assertEquals(stagedPages, pages);
        }

        private TextureAtlasAuthoringState state() {
            final TextureAtlasLayoutConstraints constraints = new TextureAtlasLayoutConstraints(
                1024, 1024, 0, 0, 4, false, false
            );
            final List<TextureAtlasLayoutItem> atlasItems = List.of(
                new TextureAtlasLayoutItem("tex-a", 120, 80),
                new TextureAtlasLayoutItem("tex-b", 64, 96)
            );
            final TextureAtlasLayoutPlan current = new TextureAtlasLayoutPlan(
                1024,
                1024,
                1,
                List.of(
                    new TextureAtlasPlacement("tex-a", 0, 0, 0, 120, 80, false),
                    new TextureAtlasPlacement("tex-b", 0, 128, 0, 64, 96, false)
                )
            );
            return new TextureAtlasAuthoringState(
                "document", "model", "atlas", 7L, constraints, atlasItems, current
            );
        }
    }

    private record Placement(String page, Object image, int x, int y) {
    }
}
