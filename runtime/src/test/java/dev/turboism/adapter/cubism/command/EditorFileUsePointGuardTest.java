package dev.turboism.adapter.cubism.command;

import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorFileUsePointGuardTest {
    @TempDir Path temporary;

    @Test
    void admitsExistingRegularCanonicalReadTargets() throws Exception {
        Path file = temporary.resolve("model.cmo3");
        Files.writeString(file, "fixture");

        assertTrue(admit(EditorFileCommand.OPEN, file, EditorOverwritePolicy.REJECT_EXISTING).allowed());
    }

    @Test
    void rejectsReadTargetsThatAreMissingDirectoriesOrNonCanonical() throws Exception {
        assertFalse(admit(EditorFileCommand.OPEN, temporary.resolve("missing.cmo3"),
            EditorOverwritePolicy.REJECT_EXISTING).allowed(), "missing file");

        Path dir = temporary.resolve("dir.cmo3");
        Files.createDirectory(dir);
        assertFalse(admit(EditorFileCommand.OPEN, dir, EditorOverwritePolicy.REJECT_EXISTING).allowed(),
            "directory is not a regular file");

        Path target = temporary.resolve("target.cmo3");
        Files.writeString(target, "fixture");
        Path link = temporary.resolve("link.cmo3");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            Assumptions.abort("symlinks are not supported on this platform");
        }
        assertFalse(admit(EditorFileCommand.OPEN, link, EditorOverwritePolicy.REJECT_EXISTING).allowed(),
            "symlink target is not canonical");
    }

    @Test
    void enforcesWriteOverwritePolicyAndParentChecks() throws Exception {
        Path target = temporary.resolve("out.cmo3");
        assertTrue(admit(EditorFileCommand.SAVE_AS, target, EditorOverwritePolicy.REJECT_EXISTING).allowed(),
            "new write target with canonical parent");

        Files.writeString(target, "existing");
        assertFalse(admit(EditorFileCommand.SAVE_AS, target, EditorOverwritePolicy.REJECT_EXISTING).allowed(),
            "existing target rejected without replacement policy");
        assertTrue(admit(EditorFileCommand.SAVE_AS, target, EditorOverwritePolicy.REPLACE_EXISTING).allowed(),
            "existing regular target admitted with replacement policy");

        assertFalse(admit(EditorFileCommand.SAVE_AS, temporary.resolve("missing-dir/out.cmo3"),
            EditorOverwritePolicy.REPLACE_EXISTING).allowed(), "missing parent");

        Path parentLink = temporary.resolve("parent-link");
        Files.createDirectory(temporary.resolve("real-parent"));
        try {
            Files.createSymbolicLink(parentLink, temporary.resolve("real-parent"));
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            Assumptions.abort("symlinks are not supported on this platform");
        }
        assertFalse(admit(EditorFileCommand.SAVE_AS, parentLink.resolve("out.cmo3"),
            EditorOverwritePolicy.REJECT_EXISTING).allowed(), "symlinked parent is not canonical");
    }

    private static EditorFileUsePointGuard.Result admit(
        final EditorFileCommand command,
        final Path file,
        final EditorOverwritePolicy policy
    ) {
        return EditorFileUsePointGuard.admit(new ResolvedEditorFileCommand(command, file, policy));
    }
}
