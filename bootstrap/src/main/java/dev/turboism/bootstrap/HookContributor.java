package dev.turboism.bootstrap;

/**
 * Self-describing install-time hook, discovered through the
 * {@code META-INF/turboism/hooks} manifest instead of being wired into the
 * agent by hand.
 *
 * <p>A contributor is a stateless factory descriptor: the agent instantiates
 * it once per start attempt, asks {@link #admitted(HookEnvironment)} whether
 * the hook belongs in this host, and forwards {@link #install(HookEnvironment)}
 * to the verified installer. Install-time state lives in the returned
 * {@link AutoCloseable}, never in the contributor.</p>
 *
 * <p>Known exception: {@link Phase#PREMAIN} transformer hooks whose installer
 * must stay reachable from the agent's runtime-start failure and
 * duplicate-runtime paths keep a {@code CURRENT} static reference that the
 * agent reads directly ({@link MeshMirrorHookContributor#CURRENT},
 * {@link WarpAltMirrorHookContributor#CURRENT}). Those contributors also carry
 * two identities by design: {@link #id()} is the report label, while the
 * startup-policy toggle lives on the contributor's own {@code HOOK_ID}
 * constant. Adding a hook still means adding a manifest line; this hand-off is
 * the only case where the agent touches contributor internals.</p>
 */
interface HookContributor {

    /**
     * @return the stable hook identity used in logs and cleanup ordering
     */
    String id();

    /**
     * The point in the bootstrap sequence where {@link #install(HookEnvironment)}
     * runs.
     */
    enum Phase {
        /**
         * Runs in premain/agentmain before the Cubism host class is located.
         * The environment carries no located host and no runtime.
         */
        PREMAIN,

        /**
         * Runs after the host class is located and verified, before the preview
         * runtime starts.
         */
        HOST_RESOLVED,

        /**
         * Runs after the preview runtime has started.
         */
        RUNTIME_STARTED
    }

    /**
     * @return the phase this hook installs in
     */
    Phase phase();

    /**
     * Decides whether the hook should be installed for the current host and
     * policy. Admission is fail-closed: returning {@code false} or throwing
     * from {@link #install(HookEnvironment)} leaves the hook uninstalled.
     *
     * @param environment the resolved bootstrap environment for this phase
     * @return {@code true} when the hook should be installed
     */
    boolean admitted(HookEnvironment environment);

    /**
     * Installs the hook.
     *
     * @param environment the resolved bootstrap environment for this phase
     * @return the handle the agent closes on shutdown; never {@code null}
     * @throws Exception when installation fails closed
     */
    AutoCloseable install(HookEnvironment environment) throws Exception;

    /**
     * Binds runtime services into a hook that installed in an earlier phase.
     * Runs during {@link Phase#RUNTIME_STARTED}; hooks that install at
     * {@link Phase#RUNTIME_STARTED} never need this.
     *
     * @param environment the runtime-started environment
     * @throws Exception when binding fails closed; the install handle is still
     *     closed by the agent
     */
    default void bind(HookEnvironment environment) throws Exception {
    }

    /**
     * @return {@code true} when the returned handle also closes on the
     *     process-exit path (before {@code runtime.closeForProcessExit()}),
     *     matching the hooks the pre-registry agent tore down with a
     *     {@code cleanup=COMPLETE phase=...} report line
     */
    default boolean closesOnProcessExit() {
        return false;
    }
}
