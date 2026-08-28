package dev.turboism.adapter.cubism.editor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorObjectValidationTraceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperties() {
        System.clearProperty("turboism.editorObjectValidation.trace");
        System.clearProperty("turboism.home");
    }

    @Test
    void defaultOffDoesNotCreateTraceArtifact() {
        System.setProperty("turboism.home", tempDir.toString());

        EditorObjectValidationTrace.begin("ArtMesh", "setOpacity", "mesh", new Object(), new Object());

        assertFalse(Files.exists(traceArtifact()));
    }

    @Test
    void enabledTraceNeverExceedsExplicitByteCap() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");

        for (int index = 0; index < 20_000; index++) {
            EditorObjectValidationTrace.event(
                index,
                "mutation",
                "ArtMesh",
                "replaceGeometry",
                "mesh-" + index,
                new Object(),
                new Object(),
                "detail=" + "x".repeat(80)
            );
        }

        assertTrue(Files.exists(traceArtifact()));
        assertTrue(Files.size(traceArtifact()) <= EditorObjectValidationTrace.MAX_BYTES);
    }

    @Test
    void sharedEvidenceArtifactWritesAreCappedAndSerialized() throws Exception {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");
        final String evidence = "x".repeat(128);
        final java.util.concurrent.ExecutorService writers =
            java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            final java.util.List<java.util.concurrent.Callable<Void>> writes =
                new java.util.ArrayList<>();
            for (int index = 0; index < 20_000; index++) {
                writes.add(() -> {
                    EditorObjectValidationTrace.writeArtifact(
                        "parameter-binding-transfer-native-bindings.txt", evidence, true
                    );
                    return null;
                });
            }
            for (var future : writers.invokeAll(writes)) future.get();
        } finally {
            writers.shutdownNow();
        }

        final Path artifact = tempDir.resolve("logs").resolve(
            "parameter-binding-transfer-native-bindings.txt"
        );
        assertTrue(Files.exists(artifact));
        assertTrue(Files.size(artifact) <= EditorObjectValidationTrace.MAX_BYTES);
    }

    @Test
    void sharedEvidenceArtifactRejectsPathTraversal() {
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");

        EditorObjectValidationTrace.writeArtifact("../outside.txt", "evidence", true);

        assertFalse(Files.exists(tempDir.resolve("outside.txt")));
    }

    @Test
    void traceWriteFailureNeverChangesMutationBehavior() throws Exception {
        Files.writeString(tempDir.resolve("logs"), "not-a-directory");
        System.setProperty("turboism.home", tempDir.toString());
        System.setProperty("turboism.editorObjectValidation.trace", "true");

        assertDoesNotThrow(() -> EditorObjectValidationTrace.begin(
            "Rotation_Deformer",
            "replaceForm",
            "rotation",
            new Object(),
            new Object()
        ));
    }

    private Path traceArtifact() {
        return tempDir.resolve("logs").resolve("editor-object-runtime-trace.txt");
    }
}
