package dev.turboism.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VerifiedControlAppearanceHookInstallerProfileTest {

    @Test
    void exact5303HasAStaticInstallerProfileWithoutWideningFutureVersions() {
        assertTrue(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.2.03"));
        assertTrue(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.3.02"));
        assertTrue(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.3.03"));
        assertFalse(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.3.04"));
        assertFalse(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.3"));
    }
}
