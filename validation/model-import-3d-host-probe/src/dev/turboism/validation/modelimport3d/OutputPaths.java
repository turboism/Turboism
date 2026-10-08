package dev.turboism.validation.modelimport3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Offline run ownership, not a sandbox against another process with this UID. */
final class OutputPaths {
    private OutputPaths() {}

    static Path checked(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        for (Path part : absolute) {
            if (part.toString().equals("..") || part.toString().equals(".")) {
                throw new IOException("refused: dot path component: " + path);
            }
        }
        for (Path p = absolute; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p)) {
                throw new IOException("refused: symlink in path: " + p);
            }
        }
        return absolute.normalize();
    }

    static Path runDirectory() throws IOException {
        Path classes;
        try {
            classes = checked(Path.of(Main.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()));
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            throw new IOException("refused: invalid code source", e);
        }
        if (classes.getNameCount() < 6
                || !classes.getFileName().toString().equals("classes")) {
            throw new IOException("refused: compile using the offline build entrypoint");
        }
        Path run = classes.getParent();
        Path runs = run.getParent();
        Path offline = runs.getParent();
        if (!run.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")
                || !runs.getFileName().toString().equals("runs")
                || !offline.getFileName().toString().equals("model-import-3d-offline")
                || !offline.getParent().getFileName().toString().equals("build")) {
            throw new IOException("refused: code source is not in an offline run");
        }
        Path marker = run.resolve(".offline-run");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !Files.readString(marker).equals("t006-offline-run-v1\n")
                || Files.exists(run.resolve(".terminal"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("refused: run unclaimed or sealed: " + run);
        }
        return run;
    }

    static Path freshOutput(Path path) throws IOException {
        Path out = checked(path);
        Path run = runDirectory();
        if (!run.equals(out.getParent())
                || !out.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")
                || out.getFileName().toString().equals("classes")) {
            throw new IOException("refused: output must be a fresh child of " + run);
        }
        if (Files.exists(out, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("refused: output already exists (even if empty): " + out);
        }
        return out;
    }
}
