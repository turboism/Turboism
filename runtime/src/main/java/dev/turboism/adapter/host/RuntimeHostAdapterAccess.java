package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.ui.action.RuntimeEditorUiActionRouter;
import dev.turboism.ui.appearance.AppearanceCoordinator;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.host.EditorUiHostLifecycle;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;

/** Unforgeable runtime composition handle for a verified, fail-closed host session. */
public sealed interface RuntimeHostAdapterAccess permits HostSession, SessionRuntimeHostAdapterAccess {

    RuntimeHostAdapters adapters();

    CubismModelAccess modelAccess();

    ParameterLifecycleCoordinator parameterLifecycle();

    PartLifecycleCoordinator partLifecycle();

    EditorUiHostLifecycle editorUiLifecycle();

    EditorUiContributionAuthority editorUiContributions();

    RuntimeEditorUiActionRouter editorUiActionRouter();

    EditorUiPluginResourceRegistry editorUiPluginResources();

    AppearanceCoordinator appearanceCoordinator();
}

/** Non-closeable adapter view used when lifecycle ownership remains with bootstrap ingress. */
final class SessionRuntimeHostAdapterAccess implements RuntimeHostAdapterAccess {

    private final RuntimeHostAdapters adapters;
    private final CubismModelAccess modelAccess;
    private final ParameterLifecycleCoordinator parameterLifecycle;
    private final PartLifecycleCoordinator partLifecycle;
    private final EditorUiHostLifecycle editorUiLifecycle;
    private final EditorUiContributionAuthority editorUiContributions;
    private final RuntimeEditorUiActionRouter editorUiActionRouter;
    private final EditorUiPluginResourceRegistry editorUiPluginResources;
    private final AppearanceCoordinator appearanceCoordinator;

    SessionRuntimeHostAdapterAccess(
        final RuntimeHostAdapters adapters,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorUiHostLifecycle editorUiLifecycle,
        final EditorUiContributionAuthority editorUiContributions,
        final RuntimeEditorUiActionRouter editorUiActionRouter,
        final EditorUiPluginResourceRegistry editorUiPluginResources,
        final AppearanceCoordinator appearanceCoordinator
    ) {
        this.adapters = java.util.Objects.requireNonNull(adapters, "adapters");
        this.modelAccess = java.util.Objects.requireNonNull(modelAccess, "modelAccess");
        this.parameterLifecycle = java.util.Objects.requireNonNull(
            parameterLifecycle,
            "parameterLifecycle"
        );
        this.partLifecycle = java.util.Objects.requireNonNull(partLifecycle, "partLifecycle");
        this.editorUiLifecycle = java.util.Objects.requireNonNull(
            editorUiLifecycle,
            "editorUiLifecycle"
        );
        this.editorUiContributions = java.util.Objects.requireNonNull(
            editorUiContributions,
            "editorUiContributions"
        );
        this.editorUiActionRouter = java.util.Objects.requireNonNull(
            editorUiActionRouter,
            "editorUiActionRouter"
        );
        this.editorUiPluginResources = java.util.Objects.requireNonNull(
            editorUiPluginResources,
            "editorUiPluginResources"
        );
        this.appearanceCoordinator = java.util.Objects.requireNonNull(
            appearanceCoordinator,
            "appearanceCoordinator"
        );
    }

    @Override
    public RuntimeHostAdapters adapters() {
        return adapters;
    }

    @Override
    public CubismModelAccess modelAccess() {
        return modelAccess;
    }

    @Override
    public ParameterLifecycleCoordinator parameterLifecycle() {
        return parameterLifecycle;
    }

    @Override
    public PartLifecycleCoordinator partLifecycle() {
        return partLifecycle;
    }

    @Override
    public EditorUiHostLifecycle editorUiLifecycle() {
        return editorUiLifecycle;
    }

    @Override
    public EditorUiContributionAuthority editorUiContributions() {
        return editorUiContributions;
    }

    @Override
    public RuntimeEditorUiActionRouter editorUiActionRouter() {
        return editorUiActionRouter;
    }

    @Override
    public EditorUiPluginResourceRegistry editorUiPluginResources() {
        return editorUiPluginResources;
    }

    @Override
    public AppearanceCoordinator appearanceCoordinator() {
        return appearanceCoordinator;
    }
}
