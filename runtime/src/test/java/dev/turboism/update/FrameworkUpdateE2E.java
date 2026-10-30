package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real-process validation, opt-in via TURBOISM_E2E_DIR (a NEW directory) and TURBOISM_E2E_AGENT (a real agent JAR).
 * A real second JVM keeps the installed agent JAR open, exactly as Cubism's JVM does, and the helper runs as its own
 * process. Writes a human-readable report next to the installation.
 */
@EnabledIfEnvironmentVariable(named = "TURBOISM_E2E_DIR", matches = ".+")
final class FrameworkUpdateE2E {
    private final StringBuilder log = new StringBuilder();

    /** Helper entry for the subprocess with an injected test key (never present in production main). */
    public static final class Main {
        public static void main(final String[] args) throws Exception {
            final byte[] der = Base64.getDecoder().decode(Files.readString(Path.of(args[0])).trim());
            final PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
            final String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
            System.exit(FrameworkUpdateHelper.run(
                    rest, System.out, System.err, Map.of("e2e", key), ProcessHandle::of, Instant::now));
        }
    }

    /** Stand-in for Cubism: holds the agent JAR on its classpath and stays alive. */
    public static final class Cubism {
        public static void main(final String[] args) throws Exception {
            Thread.sleep(600_000);
        }
    }

    private void say(final String line) {
        System.out.println(line);
        log.append(line).append(System.lineSeparator());
    }

