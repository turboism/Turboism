package dev.turboism.validation.triprobe;

import java.nio.file.Path;

/** System-property contract for the identity probe. Default is fully disabled. */
final class ProbeConfig {
    static final String PREFIX = "turboism.validation.triIdentity.";
    static final String ENABLED = PREFIX + "enabled";
    static final String PHASE = PREFIX + "phase";
    static final String OUTPUT_DIR = PREFIX + "outputDir";
    static final String RUN_ID = PREFIX + "runId";
    static final String EXPECT_CLASS_SHA = PREFIX + "expectClassSha256";
    static final String EXPECT_LOADER = PREFIX + "expectLoader";
    static final String EXPECT_CODESOURCE = PREFIX + "expectCodeSourcePrefix";
    /** Test-only knobs; never enabled by production callers. */
    static final String FAIL_INIT = PREFIX + "failInit";
    static final String THROW_ON_RECORD = PREFIX + "throwOnRecord";

    /** Official 5.3.03 TriangleList.class sha256 (jar bd0a23b9...). */
    static final String OFFICIAL_CLASS_SHA256 =
        "87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29";

    static final String TARGET_INTERNAL = "com/live2d/graphics3d/editableMesh/triangulation/TriangleList";
    static final String TARGET_DOT = TARGET_INTERNAL.replace('/', '.');
    /** Official loader observed for the host's app classpath (same pin style as t039). */
    static final String DEFAULT_LOADER = "jdk.internal.loader.ClassLoaders$AppClassLoader";

    final boolean enabled = "true".equals(System.getProperty(ENABLED));
    final String phase = System.getProperty(PHASE, "unlabelled");
    final String outputDir = System.getProperty(OUTPUT_DIR,
        System.getProperty("java.io.tmpdir") + "/tri-identity-probe");
    final String runId = System.getProperty(RUN_ID, "no-run-id");
    final String expectClassSha = System.getProperty(EXPECT_CLASS_SHA, OFFICIAL_CLASS_SHA256);
    final String expectLoader = System.getProperty(EXPECT_LOADER, DEFAULT_LOADER);
    final String expectCodeSourcePrefix = System.getProperty(EXPECT_CODESOURCE, "");
    final boolean failInit = "true".equals(System.getProperty(FAIL_INIT));
    final boolean throwOnRecord = "true".equals(System.getProperty(THROW_ON_RECORD));

    Path definitionLog() {
        return Path.of(outputDir, "tri-identity-definition-" + runId + ".log");
    }

    Path useSiteLog() {
        return Path.of(outputDir, "tri-identity-usesite-" + runId + ".log");
    }
}
