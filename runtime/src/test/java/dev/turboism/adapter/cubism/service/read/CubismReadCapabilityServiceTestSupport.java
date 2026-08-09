package dev.turboism.adapter.cubism.service.read;

import dev.turboism.adapter.cubism.ClipMaskReadAdapter;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.cubism.RenderStatusAdapter;
import dev.turboism.adapter.ui.ThemeStatusAdapter;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismFacade;

public final class CubismReadCapabilityServiceTestSupport {
    private CubismReadCapabilityServiceTestSupport() {
    }

    public static CubismReadCapabilityServiceImpl withThemeAdapter(
        final CubismFacade facade,
        final M12ReadSnapshotSource m12Source,
        final ThemeStatusAdapter themeStatusAdapter,
        final CubismPermissionGate permissionGate
    ) {
        return new CubismReadCapabilityServiceImpl(
            facade,
            m12Source,
            themeStatusAdapter,
            RenderStatusAdapter.Impl.safeMode(),
            ProjectWorkspaceAdapter.Impl.safeMode(),
            ClipMaskReadAdapter.Impl.safeMode(),
            "plugin.test",
            CubismReadPermissionGate.from(permissionGate)
        );
    }
}
