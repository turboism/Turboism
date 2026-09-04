package dev.turboism.ui.filter;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Typed Palette Filter provider backed by the connection-scoped shared Palette surface. */
public final class PaletteFilterContributionProvider implements EditorUiContributionProvider {

    private final EditorUiProviderAdmission admission;
    private final PaletteFilterHostOperations host;

    public PaletteFilterContributionProvider(
        final EditorUiProviderAdmission admission,
        final PaletteFilterHostOperations host
    ) {
        this.admission = Objects.requireNonNull(admission, "admission");
        if (admission.family() != EditorUiFamily.PALETTE_FILTER) {
            throw new IllegalArgumentException("palette-filter provider requires PALETTE_FILTER admission");
        }
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public EditorUiFamily family() {
        return EditorUiFamily.PALETTE_FILTER;
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
    public Registration apply(
        final long hostGeneration,
        final List<EditorUiContribution<?>> contributions
    ) {
        requireAdmission(hostGeneration);
        host.setFilterContributions(descriptors(contributions));
        final AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                host.setFilterContributions(List.of());
            }
        };
    }

    @Override
    public Registration reconcile(
        final long hostGeneration,
        final List<EditorUiContribution<?>> contributions,
        final Registration existing
    ) {
        requireAdmission(hostGeneration);
        if (existing == null) {
            return apply(hostGeneration, contributions);
        }
        host.setFilterContributions(descriptors(contributions));
        return existing;
    }

    private void requireAdmission(final long hostGeneration) {
        if (!admission.isAdmittedTo(hostGeneration)) {
            throw new IllegalStateException("palette-filter provider admission is stale");
        }
    }

    private static List<PaletteFilterRegistry.PaletteFilterContribution> descriptors(
        final List<EditorUiContribution<?>> contributions
    ) {
        return contributions.stream().map(contribution -> {
            if (!(contribution.descriptor() instanceof PaletteFilterRegistry.PaletteFilterContribution descriptor)) {
                throw new IllegalArgumentException("Unsupported palette filter contribution descriptor");
            }
            return descriptor;
        }).toList();
    }
}
