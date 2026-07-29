package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact Cubism 5.2 translator from a validated complete plan to staged Editor pages. */
public final class Cubism52TextureAtlasLayoutProvider implements TextureAtlasLayoutProvider {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-texture-atlas.layout";
    public static final String CAPABILITY_ID = "cubism.editor-texture-atlas.layout.write";
    public static final String CREATE_PAGE_ALIAS =
        "cubism.editor-texture-atlas.5.2.page.create";
    public static final String PLACE_IMAGE_ALIAS =
        "cubism.editor-texture-atlas.5.2.page.place-image";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        CREATE_PAGE_ALIAS,
        PLACE_IMAGE_ALIAS
    );

    private final VerifiedMemberResolver resolver;
    private final HostAccess host;

    public Cubism52TextureAtlasLayoutProvider(
        final VerifiedMemberResolver resolver,
        final HostAccess host
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.host = Objects.requireNonNull(host, "host");
        if (!isAdmitted(resolver)) {
            throw new IllegalArgumentException(
                "Texture-atlas layout requires exact verified Cubism 5.2 selectors."
            );
        }
    }

    private static boolean isAdmitted(final VerifiedMemberResolver resolver) {
        return resolver.isExactCubismVersion("5.2.0") && resolver.authorizesFeature(
            ADAPTER_SLICE_ID,
            CAPABILITY_ID,
            REQUIRED_ALIASES
        );
    }

    @Override
    public Optional<TextureAtlasAuthoringState> current() {
        return Optional.ofNullable(host.current());
    }

    @Override
    public ApplyOutcome apply(
        final TextureAtlasAuthoringState expected,
        final TextureAtlasLayoutPlan plan
    ) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(plan, "plan");
        final TextureAtlasAuthoringState current = host.current();
        if (current == null || !current.equals(expected)) return ApplyOutcome.REJECTED;
        if (current.currentPlan().equals(plan)) return ApplyOutcome.NO_CHANGE;

        final List<Object> pages = new ArrayList<>(plan.pageCount());
        for (int page = 0; page < plan.pageCount(); page++) {
            pages.add(resolver.invoke(
                CREATE_PAGE_ALIAS,
                host,
                "page-" + page,
                Integer.valueOf(plan.pageWidth()),
                Integer.valueOf(plan.pageHeight())
            ));
        }
        for (TextureAtlasPlacement placement : plan.placements()) {
            resolver.invoke(
                PLACE_IMAGE_ALIAS,
                host,
                pages.get(placement.pageIndex()),
                host.item(placement.textureId()),
                Integer.valueOf(placement.x()),
                Integer.valueOf(placement.y())
            );
        }
        host.apply(expected, plan, List.copyOf(pages));
        return ApplyOutcome.APPLIED;
    }

    /** Version-specific host bridge; it owns the atomic Editor transaction, Undo and refresh. */
    public interface HostAccess {
        TextureAtlasAuthoringState current();
        Object item(String textureId);
        void apply(TextureAtlasAuthoringState expected, TextureAtlasLayoutPlan plan, List<Object> pages);
    }
}
