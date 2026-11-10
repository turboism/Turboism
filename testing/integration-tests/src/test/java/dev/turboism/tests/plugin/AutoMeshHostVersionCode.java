package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.command.EditorCommand;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reviewed Cubism release codes in the compact form the validation queue publishes.
 *
 * <p>The queue passes {@code -Dturboism.validation.hostVersion} as a compact code such as
 * {@code 5302}, and {@code scripts/preview/reviewed_host_artifacts.py} validates every queued task
 * version in that same form. A probe that compared a dotted form would reject every real run, so
 * this type compares the compact form against the reviewed set the SDK publishes.</p>
 *
 * <p>The reviewed set comes from the SDK's own Editor command table: every reviewed release since
 * 5.0.00 appears in at least one command's declared versions, and the Auto Mesh host profiles pin
 * exactly that range. The union is therefore the SDK-visible reviewed release set, and it only
 * grows when a release is reviewed.</p>
 */
final class AutoMeshHostVersionCode {

    /** Dotted form of one reviewed release, for example {@code 5.3.02}. */
    private static final Pattern DOTTED = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    private static final String SEPARATOR = ".";

    private AutoMeshHostVersionCode() {}

    /**
     * Converts a dotted release to its compact code.
     *
     * @param dotted the dotted form, for example {@code 5.3.02}
     * @return the compact code, for example {@code 5302}, or an empty string when the input is not
     *     a dotted release
     */
    static String compact(final String dotted) {
        if (dotted == null || !DOTTED.matcher(dotted).matches()) {
            return "";
        }
        return dotted.replace(SEPARATOR, "");
    }

    /**
     * Returns every reviewed release code the SDK publishes.
     *
     * @return the compact codes in declaration order
     */
    static Set<String> reviewedCodes() {
        final Set<String> codes = new LinkedHashSet<>();
        for (final EditorCommand command : EditorCommand.values()) {
            for (final String dotted : command.supportedVersions()) {
                final String code = compact(dotted);
                if (!code.isEmpty()) {
                    codes.add(code);
                }
            }
        }
        return Set.copyOf(codes);
    }

    /**
     * Reports whether one compact code names a reviewed release.
     *
     * @param code the compact code from the validation queue
     * @return {@code true} only for an exact reviewed release
     */
    static boolean admitted(final String code) {
        return code != null && reviewedCodes().contains(code);
    }
}
