package dev.turboism.sdk.cubism.model;

/** Access to Cubism model objects. */
public interface CubismModelAccess {

    /**
     * Returns the active model.
     *
     * @throws IllegalStateException when no model is active
     */
    CubismModel active();

    /**
     * Reports whether a live runtime backend backs this access.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static CubismModelAccess unavailable() {
        return Unavailable.INSTANCE;
    }

    /**
     * Sentinel returned by {@link #unavailable()}: {@link #active()} fails closed with the
     * documented {@link IllegalStateException} for a missing active model.
     */
    enum Unavailable implements CubismModelAccess {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public CubismModel active() {
            throw new IllegalStateException("no Cubism model is active");
        }
    }
}
