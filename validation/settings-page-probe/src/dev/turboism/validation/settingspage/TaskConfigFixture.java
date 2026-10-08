package dev.turboism.validation.settingspage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Permission preparation for an explicitly bound, isolated full-UI fixture only. */
final class TaskConfigFixture {
    private TaskConfigFixture() { }

    record Prepared(boolean initiallyWritable, String beforeSha256, String afterSha256) { }

    static Prepared prepare(Path home, String expectedRun, String expectedSha,
                            boolean edgeIndex, boolean performance, boolean startupOnly) throws IOException {
        if (!edgeIndex || performance || startupOnly) {
            throw new IOException("task config preparation requires full edge-index UI mode");
        }
        if (expectedRun == null || !expectedRun.matches("queue-[0-9a-f]{32}")
            || expectedSha == null || !expectedSha.matches("[0-9a-f]{64}")) {
            throw new IOException("explicit task run and config SHA-256 required");
        }
        Path taskHome = home.toAbsolutePath().normalize();
        Path task = taskHome.getParent();
        if (task == null || !taskHome.getFileName().toString().equals("turboism-home")
            || !task.getFileName().toString().equals(expectedRun)
            || Files.isSymbolicLink(task) || Files.isSymbolicLink(taskHome)
            || !Files.isDirectory(taskHome, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("config home is not the expected isolated task home");
        }
        Path config = taskHome.resolve("config.json");
        if (Files.isSymbolicLink(config) || !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("task config must be a regular non-symlink file");
        }
        String before = digest(config);
        if (!before.equals(expectedSha)) throw new IOException("task config SHA-256 differs");
        boolean initiallyWritable = config.toFile().canWrite();
        if (!config.toFile().setWritable(true, true) || !config.toFile().canWrite()) {
            throw new IOException("could not enable task config owner write permission");
        }
        String after = digest(config);
        if (!after.equals(before)) throw new IOException("task config bytes changed during permission preparation");
        return new Prepared(initiallyWritable, before, after);
    }

    private static String digest(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
