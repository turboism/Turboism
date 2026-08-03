package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.b1.domain.ThemeBase;
import dev.turboism.plugin.uitheme.b1.domain.ThemeIcons;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageArchive;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageData;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageMetadata;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.UserFileError;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileHandleState;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileReadResult;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestResult;
import dev.turboism.sdk.ui.UserFileRequestStatus;
import dev.turboism.sdk.ui.UserFileWriteResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class ThemePackageTransferServiceTest {

    @Test
    void exportWritesTheCanonicalThemeArchiveThroughAnOpaqueHandle() {
        MemoryUserFiles files = new MemoryUserFiles();
        ThemePackageTransferService transfer = new ThemePackageTransferService(files);

        ThemePackageTransferService.ExportResult result = transfer.exportPackage(theme("user.aurora"));

        assertEquals(ThemePackageTransferService.ExportOutcome.EXPORTED, result.outcome());
        assertEquals(UserFileMode.WRITE, files.lastRequest.mode());
        assertArrayEquals(ThemePackageArchive.encode(theme("user.aurora")), files.written);
        assertEquals(UserFileHandleState.CLOSED, files.handle.state());
    }

    @Test
    void importReadsAndDecodesOneBoundedArchiveThroughAnOpaqueHandle() {
        MemoryUserFiles files = new MemoryUserFiles();
        files.read = ThemePackageArchive.encode(theme("user.aurora"));
        files.requestMode = UserFileMode.READ;
        ThemePackageTransferService transfer = new ThemePackageTransferService(files);

        ThemePackageTransferService.ImportResult result = transfer.importPackage();

        assertEquals(ThemePackageTransferService.ImportOutcome.IMPORTED, result.outcome());
        assertEquals(theme("user.aurora"), result.theme().orElseThrow());
        assertEquals(UserFileMode.READ, files.lastRequest.mode());
        assertEquals(UserFileHandleState.CLOSED, files.handle.state());
    }

    private static ThemePackageData theme(final String id) {
        return new ThemePackageData(
            new ThemePackageMetadata(id, "Aurora", "", "Turboism", "", "1", null,
                ThemeBase.DARK, ThemeIcons.LIGHT, false),
            Map.of(
                "accent", "#88C0D0", "background", "#2E3440", "surface", "#3B4252",
                "input.background", "#434C5E", "foreground", "#ECEFF4",
                "foreground.muted", "#D8DEE9", "selection.background", "#4C566A",
                "selection.foreground", "#ECEFF4", "border", "#4C566A",
                "viewport.background", "#242933"
            ),
            Map.of(), "", ""
        );
    }

    private static final class MemoryUserFiles implements UserFileAccessService {
        private final MemoryHandle handle = new MemoryHandle();
        private UserFileRequest lastRequest;
        private byte[] written;
        private byte[] read;
        private UserFileMode requestMode = UserFileMode.WRITE;

        @Override
        public CompletionStage<UserFileRequestResult> request(UserFileRequest request) {
            lastRequest = request;
            return CompletableFuture.completedFuture(new UserFileRequestResult(
                UserFileRequestStatus.GRANTED, Optional.of(handle.withMode(requestMode)), Optional.empty()
            ));
        }

        @Override public CompletionStage<UserFileReadResult<String>> readUtf8(UserFileHandle handle, int maxBytes) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<UserFileReadResult<byte[]>> readBytes(UserFileHandle handle, int maxBytes) { return CompletableFuture.completedFuture(new UserFileReadResult<>(Optional.of(read.clone()), Optional.empty(), false)); }
        @Override public CompletionStage<UserFileWriteResult> writeUtf8Atomic(UserFileHandle handle, String content) { throw new UnsupportedOperationException(); }

        @Override
        public CompletionStage<UserFileWriteResult> writeBytesAtomic(UserFileHandle handle, byte[] content) {
            written = content.clone();
            return CompletableFuture.completedFuture(new UserFileWriteResult(true, Optional.empty()));
        }
    }

    private static final class MemoryHandle implements UserFileHandle {
        private UserFileHandleState state = UserFileHandleState.ACTIVE;
        private UserFileMode mode = UserFileMode.WRITE;
        @Override public String id() { return "theme-export"; }
        @Override public String displayName() { return "aurora.zip"; }
        @Override public UserFileMode mode() { return mode; }
        @Override public UserFileLifetime lifetime() { return UserFileLifetime.ONE_OPERATION; }
        @Override public UserFileHandleState state() { return state; }
        @Override public void revoke() { state = UserFileHandleState.REVOKED; }
        @Override public void close() { state = UserFileHandleState.CLOSED; }
        MemoryHandle withMode(UserFileMode value) { mode = value; state = UserFileHandleState.ACTIVE; return this; }
    }
}
