package dev.turboism.update;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Entry point of the external update helper. It runs in its own JVM after Cubism has exited so that no loaded
 * framework bytes are ever replaced in place.
 *
 * <h2>Trust model</h2>
 *
 * The helper trusts nothing the caller says about the update. The command line carries only <em>paths</em>. The
 * helper itself:
 *
 * <ul>
 *   <li>waits for the exact process id to exit (and refuses to continue if it does not);
 *   <li>re-reads the staged manifest and signature and verifies them with the keys compiled into this program;
 *   <li>refuses downgrades and mismatched platform/channel unless explicitly allowed by a pinned policy;
 *   <li>re-hashes every staged file inside {@link FrameworkUpdateApplier} immediately before installing it.
 * </ul>
 *
 * A compromised application process can therefore at worst ask for an update the publisher really signed.
 *
 * <p>Exit codes: 0 applied, 10 nothing to do, 20 rolled back (installation unchanged), 30 recovered an earlier
 * interrupted update, 40 rollback INCOMPLETE (installation may be mixed; journal kept, must be re-run), 64 usage
 * error, 70 rejected (verification/policy), 75 Cubism still running (retry later).
 */
public final class FrameworkUpdateHelper {
    static final int EXIT_APPLIED = 0;
    static final int EXIT_NOTHING = 10;
    static final int EXIT_ROLLED_BACK = 20;
    static final int EXIT_RECOVERED = 30;
    /** Rollback incomplete: installation may be mixed. Journal kept; run again (or restart) once locks clear. */
    static final int EXIT_NEEDS_RECOVERY = 40;
    static final int EXIT_USAGE = 64;
    static final int EXIT_REJECTED = 70;
    static final int EXIT_BUSY = 75;

    /** Parsed, validated invocation. */
    record Invocation(
            Path root,
            Path stage,
            Path work,
            Optional<Long> waitForPid,
            long waitMillis,
            boolean recoverOnly,
            boolean requireHealth,
            Optional<Long> launcherPid) {}

    private FrameworkUpdateHelper() {}

    public static void main(final String[] args) {
        System.exit(run(args, System.out, System.err, trustedKeys(), ProcessHandle::of, Instant::now));
    }

    /** Keys compiled into the helper: the bundled production allowlist, never anything read from the stage. */
    private static Map<String, PublicKey> trustedKeys() {
        final var keys = dev.turboism.pluginmanagement.PluginCatalogVerifier.loadBundledTrustedKeys();
        final var result = new java.util.HashMap<String, PublicKey>();
        keys.forEach((id, key) -> result.put(id, key.publicKey()));
        return result;
    }

    @FunctionalInterface
    interface ProcessLookup {
        Optional<ProcessHandle> of(long pid);
    }

    static int run(
            final String[] args,
            final PrintStream out,
            final PrintStream err,
            final Map<String, PublicKey> keys,
            final ProcessLookup processes,
            final java.util.function.Supplier<Instant> clock) {
        final Invocation invocation;
        try {
            invocation = parse(args);
        } catch (IllegalArgumentException usage) {
            err.println("usage: " + usage.getMessage());
            err.println("  --root <install dir> --stage <staged dir> --work <state dir> [--wait-pid <pid>] "
                    + "[--wait-seconds <n>] | --recover --root <dir> --work <dir>");
            return EXIT_USAGE;
        }
        try {
            final var applier = new FrameworkUpdateApplier(invocation.root(), invocation.work());
            if (invocation.waitForPid().isPresent()
                    && !waitForExit(invocation.waitForPid().get(), invocation.waitMillis(), processes)) {
                err.println("Cubism process " + invocation.waitForPid().get() + " is still running; not touching files");
                return EXIT_BUSY;
            }
            try (var transaction = FrameworkUpdateLock.acquire(invocation.work())) {
            if (invocation.launcherPid().isPresent()) {
                final var prepared = applier.prepareLaunch(invocation.launcherPid().get());
                out.println(prepared.outcome() + ": " + prepared.detail());
                return prepared.outcome() == FrameworkUpdateApplier.Outcome.RECOVERED
                        ? EXIT_RECOVERED : EXIT_NOTHING;
            }
            // Always finish or undo any earlier interrupted transaction first.
            final var recovered = applier.recover();
            if (invocation.recoverOnly()) {
                out.println(recovered.outcome() + ": " + recovered.detail());
                return recovered.outcome() == FrameworkUpdateApplier.Outcome.RECOVERED ? EXIT_RECOVERED : EXIT_NOTHING;
            }
            final byte[] manifestBytes = readBounded(invocation.stage().resolve("manifest.json"), UpdateManifest.MAX_MANIFEST_BYTES);
            final byte[] envelopeBytes = readBounded(
                    invocation.stage().resolve("manifest.json.sig"), UpdateManifestVerifier.MAX_ENVELOPE_BYTES);
            final UpdateManifest manifest = new UpdateManifestVerifier(keys).authenticate(manifestBytes, envelopeBytes);
            final String problem = policyProblem(invocation, manifest);
            if (problem != null) {
                err.println("Rejected update: " + problem);
                return EXIT_REJECTED;
            }
            final byte[] identity = identity(invocation.root(), manifest);
            final var result = invocation.requireHealth()
                    ? applier.applyPendingHealth(manifest, invocation.stage().resolve("files"), identity)
                    : applier.apply(manifest, invocation.stage().resolve("files"), identity);
            out.println(result.outcome() + ": " + result.detail());
            return switch (result.outcome()) {
                case APPLIED, AWAITING_HEALTH -> EXIT_APPLIED;
                case ROLLED_BACK -> EXIT_ROLLED_BACK;
                case RECOVERED -> EXIT_RECOVERED;
                case NOTHING -> EXIT_NOTHING;
            };
            }
        } catch (FrameworkUpdateApplier.IncompleteRollbackException incomplete) {
            err.println("ROLLBACK INCOMPLETE - installation may be mixed; will be repaired on next run: "
                    + incomplete.getMessage());
            for (final Throwable detail : incomplete.getSuppressed()) err.println("  " + detail);
            return EXIT_NEEDS_RECOVERY;
        } catch (UpdateManifestVerifier.UpdateManifestVerificationException rejected) {
            err.println("Rejected update: " + rejected.errorCode() + ": " + rejected.getMessage());
            return EXIT_REJECTED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            err.println("Interrupted; any partial change was rolled back or will be on next start");
            return EXIT_ROLLED_BACK;
        } catch (IOException | RuntimeException failure) {
            err.println("Update failed: " + failure);
            return EXIT_ROLLED_BACK;
        }
    }

