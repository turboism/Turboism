package dev.turboism.validation.triweave;

import java.nio.file.Path;
import java.util.regex.Pattern;

import dev.turboism.validation.kmembership.Weave;

/**
 * System-property contract for the T029-TRIAB dump+weave agent. Every field needed for a gated
 * run is mandatory and validated; invalid admission config must refuse installation, never
 * throw out of premain.
 *
 * Target constants (owner / descriptor / anchor shape) are pinned from javap evidence on the
 * reviewed official jar — see DESIGN.md. They are compile-time constants, not configurable
 * properties; the only target variation is the {@code shadow-selfcheck} test profile that lets
 * the offline fixture drive the SAME code path under its own owner names.
 */
public final class WeaveAbConfig {
    static final String PREFIX = "turboism.validation.triWeave.";
    static final String ENABLED = PREFIX + "enabled";
    static final String MODE = PREFIX + "mode";
    static final String PHASE = PREFIX + "phase";
    static final String OUTPUT_DIR = PREFIX + "outputDir";
    static final String RUN_ID = PREFIX + "runId";
    static final String EXPECT_CLASS_SHA = PREFIX + "expectClassSha256";
    static final String EXPECT_LOADER = PREFIX + "expectLoader";
    static final String EXPECT_CODESOURCE = PREFIX + "expectCodeSource";
    static final String CAPTURE_N = PREFIX + "captureN";
    /** Test-only: selects the shadow-fixture target set. Never part of host admission. */
    static final String PROFILE = PREFIX + "profile";

    public static final String MODE_DUMP_ONLY = "dump-only";
    public static final String MODE_DUMP_WEAVE = "dump+weave";

    /** Official 5.3.03 TriangleList.class sha256 (jar bd0a23b9...) — same digest the
     *  T029-IDENTITY probe pinned. */
    static final String OFFICIAL_CLASS_SHA256 =
        "87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29";

    static final String OFFICIAL_TARGET_INTERNAL =
        "com/live2d/graphics3d/editableMesh/triangulation/TriangleList";
    /** javap-verified: {@code b()Lk;}, k ctor {@code ()V}, query {@code k.a(Lj;Z)Z},
     *  append {@code k.a(Lj;)Z}, iterator {@code java/util/LinkedHashSet.iterator}. */
    static final Weave.Config OFFICIAL_WEAVE = new Weave.Config(
        "b",
        "com/live2d/graphics3d/editableMesh/triangulation/k",
        "com/live2d/graphics3d/editableMesh/triangulation/j",
        "a", "a",
        "dev/turboism/validation/triweave/Helper");
    static final String OFFICIAL_CAPTURE_INTERNAL =
        "dev/turboism/validation/triweave/Capture";

    static final String PROFILE_OFFICIAL = "official";
    static final String PROFILE_SHADOW = "shadow-selfcheck";
    static final String SHADOW_TARGET_INTERNAL =
        "dev/turboism/validation/triweave/shadow/ShadowTriangleList";
    /** Shadow target names differ on purpose: this proves the weave is driven by the
     *  Config, not by hardcoded fixture names. */
    static final Weave.Config SHADOW_WEAVE = new Weave.Config(
        "produce",
        "dev/turboism/validation/triweave/shadow/ShadowK",
        "dev/turboism/validation/triweave/shadow/ShadowJ",
        "has", "add",
        "dev/turboism/validation/triweave/shadow/ShadowHelper");
    static final String SHADOW_CAPTURE_INTERNAL =
        "dev/turboism/validation/triweave/shadow/ShadowCapture";

    static final String DEFAULT_LOADER = "jdk.internal.loader.ClassLoaders$AppClassLoader";
    static final int MAX_CAPTURE = 4;
    static final int DEFAULT_CAPTURE = 4;

    static final Pattern RUN_ID_OK = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    static final Pattern SHA_OK = Pattern.compile("[0-9a-fA-F]{64}");

    public final boolean enabled = "true".equals(System.getProperty(ENABLED));
    public final String mode = System.getProperty(MODE, "");
    public final String phase = System.getProperty(PHASE, "unlabelled");
    public final String outputDir = System.getProperty(OUTPUT_DIR, "");
    public final String runId = System.getProperty(RUN_ID, "");
    public final String profile = System.getProperty(PROFILE, PROFILE_OFFICIAL);
    public final String expectClassSha;
    public final String expectLoader;
    public final String expectCodeSource = System.getProperty(EXPECT_CODESOURCE, "");
    public final int captureN;

