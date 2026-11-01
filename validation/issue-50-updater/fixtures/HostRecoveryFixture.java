package dev.turboism.pluginmanagement;

// LAB ONLY. Repeatable, isolated bad-pending -> rollback -> suspended -> DEFERRED -> cancel -> retry
// driver. Never run against a user installation; LabHomeGuard requires a dedicated marker and
// refuses Cubism roots and reparse points. No catalog/release/CI acceptance is implied.
import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.lab.LabHomeGuard;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HostRecoveryFixture {
    private HostRecoveryFixture() {}

    public static void main(final String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: HostRecoveryFixture <init|baseline|seed|restage|apply|status|blocked|cancel|chain> "
                    + "--home <labHome> [--plugins a,b] [--out <file>]");
            System.exit(2);
            return;
        }
        final String command = args[0];
        final Path requested = LabHomeGuard.requireHomeArg(args, -1);
        if ("init".equals(command)) {
            init(requested);
            return;
        }
        final Path home = LabHomeGuard.validate(requested);
        switch (command) {
            case "baseline" -> baseline(home, LabHomeGuard.optionalOutArg(args));
            case "seed" -> seed(home, LabHomeGuard.optionalPluginsArg(args), true);
            case "restage" -> seed(home, LabHomeGuard.optionalPluginsArg(args), false);
            case "apply" -> apply(home);
            case "status" -> status(home);
            case "blocked" -> blocked(home);
            case "cancel" -> cancel(home);
            case "chain" -> chain(home, LabHomeGuard.optionalOutArg(args));
            default -> {
                System.err.println("unknown command: " + command);
                System.exit(2);
            }
        }
    }

    /** Scaffolds a brand-new isolated lab: marker, empty plugin/state dirs, README and config. */
    private static void init(final Path requested) throws Exception {
        final Path home = LabHomeGuard.confineUnderBase(requested);
        LabHomeGuard.createIsolatedHome(home);
        for (final String dir : List.of(
                "plugins",
                "state/runtime/plugin-management/packages",
                "state/runtime/update/helper")) {
            Files.createDirectories(home.resolve(dir));
        }
        Files.writeString(home.resolve("README.txt"), "issue50 recovery lab baseline README\n",
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        Files.writeString(home.resolve("config.json"), "{\"comment\":\"issue50 recovery lab baseline config\"}\n",
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        System.out.println("LAB_INITIALIZED: " + home);
        System.out.println("Next: copy the staged turboism-agent.jar, installed-build.json and the real plugin JARs "
                + "into this home, then run `baseline`.");
    }

    private static void baseline(final Path home, final Path requestedOut) throws Exception {
        final Path out = requestedOut != null
                ? requestedOut
                : home.resolve("state/runtime/issue50-lab-baseline.txt");
        LabHomeGuard.recordBaseline(home, out);
        System.out.println("BASELINE_RECORDED: " + out);
    }

    /** Stages selected (default: all) plugin JARs and, when {@code corrupt}, flips the last staged byte. */
    private static void seed(final Path home, final String pluginsFilter, final boolean corrupt) throws Exception {
        LabHomeGuard.assertNoLiveJvm(home);
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        if (pending.recoveryRequired()) {
            throw new IllegalStateException("suspended intent present; run `cancel` first");
        }
        final List<Path> sources = selectPluginJars(home, pluginsFilter);
        if (sources.isEmpty()) throw new IllegalStateException("no plugin JARs found in " + home.resolve("plugins"));
        final List<PendingPluginOperations.Operation> staged = new ArrayList<>();
        for (final Path source : sources) {
            final PendingPluginOperations.StagedInstall result = pending.stage(source);
            if (!result.accepted()) {
                throw new IllegalStateException("stage rejected " + source.getFileName() + ": " + result.code());
            }
        }
        final List<PendingPluginOperations.Operation> operations = pending.operations();
        if (operations.isEmpty()) throw new IllegalStateException("stage produced no pending operations");
        for (final PendingPluginOperations.Operation operation : operations) {
            System.out.println("STAGED " + operation.pluginId() + " " + operation.version()
                    + " jarSha256=" + operation.jarSha256());
        }
        if (corrupt) {
            final PendingPluginOperations.Operation last = operations.get(operations.size() - 1);
            final Path stagedJar = Path.of(last.stagedJar()).toAbsolutePath().normalize();
            final byte[] bytes = Files.readAllBytes(stagedJar);
            bytes[bytes.length - 1] ^= 1;
            Files.write(stagedJar, bytes);
            System.out.println("CORRUPTED_LAST_STAGED: " + last.pluginId() + " " + stagedJar);
        } else {
            System.out.println("RESTAGED_WITHOUT_CORRUPTION: " + operations.size() + " operation(s)");
        }
    }

    /** Applies the pending journal exactly like startup and prints the outcome. */
    private static void apply(final Path home) throws Exception {
        LabHomeGuard.assertNoLiveJvm(home);
        final PendingPluginOperations.ApplyResult result = RuntimePluginManagementService.applyPending(home);
        System.out.println("APPLY_STATUS=" + result.status() + " APPLY_CODE=" + result.code());
        if (result.status() == PendingPluginOperations.Status.RECOVERY_REQUIRED) {
            System.exit(3);
        }
    }

    /** Prints the production review code and changes via the framework-aware management service. */
    private static void status(final Path home) throws Exception {
        try (RuntimePluginManagementService service =
                new RuntimePluginManagementService(home, java.util.Optional::empty, List::of)) {
            final CorePluginManagement.PendingChanges review = service.pendingChanges();
            System.out.println("REVIEW_CODE=" + review.code());
            System.out.println("REVIEW_SNAPSHOT=" + (review.snapshot().isBlank() ? "<blank>" : review.snapshot()));
            System.out.println("REVIEW_CHANGES=" + review.changes().size());
            for (final CorePluginManagement.PendingChange change : review.changes()) {
                System.out.println("REVIEW_CHANGE " + change.pluginId() + " " + change.operation()
                        + " " + change.version());
            }
        }
    }

    /** Asserts that a retained framework journal blocks plugin-intent cancellation (run after HostRollbackFixture). */
    private static void blocked(final Path home) throws Exception {
        try (RuntimePluginManagementService service =
                new RuntimePluginManagementService(home, java.util.Optional::empty, List::of)) {
            final CorePluginManagement.PendingChanges review = service.pendingChanges();
            if (!"PLUGIN_FRAMEWORK_RECOVERY_PENDING".equals(review.code())) {
                throw new AssertionError("expected PLUGIN_FRAMEWORK_RECOVERY_PENDING, got " + review.code());
            }
            final CorePluginManagement.OperationResult result = service.discardPendingChanges(review.snapshot());
            if (result.accepted() || !"PLUGIN_FRAMEWORK_RECOVERY_PENDING".equals(result.code())) {
                throw new AssertionError("framework recovery should block cancellation: " + result);
            }
            System.out.println("FRAMEWORK_RECOVERY_BLOCKS_CANCEL: " + result.code());
        }
    }

    /** Explicitly discards the reviewed (active or suspended) intent after user consent. */
    private static void cancel(final Path home) throws Exception {
        try (RuntimePluginManagementService service =
                new RuntimePluginManagementService(home, java.util.Optional::empty, List::of)) {
            final CorePluginManagement.PendingChanges review = service.pendingChanges();
            if (review.changes().isEmpty()) {
                System.out.println("NOTHING_TO_CANCEL: " + review.code());
                return;
            }
            final CorePluginManagement.OperationResult result = service.discardPendingChanges(review.snapshot());
            if (!result.accepted()) throw new AssertionError("cancel rejected: " + result);
            System.out.println("CANCELLED: " + result.code());
        }
        final Path failed = home.resolve("state/runtime/plugin-management/failed-pending.json");
        final Path pending = home.resolve("state/runtime/plugin-management/pending.json");
        System.out.println("failed-pending.json=" + (Files.exists(failed, LinkOption.NOFOLLOW_LINKS) ? "present" : "absent"));
        System.out.println("pending.json=" + (Files.exists(pending, LinkOption.NOFOLLOW_LINKS) ? "present" : "absent"));
    }

    /** Runs the whole in-process plugin chain and verifies plugin bytes are restored after rollback. */
    private static void chain(final Path home, final Path requestedOut) throws Exception {
        final Map<String, String> before = pluginHashMap(home);
        System.out.println("CHAIN_START plugins=" + before.size());

        seed(home, null, true);
        apply(home);
        final Map<String, String> afterRollback = pluginHashMap(home);
        if (!afterRollback.equals(before)) {
            throw new AssertionError("plugin bytes changed after rollback: " + before + " vs " + afterRollback);
        }
        System.out.println("CHAIN_ROLLBACK_RESTORED_ALL_PLUGIN_BYTES");

        // Suspended intent review (framework journal absent in the plugin-only chain).
        try (RuntimePluginManagementService service =
                new RuntimePluginManagementService(home, java.util.Optional::empty, List::of)) {
            final CorePluginManagement.PendingChanges review = service.pendingChanges();
            if (!"PLUGIN_PENDING_FAILED".equals(review.code())) {
                throw new AssertionError("expected PLUGIN_PENDING_FAILED, got " + review.code());
            }
            System.out.println("CHAIN_SUSPENDED_REVIEW: " + review.code() + " changes=" + review.changes().size());
        }
        System.out.println("NOTE: before the next apply, the full chain inserts the framework component recovery "
                + "(HostRollbackFixture then a controlled managed launch) and verifies `blocked`.");

        LabHomeGuard.assertNoLiveJvm(home);
        final PendingPluginOperations.ApplyResult deferred = RuntimePluginManagementService.applyPending(home);
        if (deferred.status() != PendingPluginOperations.Status.DEFERRED) {
            throw new AssertionError("expected DEFERRED while suspended, got " + deferred.status());
        }
        System.out.println("CHAIN_DEFERRED: " + deferred.code());

        cancel(home);
        restage(home, null);
        System.out.println("CHAIN_COMPLETE: bad-pending -> full rollback -> suspended -> DEFERRED -> cancel -> retry");
        if (requestedOut != null) {
            LabHomeGuard.recordBaseline(home, requestedOut);
            System.out.println("BASELINE_RECORDED: " + requestedOut);
        }
    }

    private static void restage(final Path home, final String pluginsFilter) throws Exception {
        seed(home, pluginsFilter, false);
    }

    private static List<Path> selectPluginJars(final Path home, final String filter) throws Exception {
        final List<Path> all = LabHomeGuard.pluginJars(home);
        if (filter == null || filter.isBlank()) return all;
        final Set<String> allowed = new HashSet<>();
        for (final Path jar : all) allowed.add(jar.getFileName().toString());
        final List<Path> selected = new ArrayList<>();
        for (final String raw : filter.split(",")) {
            final String id = raw.trim();
            if (id.isEmpty()) continue;
            // Only bare file names present under home/plugins are allowed; reject separators,
            // parent refs, absolute forms and drive letters.
            if (id.contains("/") || id.contains("\\") || id.equals(".") || id.equals("..")
                    || id.startsWith("/") || id.startsWith("\\")
                    || (id.length() >= 2 && Character.isLetter(id.charAt(0)) && id.charAt(1) == ':')) {
                throw new IllegalArgumentException("plugin filter must be a bare file name: " + raw);
            }
            final String fileName = id.endsWith(".jar") ? id : id + ".jar";
            if (!allowed.contains(fileName)) {
                throw new IllegalArgumentException("plugin JAR not among lab plugins: " + fileName);
            }
            final Path jar = home.resolve("plugins").resolve(fileName);
            if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(jar)) {
                throw new IllegalArgumentException("plugin JAR is not a regular file: " + jar);
            }
            selected.add(jar);
        }
        return selected;
    }

    private static Map<String, String> pluginHashMap(final Path home) throws Exception {
        final java.util.TreeMap<String, String> hashes = new java.util.TreeMap<>();
        for (final Path jar : LabHomeGuard.pluginJars(home)) {
            hashes.put(jar.getFileName().toString(), LabHomeGuard.sha256Hex(jar));
        }
        return hashes;
    }
}
