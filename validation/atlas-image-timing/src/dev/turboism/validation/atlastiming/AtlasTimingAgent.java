package dev.turboism.validation.atlastiming;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Validation-only premain agent that times the reviewed atlas open/regenerate path
 * (5.3.03 and 5.2.03 target lists are both installed; only the host's own owner
 * classes ever match).
 *
 * <p>It instruments only the reviewed {@link AtlasTimingTargets#reviewed()} methods with
 * stack-neutral enter/exit calls into {@link AtlasTimingProbe}; everything else is left untouched.
 * The agent never retransforms, never blocks a class load, and never throws into the host — a
 * failed gate is recorded in the evidence file and the class passes through unmodified.</p>
 */
public final class AtlasTimingAgent {
    private static final String OPT_IN_TOKEN = "ATLAS_TIMING_EXPLICIT_OPT_IN";

    private AtlasTimingAgent() {
    }

    public static void premain(final String ignored, final Instrumentation instrumentation) {
        try {
            final String optIn = require("turboism.validation.atlasTiming.optIn");
            if (!OPT_IN_TOKEN.equals(optIn)) {
                throw new IllegalArgumentException("atlasTiming opt-in token is missing");
            }
            require("turboism.validation.atlasTiming.output");
            instrumentation.addTransformer(new Transformer(), false);
            AtlasTimingProbe.flush();
        } catch (RuntimeException failure) {
            try {
                AtlasTimingProbe.markBlocked(failure.getMessage());
            } catch (Throwable suppressed) {
                // Evidence must never crash the host premain.
            }
        }
    }

    private static String require(final String key) {
        final String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }

    /** Matches reviewed owner classes by exact internal name; every other class passes through. */
    private static final class Transformer implements ClassFileTransformer {
        private final List<AtlasTimingTargets.Target> targets = AtlasTimingTargets.reviewed();
        private final Map<String, String> states = new LinkedHashMap<>();
        private final Map<String, String> shas = new LinkedHashMap<>();

        @Override
        public byte[] transform(final ClassLoader loader, final String className,
                                final Class<?> classBeingRedefined,
                                final java.security.ProtectionDomain domain,
                                final byte[] classfileBuffer) {
            if (classfileBuffer == null || className == null) return null;
            if (targets.stream().noneMatch(t -> t.ownerInternalName().equals(className))) {
                return null;
            }
            try {
                shas.put(className, sha256(classfileBuffer));
                final AtlasTimingTransformer.Outcome outcome =
                    AtlasTimingTransformer.instrument(classfileBuffer, className, targets);
                for (final AtlasTimingTargets.Target target : targets) {
                    if (!target.ownerInternalName().equals(className)) continue;
                    final String key = target.methodName() + target.descriptor();
                    final Integer count = outcome.matches().get(key);
                    // Host profiles can carry desc variants of one method (e.g.
                    // updateMesh's context type moved obfuscation buckets). A sibling
                    // variant that matched nothing must not downgrade a real match.
                    final String state =
                        count == null ? "ABSENT" : count < 0 ? "ABSTRACT" : "x" + count;
                    final String stateKey = target.methodName() + "@" + shortName(className);
                    final String existing = states.get(stateKey);
                    if (existing == null || "ABSENT".equals(existing)
                            || ("ABSTRACT".equals(existing) && state.startsWith("x"))) {
                        states.put(stateKey, state);
                    }
                }
                publish();
                return outcome.bytes();
            } catch (RuntimeException failure) {
                states.put("error@" + shortName(className), failure.getClass().getSimpleName());
                publish();
                return null;
            }
        }

        private void publish() {
            try {
                AtlasTimingProbe.setTargetStates(states.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(",")));
                AtlasTimingProbe.setClassSha(shas.entrySet().stream()
                    .map(e -> shortName(e.getKey()) + ":" + e.getValue().substring(0, 16))
                    .collect(Collectors.joining(",")));
            } catch (Throwable ignored) {
                // Evidence must never fail the transform.
            }
        }

        private static String shortName(final String internalName) {
            final int slash = internalName.lastIndexOf('/');
            return slash < 0 ? internalName : internalName.substring(slash + 1);
        }

        private static String sha256(final byte[] bytes) {
            try {
                final byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
                final StringBuilder text = new StringBuilder(hash.length * 2);
                for (final byte value : hash) {
                    text.append(Character.forDigit((value >> 4) & 0xF, 16));
                    text.append(Character.forDigit(value & 0xF, 16));
                }
                return text.toString();
            } catch (Exception failure) {
                return "sha256-unavailable";
            }
        }
    }
}
