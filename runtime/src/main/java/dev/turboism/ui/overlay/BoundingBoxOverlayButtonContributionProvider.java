package dev.turboism.ui.overlay;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.List;
import java.util.Objects;

/** Reversible provider for Cubism red-box overlay buttons. */
public final class BoundingBoxOverlayButtonContributionProvider
    implements EditorUiContributionProvider {

    private final EditorUiProviderAdmission admission;
    private final BoundingBoxOverlayButtonHostOperations host;

    public BoundingBoxOverlayButtonContributionProvider(
        final EditorUiProviderAdmission admission,
        final BoundingBoxOverlayButtonHostOperations host
    ) {
        this.admission = Objects.requireNonNull(admission, "admission");
        if (admission.family() != EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON) {
            throw new IllegalArgumentException(
                "bounding-box overlay provider requires BOUNDING_BOX_OVERLAY_BUTTON admission"
            );
        }
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public EditorUiFamily family() {
        return EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON;
    }

    @Override
    public EditorUiProviderAdmission admission() {
        return admission;
    }

    @Override
    public Registration apply(
        final long hostGeneration,
        final List<EditorUiContribution<?>> contributions
    ) {
        if (!admission.isAdmittedTo(hostGeneration)) {
            throw new IllegalStateException("bounding-box overlay provider admission is stale");
        }
        return host.install(contributions.stream()
            .map(BoundingBoxOverlayButtonDescriptor::from)
            .toList());
    }
}
