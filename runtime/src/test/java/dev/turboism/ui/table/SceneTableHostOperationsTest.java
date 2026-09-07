package dev.turboism.ui.table;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.StaticSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SceneTableHostOperationsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void unsupportedArtifactAndReadFailureNeverPoll() throws Exception {
        final AtomicInteger polls = new AtomicInteger();
        final SceneTableHostOperations unsupported = new SceneTableHostOperations(
            (artifact, loader) -> SceneTableHostProfile.forArtifact(
                HostArtifactDigest.from(artifact)
            ).map(profile -> profile.bind(loader)),
            ignored -> null,
            (delay, operation) -> polls.incrementAndGet()
        );
        final Path unknown = Files.writeString(temporaryDirectory.resolve("unknown.jar"), "unknown");

        assertEquals(
            SceneTableHostOperations.State.UNSUPPORTED,
            unsupported.connect(unknown, getClass().getClassLoader())
        );
        flushEdt();
        assertEquals(0, polls.get());

        final SceneTableHostOperations unreadable = new SceneTableHostOperations(
            (artifact, loader) -> SceneTableHostProfile.forArtifact(
                HostArtifactDigest.from(artifact)
            ).map(profile -> profile.bind(loader)),
            ignored -> null,
            (delay, operation) -> polls.incrementAndGet()
        );
        assertEquals(
            SceneTableHostOperations.State.FAILED,
            unreadable.connect(temporaryDirectory.resolve("missing.jar"), getClass().getClassLoader())
        );
        flushEdt();
        assertEquals(0, polls.get());
    }

    @Test
    void bindingFailureNeverPollsAndDisconnectInvalidatesOldRetry() throws Exception {
        final AtomicInteger polls = new AtomicInteger();
        final SceneTableHostOperations failed = new SceneTableHostOperations(
            (artifact, loader) -> {
                throw new IllegalArgumentException("selector mismatch");
            },
            ignored -> null,
            (delay, operation) -> polls.incrementAndGet()
        );
        assertEquals(
            SceneTableHostOperations.State.FAILED,
            failed.connect(temporaryDirectory.resolve("host.jar"), getClass().getClassLoader())
        );
        flushEdt();
        assertEquals(0, polls.get());

        final List<Runnable> retries = new ArrayList<>();
        final SceneTableHostProfile profile = new SceneTableHostProfile(
            "test",
            new HostArtifactDigest(0L, "0".repeat(64)),
            List.of(StaticSelector.classSelector("fixture", getClass().getName().replace('.', '/')))
        );
        final SceneTableHostOperations connecting = new SceneTableHostOperations(
            (artifact, loader) -> Optional.of(profile.bind(loader)),
            ignored -> null,
            (delay, operation) -> retries.add(operation)
        );
        assertEquals(
            SceneTableHostOperations.State.CONNECTING,
            connecting.connect(temporaryDirectory.resolve("host.jar"), getClass().getClassLoader())
        );
        flushEdt();
        assertEquals(1, retries.size());
        connecting.disconnect();
        retries.get(0).run();
        flushEdt();
        assertEquals(SceneTableHostOperations.State.DISCONNECTED, connecting.state());
        assertEquals(1, retries.size(), "stale retry must not schedule another poll");
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }
}
