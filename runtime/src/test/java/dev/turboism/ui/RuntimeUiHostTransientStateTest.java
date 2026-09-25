package dev.turboism.ui;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.ui.ChoiceDialogOption;
import dev.turboism.sdk.ui.ChoiceDialogRequest;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.StatusNotification;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RuntimeUiHostTransientStateTest {

    @Test
    void repeatedStatusAndSafeModeDiagnosticsReplaceByIdentity() {
        RuntimeUiHostCapabilityService service = service();

        for (int i = 0; i < 100; i++) {
            service.notifyStatus(new StatusNotification("same", "INFO", "value-" + i));
        }

        assertEquals(1, service.notifications().size());
        assertEquals("value-99", service.notifications().get(0).message());
        assertEquals(1, service.uiDiagnostics().size());
    }

    @Test
    void transientStatusStoreIsBounded() {
        RuntimeUiHostCapabilityService service = service();

        for (int i = 0; i < 100; i++) {
            service.notifyStatus(new StatusNotification("status-" + i, "INFO", "value"));
        }

        assertEquals(64, service.notifications().size());
    }

    @Test
    void confirmationRequestsDoNotBecomePersistentDialogRegistrations() {
        RuntimeUiHostCapabilityService service = service();

        service.confirmDialog(new DialogRequest("confirm", "Confirm", "Proceed?"));

        assertTrue(service.dialogs().isEmpty());
    }

    @Test
    void headlessChoiceRequestsFailClosedWithoutPersistentDialogState() throws Exception {
        // The scenario needs a genuinely headless AWT runtime: in a windowed test JVM the
        // production choose path shows the real modal dialog and never returns. The same
        // assertions therefore run in a child JVM on the current test JDK, launched with
        // -Djava.awt.headless=true on this module's test classpath. The parent JVM's
        // global headless state is intentionally left untouched.
        final boolean parentHeadless = GraphicsEnvironment.isHeadless();
        final Path stdout = Files.createTempFile("headless-choice-stdout", ".log");
        final Path stderr = Files.createTempFile("headless-choice-stderr", ".log");
        final Process probe = new ProcessBuilder(
            javaBinary(),
            "-Djava.awt.headless=true",
            "-cp",
            System.getProperty("java.class.path"),
            HeadlessChoiceProbe.class.getName()
        )
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start();
        try {
            if (!probe.waitFor(60, TimeUnit.SECONDS)) {
                fail(
                    "headless choice probe did not exit within 60s; stdout=" + stdout
                        + " stderr=" + stderr
                );
            }
            final String out = Files.readString(stdout);
            final String err = Files.readString(stderr);
            assertEquals(
                0,
                probe.exitValue(),
                "headless choice probe failed: stdout=" + out + " stderr=" + err
            );
            assertTrue(
                out.contains(HeadlessChoiceProbe.SUCCESS_MARKER),
                "headless choice probe did not report success: stdout=" + out + " stderr=" + err
            );
        } finally {
            probe.destroyForcibly();
        }
        assertEquals(
            parentHeadless,
            GraphicsEnvironment.isHeadless(),
            "parent JVM headless state must be unchanged by the headless probe"
        );
    }

    @Test
    void diagnosticsDedupeKeysIncludePluginId() {
        RuntimeUiHostCapabilityService first = service("plugin.a");
        RuntimeUiHostCapabilityService second = service("plugin.b");

        first.notifyStatus(new StatusNotification("status.shared", "INFO", "a"));
        second.notifyStatus(new StatusNotification("status.shared", "INFO", "b"));

        // each host instance is plugin-scoped; key identity must not collapse across plugins
        assertEquals(1, first.uiDiagnostics().size());
        assertEquals(1, second.uiDiagnostics().size());
        assertEquals(1, first.notifications().size());
        assertEquals(1, second.notifications().size());
    }

    private static RuntimeUiHostCapabilityService service() {
        return service("plugin.test");
    }

    private static RuntimeUiHostCapabilityService service(final String pluginId) {
        return new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            pluginId,
            UiHostStateSource.DEFAULT,
            new DisposableScope()
        );
    }

    private static String javaBinary() {
        return Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe"
                : "java"
        ).toString();
    }

    /**
     * Entry point for the headless child JVM spawned by
     * {@link #headlessChoiceRequestsFailClosedWithoutPersistentDialogState}. Runs the real
     * production {@code service.choose} path under {@code -Djava.awt.headless=true}; any
     * failed assertion exits non-zero with the reason on stderr.
     */
    public static final class HeadlessChoiceProbe {
        static final String SUCCESS_MARKER = "HEADLESS_CHOICE_OK";

        private HeadlessChoiceProbe() {
        }

        public static void main(final String[] args) throws Exception {
            if (!GraphicsEnvironment.isHeadless()) {
                System.err.println("headless choice probe requires -Djava.awt.headless=true");
                System.exit(2);
            }
            final RuntimeUiHostCapabilityService service = service("plugin.test");
            final Optional<String> selected = service.choose(new ChoiceDialogRequest(
                "theme-manager",
                "Choose Theme",
                "Select one",
                List.of(new ChoiceDialogOption("dark", "Dark", "Built-in", true)),
                Optional.of("dark"),
                "Apply",
                "Cancel"
            ));
            if (selected.isPresent()) {
                System.err.println("headless choose returned a selection: " + selected);
                System.exit(3);
            }
            if (!service.dialogs().isEmpty()) {
                System.err.println("headless choose left persistent dialog state: " + service.dialogs());
                System.exit(4);
            }
            System.out.println(
                SUCCESS_MARKER
                    + " pid=" + ProcessHandle.current().pid()
                    + " java.home=" + System.getProperty("java.home")
                    + " headless=" + GraphicsEnvironment.isHeadless()
            );
        }
    }
}
