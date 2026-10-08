package dev.turboism.sdk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class McpStdioLaunchTest {

    @Test
    void acceptsAnAbsoluteCommandWithBoundedArgs() {
        final String command = PathSupport.absoluteJavaLauncher();
        final McpStdioLaunch launch = new McpStdioLaunch(command, List.of("-cp", "plugin.jar", "Bridge", "/state dir"));

        assertEquals(command, launch.command());
        assertEquals(List.of("-cp", "plugin.jar", "Bridge", "/state dir"), launch.args());
        assertThrows(UnsupportedOperationException.class, () -> launch.args().add("x"));
    }

    @Test
    void rejectsRelativeOrMalformedCommands() {
        assertThrows(IllegalArgumentException.class, () -> new McpStdioLaunch("java", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new McpStdioLaunch("bin/java", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new McpStdioLaunch(" ", List.of()));
        assertThrows(NullPointerException.class, () -> new McpStdioLaunch(null, List.of()));
    }

    @Test
    void enforcesTheArgCountAndLengthBounds() {
        final String command = PathSupport.absoluteJavaLauncher();
        final List<String> seventeen = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            seventeen.add("arg-" + index);
        }
        assertThrows(IllegalArgumentException.class, () -> new McpStdioLaunch(command, seventeen));
        new McpStdioLaunch(command, seventeen.subList(0, 16));
        assertThrows(IllegalArgumentException.class, () -> new McpStdioLaunch(command, List.of("x".repeat(4097))));
        new McpStdioLaunch(command, List.of("x".repeat(4096)));
    }

    @Test
    void rejectsControlCharactersAndNullEntries() {
        final String command = PathSupport.absoluteJavaLauncher();
        assertThrows(IllegalArgumentException.class, () -> new McpStdioLaunch(command, List.of("ok", "line\nbreak")));
        assertThrows(IllegalArgumentException.class, () -> new McpStdioLaunch(command + "\t", List.of()));
        assertThrows(
                NullPointerException.class, () -> new McpStdioLaunch(command, java.util.Arrays.asList("ok", null)));
        assertThrows(NullPointerException.class, () -> new McpStdioLaunch(command, null));
    }

    @Test
    void implementsValueEquality() {
        final String command = PathSupport.absoluteJavaLauncher();
        final McpStdioLaunch first = new McpStdioLaunch(command, List.of("a", "b"));
        final McpStdioLaunch same = new McpStdioLaunch(command, List.of("a", "b"));
        final McpStdioLaunch different = new McpStdioLaunch(command, List.of("a"));

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertFalse(first.equals(different));
        assertFalse(first.equals("not-a-launch"));
    }

    @Test
    void toStringReportsOnlyTheArgCount() {
        final String command = PathSupport.absoluteJavaLauncher();
        final McpStdioLaunch launch = new McpStdioLaunch(command, List.of("secret-arg", "second"));

        final String rendered = launch.toString();
        assertTrue(rendered.contains("2"));
        assertFalse(rendered.contains(command));
        assertFalse(rendered.contains("secret-arg"));
        assertFalse(rendered.contains("second"));
    }

    private static final class PathSupport {
        private PathSupport() {}

        static String absoluteJavaLauncher() {
            return java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                    .toAbsolutePath()
                    .toString();
        }
    }
}
