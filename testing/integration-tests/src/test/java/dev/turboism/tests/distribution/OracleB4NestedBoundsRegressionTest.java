package dev.turboism.tests.distribution;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

class OracleB4NestedBoundsRegressionTest extends DistributionRegressionSupport {
    @Test void rejectsTooManyNestedEntries() throws Exception {
        assertArtifactRejected(jarWithEntries(257, 1), validSdk(), "NESTED_ENTRY_LIMIT", "artifacts[0]");
    }

    @Test void rejectsNestedCompressionBomb() throws Exception {
        assertArtifactRejected(jarWithEntries(1, 1024 * 1024), validSdk(), "NESTED_COMPRESSION_RATIO", "artifacts[0]");
    }

    @Test void rejectsNestedCaseCollision() throws Exception {
        byte[] jar = FrameworkPackageFixtures.jar("dev/turboism/A.class", "a",
            "DEV/TURBOISM/a.class", "b", "dev/turboism/bootstrap/Agent.class", "ok");
        assertArtifactRejected(jar, validSdk(), "NESTED_PATH_COLLISION", "artifacts[0]");
    }

    @Test void rejectsNestedTraversal() throws Exception {
        byte[] jar = FrameworkPackageFixtures.jar("../bad.class", "bad",
            "dev/turboism/bootstrap/Agent.class", "ok");
        assertArtifactRejected(jar, validSdk(), "NESTED_PATH_UNSAFE", "artifacts[0]");
    }

    private static byte[] jarWithEntries(int count, int contentSize) throws Exception {
        byte[] content = new byte[contentSize];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            for (int i = 0; i < count; i++) add(jar, "dev/turboism/generated/C" + i + ".class", content);
            add(jar, "dev/turboism/bootstrap/Agent.class", new byte[]{1});
        }
        return output.toByteArray();
    }

    private static void add(JarOutputStream jar, String name, byte[] content) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content);
        jar.closeEntry();
    }
}
