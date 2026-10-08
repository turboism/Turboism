package dev.turboism.userfile;

import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import java.awt.Component;
import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SwingUserFileGrantSourceTest {
    private static UserFileRequest request(UserFileMode mode) {
        return new UserFileRequest("import", "选择 GLB", List.of("glb"), mode,
            UserFileLifetime.ONE_OPERATION);
    }

    @Test void approvedReadUsesEdtAndExtensionFilter() throws Exception {
        var chooser = new StubChooser();
        try (var source = new SwingUserFileGrantSource(() -> chooser)) {
            var decision = source.request(request(UserFileMode.READ)).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(new UserFileGrantSource.Selected(new File("cube.GLB").toPath()), decision);
            assertTrue(chooser.opened);
            assertFalse(chooser.saved);
            assertEquals("选择 GLB", chooser.getDialogTitle());
            assertFalse(chooser.isMultiSelectionEnabled());
            assertFalse(chooser.isAcceptAllFileFilterUsed());
        }
    }

    @Test void writeUsesSaveDialog() throws Exception {
        var chooser = new StubChooser();
        try (var source = new SwingUserFileGrantSource(() -> chooser)) {
            assertInstanceOf(UserFileGrantSource.Selected.class,
                source.request(request(UserFileMode.WRITE)).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertTrue(chooser.saved);
            assertFalse(chooser.opened);
        }
    }

    @Test void cancelErrorAndWrongExtensionDoNotGrant() throws Exception {
        for (int answer : new int[]{JFileChooser.CANCEL_OPTION, JFileChooser.ERROR_OPTION, JFileChooser.APPROVE_OPTION}) {
            var chooser = new StubChooser();
            chooser.answer = answer;
            chooser.selected = new File("cube.txt");
            try (var source = new SwingUserFileGrantSource(() -> chooser)) {
                var decision = source.request(request(UserFileMode.READ)).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(answer == JFileChooser.CANCEL_OPTION
                    ? UserFileGrantSource.Decision.canceled() : UserFileGrantSource.Decision.unavailable(), decision);
            }
        }
    }

    @Test void closeBeforeDisplayDoesNotConstructChooser() throws Exception {
        try (var source = new SwingUserFileGrantSource(() -> { throw new AssertionError("Unexpected chooser"); })) {
            SwingUtilities.invokeAndWait(() -> {
                var pending = source.request(request(UserFileMode.READ));
                source.close();
                assertEquals(UserFileGrantSource.Decision.unavailable(), pending.toCompletableFuture().join());
            });
            SwingUtilities.invokeAndWait(() -> {});
        }
    }

    @Test void closeDuringDisplayRejectsLateApproval() throws Exception {
        var chooser = new StubChooser();
        try (var source = new SwingUserFileGrantSource(() -> chooser)) {
            chooser.duringDisplay = source::close;
            assertEquals(UserFileGrantSource.Decision.unavailable(),
                source.request(request(UserFileMode.READ)).toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
    }

    @Test void nestedRequestDoesNotReplaceVisibleChooser() throws Exception {
        var chooser = new StubChooser();
        try (var source = new SwingUserFileGrantSource(() -> chooser)) {
            chooser.duringDisplay = () -> {
                var nested = source.request(request(UserFileMode.READ)).toCompletableFuture();
                var loop = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
                SwingUtilities.invokeLater(loop::exit);
                loop.enter();
                assertEquals(UserFileGrantSource.Decision.unavailable(), nested.join());
            };
            assertInstanceOf(UserFileGrantSource.Selected.class,
                source.request(request(UserFileMode.READ)).toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
    }

    private static final class StubChooser extends JFileChooser {
        boolean opened;
        boolean saved;
        int answer = APPROVE_OPTION;
        File selected = new File("cube.GLB");
        Runnable duringDisplay = () -> {};
        private int display() {
            assertTrue(SwingUtilities.isEventDispatchThread());
            duringDisplay.run();
            setSelectedFile(selected);
            return answer;
        }
        @Override public int showOpenDialog(Component owner) { opened = true; return display(); }
        @Override public int showSaveDialog(Component owner) { saved = true; return display(); }
    }
}
