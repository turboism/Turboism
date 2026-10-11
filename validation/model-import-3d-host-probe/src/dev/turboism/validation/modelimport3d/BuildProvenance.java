package dev.turboism.validation.modelimport3d;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads this run's fixed build inputs; never substitutes the current checkout. */
final class BuildProvenance {
    private BuildProvenance() {}

    static Report.BuildInfo collect(String[] argv, Path out) throws IOException {
        Path run = OutputPaths.runDirectory();
        Map<String, String> p = new HashMap<>();
        for (String line : Files.readAllLines(OutputPaths.checked(run.resolve("build.properties")))) {
            String[] pair = line.split("=", 2);
            if (pair.length != 2 || p.putIfAbsent(pair[0], pair[1]) != null) {
                throw new IOException("invalid/duplicate build property");
            }
        }
        Set<String> required = Set.of("head", "gitStatusSha256", "sourceSha256", "sourceFiles",
                "classesSha256", "compilerVersion", "worktreeDirtyFingerprint", "runDirectoryId");
        if (!p.keySet().equals(required) || !p.get("runDirectoryId").equals(run.getFileName().toString())) {
            throw new IOException("build properties incomplete or wrong run");
        }
        int count = verifyManifest(run, "sources.sha256", "inputs", p.get("sourceSha256"));
        verifyManifest(run, "classes.sha256", "classes", p.get("classesSha256"));
        String statusSha = Report.sha256(Files.readAllBytes(OutputPaths.checked(run.resolve("git-status.txt"))));
        String dirty = Report.sha256((statusSha + "\n" + p.get("sourceSha256") + "\n")
                .getBytes(StandardCharsets.US_ASCII));
        String head = Files.readString(OutputPaths.checked(run.resolve("git-head.txt"))).strip();
        if (!statusSha.equals(p.get("gitStatusSha256"))
                || !dirty.equals(p.get("worktreeDirtyFingerprint"))
                || !head.equals(p.get("head"))
                || !(head.equals("unobserved") || head.matches("[0-9a-f]{40}"))
                || !Integer.toString(count).equals(p.get("sourceFiles"))
                || !p.get("compilerVersion").matches("17\\.[0-9A-Za-z.+_-]+")) {
            throw new IOException("build provenance mismatch");
        }
        return new Report.BuildInfo(head, dirty, System.getProperty("java.version"), List.of(argv),
                p.get("classesSha256"), p.get("sourceSha256"), count, p.get("compilerVersion"),
                p.get("gitStatusSha256"), run.getFileName() + "/" + out.getFileName());
    }

    private static int verifyManifest(Path run, String file, String directory, String expected)
            throws IOException {
        byte[] bytes = Files.readAllBytes(OutputPaths.checked(run.resolve(file)));
        if (!expected.matches("[0-9a-f]{64}") || !Report.sha256(bytes).equals(expected)) {
            throw new IOException("manifest hash mismatch: " + file);
        }
        Set<String> names = new HashSet<>();
        String previous = "";
        Path root = OutputPaths.checked(run.resolve(directory));
        for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\n")) {
            if (!line.matches("[0-9a-f]{64}  [A-Za-z0-9_./$-]+")) {
                throw new IOException("invalid manifest line");
            }
            String rel = line.substring(66);
            Path path = OutputPaths.checked(root.resolve(rel));
            if (!path.startsWith(root) || rel.compareTo(previous) <= 0 || !names.add(rel)
                    || !Files.isRegularFile(path)
                    || !Report.sha256(Files.readAllBytes(path)).equals(line.substring(0, 64))) {
                throw new IOException("build input mismatch: " + rel);
            }
            previous = rel;
        }
        try (var files = Files.walk(root)) {
            Set<String> actual = new HashSet<>();
            for (Path path : files.toList()) {
                OutputPaths.checked(path);
                if (Files.isRegularFile(path)) actual.add(root.relativize(path).toString());
            }
            if (names.isEmpty() || !actual.equals(names)) {
                throw new IOException("build input inventory mismatch: " + directory);
            }
        }
        return names.size();
    }
}
