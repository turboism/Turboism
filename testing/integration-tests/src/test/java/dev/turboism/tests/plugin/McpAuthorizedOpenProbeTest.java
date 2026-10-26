package dev.turboism.tests.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpAuthorizedOpenProbeTest {
    @TempDir
    Path temporary;

    private static final String RUN = "queue-1234567890abcdef";

    private Path[] taskPaths() throws Exception {
        Path task = Files.createDirectories(temporary.resolve(RUN));
        Path home = Files.createDirectories(task.resolve("turboism-home"));
        Files.createDirectories(home.resolve("state"));
        Path fixture = Files.writeString(task.resolve(RUN + ".cmo3"), "fixture");
        return new Path[] {home, fixture};
    }

    @Test
    void persistenceBindingPolicySeparatesExistingHeadRigAndInitialization() {
        var before = geometry();
        var changed = List.of(new Stored("mesh", List.of(1, 2, 99)), before.get(1), before.get(2));
        assertDoesNotThrow(() -> McpAuthorizedOpenProbe.checkPersistenceDelta(
                before, changed, List.of("original"), List.of("original"), true));
        assertDoesNotThrow(() -> McpAuthorizedOpenProbe.checkPersistenceDelta(
                before, changed, List.of("original"), List.of("new binding"), false));
        assertThrows(
                IllegalStateException.class,
                () -> McpAuthorizedOpenProbe.checkPersistenceDelta(
                        before, changed, List.of("original"), List.of("new binding"), true));
        assertThrows(
                IllegalStateException.class,
                () -> McpAuthorizedOpenProbe.checkPersistenceDelta(
                        before, changed, List.of("original"), List.of("original"), false));
        assertThrows(
                IllegalStateException.class,
                () -> McpAuthorizedOpenProbe.checkPersistenceDelta(
                        before, before, List.of("original"), List.of("original"), true));
    }

    @Test
    void authorizedSaveDestinationIsTaskOwnedNewAndNeverAnOverwrite() throws Exception {
        Path[] paths = taskPaths();
        Path output = McpAuthorizedOpenProbe.authorizedNewOutput(paths[0], paths[1], RUN);
        assertTrue(output.startsWith(paths[0].resolve("state")));
        assertFalse(Files.exists(output));
        Files.createDirectories(output.getParent());
        Files.writeString(output, "already exists");
        assertThrows(
                IllegalStateException.class, () -> McpAuthorizedOpenProbe.authorizedNewOutput(paths[0], paths[1], RUN));
        assertEquals("already exists", Files.readString(output));
        assertThrows(
                RuntimeException.class,
                () -> McpAuthorizedOpenProbe.authorizedNewOutput(paths[0], paths[1], "foreign-run"));
    }

    @Test
    void authorizedSaveRejectsSymlinkedParentsAndTargets() throws Exception {
        Path[] paths = taskPaths();
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Path directory = paths[0].resolve("state/authorized-save");
        Files.createSymbolicLink(directory, outside);
        assertThrows(
                IllegalStateException.class, () -> McpAuthorizedOpenProbe.authorizedOutput(paths[0], paths[1], RUN));
        Files.delete(directory);
        Files.createDirectories(directory);
        Path target = McpAuthorizedOpenProbe.authorizedOutput(paths[0], paths[1], RUN);
        Files.createSymbolicLink(target, outside.resolve("foreign.cmo3"));
        assertThrows(
                IllegalStateException.class, () -> McpAuthorizedOpenProbe.authorizedOutput(paths[0], paths[1], RUN));
    }

    record Stored(String source, List<Integer> allForms) {}

    private static List<Stored> geometry() {
        return List.of(
                new Stored("mesh", List.of(1, 2, 3)),
                new Stored("warp", List.of(4, 5, 6)),
                new Stored("rotation", List.of(7, 8, 9)));
    }

    @Test
    void focusOfExistingDocumentOrAbsentDocumentCannotPass() {
        Object old = new Object();
        assertThrows(
                IllegalStateException.class,
                () -> McpAuthorizedOpenProbe.checkReopened(old, old, geometry(), geometry(), List.of(), List.of()));
        assertThrows(
                IllegalStateException.class,
                () -> McpAuthorizedOpenProbe.checkReopened(old, null, geometry(), geometry(), List.of(), List.of()));
    }

    @Test
    void fullRecordComparisonRejectsNonTargetFormAndBindingChanges() {
        var changed = List.of(
                geometry().get(0),
                new Stored("warp", List.of(4, 5, 99)),
                geometry().get(2));
        assertThrows(
                IllegalStateException.class,
                () -> McpAuthorizedOpenProbe.checkReopened(
                        new Object(), new Object(), geometry(), changed, List.of("binding"), List.of("binding")));
        assertThrows(
                IllegalStateException.class,
                () -> McpAuthorizedOpenProbe.checkReopened(
                        new Object(), new Object(), geometry(), geometry(), List.of("binding"), List.of()));
        assertThrows(
                IllegalStateException.class,
                () -> McpAuthorizedOpenProbe.checkReopened(
                        new Object(), new Object(), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void freshDocumentRequiresAllThreeSnapshotsAndExactBindings() {
        assertDoesNotThrow(() -> McpAuthorizedOpenProbe.checkReopened(
                new Object(), new Object(), geometry(), geometry(), List.of("binding"), List.of("binding")));
    }
}
