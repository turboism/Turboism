package dev.turboism.plugin.turboismwithfx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FxRuntimePlatformTest {

    @Test
    void recognizesReviewedOperatingSystemAndArchitectureAliases() {
        assertEquals(
            "windows-x86_64",
            FxRuntimePlatform.detect("Windows 11", "amd64").orElseThrow().id()
        );
        assertEquals(
            "linux-aarch64",
            FxRuntimePlatform.detect("Linux", "arm64").orElseThrow().id()
        );
        assertEquals(
            "macos-x86_64",
            FxRuntimePlatform.detect("Mac OS X", "x86_64").orElseThrow().id()
        );
    }

    @Test
    void unknownPlatformsFailClosed() {
        assertTrue(FxRuntimePlatform.detect("Solaris", "sparcv9").isEmpty());
        assertTrue(FxRuntimePlatform.detect("Windows 11", "x86").isEmpty());
    }
}
