package dev.turboism.bootstrap;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One declarative install-time hook entry: the facts a hook family used to restate in one
 * {@link HookContributor} subclass per install, expressed as data over {@link HookManifest} and
 * {@link WeavePlan}.
 *
 * <p>The installer keeps its reviewed contract table, {@link Admission} row, {@link WeaveSpec} and its
 * static {@code admitted(...)} gate, so the admission truth table stays where it is pinned. This entry
 * adds only the report label, the persisted-preference gate and the installer factory, and every
 * declared entry installs in {@link HookContributor.Phase#HOST_RESOLVED} because runtime startup can
 * already open and decode the initial document.</p>
 *
 * <p>Entries are addressed from {@code META-INF/turboism/hooks} by {@link #key()}, so a manifest may
 * mix declarative entries with fully-qualified contributor class names and an unmigrated family keeps
 * loading unchanged.</p>
 *
 * @param reportId the protocol id the installation outcome is recorded and logged under; the manifest
 *     token derives from it, so a token can never drift from the protocol it selects
 * @param hookId the operator-facing id {@code hooks.disabledIds} matches, taken from the installer's
 *     own {@code HOOK_ID} constant so the kill switch has one source of truth
 * @param preference the persisted-preference gate, read from the launch plan instead of the file
 * @param installer the installer factory, called once per start attempt
 */
record DeclarativeHook(
        String reportId,
        String hookId,
        Predicate<HookEnvironment> preference,
        Function<InstallContext, VerifiedHookInstaller> installer) {

    /** Prefix every manifest token carries, so a token is visibly a declarative entry. */
    static final String TOKEN_PREFIX = "verified-";

    /** Prefix the protocol ids carry; the token drops it before deriving its own spelling. */
    private static final String PROTOCOL_PREFIX = "TURBOISM_";

    DeclarativeHook {
        if (!reportId.startsWith(PROTOCOL_PREFIX)) {
            throw new IllegalArgumentException("declarative hook protocol id must start with " + PROTOCOL_PREFIX);
        }
    }

    /**
     * @return the {@code META-INF/turboism/hooks} token that selects this entry
     */
    String key() {
        return TOKEN_PREFIX
                + reportId.substring(PROTOCOL_PREFIX.length())
                        .toLowerCase(Locale.ROOT)
                        .replace('_', '-');
    }
}
