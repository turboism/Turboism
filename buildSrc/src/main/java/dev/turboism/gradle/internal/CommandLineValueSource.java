package dev.turboism.gradle.internal;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.process.ExecSpec;

/**
 * The captured outcome of one external command, as a configuration-cache-compatible value.
 *
 * <p>{@code providers.exec { }.standardOutput.asText.get()} runs the command while Gradle
 * configures the build and then tries to serialize the whole exec object graph, which the
 * configuration cache rejects. A {@link ValueSource} moves the same command behind a declared
 * value: Gradle hashes the parameters, runs the command at most once per build, and stores only
 * the resulting {@link Result}. Nothing else from the build escapes into the cache entry.
 *
 * <p>The result is a plain value object: no {@code Project}, no {@code Task}, no file collection.
 * A caller that needs a task input derives it with {@code map}, so the command still runs once.
 */
public abstract class CommandLineValueSource
    implements ValueSource<CommandLineValueSource.Result, CommandLineValueSource.Parameters> {

    /** One captured command outcome: exit status, decoded streams and launch failure. */
    public static final class Result implements Serializable {
        private static final long serialVersionUID = 1L;

        private final int exitValue;
        private final String standardOutput;
        private final String standardError;
        private final String failure;

        Result(final int exitValue, final String standardOutput, final String standardError,
               final String failure) {
            this.exitValue = exitValue;
            this.standardOutput = standardOutput;
            this.standardError = standardError;
            this.failure = failure;
        }

        /** The process exit status, or {@code -1} when the command could not start at all. */
        public int getExitValue() {
            return exitValue;
        }

        /** Standard output decoded as UTF-8. */
        public String getStandardOutput() {
            return standardOutput;
        }

        /** Standard error decoded as UTF-8; a caller may surface it as a fail-closed reason. */
        public String getStandardError() {
            return standardError;
        }

        /** The launch failure message, or an empty string when the command started. */
        public String getFailure() {
            return failure;
        }

        /** True when the command started and exited with status zero. */
        public boolean succeeded() {
            return failure.isEmpty() && exitValue == 0;
        }

        /** Standard output trimmed, which is what a single-line probe result means. */
        public String trimmedStandardOutput() {
            return standardOutput.trim();
        }
    }

    /** The declared inputs of the command: what Gradle hashes to decide whether to re-run it. */
    public abstract static class Parameters implements ValueSourceParameters {

        /** The executable and its arguments, in argv order. */
        public abstract ListProperty<String> getCommandLine();

        /** The working directory the command runs in; the build directory when unset. */
        public abstract Property<File> getWorkingDirectory();

        /** Extra environment variables layered on top of the build process environment. */
        public abstract MapProperty<String, String> getEnvironment();

        /** Whether a non-zero exit status is an error instead of a captured result. */
        public abstract Property<Boolean> getIgnoreExitValue();
    }

    private final transient ExecOperations execOperations;

    @Inject
    public CommandLineValueSource(final ExecOperations execOperations) {
        this.execOperations = execOperations;
    }

    @Override
    public final Result obtain() {
        Parameters parameters = getParameters();
        List<String> commandLine = parameters.getCommandLine().getOrElse(Collections.emptyList());
        if (commandLine.isEmpty()) {
            return new Result(-1, "", "", "command line is empty");
        }
        Map<String, String> environment =
            new LinkedHashMap<>(parameters.getEnvironment().getOrElse(Collections.emptyMap()));
        File workingDirectory = parameters.getWorkingDirectory().getOrNull();
        boolean ignoreExitValue = parameters.getIgnoreExitValue().getOrElse(true);
        try {
            ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
            ByteArrayOutputStream standardError = new ByteArrayOutputStream();
            ExecResult result = execOperations.exec(spec -> {
                configure(spec, commandLine, environment, workingDirectory);
                spec.setStandardOutput(standardOutput);
                spec.setErrorOutput(standardError);
                spec.setIgnoreExitValue(true);
            });
            int exitValue = result.getExitValue();
            if (!ignoreExitValue && exitValue != 0) {
                return new Result(exitValue, "", "", "command exited with status " + exitValue);
            }
            return new Result(exitValue, decode(standardOutput), decode(standardError), "");
        } catch (RuntimeException failure) {
            return new Result(-1, "", "", describe(failure));
        }
    }

    private static void configure(final ExecSpec spec, final List<String> commandLine,
                                  final Map<String, String> environment, final File workingDirectory) {
        spec.commandLine(new ArrayList<>(commandLine));
        if (workingDirectory != null) {
            spec.workingDir(workingDirectory);
        }
        if (!environment.isEmpty()) {
            spec.environment(environment);
        }
    }

    private static String decode(final ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8);
    }

    private static String describe(final RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }
}
