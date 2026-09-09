package dev.turboism.core.runtime.psd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** Filesystem fixtures only; arbitrary test bytes do not constitute valid PSD or host evidence. */
class PsdStableSnapshotTest {
    @TempDir Path root;

    @Test
    void copiesIntoDistinctSiblingWithMatchingStreamedDigest() throws Exception {
        final PsdTemporaryFile allocation = PsdTemporaryFile.createIn(root);
        final byte[] bytes = new byte[150_003];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31);
        final Path source = allocation.validatedPath();
        Files.write(source, bytes);
        final var snapshot = PsdStableSnapshot.capture(allocation);
        assertNotEquals(source, snapshot.path());
        assertEquals(source.getParent(), snapshot.path().getParent());
        assertArrayEquals(bytes, Files.readAllBytes(snapshot.path()));
        assertArrayEquals(bytes, Files.readAllBytes(source));
        assertEquals(bytes.length, snapshot.size());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), snapshot.sha256());
    }

    @Test
    void laterAtomicSaveDoesNotRewriteExistingStage() throws Exception {
        final PsdTemporaryFile allocation = PsdTemporaryFile.createIn(root);
        final Path live = allocation.validatedPath();
        Files.writeString(live, "first fixture");
        final var first = PsdStableSnapshot.capture(allocation);
        final Path replacement = Files.writeString(root.resolve("replacement"), "second fixture");
        Files.move(replacement, live, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        final var second = PsdStableSnapshot.capture(allocation);
        assertNotEquals(first.path(), second.path());
        assertNotEquals(first.sha256(), second.sha256());
        assertEquals("first fixture", Files.readString(first.path()));
        assertEquals("second fixture", Files.readString(second.path()));
        assertEquals("second fixture", Files.readString(live));
    }

    @Test
    void rejectsEmptyInputWithoutAllocatingAStage() throws Exception {
        final PsdTemporaryFile allocation = PsdTemporaryFile.createIn(root);
        final Path live = allocation.validatedPath();
        assertThrows(IOException.class, () -> PsdStableSnapshot.capture(allocation));
        try (var files = Files.list(live.getParent())) { assertEquals(1, files.count()); }
        assertTrue(Files.exists(live));
    }

    @Test
    void enforcesBoundWithoutIncreasingApprovedLimit() throws Exception {
        final PsdTemporaryFile allocation = PsdTemporaryFile.createIn(root);
        final byte[] bytes = new byte[1025];
        Arrays.fill(bytes, (byte) 7);
        Files.write(allocation.validatedPath(), bytes);
        assertThrows(IOException.class, () -> PsdStableSnapshot.capture(allocation, 1024));
        assertEquals(1025, PsdStableSnapshot.capture(allocation, 1025).size());
        assertThrows(IllegalArgumentException.class, () -> PsdStableSnapshot.capture(allocation, 0));
        assertThrows(IllegalArgumentException.class,
            () -> PsdStableSnapshot.capture(allocation, PsdStableSnapshot.MAX_BYTES + 1));
        assertArrayEquals(bytes, Files.readAllBytes(allocation.validatedPath()));
    }

    @Test
    void refusesLiveSymlinkAndDoesNotModifyOutsideTarget() throws Exception {
        final PsdTemporaryFile allocation = PsdTemporaryFile.createIn(root);
        final Path live = allocation.validatedPath();
        final Path original = live.resolveSibling("retained-original.psd");
        Files.move(live, original);
        final Path outside = Files.writeString(root.resolve("outside"), "private fixture");
        Files.createSymbolicLink(live, outside);
        assertThrows(IOException.class, () -> PsdStableSnapshot.capture(allocation));
        assertEquals("private fixture", Files.readString(outside));
        assertTrue(Files.exists(original));
    }

    @Test
    void equalBytesYieldSameDigestButSeparateRetainedArtifacts() throws Exception {
        final PsdTemporaryFile allocation = PsdTemporaryFile.createIn(root);
        Files.writeString(allocation.validatedPath(), "unchanged fixture");
        final var first = PsdStableSnapshot.capture(allocation);
        final var second = PsdStableSnapshot.capture(allocation);
        assertEquals(first.sha256(), second.sha256());
        assertNotEquals(first.path(), second.path());
        assertTrue(Files.exists(first.path()));
        assertTrue(Files.exists(second.path()));
    }
}
