package dev.turboism.bootstrap;

/**
 * A verified installer that a {@link DeclarativeHook} entry names directly, so a hook family needs
 * no hand-written {@link HookContributor} class per install.
 *
 * <p>The installer keeps everything it already owned: its reviewed contract table, its
 * {@link Admission} row, its {@link WeaveSpec}, and the static {@code admitted(...)} gate its
 * admission truth table is pinned through. The declarative entry adds only the facts an installer
 * cannot state about itself — the report label, the persisted-preference switch and the installer
 * factory — and {@link VerifiedInstall} still owns admission ordering, attestation, registration,
 * retransformation and the restoration proof.</p>
 *
 * <p>One instance is created per start attempt, so implementers keep no install-time state outside
 * the handle this type closes.</p>
 */
interface VerifiedHookInstaller extends AutoCloseable {

    /**
     * Re-runs the installer's own reviewed-contract gate against the launch facts, so a declarative
     * entry can never install a weave its installer would refuse.
     *
     * @param context the launch facts for this attempt
     * @return whether this installer's reviewed contract admits the host
     */
    boolean admits(InstallContext context);

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws Exception when the host is admitted but verification, registration, retransformation or
     *     a post-check fails
     */
    void install() throws Exception;
}
