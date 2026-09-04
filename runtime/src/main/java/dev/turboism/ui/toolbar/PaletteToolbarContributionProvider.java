package dev.turboism.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.action.EditorUiActionRouter;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reversible typed palette-toolbar provider for the shared four-Palette host surface. */
public final class PaletteToolbarContributionProvider implements EditorUiContributionProvider {


    private final EditorUiProviderAdmission admission;
    private final PaletteToolbarHostOperations host;
    private final EditorUiActionRouter actionRouter;

    public PaletteToolbarContributionProvider(
        final EditorUiProviderAdmission admission,
        final PaletteToolbarHostOperations host,
        final EditorUiActionRouter actionRouter
    ) {
        this.admission = Objects.requireNonNull(admission, "admission");
        if (admission.family() != EditorUiFamily.PALETTE_TOOLBAR) {
            throw new IllegalArgumentException(
                "palette-toolbar provider requires PALETTE_TOOLBAR admission"
            );
        }
        this.host = Objects.requireNonNull(host, "host");
        this.actionRouter = Objects.requireNonNull(actionRouter, "actionRouter");
    }

    @Override
    public EditorUiFamily family() {
        return EditorUiFamily.PALETTE_TOOLBAR;
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
        if (!admission.isAdmittedTo(hostGeneration)) {
            throw new IllegalStateException("palette-toolbar provider admission is stale");
        }
        final List<PaletteToolbarHostOperations.ButtonContribution> buttons = new ArrayList<>();
        for (EditorUiContribution<?> contribution : contributions) {
            final PaletteToolbarContributionDescriptor descriptor =
                PaletteToolbarContributionDescriptor.from(contribution);
            buttons.add(new PaletteToolbarHostOperations.ButtonContribution(
                descriptor,
                () -> actionRouter.invoke(descriptor.pluginId(), descriptor.actionId())
            ));
        }
        try {
            host.setContributions(List.copyOf(buttons));
        } catch (RuntimeException | Error failure) {
            try {
                host.clearContributions();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        final AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                host.clearContributions();
            }
        };
    }

    @Override
    public Registration reconcile(
        final long hostGeneration,
        final List<EditorUiContribution<?>> contributions,
        final Registration existing
    ) {
        if (!admission.isAdmittedTo(hostGeneration)) {
            throw new IllegalStateException("palette-toolbar provider admission is stale");
        }
        if (existing == null) {
            return apply(hostGeneration, contributions);
        }
        final List<PaletteToolbarHostOperations.ButtonContribution> buttons = new ArrayList<>();
        for (EditorUiContribution<?> contribution : contributions) {
            final PaletteToolbarContributionDescriptor descriptor =
                PaletteToolbarContributionDescriptor.from(contribution);
            buttons.add(new PaletteToolbarHostOperations.ButtonContribution(
                descriptor,
                () -> actionRouter.invoke(descriptor.pluginId(), descriptor.actionId())
            ));
        }
        host.setContributions(List.copyOf(buttons));
        return existing;
    }
}
