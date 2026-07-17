package dev.turboism.bootstrap;

import com.live2d.cubism.CEAppCtrl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/** Child-process main used to prove the built JAR is a functioning Java agent. */
public final class PreviewAgentSmokeMain {

    private PreviewAgentSmokeMain() {
    }

    public static void main(final String[] args) throws Exception {
        CEAppCtrl.touch();
        final Path home = Path.of(System.getProperty("turboism.home"));
        final Path log = home.resolve("logs/turboism.log");
        final Path state = home.resolve("state");
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        boolean loadObserved = false;
        boolean shutdownCompleted = false;
        while (Instant.now().isBefore(deadline)) {
            final String content = Files.isRegularFile(log)
                ? Files.readString(log)
                : "";
            if (content.contains("Turboism preview startup failed")) {
                throw new IllegalStateException("Agent startup failed:\n" + content);
            }
            loadObserved |= content.contains("Plugin load complete: loaded=1");
            if (loadObserved && !shutdownCompleted) {
                shutdownCompleted = TurboismAgent.shutdownForTesting();
            }
            if (shutdownCompleted
                && content.contains("Plugin unloaded with state UNLOADED")
                && finalReportsExist(state)) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException(
            "Timed out waiting for explicit Turboism shutdown and final reports at " + home
        );
    }

    private static boolean finalReportsExist(final Path state) {
        return Files.isRegularFile(state.resolve("preview-runtime-report.json"))
            && Files.isRegularFile(state.resolve("plugin-load-report.json"))
            && Files.isRegularFile(state.resolve("capability-report.json"))
            && Files.isRegularFile(state.resolve("i18n-report.json"));
    }
}