    /** Policy checks that depend on the installation, not on signatures. Returns a reason, or null if acceptable. */
    static String policyProblem(final Invocation invocation, final UpdateManifest manifest) throws IOException {
        if (!manifest.targetPlatform().equals("windows-x64") && !manifest.targetPlatform().equals("all")) {
            return "wrong platform " + manifest.targetPlatform();
        }
        final Path metadata = invocation.root().resolve(InstalledBuild.METADATA_FILE_NAME);
        if (Files.isRegularFile(metadata)) {
            final var tree = UpdateFileSupport.JSON.readTree(Files.readAllBytes(metadata));
            final String installed = tree.path("version").asText("");
            if (!installed.isEmpty()) {
                final UpdateVersion current = UpdateVersion.parseInstalled(installed);
                final UpdateVersion target = UpdateVersion.parse(manifest.version());
                if (current != null && target.compareTo(current) <= 0) {
                    return "target " + manifest.version() + " is not newer than installed " + installed;
                }
            }
        }
        return null;
    }

    /** Exact bytes of the new installed-build.json. Preserves the installed variant. */
    static byte[] identity(final Path root, final UpdateManifest manifest) throws IOException {
        String variant = InstalledBuild.inferVariant(root);
        final var node = UpdateFileSupport.JSON.createObjectNode();
        node.put("schemaVersion", 1);
        node.put("variant", variant);
        node.put("version", manifest.version());
        node.put("channel", manifest.channel());
        node.put("buildNumber", manifest.buildNumber());
        node.put("sourceRevision", manifest.sourceRevision());
        return (UpdateFileSupport.JSON.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static Invocation parse(final String[] args) {
        Path root = null;
        Path stage = null;
        Path work = null;
        Long pid = null;
        long waitSeconds = 120;
        boolean recover = false;
        boolean requireHealth = false;
        Long launcherPid = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = path(args, ++i);
                case "--stage" -> stage = path(args, ++i);
                case "--work" -> work = path(args, ++i);
                case "--wait-pid" -> pid = number(args, ++i);
                case "--wait-seconds" -> waitSeconds = number(args, ++i);
                case "--recover" -> recover = true;
                case "--require-health" -> requireHealth = true;
                case "--prepare-launch" -> launcherPid = number(args, ++i);
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (root == null || work == null || (!recover && launcherPid == null && stage == null)) {
            throw new IllegalArgumentException("--root, --work and (unless --recover) --stage are required");
        }
        if (waitSeconds < 0 || waitSeconds > 3600) throw new IllegalArgumentException("--wait-seconds out of range");
        return new Invocation(
                root, stage == null ? null : stage, work, Optional.ofNullable(pid), waitSeconds * 1000, recover,
                requireHealth, Optional.ofNullable(launcherPid));
    }

    private static Path path(final String[] args, final int index) {
        if (index >= args.length) throw new IllegalArgumentException("missing value");
        final Path path = Path.of(args[index]);
        if (!path.isAbsolute()) throw new IllegalArgumentException("paths must be absolute: " + args[index]);
        return path.normalize();
    }

    private static long number(final String[] args, final int index) {
        if (index >= args.length) throw new IllegalArgumentException("missing value");
        try {
            return Long.parseLong(args[index]);
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("not a number: " + args[index]);
        }
    }

    /** True once the process is gone. Never assumes exit from a closed window; polls the real process. */
    static boolean waitForExit(final long pid, final long maxMillis, final ProcessLookup processes) {
        final long deadline = System.nanoTime() + maxMillis * 1_000_000L;
        while (true) {
            final Optional<ProcessHandle> handle = processes.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) return true;
            if (System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static byte[] readBounded(final Path path, final int limit) throws IOException {
        final var attributes = FrameworkUpdateCache.attributes(path);
        if (attributes == null || !attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() > limit) {
            throw new IOException("Staged document missing or invalid: " + path.getFileName());
        }
        return Files.readAllBytes(path);
    }
}
