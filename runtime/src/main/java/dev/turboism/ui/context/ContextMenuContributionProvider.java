package dev.turboism.ui.context;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.ui.action.EditorUiActionRouter;
import dev.turboism.ui.action.RuntimeEditorUiActionRouter;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reversible provider for typed object context-menu contributions. */
public final class ContextMenuContributionProvider implements EditorUiContributionProvider {

    private final EditorUiProviderAdmission admission;
    private final ContextMenuHostOperations host;
    private final ContextActionRouter actionRouter;

    public ContextMenuContributionProvider(
        final EditorUiProviderAdmission admission,
        final ContextMenuHostOperations host,
        final EditorUiActionRouter actionRouter
    ) {
        this(admission, host, (pluginId, actionId, context) -> {
            if (actionRouter instanceof RuntimeEditorUiActionRouter runtime) {
                runtime.invoke(pluginId, actionId, context);
            } else {
                actionRouter.invoke(pluginId, actionId);
            }
        });
    }

    public ContextMenuContributionProvider(
        final EditorUiProviderAdmission admission,
        final ContextMenuHostOperations host,
        final ContextActionRouter actionRouter
    ) {
        this.admission = Objects.requireNonNull(admission, "admission");
        if (admission.family() != EditorUiFamily.CONTEXT_MENU) {
            throw new IllegalArgumentException("context-menu provider requires CONTEXT_MENU admission");
        }
        this.host = Objects.requireNonNull(host, "host");
        this.actionRouter = Objects.requireNonNull(actionRouter, "actionRouter");
    }

    @Override
    public EditorUiFamily family() {
        return EditorUiFamily.CONTEXT_MENU;
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
            throw new IllegalStateException("context-menu provider admission is stale");
        }
        final List<Registration> registrations = new ArrayList<>();
        try {
            for (EditorUiContribution<?> contribution : contributions) {
                final ContextMenuContributionDescriptor descriptor =
                    ContextMenuContributionDescriptor.from(contribution);
                registrations.add(Objects.requireNonNull(host.addItem(
                    descriptor,
                    selection -> route(descriptor, hostGeneration, selection)
                ), "host.addItem()"));
            }
        } catch (RuntimeException | Error failure) {
            closeAllSuppressing(registrations, failure);
            throw failure;
        }
        return () -> closeAll(registrations);
    }

    private void route(
        final ContextMenuContributionDescriptor descriptor,
        final long hostGeneration,
        final ContextMenuSelection selection
    ) {
        if (selection.hostGeneration() != hostGeneration || !descriptor.matches(selection)) {
            return;
        }
        actionRouter.invoke(
            descriptor.pluginId(),
            descriptor.actionId(),
            new ContextMenuActionContext(selection)
        );
    }

    @FunctionalInterface
    public interface ContextActionRouter {
        void invoke(String pluginId, String actionId, ActionRegistry.ActionContext context);
    }

    private record ContextMenuActionContext(ContextMenuSelection selection)
        implements ActionRegistry.ActionContext {
        @Override
        public java.util.Optional<ContextMenuSelection> contextMenuSelection() {
            return java.util.Optional.of(selection);
        }
    }

    private static void closeAll(final List<? extends Registration> registrations) {
        RuntimeException first = null;
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException exception) {
                if (first == null) {
                    first = exception;
                } else {
                    first.addSuppressed(exception);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static void closeAllSuppressing(
        final List<? extends Registration> registrations,
        final Throwable failure
    ) {
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }
}
