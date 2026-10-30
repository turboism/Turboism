package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrameworkUpdateHelperTest {
    @TempDir
    Path temporary;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private final class Fixture {
        final KeyPair keys;
        final Path root = temporary.resolve("install");
        final Path work = temporary.resolve("work");
        final Path stage = temporary.resolve("stage");

        Fixture(final String installedVersion, final String targetVersion) throws Exception {
            keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Files.createDirectories(root);
            Files.writeString(root.resolve("turboism-agent.jar"), "agent-old");
            Files.writeString(root.resolve("config.json"), "{\"user\":1}");
            Files.writeString(
                    root.resolve("installed-build.json"),
                    "{\"schemaVersion\":1,\"variant\":\"full\",\"version\":\"" + installedVersion + "\"}\n");
            final Map<String, byte[]> files = new TreeMap<>();
            files.put("turboism-agent.jar", "agent-new".getBytes(StandardCharsets.UTF_8));
            final List<UpdateManifestEntry> entries = new ArrayList<>();
            for (var e : files.entrySet()) {
                final Path p = stage.resolve("files").resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.write(p, e.getValue());
                final String hash = FrameworkUpdateTaskStore.hash(e.getValue());
                entries.add(new UpdateManifestEntry(e.getKey(), e.getValue().length, hash, "sha256:" + hash));
            }
            final var manifest = new UpdateManifest(
                    1, "fixture", targetVersion, "stable", 60, "c".repeat(40), "1.0.0", "windows-x64", entries);
            final byte[] bytes = manifest.toCanonicalBytes();
            Files.write(stage.resolve("manifest.json"), bytes);
            Files.write(stage.resolve("manifest.json.sig"), sign(bytes, keys));
        }

        Map<String, PublicKey> trusted() {
            return Map.of("fixture", keys.getPublic());
        }

        String[] args(final String... extra) {
            final List<String> a = new ArrayList<>(List.of(
                    "--root", root.toString(), "--stage", stage.toString(), "--work", work.toString()));
            a.addAll(List.of(extra));
            return a.toArray(String[]::new);
        }

        int run(final Fixture unused, final ProcessLookupHolder lookup, final String... extra) {
            return FrameworkUpdateHelper.run(
                    args(extra),
                    new PrintStream(out),
                    new PrintStream(err),
                    trusted(),
                    lookup.lookup,
                    Instant::now);
        }
    }

    private record ProcessLookupHolder(FrameworkUpdateHelper.ProcessLookup lookup) {}

    private static final ProcessLookupHolder GONE = new ProcessLookupHolder(pid -> Optional.empty());

    private static byte[] sign(final byte[] manifest, final KeyPair keys) throws Exception {
        final Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(manifest);
        return UpdateFileSupport.JSON.writeValueAsBytes(UpdateFileSupport.JSON
                .createObjectNode()
                .put("format", UpdateManifestVerifier.SIGNATURE_FORMAT)
                .put("schemaVersion", 1)
                .put("algorithm", "Ed25519")
                .put("keyId", "fixture")
                .put("manifestSha256", FrameworkUpdateTaskStore.hash(manifest))
                .put("signature", Base64.getEncoder().encodeToString(signer.sign())));
    }

    @Test
    void appliesVerifiedUpdateAndWritesIdentityLast() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        assertEquals(FrameworkUpdateHelper.EXIT_APPLIED, f.run(f, GONE), err.toString());
        assertEquals("agent-new", Files.readString(f.root.resolve("turboism-agent.jar")));
        assertEquals("{\"user\":1}", Files.readString(f.root.resolve("config.json")));
        final var identity = UpdateFileSupport.JSON.readTree(Files.readAllBytes(f.root.resolve("installed-build.json")));
        assertEquals("1.1.0", identity.path("version").asText());
        assertEquals("full", identity.path("variant").asText(), "variant preserved");
        assertEquals(60, identity.path("buildNumber").asLong());
    }

    @Test
    void refusesToTouchFilesWhileCubismStillRuns() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        final ProcessHandle self = ProcessHandle.current();
        final int code =
                f.run(f, new ProcessLookupHolder(pid -> Optional.of(self)), "--wait-pid", "1", "--wait-seconds", "0");
        assertEquals(FrameworkUpdateHelper.EXIT_BUSY, code);
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
        assertFalse(Files.exists(f.work.resolve("journal.json")));
    }

    @Test
    void proceedsOnceTheProcessExits() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        final var calls = new java.util.concurrent.atomic.AtomicInteger();
        final ProcessHandle self = ProcessHandle.current();
        final var lookup = new ProcessLookupHolder(pid -> calls.incrementAndGet() < 3 ? Optional.of(self) : Optional.empty());
        assertEquals(FrameworkUpdateHelper.EXIT_APPLIED, f.run(f, lookup, "--wait-pid", "1", "--wait-seconds", "30"));
        assertTrue(calls.get() >= 3);
    }

    @Test
    void rejectsUntrustedKeyAndTamperedSignatureWithoutChange() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        final KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final int wrongKey = FrameworkUpdateHelper.run(
                f.args(), new PrintStream(out), new PrintStream(err), Map.of("fixture", other.getPublic()), pid -> Optional.empty(), Instant::now);
        assertEquals(FrameworkUpdateHelper.EXIT_REJECTED, wrongKey);
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));

        final byte[] manifest = Files.readAllBytes(f.stage.resolve("manifest.json"));
        manifest[manifest.length / 2] ^= 1;
        Files.write(f.stage.resolve("manifest.json"), manifest);
        assertEquals(FrameworkUpdateHelper.EXIT_REJECTED, f.run(f, GONE));
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
    }

    @Test
    void rejectsDowngradeAndSameVersion() throws Exception {
        for (final String target : List.of("1.0.0", "0.9.0")) {
            final Fixture f = new Fixture("1.0.0", target);
            assertEquals(FrameworkUpdateHelper.EXIT_REJECTED, f.run(f, GONE), target);
            assertTrue(err.toString().contains("not newer"), err.toString());
            assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
            temporaryReset();
        }
    }

    private void temporaryReset() throws IOException {
        err.reset();
        out.reset();
        try (var stream = Files.list(temporary)) {
            for (final Path p : stream.toList()) deleteTree(p);
        }
    }

    private static void deleteTree(final Path path) throws IOException {
        try (var stream = Files.walk(path)) {
            for (final Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    @Test
    void stagedByteTamperingAfterSigningIsCaughtAndRolledBackOrRejected() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        Files.writeString(f.stage.resolve("files/turboism-agent.jar"), "agent-bad");
        final int code = f.run(f, GONE);
        assertTrue(
                code == FrameworkUpdateHelper.EXIT_ROLLED_BACK || code == FrameworkUpdateHelper.EXIT_REJECTED,
                "exit " + code);
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
    }

    @Test
    void recoverOnlyRestoresAnInterruptedUpdate() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        final class Crash extends Error {}
        final var applier = new FrameworkUpdateApplier(f.root, f.work, p -> {
            if (p.equals("after-replace:turboism-agent.jar")) throw new Crash();
        });
        final var manifest = new UpdateManifestVerifier(f.trusted())
                .authenticate(
                        Files.readAllBytes(f.stage.resolve("manifest.json")),
                        Files.readAllBytes(f.stage.resolve("manifest.json.sig")));
        try {
            applier.apply(manifest, f.stage.resolve("files"), "{}".getBytes(StandardCharsets.UTF_8));
        } catch (Crash expected) {
        }
        assertEquals("agent-new", Files.readString(f.root.resolve("turboism-agent.jar")), "half-applied");
        final int code = FrameworkUpdateHelper.run(
                new String[] {"--recover", "--root", f.root.toString(), "--work", f.work.toString()},
                new PrintStream(out),
                new PrintStream(err),
                f.trusted(),
                pid -> Optional.empty(),
                Instant::now);
        assertEquals(FrameworkUpdateHelper.EXIT_RECOVERED, code);
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
    }

    @Test
    void nextRunAlwaysFinishesEarlierTransactionBeforeApplying() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        final class Crash extends Error {}
        final var manifest = new UpdateManifestVerifier(f.trusted())
                .authenticate(
                        Files.readAllBytes(f.stage.resolve("manifest.json")),
                        Files.readAllBytes(f.stage.resolve("manifest.json.sig")));
        try {
            new FrameworkUpdateApplier(f.root, f.work, p -> {
                        if (p.equals("after-replace:turboism-agent.jar")) throw new Crash();
                    })
                    .apply(manifest, f.stage.resolve("files"), "{}".getBytes(StandardCharsets.UTF_8));
        } catch (Crash expected) {
        }
        assertEquals(FrameworkUpdateHelper.EXIT_APPLIED, f.run(f, GONE), err.toString());
        assertEquals("agent-new", Files.readString(f.root.resolve("turboism-agent.jar")));
        assertFalse(Files.exists(f.work.resolve("journal.json")));
    }

    @Test
    void usageErrorsAreRejectedBeforeAnyIo() {
        for (final String[] bad : new String[][] {
            {},
            {"--root", "relative", "--stage", "/x", "--work", "/y"},
            {"--root", temporary.toString(), "--bogus"},
            {"--root", temporary.toString(), "--work", temporary.resolve("w").toString()},
            {"--root", temporary.toString(), "--stage", temporary.toString(), "--work", temporary.resolve("w").toString(), "--wait-seconds", "99999"}
        }) {
            assertEquals(
                    FrameworkUpdateHelper.EXIT_USAGE,
                    FrameworkUpdateHelper.run(bad, new PrintStream(out), new PrintStream(err), Map.of(), pid -> Optional.empty(), Instant::now),
                    String.join(" ", bad));
        }
    }

    /**
     * Real second JVM, real process wait. The production key allowlist is bundled, so a fixture-signed update must be
     * REJECTED by the genuine entry point: this proves the helper does not trust a key supplied with the stage.
     */
    @Test
    void realSubprocessRejectsUpdateNotSignedByBundledProductionKey() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        final Process helper = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        "dev.turboism.update.FrameworkUpdateHelper",
                        "--root", f.root.toString(),
                        "--stage", f.stage.toString(),
                        "--work", f.work.toString())
                .redirectErrorStream(true)
                .start();
        final String output = new String(helper.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(helper.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "helper must terminate");
        assertEquals(FrameworkUpdateHelper.EXIT_REJECTED, helper.exitValue(), output);
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
    }

    /** The helper must really block on a live process and release only after that process exits. */
    @Test
    void realSubprocessWaitsForRealProcessExit() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        final Process sleeper = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-version")
                .redirectErrorStream(true)
                .start();
        sleeper.getInputStream().readAllBytes();
        sleeper.waitFor();
        final Process live = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"),
                        "dev.turboism.update.FrameworkUpdateHelperTest$Sleep")
                .redirectErrorStream(true)
                .start();
        try {
            final long started = System.nanoTime();
            final int busy = FrameworkUpdateHelper.run(
                    f.args("--wait-pid", Long.toString(live.pid()), "--wait-seconds", "1"),
                    new PrintStream(out), new PrintStream(err), f.trusted(), ProcessHandle::of, Instant::now);
            assertEquals(FrameworkUpdateHelper.EXIT_BUSY, busy);
            assertTrue(System.nanoTime() - started >= 900_000_000L, "really waited about a second");
            assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
        } finally {
            live.destroyForcibly();
            live.waitFor();
        }
        assertEquals(
                FrameworkUpdateHelper.EXIT_APPLIED,
                FrameworkUpdateHelper.run(
                        f.args("--wait-pid", Long.toString(live.pid()), "--wait-seconds", "10"),
                        new PrintStream(out), new PrintStream(err), f.trusted(), ProcessHandle::of, Instant::now),
                err.toString());
        assertEquals("agent-new", Files.readString(f.root.resolve("turboism-agent.jar")));
    }

    /** Child process used as a stand-in for a running Cubism. */
    public static final class Sleep {
        public static void main(final String[] args) throws Exception {
            Thread.sleep(120_000);
        }
    }
}