    @Test
    void realWindowsLifecycle() throws Exception {
        final Path base = Path.of(System.getenv("TURBOISM_E2E_DIR"));
        assertFalse(Files.exists(base), "E2E directory must be new");
        final Path root = base.resolve("install");
        final Path work = base.resolve("work");
        final Path stage = base.resolve("stage");
        final Path keyFile = base.resolve("e2e-public-key.b64");
        Files.createDirectories(root.resolve("graal/lib"));
        Files.createDirectories(root.resolve("plugins"));
        Files.createDirectories(root.resolve("state"));

        final byte[] oldAgent = Files.readAllBytes(Path.of(System.getenv("TURBOISM_E2E_AGENT")));
        final byte[] newAgent = withMarker(oldAgent);
        final Map<String, byte[]> newFiles = new TreeMap<>();
        newFiles.put("turboism-agent.jar", newAgent);
        newFiles.put("README.txt", "readme 1.1".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("turboism-agent.jar"), oldAgent);
        Files.writeString(root.resolve("README.txt"), "readme 1.0");
        Files.writeString(root.resolve("config.json"), "{\"user\":\"keep\"}");
        Files.writeString(root.resolve("plugins/third-party.jar"), "user plugin");
        Files.writeString(root.resolve("state/settings"), "user state");
        Files.writeString(
                root.resolve("installed-build.json"),
                "{\"schemaVersion\":1,\"variant\":\"full\",\"version\":\"1.0.0\"}\n");

        final KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        final List<UpdateManifestEntry> entries = new ArrayList<>();
        for (var e : newFiles.entrySet()) {
            final Path p = stage.resolve("files").resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
            final String h = FrameworkUpdateTaskStore.hash(e.getValue());
            entries.add(new UpdateManifestEntry(e.getKey(), e.getValue().length, h, "sha256:" + h));
        }
        final byte[] manifest = new UpdateManifest(
                        1, "e2e", "1.1.0", "stable", 70, "d".repeat(40), "1.0.0", "windows-x64", entries)
                .toCanonicalBytes();
        Files.write(stage.resolve("manifest.json"), manifest);
        final Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(manifest);
        Files.write(
                stage.resolve("manifest.json.sig"),
                UpdateFileSupport.JSON.writeValueAsBytes(UpdateFileSupport.JSON
                        .createObjectNode()
                        .put("format", UpdateManifestVerifier.SIGNATURE_FORMAT)
                        .put("schemaVersion", 1)
                        .put("algorithm", "Ed25519")
                        .put("keyId", "e2e")
                        .put("manifestSha256", FrameworkUpdateTaskStore.hash(manifest))
                        .put("signature", Base64.getEncoder().encodeToString(signer.sign()))));

        final String cp = System.getProperty("java.class.path");
        final String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        say("os=" + System.getProperty("os.name") + " java=" + System.getProperty("java.version"));
        say("agent bytes old=" + oldAgent.length + " new=" + newAgent.length);

        // 1. A real JVM holding the installed agent JAR open, like Cubism does.
        final Process cubism = new ProcessBuilder(
                        javaBin, "-cp", root.resolve("turboism-agent.jar") + ";" + cp, Cubism.class.getName())
                .redirectErrorStream(true)
                .start();
        try {
            Thread.sleep(1500);
            assertTrue(cubism.isAlive());
            say("[1] stand-in Cubism JVM pid=" + cubism.pid() + " holds turboism-agent.jar");

            // 2. Helper told to wait for that pid: must refuse (exit 75) and change nothing.
            final int busy = helper(
                    javaBin, cp, keyFile, root, stage, work,
                    "--wait-pid", Long.toString(cubism.pid()), "--wait-seconds", "2");
            say("[2] helper while Cubism running, waited 2s -> exit " + busy + " (expect 75)");
            assertEquals(FrameworkUpdateHelper.EXIT_BUSY, busy);
            assertArrayEquals(oldAgent, Files.readAllBytes(root.resolve("turboism-agent.jar")));

            // 3. Helper NOT told to wait: the OS file lock must defeat it, and rollback must restore the old install.
            final int locked = helper(javaBin, cp, keyFile, root, stage, work);
            say("[3] helper ignoring the lock, real Windows sharing violation -> exit " + locked
                    + " (expect 20 rolled back)");
            assertEquals(FrameworkUpdateHelper.EXIT_ROLLED_BACK, locked);
            assertArrayEquals(oldAgent, Files.readAllBytes(root.resolve("turboism-agent.jar")));
            assertEquals("readme 1.0", Files.readString(root.resolve("README.txt")), "earlier file restored");
            assertFalse(Files.exists(work.resolve("journal.json")), "journal cleaned after rollback");
            say("    old installation intact, journal clean");
        } finally {
            cubism.destroyForcibly();
            cubism.waitFor();
        }
        say("[4] stand-in Cubism exited (pid " + cubism.pid() + ")");

        // 5. After the exit the update applies.
        final int applied = helper(
                javaBin, cp, keyFile, root, stage, work,
                "--wait-pid", Long.toString(cubism.pid()), "--wait-seconds", "10");
        say("[5] helper after exit -> exit " + applied + " (expect 0)");
        assertEquals(FrameworkUpdateHelper.EXIT_APPLIED, applied);
        assertArrayEquals(newAgent, Files.readAllBytes(root.resolve("turboism-agent.jar")));
        assertEquals("readme 1.1", Files.readString(root.resolve("README.txt")));
        final var identity = UpdateFileSupport.JSON.readTree(Files.readAllBytes(root.resolve("installed-build.json")));
        assertEquals("1.1.0", identity.path("version").asText());
        assertEquals(70, identity.path("buildNumber").asLong());
        say("    installed-build.json -> " + identity);

        // 6. User state untouched.
        assertEquals("{\"user\":\"keep\"}", Files.readString(root.resolve("config.json")));
        assertEquals("user plugin", Files.readString(root.resolve("plugins/third-party.jar")));
        assertEquals("user state", Files.readString(root.resolve("state/settings")));
        say("[6] config.json, third-party plugin and state untouched");

        // 7. Re-running the same update is refused (not newer), not re-applied.
        final int again = helper(javaBin, cp, keyFile, root, stage, work);
        say("[7] same update again -> exit " + again + " (expect 70 rejected: not newer)");
        assertEquals(FrameworkUpdateHelper.EXIT_REJECTED, again);

        // 8. The real production entry point must reject an update signed by an unknown key.
        final Process production = new ProcessBuilder(
                        javaBin, "-cp", cp, "dev.turboism.update.FrameworkUpdateHelper",
                        "--root", root.toString(), "--stage", stage.toString(), "--work", work.toString())
                .redirectErrorStream(true)
                .start();
        production.getInputStream().readAllBytes();
        production.waitFor();
        say("[8] production entry point, e2e-signed update -> exit " + production.exitValue() + " (expect 70)");
        assertEquals(FrameworkUpdateHelper.EXIT_REJECTED, production.exitValue());

        say("RESULT: PASS");
        Files.writeString(base.resolve("e2e-report.txt"), log.toString());
    }

    private static int helper(
            final String javaBin,
            final String cp,
            final Path keyFile,
            final Path root,
            final Path stage,
            final Path work,
            final String... extra)
            throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>(List.of(
                javaBin, "-cp", cp, Main.class.getName(), keyFile.toString(),
                "--root", root.toString(), "--stage", stage.toString(), "--work", work.toString()));
        command.addAll(List.of(extra));
        final Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertTrue(p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS));
        if (!out.isEmpty()) System.out.println("    helper> " + out.replace("\n", "\n    helper> "));
        return p.exitValue();
    }

    private static byte[] withMarker(final byte[] jar) throws IOException {
        final var out = new java.io.ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(jar));
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                zip.putNextEntry(new ZipEntry(e.getName()));
                in.transferTo(zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("META-INF/e2e-update-marker"));
            zip.write("1.1.0".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
