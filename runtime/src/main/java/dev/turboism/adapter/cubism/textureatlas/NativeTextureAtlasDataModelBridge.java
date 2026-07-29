package dev.turboism.adapter.cubism.textureatlas;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Static bridge called only by the exact verified texture-atlas editor hook. */
public final class NativeTextureAtlasDataModelBridge {

    private static final AtomicReference<TextureAtlasDataModelCapture> INSTALLED =
        new AtomicReference<>();

    private NativeTextureAtlasDataModelBridge() {
    }

    public static void install(final TextureAtlasDataModelCapture capture) {
        INSTALLED.set(Objects.requireNonNull(capture, "capture"));
    }

    public static void uninstall(final TextureAtlasDataModelCapture capture) {
        INSTALLED.compareAndSet(capture, null);
    }

    public static void initialized(final Object dataModel) {
        final TextureAtlasDataModelCapture capture = INSTALLED.get();
        if (capture != null) {
            capture.capture(dataModel);
        }
    }
}
