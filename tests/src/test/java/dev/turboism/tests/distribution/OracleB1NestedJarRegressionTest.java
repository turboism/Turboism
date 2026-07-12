package dev.turboism.tests.distribution;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

class OracleB1NestedJarRegressionTest extends DistributionRegressionSupport {
    @Test void rejectsRandomRuntimeBytes() throws Exception {
        assertArtifactRejected(new byte[]{1, 2, 3, 4}, validSdk(), "ARTIFACT_JAR_INVALID", "artifacts[0]");
    }

    @Test void rejectsEmptyRuntimeBytes() throws Exception {
        assertArtifactRejected(new byte[0], validSdk(), "ARTIFACT_JAR_INVALID", "artifacts[0]");
    }

    @Test void rejectsTextSdkBytes() throws Exception {
        assertArtifactRejected(validRuntime(), "not a jar".getBytes(), "ARTIFACT_JAR_INVALID", "artifacts[1]");
    }

    @Test void rejectsEmptyJar() throws Exception {
        assertArtifactRejected(FrameworkPackageFixtures.jar(), validSdk(), "ARTIFACT_REQUIRED_CLASS_MISSING", "artifacts[0]");
    }

    @Test void rejectsTruncatedJar() throws Exception {
        byte[] jar = validRuntime();
        assertArtifactRejected(Arrays.copyOf(jar, jar.length - 9), validSdk(), "ARTIFACT_JAR_INVALID", "artifacts[0]");
    }

    @Test void rejectsTrailingGarbage() throws Exception {
        byte[] jar = Arrays.copyOf(validRuntime(), validRuntime().length + 4);
        assertArtifactRejected(jar, validSdk(), "ARTIFACT_JAR_INVALID", "artifacts[0]");
    }

    @Test void rejectsCrcError() throws Exception {
        assertArtifactRejected(corruptEntryData(validRuntime()), validSdk(), "ARTIFACT_JAR_INVALID", "artifacts[0]");
    }
}
