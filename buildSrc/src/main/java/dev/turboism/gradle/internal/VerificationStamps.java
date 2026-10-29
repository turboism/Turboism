package dev.turboism.gradle.internal;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;

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
        apply(task, () -> true);
    }

    /**
     * Registers build/verification-stamps/<task>.stamp as the task's output, written only
     * when writeCondition is true. If writeCondition is false (e.g. check was skipped),
     * any existing stamp is deleted so the task cannot be cached as successful.
     */
    public static void apply(Task task, BooleanSupplier writeCondition) {
        Provider<RegularFile> stamp = task.getProject().getLayout().getBuildDirectory()
            .file("verification-stamps/" + task.getName() + ".stamp");
        task.getOutputs().file(stamp);
        task.getOutputs().upToDateWhen(t -> writeCondition.getAsBoolean());
        task.getOutputs().cacheIf(t -> writeCondition.getAsBoolean());
        task.doLast(ignored -> {
            File file = stamp.get().getAsFile();
            try {
                if (!writeCondition.getAsBoolean()) {
                    Files.deleteIfExists(file.toPath());
                    return;
                }
                file.getParentFile().mkdirs();
                Files.write(file.toPath(), "ok\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }
}
