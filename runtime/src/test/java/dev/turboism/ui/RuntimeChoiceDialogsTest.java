package dev.turboism.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.JLabel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RuntimeChoiceDialogsTest {

    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @AfterEach
    void clearDiagnostics() {
        RuntimeDiagnostics.clear();
    }

    @Test
    void nonWebUrlRendersPlainTextWithDiagnostic() {
        RuntimeDiagnostics.install(
                (level, component, message, failure) -> warnings.add(component + ":" + message));
        final JLabel label = RuntimeChoiceDialogs.urlLabel("release notes", "file:///etc/passwd");
        assertEquals("release notes", label.getText());
        assertEquals(Boolean.TRUE, label.getClientProperty("html.disable"));
        assertEquals(0, label.getMouseListeners().length);
        assertTrue(warnings.stream().anyMatch(entry -> entry.contains("file:///etc/passwd")));
    }

    @Test
    void webUrlRendersClickableEscapedLink() {
        final JLabel label =
                RuntimeChoiceDialogs.urlLabel("<b>notes</b>", "https://turboism.dev/releases");
        assertEquals("<html><a href=''>&lt;b&gt;notes&lt;/b&gt;</a></html>", label.getText());
        assertNull(label.getClientProperty("html.disable"));
        assertEquals(1, label.getMouseListeners().length);
    }

    @Test
    void unparseableUrlIsRefused() {
        RuntimeDiagnostics.install(
                (level, component, message, failure) -> warnings.add(component + ":" + message));
        final JLabel label = RuntimeChoiceDialogs.urlLabel("", "custom-scheme://host");
        assertEquals("-", label.getText());
        assertEquals(0, label.getMouseListeners().length);
        assertTrue(warnings.stream().anyMatch(entry -> entry.contains("custom-scheme")));
    }
}
