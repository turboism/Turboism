package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class VerifiedControlAppearanceHookInstallerProfileTest {

    @Test
    void exact5303HasAStaticInstallerProfileWithoutWideningFutureVersions() {
        assertTrue(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.0.00"));
        assertTrue(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.0.06"));
        assertTrue(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.1.00"));
        assertTrue(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.1.03"));
        assertTrue(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.2.00"));
        assertTrue(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.2.03"));
        assertTrue(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.3.02"));
        assertTrue(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.3.03"));
        assertTrue(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.3.04"));
        assertFalse(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.4.00"));
        assertFalse(VerifiedControlAppearanceHookInstaller.supportsStaticProfile("5.3"));
    }
}
