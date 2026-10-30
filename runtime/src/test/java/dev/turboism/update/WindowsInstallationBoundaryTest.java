package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class WindowsInstallationBoundaryTest {
    @Test
    void defaultReceiptLoaderRemainsDisabled() {
        assertThrows(IOException.class, FrameworkInstallationReceiptLoader.disabled()::load);
    }

    @Test
    void validationFactoryCannotLoadWindowsCodeOnOtherPlatforms() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            assertThrows(
                    IOException.class,
                    () -> WindowsInstallationFiles.validationFactory(Path.of("missing.dll"), "0".repeat(64)));
        } else {
            assertThrows(
                    IOException.class,
                    () -> WindowsInstallationFiles.validationFactory(Path.of("missing.dll"), "invalid"));
        }
    }
}
