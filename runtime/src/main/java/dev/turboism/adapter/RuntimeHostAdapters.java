package dev.turboism.adapter;

import dev.turboism.adapter.cubism.ClipMaskReadAdapter;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.cubism.RenderStatusAdapter;
import dev.turboism.adapter.ui.MainToolbarAdapter;
import dev.turboism.adapter.ui.MainToolbarAdapterImpl;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.adapter.ui.StatusToolbarAdapterImpl;
import dev.turboism.adapter.ui.ThemeStatusAdapter;
import dev.turboism.adapter.ui.ThemeStatusAdapterImpl;
import dev.turboism.adapter.ui.UiSurfaceAdapter;
import dev.turboism.adapter.ui.UiSurfaceAdapterImpl;

import java.util.Objects;

/**
 * Runtime composition-root bundle for behavior-driven host adapters.
 *
 * <p>The default is safe mode. A launcher/host integration may pass connected
 * adapters without changing plugin-facing SDK APIs.</p>
 */
public record RuntimeHostAdapters(
    ThemeStatusAdapter themeStatus,
    RenderStatusAdapter renderStatus,
    ProjectWorkspaceAdapter projectWorkspace,
    ClipMaskReadAdapter clipMaskRead,
    StatusToolbarAdapter statusToolbar,
    MainToolbarAdapter mainToolbar,
    UiSurfaceAdapter uiSurface
) {

    public RuntimeHostAdapters {
        themeStatus = Objects.requireNonNull(themeStatus, "themeStatus");
        renderStatus = Objects.requireNonNull(renderStatus, "renderStatus");
        projectWorkspace = Objects.requireNonNull(projectWorkspace, "projectWorkspace");
        clipMaskRead = Objects.requireNonNull(clipMaskRead, "clipMaskRead");
        statusToolbar = Objects.requireNonNull(statusToolbar, "statusToolbar");
        mainToolbar = Objects.requireNonNull(mainToolbar, "mainToolbar");
        uiSurface = Objects.requireNonNull(uiSurface, "uiSurface");
    }

    public static RuntimeHostAdapters safeMode() {
        return new RuntimeHostAdapters(
            ThemeStatusAdapterImpl.safeMode(),
            RenderStatusAdapter.Impl.safeMode(),
            ProjectWorkspaceAdapter.Impl.safeMode(),
            ClipMaskReadAdapter.Impl.safeMode(),
            StatusToolbarAdapterImpl.safeMode(),
            MainToolbarAdapterImpl.safeMode(),
            UiSurfaceAdapterImpl.safeMode()
        );
    }
}
