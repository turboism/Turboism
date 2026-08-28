package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasSizeBucket;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTextureAtlasEditorSessionTest {

    @Test
    void absentViewReturnsEmptyForBothProjections() {
        final RuntimeTextureAtlasEditorSession session = new RuntimeTextureAtlasEditorSession(
            () -> null
        );

        assertTrue(session.summary().isEmpty());
        assertTrue(session.selectedTexture().isEmpty());
    }

    @Test
    void stableSessionRequiresOneGenerationBoundResolverAndView() {
        final Fixture fixture = new Fixture();
        final AtomicReference<RuntimeTextureAtlasEditorSession.GenerationBinding> binding =
            new AtomicReference<>();
        final RuntimeTextureAtlasEditorSession session = new RuntimeTextureAtlasEditorSession(
            binding::get
        );

        assertTrue(session.summary().isEmpty());
        assertTrue(session.selectedTexture().isEmpty());

        binding.set(new RuntimeTextureAtlasEditorSession.GenerationBinding(1, resolver(), fixture.view));
        assertTrue(session.selectedTexture().isPresent());

        binding.set(null);
        assertTrue(session.summary().isEmpty());
        assertTrue(session.selectedTexture().isEmpty());
    }

    @Test
    void failsClosedWhenNoSameGenerationPairIsAvailable() {
        final Fixture fixture = new Fixture();
        final AtomicReference<RuntimeTextureAtlasEditorSession.GenerationBinding> binding =
            new AtomicReference<>(new RuntimeTextureAtlasEditorSession.GenerationBinding(
                1, resolver(), fixture.view
            ));
        final RuntimeTextureAtlasEditorSession session = new RuntimeTextureAtlasEditorSession(binding::get);

        binding.set(null);

        assertTrue(session.selectedTexture().isEmpty());
    }

    @Test
    void readsCurrentPageWithoutRequiringModelImageSelection() {
        final Fixture fixture = new Fixture();
        final RuntimeTextureAtlasEditorSession session = new RuntimeTextureAtlasEditorSession(
            () -> new RuntimeTextureAtlasEditorSession.GenerationBinding(1, resolver(), fixture.view)
        );

        assertEquals(
            new TextureAtlasSummary(2, 1, List.of(
                new TextureAtlasSizeBucket(4, 3, 1),
                new TextureAtlasSizeBucket(2, 2, 1)
            )),
            session.selectedTexture().orElseThrow()
        );

        fixture.dataModel.currentPage = fixture.secondPage;

        assertEquals(
            new TextureAtlasSummary(1, 1, List.of(new TextureAtlasSizeBucket(8, 8, 1))),
            session.selectedTexture().orElseThrow()
        );
    }

    private static VerifiedMemberResolver resolver() {
        return TestVerifiedResolvers.create(
            "5.3.02",
            VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
            Set.of(VerifiedCubism5302TextureAtlasSelectorContract.CAPABILITY_ID),
            List.of(
                method(
                    VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_VIEW_DATA_MODEL,
                    View.class, "dataModel", descriptor(DataModel.class)
                ),
                method(
                    VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_DATA_MODEL_CURRENT_PAGE,
                    DataModel.class, "currentPage", descriptor(PageState.class)
                ),
                method(
                    VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_PAGE_STATE_ATLAS,
                    PageState.class, "atlas", descriptor(Atlas.class)
                ),
                method("cubism.texture-atlas.atlas.entries", Atlas.class, "entries", "()Ljava/util/List;"),
                method("cubism.texture-atlas.entry.image", Entry.class, "image", descriptor(Image.class)),
                method("cubism.texture-atlas.image.width", Image.class, "width", "()I"),
                method("cubism.texture-atlas.image.height", Image.class, "height", "()I")
            ),
            RuntimeTextureAtlasEditorSessionTest.class.getClassLoader()
        );
    }

    private static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias,
            owner.getName().replace('.', '/'),
            name,
            descriptor,
            StaticSelector.ACCESS_PUBLIC
        );
    }

    private static String descriptor(final Class<?> type) {
        return "()L" + type.getName().replace('.', '/') + ";";
    }

    public static final class View {
        private final DataModel dataModel;

        View(final DataModel dataModel) {
            this.dataModel = dataModel;
        }

        public DataModel dataModel() {
            return dataModel;
        }
    }

    public static final class DataModel {
        private PageState currentPage;

        DataModel(final PageState currentPage) {
            this.currentPage = currentPage;
        }

        public PageState currentPage() {
            return currentPage;
        }
    }

    public static final class PageState {
        private final Atlas atlas;

        PageState(final Atlas atlas) {
            this.atlas = atlas;
        }

        public Atlas atlas() {
            return atlas;
        }
    }

    public static final class Atlas {
        private final List<Entry> entries;

        Atlas(final List<Entry> entries) {
            this.entries = entries;
        }

        public List<Entry> entries() {
            return entries;
        }
    }

    public static final class Entry {
        private final Image image;

        Entry(final Image image) {
            this.image = image;
        }

        public Image image() {
            return image;
        }
    }

    public static final class Image {
        private final int width;
        private final int height;

        Image(final int width, final int height) {
            this.width = width;
            this.height = height;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }
    }

    private static final class Fixture {
        final PageState secondPage = new PageState(new Atlas(List.of(
            new Entry(new Image(8, 8))
        )));
        final DataModel dataModel = new DataModel(new PageState(new Atlas(List.of(
            new Entry(new Image(4, 3)),
            new Entry(new Image(2, 2))
        ))));
        final View view = new View(dataModel);
    }
}
