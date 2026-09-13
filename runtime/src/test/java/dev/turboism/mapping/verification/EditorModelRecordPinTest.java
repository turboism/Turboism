package dev.turboism.mapping.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditorModelRecordPinTest {
    @Test
    void qSelectorRecordsMatchThePinsUsedBeforeHostConnection() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("compatibility/cubism/verification")))
            root = root.getParent();
        assertNotNull(root, "Repository verification records must be available; never skip pin validation");
        for (var pin : List.of(EditorModelVerificationManifest.RECORD_5_2_03,
                EditorModelVerificationManifest.RECORD_5_3_02, EditorModelVerificationManifest.RECORD_5_3_03)) {
            var loaded = new StaticVerificationRecordLoader().load(root.resolve(
                "compatibility/cubism/verification/cubism-" + pin.cubismVersion() + "-editor-model.json"));
            // Static selector validity alone is insufficient: runtime admission pins the entire record bytes.
            assertEquals(pin.recordSha256(), loaded.sha256(), "Runtime record pin drift: " + pin.cubismVersion());
            assertEquals(pin.verificationId(), loaded.record().verificationId());
            assertTrue(loaded.record().selectors().stream().anyMatch(s ->
                s.alias().equals("cubism.texture-atlas.native.item.scale")), "q selector missing");
        }
    }
}
