package dev.turboism.recentpreview;

import dev.turboism.adapter.cubism.RecentPreviewContributionAdapter;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewContributionService;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewRenderer;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

/** Runtime {@link RecentPreviewContributionService}: permission-gated popup bridge. */
public final class RuntimeRecentPreviewContributionService implements RecentPreviewContributionService {
    public static final String PERMISSION = dev.turboism.sdk.permission.PermissionIds.TURBOISM_UI_RECENT_PREVIEW_CONTRIBUTE;
    private final RecentPreviewContributionAdapter adapter;
    private final PermissionChecker permissionChecker;

    public RuntimeRecentPreviewContributionService(
        final RecentPreviewContributionAdapter adapter,
        final PermissionChecker permissionChecker
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
    }

    @Override
    public Registration contribute(final RecentPreviewRenderer renderer) {
        permissionChecker.check(PERMISSION, "ui.recent-preview.contribute");
        return adapter.contribute(Objects.requireNonNull(renderer, "renderer"));
    }

    @Override
    public void refresh() {
        permissionChecker.check(PERMISSION, "ui.recent-preview.refresh");
        adapter.refresh();
    }
}
