package dev.turboism.adapter.cubism.startup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class StartupHostArtifactLocator {

    private static final String HOST_JAR_NAME = "live2d_cubism.jar";

    private StartupHostArtifactLocator() {
    }

    static Result locate(final String classPath, final Path workingDirectory) {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        if (classPath == null || classPath.isBlank()) {
            return new Result(Status.MISSING, null);
        }
        final Path cwd = workingDirectory.toAbsolutePath().normalize();
        final List<Path> candidates = new ArrayList<>();
        for (String rawEntry : classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (rawEntry.isBlank()) {
                continue;
            }
            final Path supplied;
            try {
                supplied = Path.of(rawEntry);
            } catch (RuntimeException invalidPath) {
                continue;
            }
            final Path fileName = supplied.getFileName();
            if (fileName == null
                || !fileName.toString().toLowerCase(Locale.ROOT).equals(HOST_JAR_NAME)) {
                continue;
            }
            final Path resolved = (supplied.isAbsolute() ? supplied : cwd.resolve(supplied))
                .toAbsolutePath()
                .normalize();
            try {
                if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
                    return new Result(Status.UNREADABLE, null);
                }
                candidates.add(resolved.toRealPath(LinkOption.NOFOLLOW_LINKS));
            } catch (IOException | RuntimeException failure) {
                return new Result(Status.UNREADABLE, null);
            }
        }
        if (candidates.isEmpty()) {
            return new Result(Status.MISSING, null);
        }
        if (candidates.size() != 1) {
            return new Result(Status.AMBIGUOUS, null);
        }
        return new Result(Status.FOUND, candidates.get(0));
    }

    enum Status {
        FOUND,
        MISSING,
        AMBIGUOUS,
        UNREADABLE
    }

    record Result(Status status, Path artifact) {
        Result {
            Objects.requireNonNull(status, "status");
            if ((status == Status.FOUND) != (artifact != null)) {
                throw new IllegalArgumentException("artifact is present only for FOUND");
            }
        }
    }
}
