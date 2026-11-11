package dev.turboism.core.descriptor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The retired capability ids a manifest may still declare, and the canonical id each one means.
 *
 * <p>Capability ids are manifest vocabulary, so a plugin published against an older descriptor
 * keeps declaring whatever the framework shipped then. Two families named the same runtime
 * capability twice: the singular and plural parameter read/write pair, and the three history read
 * ids. Each retired id now normalises to one canonical id, so admission, reporting and the
 * compatibility matrix see a single vocabulary while an older manifest still loads.
 *
 * <p>The table is closed and lives beside the parser: a retired id normalises only when the
 * canonical id is a live capability of the same family, and anything else stays as declared and is
 * reported as unknown. Guessing an owner for an unrecognised id would silently widen admission.
 * Not instantiable.
 */
final class CapabilityAliases {

    /** Canonical parameter read capability. */
    static final String PARAMETER_READ = "cubism.parameter.read";
    /** Canonical parameter write capability. */
    static final String PARAMETER_WRITE = "cubism.parameter.write";
    /** Canonical native history read capability. */
    static final String HISTORY_READ = "cubism.editor-history.read";
    /** Canonical native history move capability. */
    static final String HISTORY_MOVE = "cubism.editor-history.move";

    /** Retired capability id to the canonical capability id that replaced it. */
    private static final Map<String, String> RETIRED = Map.of(
            "cubism.parameters.read", PARAMETER_READ,
            "cubism.parameters.write", PARAMETER_WRITE,
            "cubism.history.read", HISTORY_READ,
            "cubism.history.write", HISTORY_MOVE,
            "cubism.editor-history.semantic-read", HISTORY_READ);

    private CapabilityAliases() {}

    /**
     * Returns the canonical id for a declared capability id.
     *
     * @param declared the capability id as written in a manifest, never {@code null}
     * @return the canonical id, which is {@code declared} itself when it was never retired
     */
    static String canonical(final String declared) {
        return RETIRED.getOrDefault(Objects.requireNonNull(declared, "declared"), declared);
    }

    /**
     * Normalises a declared capability list, dropping the duplicates a migration can create.
     *
     * @param declared the capability ids as written in a manifest
     * @return the canonical ids in first-declaration order, without duplicates
     */
    static List<String> canonicalize(final List<String> declared) {
        Objects.requireNonNull(declared, "declared");
        return declared.stream().map(CapabilityAliases::canonical).distinct().toList();
    }

    /**
     * Returns the retired ids that normalise to {@code canonicalId}.
     *
     * @param canonicalId a canonical capability id
     * @return the retired ids it replaced, in no defined order
     */
    static Set<String> retiredIdsFor(final String canonicalId) {
        final Set<String> retired = new LinkedHashSet<>();
        RETIRED.forEach((from, to) -> {
            if (to.equals(canonicalId)) {
                retired.add(from);
            }
        });
        return Set.copyOf(retired);
    }

    /**
     * Returns the canonical id that replaced {@code retiredId}.
     *
     * @param retiredId a capability id a manifest may still declare
     * @return the canonical id, or empty when {@code retiredId} was never retired
     */
    static Optional<String> replacementFor(final String retiredId) {
        return Optional.ofNullable(RETIRED.get(Objects.requireNonNull(retiredId, "retiredId")));
    }

    /**
     * Returns every retired capability id.
     *
     * @return the immutable retired id set
     */
    static Set<String> retiredIds() {
        return RETIRED.keySet();
    }
}
