package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.turboism.adapter.cubism.performance.ClipMaskProbeMethodTransformer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins both activation carriers of the measurement-only clipping-mask probe: the JVM
 * property the Wine/Proton runner forwards, and the home-relative marker file the
 * native-Windows runner stages (the managed launcher does not forward inherited Java
 * options). Neither present means the probe stays off.
 */
final class ClipMaskProbeHookContributorTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(ClipMaskProbeMethodTransformer.ENABLE_PROPERTY);
    }

    @Test
    void propertyCarrierWinsAndFlagCarrierStandsAlone(@TempDir final Path home) throws Exception {
        assertNull(ClipMaskProbeHookContributor.activationChannel(home));

        final Path flag = home.resolve(ClipMaskProbeHookContributor.ENABLE_FLAG);
        Files.createDirectories(flag.getParent());
        Files.writeString(flag, "presence only\n");
        assertEquals("home-flag", ClipMaskProbeHookContributor.activationChannel(home));

        System.setProperty(ClipMaskProbeMethodTransformer.ENABLE_PROPERTY, "true");
        assertEquals("property", ClipMaskProbeHookContributor.activationChannel(home));
        assertEquals("property", ClipMaskProbeHookContributor.activationChannel(null));
    }

    @Test
    void unknownHomeNeverActivates(@TempDir final Path home) {
        assertNull(ClipMaskProbeHookContributor.activationChannel(null));
        assertNull(ClipMaskProbeHookContributor.activationChannel(home.resolve("missing")));
    }
}
