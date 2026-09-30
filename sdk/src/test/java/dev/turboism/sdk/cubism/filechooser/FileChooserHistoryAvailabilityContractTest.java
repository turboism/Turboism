package dev.turboism.sdk.cubism.filechooser;

import dev.turboism.sdk.CubismEditor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class FileChooserHistoryAvailabilityContractTest {

    private static final String[] REVIEWED_VERSIONS = {"5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"};

    @Test
    void independentlyVerifiedHistoryOperationsDeclareExact53Availability()
        throws Exception {
        assertExact53(FileChooserHistoryService.class.getMethod("projectRecentDirectory"));
        assertExact53(FileChooserHistoryService.class.getMethod("exportRecentDirectory"));
        assertExact53(FileChooserHistoryService.class.getMethod(
            "setProjectRecentDirectory",
            Path.class
        ));
        assertExact53(FileChooserHistoryService.class.getMethod(
            "setExportRecentDirectory",
            Path.class
        ));
        assertExact53(FileChooserHistoryService.class.getMethod("exportSeparationEnabled"));
    }

    @Test
    void providerRegistrationDoesNotClaimEditorVersionAvailability()
        throws Exception {
        assertNull(FileChooserHistoryService.class.getAnnotation(CubismEditor.class));
        assertNull(FileChooserHistoryService.class.getMethod(
            "registerProvider",
            FileChooserHistoryService.Provider.class
        ).getAnnotation(CubismEditor.class));
    }

    private static void assertExact53(final java.lang.reflect.Method method) {
        assertArrayEquals(REVIEWED_VERSIONS, method.getAnnotation(CubismEditor.class).value());
    }
}
