package dev.turboism.ui.appearance;

import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceStatus;

import java.util.Objects;

/** Session-owned stable provider view whose exact-host delegate is replaceable. */
public final class DynamicAppearanceHostProvider implements AppearanceHostProvider {

    private final Object monitor = new Object();
    private AppearanceHostProvider delegate = new UnavailableAppearanceHostProvider();
    private long generation;

    /**
     * Swaps in the exact-host delegate for the detected Cubism build.
     *
     * <p>Bumps the generation, which invalidates every restore point captured through
     * the previous delegate: restoring one afterwards fails rather than applying a
     * baseline to the wrong host.</p>
     *
     * @param provider the delegate to route all further calls to
     * @throws NullPointerException when {@code provider} is {@code null}
     */
    public void connect(final AppearanceHostProvider provider) {
        synchronized (monitor) {
            delegate = Objects.requireNonNull(provider, "provider");
            generation++;
        }
    }

    /**
     * Drops the current delegate back to the fail-closed unavailable provider and bumps
     * the generation, invalidating outstanding restore points.
     */
    public void deactivate() {
        synchronized (monitor) {
            delegate = new UnavailableAppearanceHostProvider();
            generation++;
        }
    }

    @Override
    public boolean isAvailable() {
        synchronized (monitor) {
            return delegate.isAvailable();
        }
    }

    @Override
    public AppearanceStatus readStatus() {
        synchronized (monitor) {
            return delegate.readStatus();
        }
    }

    @Override
    public RestorePoint captureRestorePoint() {
        synchronized (monitor) {
            return new GenerationRestorePoint(generation, delegate.captureRestorePoint());
        }
    }

    @Override
    public ApplyOutcome apply(final AppearanceRequest request) {
        synchronized (monitor) {
            return delegate.apply(request);
        }
    }

    @Override
    public void restore(final RestorePoint restorePoint) {
        synchronized (monitor) {
            if (!(restorePoint instanceof GenerationRestorePoint point)
                || point.generation() != generation) {
                throw new IllegalStateException("Appearance restore point is stale");
            }
            delegate.restore(point.delegate());
        }
    }

    private record GenerationRestorePoint(
        long generation,
        RestorePoint delegate
    ) implements RestorePoint {
        private GenerationRestorePoint {
            delegate = Objects.requireNonNull(delegate, "delegate");
        }
    }
}
