package dev.turboism.bootstrap;

/**
 * The contributor the agent synthesizes for one {@link DeclarativeHook} entry, so a declarative hook
 * runs through the same install, bind, report and cleanup protocol as a hand-written contributor.
 *
 * <p>Only {@link #installAdmitted} differs from the hand-written family it replaces: the persisted
 * preference and the installer's own {@code admitted(...)} gate are read from the entry, and the
 * installer is created from the launch facts. Reporting, refusal codes and failure containment stay
 * in {@link NativeOptimizationHookContributor}, so a declarative hook cannot drift from a
 * class-declared one.</p>
 */
final class DeclarativeHookContributor extends NativeOptimizationHookContributor {

    private final DeclarativeHook entry;

    private DeclarativeHookContributor(final DeclarativeHook entry) {
        super(entry.reportId(), entry.hookId());
        this.entry = entry;
    }

    /**
     * @param entry a declared entry
     * @return the contributor that installs it
     */
    static DeclarativeHookContributor of(final DeclarativeHook entry) {
        return new DeclarativeHookContributor(entry);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!entry.preference().test(environment)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(REFUSED_PREFERENCE_DISABLED);
        }
        final InstallContext context = InstallContext.of(environment);
        final VerifiedHookInstaller installer = entry.installer().apply(context);
        if (!installer.admits(context)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(VerifiedInstall.ADMISSION_REFUSED);
        }
        installer.install();
        return installer;
    }
}
