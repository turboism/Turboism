package dev.turboism.preview;

import dev.turboism.mapping.verification.VerifiedAccessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewLogTest {

    @TempDir
    Path temporary;

    @Test
    void errorLogIncludesVerifiedSelectorDiagnosticsAndCauseChain() throws Exception {
        final Path path = temporary.resolve("turboism.log");
        final VerifiedAccessException failure = new VerifiedAccessException(
            "cubism.editor-model.parameter-group.add",
            VerifiedAccessException.FailureKind.INVOCATION,
            "Verified host selector invocation failed safely.",
            new IllegalAccessException("fixture")
        );

        try (PreviewLog log = new PreviewLog(path)) {
            log.error("probe", "Combined action failed", failure);
        }

        final String content = Files.readString(path);
        assertTrue(content.contains(
            "dev.turboism.mapping.verification.VerifiedAccessException: "
                + "Verified host selector invocation failed safely. "
                + "[alias=cubism.editor-model.parameter-group.add, failureKind=INVOCATION]"
        ));
        assertTrue(content.contains("caused by java.lang.IllegalAccessException: fixture"));
    }
}
