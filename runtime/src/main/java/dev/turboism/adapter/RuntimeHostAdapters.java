package dev.turboism.adapter;

import dev.turboism.adapter.cubism.ClipMaskReadAdapter;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.cubism.RenderStatusAdapter;
import dev.turboism.adapter.cubism.VerifiedClipMaskHostOperations;
import dev.turboism.adapter.cubism.VerifiedProjectWorkspaceHostOperations;
import dev.turboism.adapter.ui.MainToolbarAdapter;
import dev.turboism.adapter.ui.MainToolbarAdapterImpl;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.adapter.ui.StatusToolbarAdapterImpl;
import dev.turboism.adapter.ui.ThemeStatusAdapter;
import dev.turboism.adapter.ui.ThemeStatusAdapterImpl;
import dev.turboism.adapter.ui.UiSurfaceAdapter;
import dev.turboism.adapter.ui.UiSurfaceAdapterImpl;
import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
import dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

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

    /**
     * Connects only the statically verified project/workspace slice.
     * Other adapters remain in safe mode until they receive their own evidence.
     */
    static RuntimeHostAdapters withVerifiedProjectWorkspace(
        final VerifiedMemberResolver resolver
    ) {
        Objects.requireNonNull(resolver, "resolver");
        if (!resolver.isExactCubismVersion(ProjectWorkspaceVerificationManifest.CUBISM_VERSION)
            || !resolver.authorizes(
                ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
                ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
                ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES
            )) {
            throw new IllegalArgumentException(
                "resolver does not authorize the complete project/workspace adapter slice"
            );
        }
        return new RuntimeHostAdapters(
            ThemeStatusAdapterImpl.safeMode(),
            RenderStatusAdapter.Impl.safeMode(),
            ProjectWorkspaceAdapter.Impl.connected(new VerifiedProjectWorkspaceHostOperations(
                resolver,
                resolver.cubismVersion()
            )),
            ClipMaskReadAdapter.Impl.safeMode(),
            StatusToolbarAdapterImpl.safeMode(),
            MainToolbarAdapterImpl.safeMode(),
            UiSurfaceAdapterImpl.safeMode()
        );
    }

    /**
     * Connects only the statically verified clip-mask slice.
     * Other adapters remain in safe mode until they receive their own evidence.
     */
    static RuntimeHostAdapters withVerifiedClipMask(final VerifiedMemberResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (!resolver.isExactCubismVersion(ClipMaskVerificationManifest.CUBISM_VERSION)
            || !resolver.authorizes(
                ClipMaskVerificationManifest.ADAPTER_SLICE_ID,
                ClipMaskVerificationManifest.CAPABILITY_IDS,
                ClipMaskVerificationManifest.REQUIRED_ALIASES
            )) {
            throw new IllegalArgumentException(
                "resolver does not authorize the complete clip-mask adapter slice"
            );
        }
        return new RuntimeHostAdapters(
            ThemeStatusAdapterImpl.safeMode(),
            RenderStatusAdapter.Impl.safeMode(),
            ProjectWorkspaceAdapter.Impl.safeMode(),
            ClipMaskReadAdapter.Impl.connected(new VerifiedClipMaskHostOperations(
                resolver,
                resolver.cubismVersion()
            )),
            StatusToolbarAdapterImpl.safeMode(),
            MainToolbarAdapterImpl.safeMode(),
            UiSurfaceAdapterImpl.safeMode()
        );
    }

    /** Atomically combines independently verified read-only slices into one adapter bundle. */
    static RuntimeHostAdapters withVerifiedProjectWorkspaceAndClipMask(
        final VerifiedMemberResolver projectWorkspaceResolver,
        final VerifiedMemberResolver clipMaskResolver
    ) {
        final RuntimeHostAdapters project = withVerifiedProjectWorkspace(projectWorkspaceResolver);
        final RuntimeHostAdapters clip = withVerifiedClipMask(clipMaskResolver);
        return new RuntimeHostAdapters(
            project.themeStatus(),
            project.renderStatus(),
            project.projectWorkspace(),
            clip.clipMaskRead(),
            project.statusToolbar(),
            project.mainToolbar(),
            project.uiSurface()
        );
    }
}
