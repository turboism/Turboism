package dev.turboism.bootstrap;

import java.util.Objects;

/**
 * The admission row one verified hook declares: the policy id the kill switch and the report line
 * share, whether the launch startup policy is consulted at all, and the JVM predicate.
 *
 * <p>The ordering of the row (request, JVM feature, policy, reviewed target) lives in
 * {@link VerifiedInstall#admits}; the reviewed-target expression stays at the hook so each admission
 * source — pin table, digest table, version string plus JAR entries — remains visible where it is
 * declared instead of hiding behind a shared abstraction.</p>
 *
 * @param hookId the hook identity the kill switch matches: {@code cubism.*} for the product
 *     optimizations, and the protocol id for a test-only diagnostic
 * @param policyCheck whether admission consults {@code safeMode}/{@code hooks.disabledIds}
 * @param jvmCheck the JVM predicate this hook froze
 */
record Admission(String hookId, PolicyCheck policyCheck, JvmCheck jvmCheck) {

    Admission {
        Objects.requireNonNull(hookId, "hookId");
        Objects.requireNonNull(policyCheck, "policyCheck");
        Objects.requireNonNull(jvmCheck, "jvmCheck");
    }
}
