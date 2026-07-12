package dev.turboism.tests.distribution;

import dev.turboism.distribution.FrameworkInstallPlan;
import dev.turboism.distribution.PackageIdentity;
import dev.turboism.distribution.PlannedFile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class OracleFinalBlockerRegressionTest extends DistributionRegressionSupport {
    @Test void rejectsFakeTerminalEocdProbe() throws Exception {
        byte[] jar = validRuntime();
        byte[] forged = Arrays.copyOf(jar, jar.length + 22);
        putInt(forged, jar.length, 0x06054b50);
        putInt(forged, jar.length + 16, jar.length);
        assertArtifactRejected(forged, validSdk(), "ARTIFACT_JAR_INVALID", "artifacts[0]");
    }

    @Test void publicPlanTypesHaveNoPublicConstructors() {
        assertNoPublicConstructor(FrameworkInstallPlan.class);
        assertNoPublicConstructor(PackageIdentity.class);
        assertNoPublicConstructor(PlannedFile.class);
    }

    private static void assertNoPublicConstructor(Class<?> type) {
        for (var constructor : type.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()), type.getName());
        }
    }

    private static void putInt(byte[] bytes, int at, int value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> (8 * i));
    }
}
