package dev.turboism.recentfile;

import dev.turboism.adapter.cubism.RecentFileAdapter;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.recentfile.RecentFileService;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;

import java.util.List;
import java.util.Objects;

/** Runtime {@link RecentFileService}: permission-gated host Recent menu projection. */
public final class RuntimeRecentFileService implements RecentFileService {
    public static final String PERMISSION = dev.turboism.sdk.permission.PermissionIds.TURBOISM_CUBISM_RECENT_FILE_READ;
    private final RecentFileAdapter adapter;
    private final PermissionChecker permissionChecker;

    public RuntimeRecentFileService(
        final RecentFileAdapter adapter,
        final PermissionChecker permissionChecker
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
    }

    @Override
    public List<RecentFileSummary> list() {
        permissionChecker.check(PERMISSION, "cubism.recent-file.list");
        return adapter.list();
    }
}
