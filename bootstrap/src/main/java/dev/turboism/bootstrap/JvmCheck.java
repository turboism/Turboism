package dev.turboism.bootstrap;

/**
 * The JVM predicate one verified hook freezes, expressed as {@code Runtime.version().feature()}.
 *
 * <p>The hooks historically disagreed, so the three values are preserved verbatim rather than
 * normalized: widening a predicate would newly install a weave on a JVM the hook was never measured
 * against.</p>
 */
enum JvmCheck {
    /** The hook has no JVM predicate of its own. */
    ANY,

    /** The hook requires Java 17 or newer. */
    AT_LEAST_17,

    /**
     * The hook requires exactly Java 17. Preserved verbatim: the launcher can start a user-supplied
     * GraalVM ({@code cubismJvm=graalvm}), so this is live rather than dead code, and a JDK-21 host must
     * not newly install these two weaves.
     */
    EXACTLY_17;

    /**
     * @param jvmFeature {@code Runtime.version().feature()} of the running JVM
     * @return whether the JVM half of the admission row admits the hook
     */
    boolean admits(final int jvmFeature) {
        return switch (this) {
            case ANY -> true;
            case AT_LEAST_17 -> jvmFeature >= 17;
            case EXACTLY_17 -> jvmFeature == 17;
        };
    }
}
