package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurboismAgentAdmissionRoutingTest {

    private static final List<String> REQUIRED_5303_RUNTIME_RECORDS = List.of(
        "cubism-5.3.03-project-workspace.json",
        "cubism-5.3.03-editor-model.json",
        "cubism-5.3.03-ui-main-toolbar.json",
        "cubism-5.3.03-ui-embedded-panel.json",
        "cubism-5.3.03-ui-top-menu.json",
        "cubism-5.3.03-ui-bounding-box-overlay.json",
        "cubism-5.3.03-ui-status-bar.json",
        "cubism-5.3.03-clipmask.json",
        "cubism-5.3.03-autobackup.json",
        "cubism-5.3.03-ui-control-appearance.json"
    );

    @Test
    void exact5303IdentityPassesTheAgentFullRuntimeGate() {
        assertTrue(ReviewedHostArtifacts.isReviewed(ReviewedHostArtifacts.CUBISM_5_3_03));
        assertTrue(
            ReviewedHostArtifacts.admitsFullRuntime(
                ReviewedHostArtifacts.CUBISM_5_3_03_VERSION
            )
        );
    }

    @Test
    void exact5303FullRuntimeRecordsArePackagedForAgentExtraction() throws Exception {
        for (String fileName : REQUIRED_5303_RUNTIME_RECORDS) {
            final String resource = "/META-INF/turboism/verification/" + fileName;
            try (InputStream stream = TurboismAgent.class.getResourceAsStream(resource)) {
                assertNotNull(stream, resource);
                assertTrue(stream.read() >= 0, resource + " must not be empty");
            }
        }
    }
}
