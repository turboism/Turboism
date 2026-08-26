package dev.turboism.tests.distribution;

import org.junit.jupiter.api.Test;

class OracleB2MultiReleaseRegressionTest extends DistributionRegressionSupport {
    @Test void rejectsVersionedRuntimeClass() throws Exception {
        assertVersioned("runtime", "META-INF/versions/17/dev/turboism/bootstrap/Agent.class");
    }

    @Test void rejectsVersionedSdkClass() throws Exception {
        assertVersioned("sdk", "META-INF/versions/17/dev/turboism/sdk/Plugin.class");
    }

    @Test void rejectsVersionedPluginMetadata() throws Exception {
        assertVersioned("runtime", "META-INF/versions/17/META-INF/turboism/plugin.json");
    }

    @Test void rejectsVersionedTestClass() throws Exception {
        assertVersioned("runtime", "META-INF/versions/17/dev/turboism/core/HiddenTest.class");
    }

    @Test void rejectsVersionedLive2dClass() throws Exception {
        assertVersioned("runtime", "META-INF/versions/17/com/live2d/Host.class");
    }

    private void assertVersioned(String role, String entry) throws Exception {
        byte[] changed = FrameworkPackageFixtures.jar(entry, "bad",
            role.equals("runtime") ? "dev/turboism/bootstrap/Agent.class" : "dev/turboism/sdk/Plugin.class", "ok");
        byte[] runtime = role.equals("runtime") ? changed : validRuntime();
        byte[] sdk = role.equals("sdk") ? changed : validSdk();
        assertArtifactRejected(runtime, sdk, "MULTI_RELEASE_JAR_UNSUPPORTED",
            role.equals("runtime") ? "artifacts[0]" : "artifacts[1]");
    }
}
