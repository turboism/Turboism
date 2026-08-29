package dev.turboism.sdk.cubism.screenshot;

/**
 * Indicates that the recent-preview target disappeared or was replaced while a capture was being
 * scheduled. This is an expected host lifecycle race, not an image-capture failure.
 */
public final class ScreenshotCaptureTargetUnavailableException extends IllegalStateException {

    public ScreenshotCaptureTargetUnavailableException() {
        super("screenshot target is unavailable");
    }
}
