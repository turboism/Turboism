package dev.turboism.screenshot;

import dev.turboism.adapter.cubism.ScreenshotCaptureAdapter;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Runtime {@link ScreenshotCaptureService}: permission-gated bounded preview capture. */
public final class RuntimeScreenshotCaptureService implements ScreenshotCaptureService {
    public static final String PERMISSION = dev.turboism.sdk.permission.PermissionIds.TURBOISM_UI_VIEWPORT_READ;
    private final ScreenshotCaptureAdapter adapter;
    private final PermissionChecker permissionChecker;

    public RuntimeScreenshotCaptureService(
        final ScreenshotCaptureAdapter adapter,
        final PermissionChecker permissionChecker
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
    }

    @Override
    public CompletionStage<ScreenshotCaptureResult> capture(final ScreenshotCaptureRequest request) {
        permissionChecker.check(PERMISSION, "cubism.screenshot.capture");
        return adapter.capture(Objects.requireNonNull(request, "request"));
    }
}
