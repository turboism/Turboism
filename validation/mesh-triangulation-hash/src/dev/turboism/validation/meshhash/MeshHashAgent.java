package dev.turboism.validation.meshhash;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Validation-only premain agent for the triangulation constant-hash fix.
 *
 * <p>It does exactly two things, both bounded to named host classes:</p>
 * <ol>
 *   <li>in {@code fix} mode, replaces {@code triangulation.l#hashCode()} with the corrected,
 *       permutation-invariant hash, but only when the observed class bytes match the reviewed
 *       SHA-256;</li>
 *   <li>in both modes, appends a digest call before every return of
 *       {@code GEditableMesh2.updateMesh}, so a native and a patched run produce a directly
 *       comparable record of the triangulation output.</li>
 * </ol>
 *
 * <p>Everything else is left untouched: unknown classes and unrecognised shapes make the
 * transformer return {@code null}, never a modified class. The digest is structural only — index
 * buffer values plus point/edge counts — and is written to a bounded evidence file.</p>
 */
public final class MeshHashAgent {
    static final String TRIPLE_INTERNAL = "com/live2d/graphics3d/editableMesh/triangulation/l";
    static final String MESH_INTERNAL = "com/live2d/graphics3d/editableMesh/GEditableMesh2";
    private static final String MESH_UPDATE_DESCRIPTOR = "(Lcom/live2d/util/j/a;Z)V";
    private static final String POINT_DESCRIPTOR =
        "Lcom/live2d/graphics3d/editableMesh/triangulation/TriPoint;";
    private static final String OPT_IN_TOKEN = "MESH_HASH_EXPLICIT_OPT_IN";
    private static final String MODE_PROBE = "probe";
    private static final String MODE_FIX = "fix";

    private MeshHashAgent() {
    }

    public static void premain(final String ignored, final Instrumentation instrumentation) {
        try {
            final Config config = Config.fromSystemProperties();
            MeshHashDigestProbe.setMode(config.mode());
            instrumentation.addTransformer(new Transformer(config), false);
            MeshHashDigestProbe.flush();
        } catch (RuntimeException failure) {
            MeshHashDigestProbe.markBlocked(failure.getMessage());
        }
    }

    /** Immutable, validated launch configuration; every field is mandatory and bounded. */
    record Config(String mode, String expectedTripleSha256, Path output) {
        static Config fromSystemProperties() {
            final String mode = require("turboism.validation.meshHash.mode");
            if (!MODE_PROBE.equals(mode) && !MODE_FIX.equals(mode)) {
                throw new IllegalArgumentException("meshHash.mode must be probe or fix");
            }
            final String optIn = require("turboism.validation.meshHash.optIn");
            if (!OPT_IN_TOKEN.equals(optIn)) {
                throw new IllegalArgumentException("meshHash opt-in token is missing");
            }
            final String hash = require("turboism.validation.meshHash.tripleSha256");
            if (!hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("meshHash.tripleSha256 is not a lowercase SHA-256");
            }
            final Path output = Path.of(require("turboism.validation.meshHash.output"));
            return new Config(mode, hash, output);
        }

        private static String require(final String key) {
            final String value = System.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing " + key);
            }
            return value;
        }
    }

    /** Selects the two reviewed host classes by exact internal name; everything else is untouched. */
    private static final class Transformer implements ClassFileTransformer {
        private final Config config;
        private final CornerTripleHashPatcher patcher =
            new CornerTripleHashPatcher(TRIPLE_INTERNAL, POINT_DESCRIPTOR);

        Transformer(final Config config) {
            this.config = config;
        }

        @Override
        public byte[] transform(final ClassLoader loader, final String className,
                                final Class<?> classBeingRedefined, final java.security.ProtectionDomain domain,
                                final byte[] classfileBuffer) {
            if (classfileBuffer == null) return null;
            try {
                if (TRIPLE_INTERNAL.equals(className)) {
                    return patchTriple(classfileBuffer);
                }
                if (MESH_INTERNAL.equals(className)) {
                    return hookMesh(classfileBuffer);
                }
            } catch (RuntimeException failure) {
                MeshHashDigestProbe.markBlocked(failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            return null;
        }

        private byte[] patchTriple(final byte[] original) {
            final String observed = sha256(original);
            if (MODE_PROBE.equals(config.mode())) {
                MeshHashDigestProbe.setTriple("SEEN_UNPATCHED", observed);
                return null;
            }
            if (!config.expectedTripleSha256().equals(observed)) {
                MeshHashDigestProbe.setTriple("HASH_MISMATCH", observed);
                return null;
            }
            try {
                final byte[] patched = patcher.patch(original);
                MeshHashDigestProbe.setTriple("PATCHED", observed);
                return patched;
            } catch (CornerTripleHashPatcher.Rejected rejected) {
                MeshHashDigestProbe.setTriple("SHAPE_REJECTED", rejected.getMessage());
                return null;
            }
        }

        private byte[] hookMesh(final byte[] original) {
            final byte[] hooked;
            try {
                hooked = MeshUpdateDigestTransformer.hook(original, MESH_UPDATE_DESCRIPTOR);
            } catch (MeshUpdateDigestTransformer.NotApplicable notApplicable) {
                MeshHashDigestProbe.setMeshHook("NOT_APPLICABLE", notApplicable.getMessage());
                return null;
            }
            MeshHashDigestProbe.setMeshHook("HOOKED", "ok");
            return hooked;
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
                throw new IllegalStateException("SHA-256 is unavailable", failure);
            }
        }
    }
}
