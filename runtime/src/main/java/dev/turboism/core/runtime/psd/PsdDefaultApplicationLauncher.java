package dev.turboism.core.runtime.psd;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Runtime-private seam that hands one validated, runtime-allocated PSD to the system's default
 * application.
 *
 * <p>The launcher never chooses an application, never accepts a user-supplied path or command, and
 * never changes file associations. The caller must already have validated the allocation and the
 * plugin's process permission. A missing desktop or missing association is reported as a failure to
 * open; it must not be worked around by editing system settings.</p>
 */
@FunctionalInterface
interface PsdDefaultApplicationLauncher {
    /** Requests that the operating system open this exact regular file. */
    void launch(Path file) throws IOException;

    /** The production launcher: AWT desktop when available, otherwise a bounded OS fallback. */
    static PsdDefaultApplicationLauncher system() {
        return PsdDefaultApplicationLauncher::openWithSystem;
    }

    private static void openWithSystem(final Path file) throws IOException {
        final Path target = Objects.requireNonNull(file, "file");
        if (!GraphicsEnvironment.isHeadless()
                && Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(target.toFile());
            return;
        }
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final ProcessBuilder builder;
        if (os.contains("win")) {
            builder = new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", target.toString());
        } else if (os.contains("mac")) {
            builder = new ProcessBuilder("open", target.toString());
        } else {
            builder = new ProcessBuilder("xdg-open", target.toString());
        }
        builder.start();
    }
}
