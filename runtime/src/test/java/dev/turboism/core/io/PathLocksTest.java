package dev.turboism.core.io;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused regression for the shared per-path monitor.
 *
 * <p>Baseline: four call sites kept their own {@code ConcurrentHashMap<Path, Object>} for the same
 * purpose, so two writers in different packages did not see each other's lock even for the same
 * file. The one behaviour that matters is that every equivalent spelling of a path yields the same
 * monitor; distinct paths must stay independent.
 */
class PathLocksTest {

    @TempDir
    Path temporary;

    @Test
    void equivalentSpellingsOfOnePathShareAMonitor() {
        final Path direct = temporary.resolve("state").resolve("config.json");
        final Path folded = direct.getParent().resolve("..").resolve("state").resolve("config.json");

        assertSame(PathLocks.forPath(direct), PathLocks.forPath(folded));
    }

    @Test
    void absoluteAndRelativeSpellingsOfOnePathShareAMonitor() {
        final Path relative = Paths.get("build", "state", "config.json");

        assertSame(PathLocks.forPath(relative), PathLocks.forPath(relative.toAbsolutePath()));
    }

    @Test
    void distinctPathsKeepDistinctMonitors() {
        assertNotSame(
                PathLocks.forPath(temporary.resolve("config.json")),
                PathLocks.forPath(temporary.resolve("keybindings.properties")));
    }

    @Test
    void aNullPathIsRejected() {
        assertThrows(NullPointerException.class, () -> PathLocks.forPath(null));
    }
}
