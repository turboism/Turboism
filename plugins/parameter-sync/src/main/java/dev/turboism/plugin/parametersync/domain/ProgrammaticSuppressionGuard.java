package dev.turboism.plugin.parametersync.domain;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Guard that tracks programmatic writes made by the synchronization engine.
 *
 * <p>When the plugin sets target parameter values via {@code Parameter#setValue}, the host
 * emits change events back through {@code onParameterValueChanged}. This guard detects and
 * suppresses those secondary events, completely preventing oscillation feedback loops.</p>
 */
public final class ProgrammaticSuppressionGuard {

    private static final long DEFAULT_EXPIRY_NANOS = 500_000_000L; // 500 ms window
    private final ConcurrentHashMap<String, SuppressedState> suppressed = new ConcurrentHashMap<>();

    private record SuppressedState(float expectedValue, long expiryNanos) {}

    /**
     * Marks that a parameter is being updated programmatically with an expected value.
     */
    public void markProgrammaticWrite(final String parameterId, final float expectedValue) {
        if (parameterId == null || parameterId.isBlank()) {
            return;
        }
        final long expiry = System.nanoTime() + DEFAULT_EXPIRY_NANOS;
        suppressed.put(parameterId.strip(), new SuppressedState(expectedValue, expiry));
    }

    /**
     * Determines whether an incoming parameter value change should be suppressed.
     * Returns {@code true} if the change was originated by this plugin's own programmatic write.
     */
    public boolean shouldSuppress(final String parameterId, final float currentValue) {
        if (parameterId == null || parameterId.isBlank()) {
            return false;
        }
        final String id = parameterId.strip();
        final SuppressedState state = suppressed.get(id);
        if (state == null) {
            return false;
        }
        final long now = System.nanoTime();
        if (now > state.expiryNanos()) {
            suppressed.remove(id, state);
            return false;
        }
        // If the value is close to the expected value, consume the suppression
        if (Math.abs(state.expectedValue() - currentValue) < 2e-3f) {
            suppressed.remove(id, state);
            return true;
        }
        // Still within the recent write window
        return true;
    }

    public void clear() {
        suppressed.clear();
    }
}
