package dev.turboism.mapping.verification;

import java.util.Objects;
import java.util.Set;

/**
 * Combined runtime trust contract for the recent-files preview slice: the Recent
 * menu chain (embedded-panel slice) plus the current-project chain (project-workspace
 * slice). No new verification record is introduced — both chains are already pinned by
 * the reviewed embedded-panel and project-workspace records — so this contract only
 * narrows each plan to the aliases the recent-preview operations actually use.
 */
public final class RecentPreviewVerificationManifest {

    public static final String ADAPTER_SLICE_ID = "adapter.recent-preview";

    public static final Set<String> CAPABILITY_IDS = Set.of(
        "cubism.recent-file.read",
        "cubism.screenshot.capture",
        "ui.recent-preview.contribute"
    );

    /** Aliases used on the project-workspace plan (subset of its reviewed record). */
    public static final Set<String> PROJECT_REQUIRED_ALIASES = Set.of(
        "cubism.app-controller.instance",
        "cubism.app-controller.current-document",
        "cubism.document.file-content",
        "cubism.file-content.file"
    );

    /** Aliases used on the embedded-panel plan (subset of its reviewed record). */
    public static final Set<String> PANEL_REQUIRED_ALIASES = Set.of(
        "cubism.ui-panel.app-controller.instance",
        "cubism.ui-panel.app-controller.main-frame",
        "cubism.ui-panel.main-frame.window",
        "cubism.ui-panel.window.menu-bar",
        "cubism.ui-panel.menu-bar.menus",
        "cubism.ui-panel.menu.swing"
    );

    private RecentPreviewVerificationManifest() {
    }

    /**
     * Accepts only the reviewed version pairs on the same host artifact: both 5.3.02,
     * or the 5.2.03 host whose project-workspace record is labeled 5.2.0.
     */
    public static boolean authorizes(
        final VerifiedMemberResolver projectResolver,
        final VerifiedMemberResolver panelResolver
    ) {
        if (projectResolver == null || panelResolver == null) {
            return false;
        }
        final String projectVersion = projectResolver.cubismVersion();
        final String panelVersion = panelResolver.cubismVersion();
        final boolean reviewed = "5.3.02".equals(projectVersion) && "5.3.02".equals(panelVersion)
            || "5.2.03".equals(projectVersion) && "5.2.03".equals(panelVersion);
        if (!reviewed) {
            return false;
        }
        if (projectResolver.hostClassLoader() != panelResolver.hostClassLoader()) {
            return false;
        }
        return projectResolver.authorizesFeature(
            ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
            "cubism.project.read",
            PROJECT_REQUIRED_ALIASES
        ) && panelResolver.authorizesFeature(
            EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID,
            EmbeddedPanelVerificationManifest.CAPABILITY_ID,
            PANEL_REQUIRED_ALIASES
        );
    }

    /** Fails closed with an explicit message when the resolver pair is not authorized. */
    public static void requireAuthorized(
        final VerifiedMemberResolver projectResolver,
        final VerifiedMemberResolver panelResolver
    ) {
        if (!authorizes(
            Objects.requireNonNull(projectResolver, "projectResolver"),
            Objects.requireNonNull(panelResolver, "panelResolver")
        )) {
            throw new IllegalArgumentException(
                "resolvers do not authorize the complete recent-preview slice"
            );
        }
    }
}
