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

    private static final int MAX_CONTRIBUTIONS = 8;

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
    public boolean supportsIncrementalReconcile() {
        return true;
    }

    @Override
    public Registration reconcile(
        final long hostGeneration,
        final List<EditorUiContribution<?>> contributions,
        final Registration existing
    ) {
        if (!admission.isAdmittedTo(hostGeneration)) {
            throw new IllegalStateException("bounding-box overlay provider admission is stale");
        }
        final List<BoundingBoxOverlayButtonDescriptor> requested = descriptors(contributions);
        if (existing == null) {
            return requested.isEmpty() ? null : host.install(requested);
        }
        return host.reconcile(requested, existing);
    }

    @Override
    public Registration apply(
        final long hostGeneration,
        final List<EditorUiContribution<?>> contributions
    ) {
        if (!admission.isAdmittedTo(hostGeneration)) {
            throw new IllegalStateException("bounding-box overlay provider admission is stale");
        }
        return host.install(descriptors(contributions));
    }

    private static List<BoundingBoxOverlayButtonDescriptor> descriptors(
        final List<EditorUiContribution<?>> contributions
    ) {
        Objects.requireNonNull(contributions, "contributions");
        if (contributions.size() > MAX_CONTRIBUTIONS) {
            throw new IllegalArgumentException(
                "bounding-box overlay supports at most " + MAX_CONTRIBUTIONS + " contributions"
            );
        }
        return contributions.stream()
            .map(BoundingBoxOverlayButtonDescriptor::from)
            .toList();
    }
}
