package dev.turboism.tests.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verdict-seam tests for {@link WindowsEditorObjectPeerValidationProbe}'s handshake gate. */
final class WindowsEditorObjectPeerValidationProbeTest {

    @TempDir
    private Path tempDir;

    @Test
    void peerHandshakeModeCoversOnlyCloseHandshakeModes() {
        assertTrue(WindowsEditorObjectPeerValidationProbe.peerHandshakeMode("plugin-scope-close"));
        assertTrue(WindowsEditorObjectPeerValidationProbe.peerHandshakeMode("document-close"));
        assertTrue(
                WindowsEditorObjectPeerValidationProbe.peerHandshakeMode("native-control-background-document-close"));
        assertFalse(WindowsEditorObjectPeerValidationProbe.peerHandshakeMode("perf-observe"));
        assertFalse(WindowsEditorObjectPeerValidationProbe.peerHandshakeMode(""));
    }

    @Test
    void awaitMarkerReturnsTrueWhenMarkerExists() throws Exception {
        final Path marker = tempDir.resolve("editor-object-peer-request.txt");
        Files.writeString(marker, "request");
        assertTrue(WindowsEditorObjectPeerValidationProbe.awaitMarker(marker, 1, 0));
    }

    @Test
    void awaitMarkerFailsClosedWhenMarkerNeverAppears() throws Exception {
        final Path marker = tempDir.resolve("editor-object-peer-request.txt");
        assertFalse(WindowsEditorObjectPeerValidationProbe.awaitMarker(marker, 2, 1));
    }
}
