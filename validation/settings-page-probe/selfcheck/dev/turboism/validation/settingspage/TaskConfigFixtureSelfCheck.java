package dev.turboism.validation.settingspage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

/** Exercises file ownership boundaries without initializing a host or SDK. */
public final class TaskConfigFixtureSelfCheck {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        String run = "queue-0123456789abcdef0123456789abcdef";
        Path home = Files.createDirectories(root.resolve(run).resolve("turboism-home"));
        Path config = home.resolve("config.json");
        byte[] content = "{\"meshTriangulationEdgeIndex\":false}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(config, content);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        Set<PosixFilePermission> readonly = Set.of(PosixFilePermission.OWNER_READ);
        Files.setPosixFilePermissions(config, readonly);
        refuse(() -> TaskConfigFixture.prepare(home, "queue-11111111111111111111111111111111", sha, true, false, false));
        refuse(() -> TaskConfigFixture.prepare(home, run, "0".repeat(64), true, false, false));
        refuse(() -> TaskConfigFixture.prepare(home, run, sha, true, false, true));
        refuse(() -> TaskConfigFixture.prepare(home, run, sha, true, true, false));
        refuse(() -> TaskConfigFixture.prepare(home, run, sha, false, false, false));
        require(Files.getPosixFilePermissions(config).equals(readonly), "refused requests changed permissions");
        TaskConfigFixture.Prepared result = TaskConfigFixture.prepare(home, run, sha, true, false, false);
        require(result.beforeSha256().equals(sha) && result.afterSha256().equals(sha), "digests differ");
        require(Arrays.equals(content, Files.readAllBytes(config)), "permission change altered config bytes");
        require(Files.getPosixFilePermissions(config).equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)),
            "permission change enabled non-owner writes");
        Files.setPosixFilePermissions(config, readonly);
        require(Arrays.equals(content, Files.readAllBytes(config)), "round-trip altered config bytes");
        Path wrongHome = Files.createDirectories(root.resolve(run).resolve("user-home"));
        Files.write(wrongHome.resolve("config.json"), content);
        refuse(() -> TaskConfigFixture.prepare(wrongHome, run, sha, true, false, false));
        Files.delete(config);
        Files.createSymbolicLink(config, wrongHome.resolve("config.json"));
        refuse(() -> TaskConfigFixture.prepare(home, run, sha, true, false, false));
        Files.delete(config);
        Files.createDirectory(config);
        refuse(() -> TaskConfigFixture.prepare(home, run, sha, true, false, false));
        System.out.println("PASS task config boundary selfcheck: " + checks + " assertions");
    }

    private static void refuse(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("unsafe permission preparation was accepted");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    @FunctionalInterface
    private interface Action { void run() throws Exception; }
}
