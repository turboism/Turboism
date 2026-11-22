package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrameworkUpdateStageTest {
    @TempDir
    Path temporary;

    private record Built(FrameworkUpdateDownloader.Downloaded downloaded, byte[] changed, byte[] same) {}

    private Built downloaded() throws IOException {
        final byte[] changed = "agent-new".getBytes(StandardCharsets.UTF_8);
        final byte[] same = "unchanged".getBytes(StandardCharsets.UTF_8);
        final var changedEntry = entry("turboism-agent.jar", changed);
        final var sameEntry = entry("graal/lib/keep.jar", same);
        final Path manifest = temporary.resolve("cache-manifest");
        final Path envelope = temporary.resolve("cache-envelope");
        Files.writeString(manifest, "{manifest}");
        Files.writeString(envelope, "{envelope}");
        final Path object = temporary.resolve("cache-object");
        Files.write(object, changed);
        final var plan = new FrameworkDeltaPlanner.Plan(
                List.of(
                        new FrameworkDeltaPlanner.Decision(changedEntry, FrameworkDeltaPlanner.Action.DOWNLOAD),
                        new FrameworkDeltaPlanner.Decision(sameEntry, FrameworkDeltaPlanner.Action.REUSE)),
                changed.length);
        return new Built(
                new FrameworkUpdateDownloader.Downloaded(
                        manifest, envelope, plan, Map.of(changedEntry.sha256(), object), Optional.empty()),
                changed,
                same);
    }

    private static UpdateManifestEntry entry(final String path, final byte[] bytes) {
        final String hash = FrameworkUpdateTaskStore.hash(bytes);
        return new UpdateManifestEntry(path, bytes.length, hash);
    }

    @Test
    void stagesDocumentsAndOnlyChangedFiles() throws Exception {
        final Built built = downloaded();
        final Path stage = FrameworkUpdateStage.materialize(built.downloaded(), temporary.resolve("stage"));
        assertEquals("{manifest}", Files.readString(stage.resolve("manifest.json")));
        assertEquals("{envelope}", Files.readString(stage.resolve("manifest.json.sig")));
        assertArrayEquals(built.changed(), Files.readAllBytes(stage.resolve("files/turboism-agent.jar")));
        assertFalse(Files.exists(stage.resolve("files/graal/lib/keep.jar")), "reused file is not copied");
        assertFalse(Files.exists(temporary.resolve("stage.building")), "no temp directory left");
        assertEquals(
                Map.of("turboism-agent.jar", FrameworkUpdateTaskStore.hash(built.changed())),
                FrameworkUpdateStage.expectedFiles(built.downloaded()));
    }

    @Test
    void stagesCopyLocalEntriesAlongsideDownloads() throws Exception {
        final byte[] copied = "identical-renamed-sdk".getBytes(StandardCharsets.UTF_8);
        final var copyEntry = entry("graal/lib/sdk-0.70.2.jar", copied);
        final Path manifest = temporary.resolve("copy-manifest");
        final Path envelope = temporary.resolve("copy-envelope");
        final Path object = temporary.resolve("copy-object");
        Files.writeString(manifest, "{manifest}");
        Files.writeString(envelope, "{envelope}");
        Files.write(object, copied);
        final var plan = new FrameworkDeltaPlanner.Plan(
                List.of(new FrameworkDeltaPlanner.Decision(
                        copyEntry,
                        FrameworkDeltaPlanner.Action.COPY_LOCAL,
                        Optional.of("graal/lib/sdk-0.70.2-beta.4.jar"))),
                0L);
        final var downloaded = new FrameworkUpdateDownloader.Downloaded(
                manifest, envelope, plan, Map.of(copyEntry.sha256(), object), Optional.empty(), 0L);

        final Path stage = FrameworkUpdateStage.materialize(downloaded, temporary.resolve("copy-stage"));
        assertArrayEquals(copied, Files.readAllBytes(stage.resolve("files/graal/lib/sdk-0.70.2.jar")));
        assertEquals(
                Map.of("graal/lib/sdk-0.70.2.jar", copyEntry.sha256()), FrameworkUpdateStage.expectedFiles(downloaded));
    }

    @Test
    void cacheObjectChangedAfterDownloadIsRejectedAndLeavesNoStage() throws Exception {
        final Built built = downloaded();
        Files.writeString(built.downloaded().objects().values().iterator().next(), "agent-evl");
        assertThrows(
                IOException.class,
                () -> FrameworkUpdateStage.materialize(built.downloaded(), temporary.resolve("stage")));
        assertFalse(Files.exists(temporary.resolve("stage")));
        assertFalse(Files.exists(temporary.resolve("stage.building")));
    }

    @Test
    void existingStageIsNeverOverwritten() throws Exception {
        final Built built = downloaded();
        Files.createDirectory(temporary.resolve("stage"));
        Files.writeString(temporary.resolve("stage/marker"), "keep");
        assertThrows(
                IOException.class,
                () -> FrameworkUpdateStage.materialize(built.downloaded(), temporary.resolve("stage")));
        assertEquals("keep", Files.readString(temporary.resolve("stage/marker")));
    }

    @Test
    void stageProducedHereIsExactlyWhatTheHelperConsumes() throws Exception {
        // The layout contract between the two classes: files/ is keyed by manifest path.
        final Built built = downloaded();
        final Path stage = FrameworkUpdateStage.materialize(built.downloaded(), temporary.resolve("stage"));
        for (final var decision : built.downloaded().plan().decisions()) {
            final boolean present =
                    Files.exists(stage.resolve("files").resolve(decision.entry().relativePath()));
            assertEquals(decision.action() == FrameworkDeltaPlanner.Action.DOWNLOAD, present);
        }
    }
}
