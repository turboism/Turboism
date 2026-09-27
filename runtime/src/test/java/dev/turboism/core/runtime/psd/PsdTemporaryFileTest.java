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
    void sourceNamesPreserveUnicodeCaseAndIsolateIdenticalNames() throws Exception {
        for (final String name : new String[] {"模型 原图.psd", "Original.PSD", "没有扩展名"}) {
            final PsdTemporaryFile first = PsdTemporaryFile.createIn(temporaryRoot);
            final PsdTemporaryFile second = PsdTemporaryFile.createIn(temporaryRoot);
            final Path original = first.validatedPath();
            Files.writeString(original, "PSD contents");
            first.useSourceName(name);
            second.useSourceName(name);
            assertEquals(name.equals("没有扩展名") ? name + ".psd" : name, first.fileName());
            assertEquals(first.fileName(), second.fileName());
            assertNotEquals(first.validatedPath(), second.validatedPath());
            assertEquals("PSD contents", Files.readString(first.validatedPath()));
            assertFalse(Files.exists(original));
        }
    }

    @Test
    void sourceNameCanDifferOnlyInCaseFromTheFallback() throws Exception {
        final PsdTemporaryFile target = PsdTemporaryFile.createIn(temporaryRoot);
        target.useSourceName("EXTERNAL-EDIT.PSD");
        try (var paths = Files.list(target.validatedPath().getParent())) {
            assertEquals(java.util.List.of("EXTERNAL-EDIT.PSD"),
                paths.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void unsafeNamesFallBackWithoutEscapingTheAllocation() throws Exception {
        for (final String name : new String[] {null, "", " ", ".", "..", "../outside.psd",
            "C:\\outside.psd", "/outside.psd", "a/b.psd", "a:b.psd", "a?b", "a\u0000b",
            "a\nb", "CON.psd", "nul", "COM1.PSD", "LPT².psd", "trailing.", "trailing ",
            "中".repeat(100)}) {
            final PsdTemporaryFile target = PsdTemporaryFile.createIn(temporaryRoot);
            final Path original = target.validatedPath();
            target.useSourceName(name);
            assertEquals(original, target.validatedPath(), String.valueOf(name));
            assertEquals("external-edit.psd", target.fileName());
        }
    }

    @Test
    void renamingNeverOverwritesAnExistingFile() throws Exception {
        final PsdTemporaryFile target = PsdTemporaryFile.createIn(temporaryRoot);
        final Path original = target.validatedPath();
        final Path occupied = Files.writeString(original.resolveSibling("original.psd"), "untouched");
        assertThrows(IOException.class, () -> target.useSourceName("original.psd"));
        assertEquals("untouched", Files.readString(occupied));
        assertEquals(original, target.validatedPath());
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
        assertThrows(IOException.class, () -> target.useSourceName("original.psd"));
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
