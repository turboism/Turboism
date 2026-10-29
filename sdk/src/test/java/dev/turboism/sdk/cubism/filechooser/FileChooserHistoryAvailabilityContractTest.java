package dev.turboism.sdk.cubism.filechooser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.CubismEditor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class FileChooserHistoryAvailabilityContractTest {

    private static final String[] REVIEWED_VERSIONS = {
        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03",
        "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
    };

    @Test
    void independentlyVerifiedHistoryOperationsDeclareExact53Availability() throws Exception {
        assertExact53(FileChooserHistoryService.class.getMethod("projectRecentDirectory"));
        assertExact53(FileChooserHistoryService.class.getMethod("exportRecentDirectory"));
        assertExact53(FileChooserHistoryService.class.getMethod("setProjectRecentDirectory", Path.class));
        assertExact53(FileChooserHistoryService.class.getMethod("setExportRecentDirectory", Path.class));
        assertExact53(FileChooserHistoryService.class.getMethod("exportSeparationEnabled"));
    }

    @Test
    void providerRegistrationDoesNotClaimEditorVersionAvailability() throws Exception {
        assertUnrestrictive(FileChooserHistoryService.class);
        assertNull(FileChooserHistoryService.class
                .getMethod("registerProvider", FileChooserHistoryService.Provider.class)
                .getAnnotation(CubismEditor.class));
    }

    private static void assertUnrestrictive(final Class<?> type) {
        final CubismEditor declaration = type.getAnnotation(CubismEditor.class);
        assertNotNull(declaration);
        assertEquals(0, declaration.value().length);
        assertTrue(declaration.from().isEmpty());
        assertTrue(declaration.to().isEmpty());
        assertEquals(0, declaration.exclude().length);
    }

    private static void assertExact53(final java.lang.reflect.Method method) {
        assertArrayEquals(
                REVIEWED_VERSIONS, method.getAnnotation(CubismEditor.class).value());
    }
}
