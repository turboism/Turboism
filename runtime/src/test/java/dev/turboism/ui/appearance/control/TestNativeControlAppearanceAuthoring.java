package dev.turboism.ui.appearance.control;

import dev.turboism.adapter.cubism.NativeControlAppearanceAuthoring;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import dev.turboism.sdk.ui.appearance.NativeControlAppearance;
import dev.turboism.sdk.ui.appearance.NativeControlBackground;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Test double for the runtime-private native-control authoring seam. */
final class TestNativeControlAppearanceAuthoring {

    static NativeControlAppearanceAuthoring unavailable() {
        return new NativeControlAppearanceAuthoring() {
            @Override public NativeControlAppearance snapshot(final ControlAppearanceTarget target) {
                throw new UnsupportedOperationException("unavailable");
            }

            @Override public void setNativeBackground(
                final ControlAppearanceTarget target,
                final NativeControlBackground background
            ) {
                throw new UnsupportedOperationException("unavailable");
            }
        };
    }

    static NativeControlAppearanceAuthoring of(
        final Function<ControlAppearanceTarget, NativeControlAppearance> snapshot,
        final BiConsumer<ControlAppearanceTarget, NativeControlBackground> write
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(write, "write");
        return new NativeControlAppearanceAuthoring() {
            @Override public NativeControlAppearance snapshot(final ControlAppearanceTarget target) {
                return snapshot.apply(target);
            }

            @Override public void setNativeBackground(
                final ControlAppearanceTarget target,
                final NativeControlBackground background
            ) {
                write.accept(target, background);
            }
        };
    }

    private TestNativeControlAppearanceAuthoring() {
    }
}
