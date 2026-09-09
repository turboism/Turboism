package dev.turboism.core.runtime.psd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PsdTemporaryFileTest {
    @TempDir
    Path temporaryRoot;

    @Test
    void allocationsAreUniqueReservedFilesWithinTheTemporaryRoot() throws Exception {
        final PsdTemporaryFile first = PsdTemporaryFile.createIn(temporaryRoot);
        final PsdTemporaryFile second = PsdTemporaryFile.createIn(temporaryRoot);
        final Path file = first.validatedPath();
        assertEquals(temporaryRoot.toRealPath(), file.getParent().getParent());
        assertEquals("external-edit.psd", file.getFileName().toString());
        assertTrue(Files.isRegularFile(file));
        assertEquals(0, Files.size(file));
        assertNotEquals(file.getParent(), second.validatedPath().getParent());
    }

    @Test
    void writesStayInTempAndValidationDoesNotRemoveTheFile() throws Exception {
        final Path source = Files.writeString(temporaryRoot.resolve("original.psd"), "original");
        final PsdTemporaryFile target = PsdTemporaryFile.createIn(temporaryRoot);
        final Path file = target.validatedPath();
        Files.writeString(file, "edited");
        assertEquals(file, target.validatedPath());
        assertEquals("edited", Files.readString(file));
        assertEquals("original", Files.readString(source));
    }

    @Test
    void editorStyleRegularFileReplacementIsAllowed() throws Exception {
        final PsdTemporaryFile target = PsdTemporaryFile.createIn(temporaryRoot);
        final Path file = target.validatedPath();
        final Path replacement = Files.writeString(file.resolveSibling("editor-save.tmp"), "new bytes");
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
        assertEquals(file, target.validatedPath());
        assertEquals("new bytes", Files.readString(target.validatedPath()));
    }

    @Test
    void missingTargetIsNotRecreatedDuringValidation() throws Exception {
        final PsdTemporaryFile target = PsdTemporaryFile.createIn(temporaryRoot);
        final Path file = target.validatedPath();
        Files.delete(file);
        assertThrows(IOException.class, target::validatedPath);
        assertFalse(Files.exists(file));
    }

    @Test
    void replacingFileWithDirectoryIsRejected() throws Exception {
        final PsdTemporaryFile target = PsdTemporaryFile.createIn(temporaryRoot);
        final Path file = target.validatedPath();
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(IOException.class, target::validatedPath);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void symlinkToAnotherFileIsRejectedWithoutTouchingItsContent() throws Exception {
        final Path outside = Files.writeString(temporaryRoot.resolve("unrelated.psd"), "untouched");
        final PsdTemporaryFile target = PsdTemporaryFile.createIn(temporaryRoot);
        final Path file = target.validatedPath();
        Files.delete(file);
        Files.createSymbolicLink(file, outside);
        assertThrows(IOException.class, target::validatedPath);
        assertEquals("untouched", Files.readString(outside));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void replacedParentDirectoryCannotRedirectTheAllocation() throws Exception {
        final PsdTemporaryFile target = PsdTemporaryFile.createIn(temporaryRoot);
        final Path file = target.validatedPath();
        final Path parent = file.getParent();
        final Path moved = parent.resolveSibling("moved-allocation");
        Files.move(parent, moved);
        Files.createSymbolicLink(parent, moved);
        assertThrows(IOException.class, target::validatedPath);
        assertTrue(Files.isRegularFile(moved.resolve(file.getFileName())));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aNewDirectoryAtTheSamePathIsNotTheOriginalAllocation() throws Exception {
        final PsdTemporaryFile target = PsdTemporaryFile.createIn(temporaryRoot);
        final Path file = target.validatedPath();
        Files.move(file.getParent(), temporaryRoot.resolve("old-allocation"));
        Files.createDirectory(file.getParent());
        Files.createFile(file);
        assertThrows(IOException.class, target::validatedPath);
    }

    @Test
    void invalidTemporaryRootsFailWithoutCreatingFallbackFiles() throws Exception {
        final Path notDirectory = Files.createFile(temporaryRoot.resolve("not-a-directory"));
        assertThrows(IOException.class, () -> PsdTemporaryFile.createIn(notDirectory));
        assertThrows(IOException.class,
            () -> PsdTemporaryFile.createIn(temporaryRoot.resolve("missing-root")));
        assertThrows(NullPointerException.class, () -> PsdTemporaryFile.createIn(null));
    }
}
