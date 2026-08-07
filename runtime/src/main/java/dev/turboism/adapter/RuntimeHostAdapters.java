package dev.turboism.adapter;

import dev.turboism.adapter.cubism.ClipMaskReadAdapter;
import dev.turboism.adapter.cubism.PreviewCaptureHostOperations;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.cubism.RecentFileAdapter;
import dev.turboism.adapter.cubism.RecentPreviewContributionAdapter;
import dev.turboism.adapter.cubism.ScreenshotCaptureAdapter;
import dev.turboism.adapter.cubism.VerifiedRecentFileListHostOperations;
import dev.turboism.adapter.cubism.VerifiedRecentPreviewPopupHostOperations;
import dev.turboism.adapter.cubism.RenderStatusAdapter;
import dev.turboism.adapter.cubism.VerifiedClipMaskHostOperations;
import dev.turboism.adapter.cubism.VerifiedProjectWorkspaceHostOperations;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.adapter.ui.StatusToolbarAdapterImpl;
import dev.turboism.adapter.ui.ThemeStatusAdapter;
import dev.turboism.adapter.ui.ThemeStatusAdapterImpl;
import dev.turboism.adapter.ui.UiSurfaceAdapter;
import dev.turboism.adapter.ui.UiSurfaceAdapterImpl;
import dev.turboism.adapter.ui.VerifiedCxStatusBarHostAccess;
import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
import dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest;
import dev.turboism.mapping.verification.RecentPreviewVerificationManifest;
import dev.turboism.mapping.verification.StatusBarVerificationManifest;
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
    UiSurfaceAdapter uiSurface,
    RecentFileAdapter recentFiles,
    ScreenshotCaptureAdapter screenshots,
    RecentPreviewContributionAdapter recentPreviews
) {

    public RuntimeHostAdapters {
        themeStatus = Objects.requireNonNull(themeStatus, "themeStatus");
        renderStatus = Objects.requireNonNull(renderStatus, "renderStatus");
        projectWorkspace = Objects.requireNonNull(projectWorkspace, "projectWorkspace");
        clipMaskRead = Objects.requireNonNull(clipMaskRead, "clipMaskRead");
        statusToolbar = Objects.requireNonNull(statusToolbar, "statusToolbar");
        uiSurface = Objects.requireNonNull(uiSurface, "uiSurface");
        recentFiles = Objects.requireNonNull(recentFiles, "recentFiles");
        screenshots = Objects.requireNonNull(screenshots, "screenshots");
        recentPreviews = Objects.requireNonNull(recentPreviews, "recentPreviews");
    }

    /** Compatibility constructor: recent-preview slots stay in safe mode. */
    public RuntimeHostAdapters(
        final ThemeStatusAdapter themeStatus,
        final RenderStatusAdapter renderStatus,
        final ProjectWorkspaceAdapter projectWorkspace,
        final ClipMaskReadAdapter clipMaskRead,
        final StatusToolbarAdapter statusToolbar,
        final UiSurfaceAdapter uiSurface
    ) {
        this(
            themeStatus, renderStatus, projectWorkspace, clipMaskRead, statusToolbar, uiSurface,
            RecentFileAdapter.safeMode(), ScreenshotCaptureAdapter.safeMode(),
            RecentPreviewContributionAdapter.safeMode()
        );
    }

    public static RuntimeHostAdapters safeMode() {
        return new RuntimeHostAdapters(
            ThemeStatusAdapterImpl.safeMode(),
            RenderStatusAdapter.Impl.safeMode(),
            ProjectWorkspaceAdapter.Impl.safeMode(),
            ClipMaskReadAdapter.Impl.safeMode(),
            StatusToolbarAdapterImpl.safeMode(),
            UiSurfaceAdapterImpl.safeMode(),
            RecentFileAdapter.safeMode(),
            ScreenshotCaptureAdapter.safeMode(),
            RecentPreviewContributionAdapter.safeMode()
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
        if (!ProjectWorkspaceVerificationManifest.authorizes(resolver)) {
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
            UiSurfaceAdapterImpl.safeMode(),
            RecentFileAdapter.safeMode(),
            ScreenshotCaptureAdapter.safeMode(),
            RecentPreviewContributionAdapter.safeMode()
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
            UiSurfaceAdapterImpl.safeMode(),
            RecentFileAdapter.safeMode(),
            ScreenshotCaptureAdapter.safeMode(),
            RecentPreviewContributionAdapter.safeMode()
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
            project.uiSurface(),
            project.recentFiles(),
            project.screenshots(),
            project.recentPreviews()
        );
    }

    /**
     * Replaces only the status-toolbar slot of an existing bundle with the
     * verified 5.3.02 native status slice; every other adapter is preserved.
     */
    static RuntimeHostAdapters withVerifiedStatusBar(
        final RuntimeHostAdapters base,
        final VerifiedMemberResolver statusBarResolver
    ) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(statusBarResolver, "statusBarResolver");
        if (!statusBarResolver.isExactCubismVersion(StatusBarVerificationManifest.CUBISM_VERSION)
            || !statusBarResolver.authorizes(
                StatusBarVerificationManifest.ADAPTER_SLICE_ID,
                StatusBarVerificationManifest.CAPABILITY_IDS,
                StatusBarVerificationManifest.REQUIRED_ALIASES
            )) {
            throw new IllegalArgumentException(
                "resolver does not authorize the complete status-bar adapter slice"
            );
        }
        return new RuntimeHostAdapters(
            base.themeStatus(),
            base.renderStatus(),
            base.projectWorkspace(),
            base.clipMaskRead(),
            StatusToolbarAdapterImpl.connectedVerifiedCx(
                statusBarResolver.cubismVersion(),
                new VerifiedCxStatusBarHostAccess(statusBarResolver)
            ),
            base.uiSurface(),
            base.recentFiles(),
            base.screenshots(),
            base.recentPreviews()
        );
    }

    /**
     * Connects only the verified recent-files preview slice: menu list, bounded
     * capture, and the hover popup bridge. The popup bridge self-suppresses around
     * captures. Every other adapter is preserved.
     */
    public static RuntimeHostAdapters withVerifiedRecentPreview(
        final RuntimeHostAdapters base,
        final VerifiedMemberResolver projectResolver,
        final VerifiedMemberResolver panelResolver
    ) {
        Objects.requireNonNull(base, "base");
        RecentPreviewVerificationManifest.requireAuthorized(projectResolver, panelResolver);
        final VerifiedRecentFileListHostOperations files =
            new VerifiedRecentFileListHostOperations(projectResolver, panelResolver);
        final VerifiedRecentPreviewPopupHostOperations popup =
            new VerifiedRecentPreviewPopupHostOperations(panelResolver);
        return new RuntimeHostAdapters(
            base.themeStatus(),
            base.renderStatus(),
            base.projectWorkspace(),
            base.clipMaskRead(),
            base.statusToolbar(),
            base.uiSurface(),
            RecentFileAdapter.connected(files),
            ScreenshotCaptureAdapter.connected(new PreviewCaptureHostOperations(
                panelResolver, files, popup,
                // temporary diagnostic wiring for host verification; remove after Phase 5
                System.err::println
            )),
            RecentPreviewContributionAdapter.connected(popup)
        );
    }
}
