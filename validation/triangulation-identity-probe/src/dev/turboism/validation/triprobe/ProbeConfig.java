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
     * CodeSource comparison rule (explicit, both sides normalized identically):
     * {@code toExternalForm} → trim → require {@code scheme:} → collapse redundant leading
     * slashes after the scheme to one → strip trailing '/'. Exact case-sensitive equality only —
     * no prefix, substring, or wildcard matching. Known limitation: an {@code //authority}
     * component is folded into the path form, so authority-bearing URLs lose the authority
     * distinction — acceptable for file-based sources.
     */
    static String normalizeCodeSource(String externalForm) {
        if (externalForm == null) return null;
        String s = externalForm.trim();
        int colon = s.indexOf(':');
        if (s.isEmpty() || s.contains("..") || colon < 1) return null;
        String scheme = s.substring(0, colon);
        String rest = s.substring(colon + 1).replaceAll("^/+", "/");
        s = scheme + ":" + rest;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    boolean codeSourceMatches(String actualExternalForm) {
        String expected = normalizeCodeSource(expectCodeSource);
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
