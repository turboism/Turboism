package dev.turboism.recentpreview;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewCacheService;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Runtime {@link RecentPreviewCacheService}: permission-gated preview cache deletion. */
public final class RuntimeRecentPreviewCacheService implements RecentPreviewCacheService {

    private final String pluginId;
    private final RecentPreviewCacheRegistry registry;
    private final PermissionChecker permissionChecker;

    public RuntimeRecentPreviewCacheService(
            final String pluginId,
            final RecentPreviewCacheRegistry registry,
            final PermissionChecker permissionChecker) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
    }

    @Override
    public CompletionStage<Boolean> delete(final RecentFileId id) {
        Objects.requireNonNull(id, "id");
        permissionChecker.check(PermissionIds.TURBOISM_UI_RECENT_PREVIEW_MANAGE, "ui.recent-preview.manage");
        return registry.delete(id);
    }

    @Override
    public Registration provide(final DeletionHandler handler) {
        permissionChecker.check(PermissionIds.TURBOISM_UI_RECENT_PREVIEW_CONTRIBUTE, "ui.recent-preview.contribute");
        return registry.provide(pluginId, Objects.requireNonNull(handler, "handler"));
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
