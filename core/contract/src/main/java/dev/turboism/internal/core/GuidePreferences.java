package dev.turboism.internal.core;

/**
 * Runtime-owned persistence for the one-time product guide consumed by the framework shell.
 *
 * <p>The preference admits the guide once per installation: it is true until the user dismisses
 * the guide, and every later start reads the stored dismissal. Installers preserve the stored
 * value, so an upgrade does not resurrect a guide the user already closed.</p>
 */
public interface GuidePreferences {

    /**
     * @return whether the user has not dismissed the guide yet; the shell opens the guide only
     *     while this is true
     */
    boolean shouldShowGuide();

    /**
     * Records the dismissal so no later start opens the guide again. Calling it repeatedly is
     * harmless.
     *
     * @throws IllegalStateException if the stored value cannot be rewritten
     */
    void markGuideSeen();

    /**
     * @return a fail-closed sentinel for runtimes without a writable config document: it reports
     *     nothing to show, so an untracked guide window never appears
     */
    static GuidePreferences unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: never shows the guide and stores nothing. */
    enum Unavailable implements GuidePreferences {
        INSTANCE;

        @Override
        public boolean shouldShowGuide() {
            return false;
        }

        @Override
        public void markGuideSeen() {}
    }
}
