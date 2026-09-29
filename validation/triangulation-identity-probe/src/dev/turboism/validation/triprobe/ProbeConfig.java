package dev.turboism.validation.triprobe;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** System-property contract. Every field needed for a gated run is mandatory and validated;
 * invalid admission config must refuse installation, never throw out of premain. */
final class ProbeConfig {
    static final String PREFIX = "turboism.validation.triIdentity.";
    static final String ENABLED = PREFIX + "enabled";
    static final String PHASE = PREFIX + "phase";
    static final String OUTPUT_DIR = PREFIX + "outputDir";
    static final String RUN_ID = PREFIX + "runId";
    static final String EXPECT_CLASS_SHA = PREFIX + "expectClassSha256";
    static final String EXPECT_LOADER = PREFIX + "expectLoader";
    static final String EXPECT_CODESOURCE = PREFIX + "expectCodeSource";
    /** Test-only knobs; never part of host admission. */
    static final String FAIL_INIT = PREFIX + "failInit";
    static final String THROW_ON_RECORD = PREFIX + "throwOnRecord";

    /** Official 5.3.03 TriangleList.class sha256 (jar bd0a23b9...). */
    static final String OFFICIAL_CLASS_SHA256 =
        "87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29";

    static final String TARGET_INTERNAL = "com/live2d/graphics3d/editableMesh/triangulation/TriangleList";
    static final String TARGET_DOT = TARGET_INTERNAL.replace('/', '.');
    static final String DEFAULT_LOADER = "jdk.internal.loader.ClassLoaders$AppClassLoader";

    static final Pattern RUN_ID_OK = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    static final Pattern SHA_OK = Pattern.compile("[0-9a-fA-F]{64}");

    final boolean enabled = "true".equals(System.getProperty(ENABLED));
    final String phase = System.getProperty(PHASE, "unlabelled");
    final String outputDir = System.getProperty(OUTPUT_DIR, "");
    final String runId = System.getProperty(RUN_ID, "");
    final String expectClassSha = System.getProperty(EXPECT_CLASS_SHA, OFFICIAL_CLASS_SHA256);
    final String expectLoader = System.getProperty(EXPECT_LOADER, DEFAULT_LOADER);
    final String expectCodeSource = System.getProperty(EXPECT_CODESOURCE, "");
    final boolean failInit = "true".equals(System.getProperty(FAIL_INIT));
    final boolean throwOnRecord = "true".equals(System.getProperty(THROW_ON_RECORD));

    /**
     * Admission validation; returns a reject reason or null. Missing/invalid config is a safe
     * refusal — the premain reports it and installs nothing.
     */
    String validate() {
        if (!enabled) return "disabled";
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
        return null;
    }

    /**
     * CodeSource comparison rule (explicit): real URI parsing only.
     *
     * <ul>
     *   <li>Must parse as an absolute {@code file} URI (scheme case-insensitive).</li>
     *   <li>Authority must be empty or absent — {@code file:///x} and {@code file:/x} are the
     *       same resource and canonicalize identically; {@code file://host/x} is rejected.</li>
     *   <li>Query, fragment, user-info, and port are rejected.</li>
     *   <li>Path must be absolute and non-empty; a {@code .} or {@code ..} path segment is
     *       rejected (segment-level check on the parsed path, not string search).</li>
     *   <li>Canonical form is {@code file:<rawPath>} — the raw path is preserved verbatim,
     *       including any trailing slash; different external forms that do not share this
     *       canonical form never match.</li>
     * </ul>
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
        return Path.of(outputDir, "tri-identity-definition-" + runId + ".log");
    }

    Path useSiteLog() {
        return Path.of(outputDir, "tri-identity-usesite-" + runId + ".log");
    }
}
