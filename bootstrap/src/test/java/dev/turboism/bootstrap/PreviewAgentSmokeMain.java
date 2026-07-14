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
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(log)) {
                final String content = Files.readString(log);
                if (content.contains("Plugin load complete: loaded=1")) {
                    return;
                }
                if (content.contains("Turboism preview startup failed")) {
                    throw new IllegalStateException("Agent startup failed:\n" + content);
                }
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("Timed out waiting for the Turboism agent plugin load log at " + log);
    }
}