    /** Resolved from the profile — the only source of target shape. */
    public final String targetInternal;
    public final Weave.Config weave;
    public final String captureInternal;

    public WeaveAbConfig() {
        boolean shadow = PROFILE_SHADOW.equals(profile);
        this.targetInternal = shadow ? SHADOW_TARGET_INTERNAL : OFFICIAL_TARGET_INTERNAL;
        this.weave = shadow ? SHADOW_WEAVE : OFFICIAL_WEAVE;
        this.captureInternal = shadow ? SHADOW_CAPTURE_INTERNAL : OFFICIAL_CAPTURE_INTERNAL;
        this.expectClassSha = System.getProperty(EXPECT_CLASS_SHA,
            shadow ? "" : OFFICIAL_CLASS_SHA256);
        this.expectLoader = System.getProperty(EXPECT_LOADER,
            shadow ? "" : DEFAULT_LOADER);
        int n;
        try {
            n = Integer.parseInt(System.getProperty(CAPTURE_N,
                Integer.toString(DEFAULT_CAPTURE)));
        } catch (NumberFormatException e) {
            n = -1;
        }
        this.captureN = n;
    }

    /**
     * Admission validation; returns a reject reason or null. Missing/invalid config is a safe
     * refusal — the premain reports it and installs nothing.
     */
    String validate() {
        if (!enabled) return "disabled";
        if (!PROFILE_OFFICIAL.equals(profile) && !PROFILE_SHADOW.equals(profile))
            return "profile-invalid";
        if (!MODE_DUMP_ONLY.equals(mode) && !MODE_DUMP_WEAVE.equals(mode))
            return mode.isEmpty() ? "mode-missing" : "mode-invalid";
        if (outputDir.isEmpty()) return "outputDir-missing";
        try {
            Path p = Path.of(outputDir);
            if (!p.isAbsolute()) return "outputDir-not-absolute";
        } catch (RuntimeException e) {
            return "outputDir-invalid:" + e.getClass().getSimpleName();
        }
        if (runId.isEmpty() || !RUN_ID_OK.matcher(runId).matches()) return "runId-invalid";
        if (!SHA_OK.matcher(expectClassSha).matches()) return "expectClassSha256-invalid";
        if (expectLoader.isEmpty()) return "expectLoader-missing";
        if (expectCodeSource.isEmpty()) return "expectCodeSource-missing";
        if (normalizeCodeSource(expectCodeSource) == null) return "expectCodeSource-invalid";
        if (captureN < 1 || captureN > MAX_CAPTURE) return "captureN-out-of-range";
        return null;
    }

    boolean woven() { return MODE_DUMP_WEAVE.equals(mode); }

    /**
     * CodeSource comparison rule — identical to the T029-IDENTITY contract: real URI parsing,
     * absolute {@code file} scheme only, no authority/user-info/port/query/fragment, no dot
     * segments, canonical form {@code file:<rawPath>}.
     */
    static String normalizeCodeSource(String externalForm) {
        if (externalForm == null) return null;
        java.net.URI uri;
        try {
            uri = new java.net.URI(externalForm);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
        if (!uri.isAbsolute()) return null;
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("file")) return null;
        String authority = uri.getRawAuthority();
        if (authority != null && !authority.isEmpty()) return null; // file://host/... rejected
        if (uri.getRawUserInfo() != null || uri.getPort() != -1
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return null;
        }
        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/") || path.length() < 2) return null;
        for (String seg : path.split("/")) {
            if (seg.equals(".") || seg.equals("..")) return null;
        }
        return "file:" + path;
    }

    /** Exact canonical-form equality on both sides; invalid input never matches. */
    static boolean codeSourceMatches(String expectedExternalForm, String actualExternalForm) {
        String expected = normalizeCodeSource(expectedExternalForm);
        String actual = normalizeCodeSource(actualExternalForm);
        return expected != null && expected.equals(actual);
    }

    Path definitionLog() {
        return Path.of(outputDir, "tri-weave-def-" + runId + ".log");
    }

    Path dumpLog() {
        return Path.of(outputDir, "tri-weave-dump-" + runId + ".log");
    }

    Path statusFile() {
        return Path.of(outputDir, "tri-weave-status-" + runId + ".txt");
    }
}
