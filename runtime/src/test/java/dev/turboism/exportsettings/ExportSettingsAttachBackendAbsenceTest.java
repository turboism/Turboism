package dev.turboism.exportsettings;

import dev.turboism.ui.host.EditorUiFamily;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable proof that the attach backend stays authority-internal: no production
 * class other than {@link RuntimeExportSettingsAuthority} (its sole consumer) may
 * reference it, and this slice added no editor-UI family admission.
 */
final class ExportSettingsAttachBackendAbsenceTest {

    private static final String BACKEND_INTERNAL_NAME =
        "dev/turboism/exportsettings/ExportSettingsAttachBackend";

    @Test
    void onlyTheAuthorityReferencesTheAttachBackend() throws Exception {
        final Path mainClasses = runtimeMainClassesDirectory();
        assertTrue(Files.isDirectory(mainClasses), "runtime main classes must exist: " + mainClasses);
        final List<String> references = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(mainClasses)) {
            walk.filter(path -> path.toString().endsWith(".class"))
                .filter(ExportSettingsAttachBackendAbsenceTest::isNotTheBackendOrItsSoleConsumer)
                .forEach(path -> {
                    try {
                        final byte[] bytes = Files.readAllBytes(path);
                        if (new String(bytes, StandardCharsets.ISO_8859_1)
                            .contains(BACKEND_INTERNAL_NAME)) {
                            references.add(mainClasses.relativize(path).toString());
                        }
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
        }
        assertTrue(
            references.isEmpty(),
            "runtime production classes other than the authority must not reference the attach backend: " + references
        );
    }

    @Test
    void noExportSettingsEditorUiFamilyOrCapabilityAdmissionWasAdded() {
        assertFalse(
            Arrays.stream(EditorUiFamily.values())
                .anyMatch(family -> family.name().contains("EXPORT_SETTINGS")),
            "no export-settings Editor UI family may exist while admission is absent"
        );
    }

    private static boolean isNotTheBackendOrItsSoleConsumer(final Path path) {
        final String name = path.getFileName().toString();
        return !name.equals("ExportSettingsAttachBackend.class")
            && !name.startsWith("ExportSettingsAttachBackend$")
            && !name.equals("RuntimeExportSettingsAuthority.class")
            && !name.startsWith("RuntimeExportSettingsAuthority$");
    }

    private static Path runtimeMainClassesDirectory() throws Exception {
        final Path testClasses = Path.of(
            ExportSettingsAttachBackendAbsenceTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
        );
        return testClasses.getParent().resolve("main");
    }
}
