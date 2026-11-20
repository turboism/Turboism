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

    private static final String EXPECTED_FROM = "5.0.00";

    @Test
    void independentlyVerifiedHistoryOperationsDeclareFromBoundAvailability() throws Exception {
        assertFrom50(FileChooserHistoryService.class.getMethod("projectRecentDirectory"));
        assertFrom50(FileChooserHistoryService.class.getMethod("exportRecentDirectory"));
        assertFrom50(FileChooserHistoryService.class.getMethod("setProjectRecentDirectory", Path.class));
        assertFrom50(FileChooserHistoryService.class.getMethod("setExportRecentDirectory", Path.class));
        assertFrom50(FileChooserHistoryService.class.getMethod("exportSeparationEnabled"));
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

    private static void assertFrom50(final java.lang.reflect.Method method) {
        final CubismEditor declaration = method.getAnnotation(CubismEditor.class);
        assertNotNull(declaration);
        assertArrayEquals(new String[0], declaration.value());
        assertEquals(EXPECTED_FROM, declaration.from());
    }
}
