package dev.turboism.ui;

import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.host.EditorUiHostSnapshot;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BoundingBoxOverlayButtonRuntimeTest {

    @Test
    void contributionUsesSharedAuthorityAndPluginScopeCleanup() throws Exception {
        final RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        final EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        final DisposableScope scope = new DisposableScope();
        final RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            dev.turboism.permissions.PermissionChecker.allowAll(),
            "plugin.overlay",
            UiHostStateSource.DEFAULT,
            scope,
            dev.turboism.adapter.ui.StatusToolbarAdapterImpl.safeMode(),
            dev.turboism.adapter.ui.UiSurfaceAdapterImpl.safeMode(),
            null,
            authority
        );
        final BoundingBoxOverlayButton button = button("fit-selection", 30, () -> { });

        service.contributeBoundingBoxOverlayButton(button);

        final List<EditorUiContribution<?>> contributions = authority.contributions(
            EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON
        );
        assertEquals(1, contributions.size());
        assertEquals(button.id(), ((BoundingBoxOverlayButton) contributions.get(0).descriptor()).id());

        scope.close();

        assertEquals(List.of(), authority.contributions(EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON));
        authority.close();
    }

    @Test
    void contributionKeepsThePluginCallbackForNativeClickRouting() {
        final AtomicInteger clicks = new AtomicInteger();
        final RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        final EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        final RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            dev.turboism.permissions.PermissionChecker.allowAll(),
            "plugin.overlay",
            UiHostStateSource.DEFAULT,
            new DisposableScope(),
            dev.turboism.adapter.ui.StatusToolbarAdapterImpl.safeMode(),
            dev.turboism.adapter.ui.UiSurfaceAdapterImpl.safeMode(),
            null,
            authority
        );
        service.contributeBoundingBoxOverlayButton(
            button("fit-selection", 30, clicks::incrementAndGet)
        );

        final BoundingBoxOverlayButton stored = (BoundingBoxOverlayButton) authority.contributions(
            EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON
        ).get(0).descriptor();
        stored.onClick().run();

        assertEquals(1, clicks.get());
        authority.close();
    }

    @Test
    void hostLifecycleFailClosesWhenBoundingBoxFamilyIsNotReady() {
        final RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        lifecycle.absent();
        final EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        final RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            dev.turboism.permissions.PermissionChecker.allowAll(),
            "plugin.overlay",
            UiHostStateSource.DEFAULT,
            new DisposableScope(),
            dev.turboism.adapter.ui.StatusToolbarAdapterImpl.safeMode(),
            dev.turboism.adapter.ui.UiSurfaceAdapterImpl.safeMode(),
            null,
            authority
        );

        service.contributeBoundingBoxOverlayButton(button("fit-selection", 30, () -> { }));

        assertEquals(1, authority.contributions(EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON).size());
        assertFalse(lifecycle.snapshot().isReady(EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON));
        authority.close();
    }

    private static BoundingBoxOverlayButton button(
        final String id,
        final int order,
        final Runnable onClick
    ) {
        return new BoundingBoxOverlayButton(
            id,
            "Fit selection",
            new BoundingBoxOverlayButton.IconVariants(
                "icons/fit.png",
                Optional.of("icons/fit-hover.png"),
                Optional.empty(),
                Optional.empty()
            ),
            order,
            onClick
        );
    }
}
