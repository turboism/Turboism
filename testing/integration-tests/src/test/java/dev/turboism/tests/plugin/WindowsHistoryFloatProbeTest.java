package dev.turboism.tests.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verdict-seam tests for {@link WindowsHistoryFloatProbe}'s component scan and evidence write. */
final class WindowsHistoryFloatProbeTest {

    @TempDir
    private Path tempDir;

    @Test
    void scanFindsNamedComponentRecursively() {
        final JPanel root = new JPanel();
        final JPanel nested = new JPanel();
        final JTextField field = new JTextField();
        field.setName("history.float.probe.field");
        nested.add(field);
        nested.add(new JButton("unrelated"));
        root.add(nested);

        final List<String> found = new java.util.ArrayList<>();
        WindowsHistoryFloatProbe.scan(root, "history.float.probe.field", found);
        assertEquals(1, found.size());
        assertTrue(found.get(0).startsWith(JTextField.class.getName() + "@"));
    }

    @Test
    void scanNamesReportsNoHostedPanelsForPlainSwingTree() {
        final JPanel root = new JPanel();
        final JPanel nested = new JPanel();
        nested.add(new JButton("probe"));
        root.add(nested);

        final List<String> names = new java.util.ArrayList<>();
        WindowsHistoryFloatProbe.scanNames(root, names);
        assertTrue(names.isEmpty());
    }

    @Test
    void appendAccumulatesAndWriteTruncates() throws Exception {
        final Path artifact = tempDir.resolve("probe-evidence.txt");
        WindowsHistoryFloatProbe.append(artifact, "first");
        WindowsHistoryFloatProbe.append(artifact, "second");
        String contents = Files.readString(artifact);
        assertTrue(contents.contains("first"));
        assertTrue(contents.contains("second"));

        WindowsHistoryFloatProbe.write(artifact, "only");
        contents = Files.readString(artifact);
        assertTrue(contents.contains("only"));
        assertTrue(!contents.contains("first"));
    }
}
