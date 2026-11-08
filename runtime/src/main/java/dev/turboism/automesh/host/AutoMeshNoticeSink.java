package dev.turboism.automesh.host;

/**
 * Delivers Auto Mesh notices to the user through the runtime's existing status surface.
 *
 * <p>The sink is invoked from the Editor UI thread and from the completion callback of a started
 * run. An implementation must never block: the guard answers the host funnel before the notice is
 * rendered, and a blocking sink would stall the Editor's own event dispatch.</p>
 */
@FunctionalInterface
public interface AutoMeshNoticeSink {

    /**
     * Shows one notice.
     *
     * @param notice the localized notice to render
     */
    void show(AutoMeshNotice notice);
}
