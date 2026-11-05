package dev.turboism.bootstrap;

/**
 * Install/close side effects a hook owns besides the weave itself. Today only the test-only glGetError
 * experiment needs them: it publishes the composed method shape other verified installers admit against.
 *
 * @param <T> the target/profile type the owning hook installs with
 */
interface WeaveEffects<T> {

    /**
     * @param <T> the target/profile type the hook installs with
     * @return the no-op side effects of a hook that owns none
     */
    static <T> WeaveEffects<T> none() {
        return new WeaveEffects<T>() {};
    }

    /**
     * Runs after the weave is proven and before anything is reported as complete, so a published shape
     * always describes bytes that really were rewritten.
     *
     * @param hook the installed handle, so the effect can read the bound contract and the launch facts
     * @throws Exception when publishing fails and the installation must be rolled back
     */
    default void confirmed(final VerifiedInstall<T> hook) throws Exception {}

    /**
     * Runs before the restoration capture, so a claim about our own bytes is withdrawn before those
     * bytes are compared with the official ones: bytes that stay rewritten after a failed restore are
     * then no longer claimed as ours.
     *
     * @param hook the handle that is closing
     */
    default void withdrawn(final VerifiedInstall<T> hook) {}
}
