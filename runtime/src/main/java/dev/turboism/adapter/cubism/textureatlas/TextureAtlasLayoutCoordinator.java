package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;

import java.util.Objects;
import java.util.Optional;

/** Session-owned serial authority for texture-atlas authoring access. */
public final class TextureAtlasLayoutCoordinator implements AutoCloseable {

    private TextureAtlasLayoutProvider provider;
    private long generation;
    private boolean closed;

    public synchronized void connect(final TextureAtlasLayoutProvider provider) {
        requireOpen();
        this.provider = Objects.requireNonNull(provider, "provider");
        generation++;
    }

    public synchronized void deactivate() {
        if (closed) return;
        provider = null;
        generation++;
    }

    synchronized Optional<Snapshot> current() {
        if (closed || provider == null) return Optional.empty();
        try {
            return Objects.requireNonNull(provider.current(), "provider.current()")
                .map(state -> new Snapshot(generation, state));
        } catch (Error error) {
            deactivateAfterProviderFailure();
            throw error;
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    synchronized TextureAtlasLayoutApplyResult apply(
        final long expectedGeneration,
        final TextureAtlasAuthoringState expected,
        final TextureAtlasLayoutPlan plan
    ) {
        if (closed) return failed(TextureAtlasLayoutFailureCode.RUNTIME_CLOSED, "Texture atlas runtime is closed.");
        if (provider == null) {
            return failed(TextureAtlasLayoutFailureCode.CAPABILITY_UNAVAILABLE, "Texture atlas layout capability is unavailable.");
        }
        if (generation != expectedGeneration) {
            return failed(TextureAtlasLayoutFailureCode.TARGET_STALE, "The texture atlas target is stale.");
        }
        final Optional<TextureAtlasAuthoringState> current;
        try {
            current = Objects.requireNonNull(provider.current(), "provider.current()");
        } catch (Error error) {
            deactivateAfterProviderFailure();
            throw error;
        } catch (RuntimeException exception) {
            return failed(TextureAtlasLayoutFailureCode.PROVIDER_FAILED, "Texture atlas provider failed safely.");
        }
        if (current.isEmpty() || !samePlanningState(expected, current.orElseThrow())) {
            return failed(TextureAtlasLayoutFailureCode.TARGET_STALE, "The texture atlas target is stale.");
        }
        try {
            return switch (Objects.requireNonNull(provider.apply(current.orElseThrow(), plan), "provider.apply()")) {
                case APPLIED -> TextureAtlasLayoutApplyResult.applied();
                case NO_CHANGE -> TextureAtlasLayoutApplyResult.noChange();
                case REJECTED -> failed(
                    TextureAtlasLayoutFailureCode.PROVIDER_REJECTED,
                    "Texture atlas provider rejected the validated plan."
                );
            };
        } catch (Error error) {
            deactivateAfterProviderFailure();
            throw error;
        } catch (RuntimeException exception) {
            return failed(TextureAtlasLayoutFailureCode.PROVIDER_FAILED, "Texture atlas provider failed safely.");
    }
    }

    synchronized long generation() {
        return generation;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    private boolean samePlanningState(
        final TextureAtlasAuthoringState expected,
        final TextureAtlasAuthoringState current
    ) {
        return expected.equals(current);
    }

    private void deactivateAfterProviderFailure() {
        provider = null;
        generation++;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Texture atlas coordinator is closed.");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        provider = null;
        generation++;
        closed = true;
    }

    private static TextureAtlasLayoutApplyResult failed(
        final TextureAtlasLayoutFailureCode code,
        final String message
    ) {
        return TextureAtlasLayoutApplyResult.failed(code, message);
    }

    record Snapshot(long generation, TextureAtlasAuthoringState state) {
        Snapshot {
            state = Objects.requireNonNull(state, "state");
        }
    }
}
