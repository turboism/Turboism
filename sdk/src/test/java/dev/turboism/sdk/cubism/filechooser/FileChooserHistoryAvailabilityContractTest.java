package dev.turboism.sdk.cubism.filechooser;

import dev.turboism.sdk.CubismEditor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class FileChooserHistoryAvailabilityContractTest {

    private static final String[] EXACT_5_3 = {"5.3.02", "5.3.03"};

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
        assertArrayEquals(EXACT_5_3, method.getAnnotation(CubismEditor.class).value());
    }
}
