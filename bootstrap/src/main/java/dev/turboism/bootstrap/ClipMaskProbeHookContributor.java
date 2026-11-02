package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.performance.ClipMaskProbeMethodTransformer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Contributor for the measurement-only clipping-mask probe. It changes no host
 * behaviour: the woven bytes only consult JDK functional-interface slots.
 *
 * <p>Two activation carriers exist because the native-Windows harness cannot rely on
 * {@code JAVA_TOOL_OPTIONS} - the installed managed launcher builds its option line from
 * {@code config.json} and never forwards inherited Java options:</p>
 *
 * <ul>
 *   <li>a JVM property, {@code -Dturboism.validation.clipMaskProbe=true}, used by the
 *       Wine/Proton runner (it forwards {@code --jvm-option} as
 *       {@code JAVA_TOOL_OPTIONS} in the generated {@code launch.bat});</li>
 *   <li>a marker file the operator stages inside the isolated Turboism home before
 *       launch, {@code state/dev.turboism.validation.clip-mask-probe/enabled.flag}.
 *       That home is on every launch command line ({@code -Dturboism.home=...}) for the
 *       official, managed and preview launchers alike, and it is the same carrier the
 *       other host-validation exercisers use for their trigger flags.</li>
 * </ul>
 *
 * <p>The probe is a diagnostic, not a product optimization, so the hook policy is not
 * consulted. The {@code probe=ACTIVE} log marker reports the reviewed selectors, the
 * activation channel and which owners were already loaded; the workload-side
 * {@code leg.N.clipMask.*} keys (or a non-zero {@code clipMask.active} in the
 * performance-probe report) are the evidence that it is actually live, since
 * {@code installation=COMPLETE} alone only proves the install attempt finished.</p>
 */
final class ClipMaskProbeHookContributor extends NativeOptimizationHookContributor {

    /** Home-relative marker file that activates the probe without a JVM property. */
    static final String ENABLE_FLAG = "state/dev.turboism.validation.clip-mask-probe/enabled.flag";

    ClipMaskProbeHookContributor() {
        super("TURBOISM_CLIP_MASK_PROBE");
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        final String channel = activationChannel(environment.options().home());
        if (channel == null) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        final VerifiedClipMaskProbeInstaller installer =
                new VerifiedClipMaskProbeInstaller(environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(
                environment,
                id() + " probe=ACTIVE via=" + channel
                        + " sites=" + installer.sites()
                        + " version=" + installer.cubismVersion()
                        + " owners=" + installer.wovenOwners()
                        + " pending=" + installer.pendingOwners()
                        + (installer.failure() == null ? "" : " refused=" + installer.failure()));
        return installer;
    }

    /**
     * @param home the isolated Turboism home on this launch, or {@code null} when unknown
     * @return the carrier that enabled the probe, or {@code null} when neither did
     */
    static String activationChannel(final Path home) {
        if (Boolean.getBoolean(ClipMaskProbeMethodTransformer.ENABLE_PROPERTY)) return "property";
        if (home != null && Files.isRegularFile(home.resolve(ENABLE_FLAG))) return "home-flag";
        return null;
    }
}
