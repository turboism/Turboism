package dev.turboism.gradle.internal;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.gradle.api.Task;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;

/**
 * Check tasks prove a predicate over their declared inputs and produce no artifact;
 * with no output Gradle can never mark them up-to-date and re-runs them on every
 * build. The stamp file is that persistent output, written only after the check
 * action succeeds. Call it after any doLast check action so the stamp cannot be
 * written ahead of a failing check.
 */
public final class VerificationStamps {

    private VerificationStamps() {
    }

    /** Registers build/verification-stamps/<task>.stamp as the task's only output. */
    public static void apply(Task task) {
        Provider<RegularFile> stamp = task.getProject().getLayout().getBuildDirectory()
            .file("verification-stamps/" + task.getName() + ".stamp");
        task.getOutputs().file(stamp);
        task.doLast(ignored -> {
            File file = stamp.get().getAsFile();
            file.getParentFile().mkdirs();
            try {
                Files.write(file.toPath(), "ok\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }
}
