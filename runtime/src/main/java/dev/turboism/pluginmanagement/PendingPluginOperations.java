package dev.turboism.pluginmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.distribution.LocalPluginPackageInspector;
import dev.turboism.distribution.PluginInstallPlan;
import dev.turboism.distribution.PluginJarPreflight;
import dev.turboism.distribution.PreparedPluginPackage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Runtime-owned staging journal applied before plugin discovery. */
public final class PendingPluginOperations {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CORE_ID = "turboism.core";
    private final Path home;
    private final Path plugins;
    private final Path staging;
    private final Path packages;
    private final Path journal;
    private final ConfinedPluginFiles files;
    private final SnapshotCopier snapshotCopier;

    public PendingPluginOperations(final Path requestedHome) {
        this(requestedHome, PendingPluginOperations::copySnapshot);
    }

    PendingPluginOperations(final Path requestedHome, final SnapshotCopier snapshotCopier) {
        home = requestedHome.toAbsolutePath().normalize();
        plugins = home.resolve("plugins");
        staging = home.resolve("state/runtime/plugin-management");
        packages = staging.resolve("packages");
        journal = staging.resolve("pending.json");
        files = new ConfinedPluginFiles(home);
        this.snapshotCopier = java.util.Objects.requireNonNull(snapshotCopier, "snapshotCopier");
    }

    synchronized StagedInstall stage(final Path source) {
        final LocalPluginPackageInspector.Preparation result = new LocalPluginPackageInspector().prepare(source, packages);
        if (result instanceof LocalPluginPackageInspector.PreparationRejected rejected) {
            return new StagedInstall(false, rejected.code(), null);
        }
        final PreparedPluginPackage prepared = ((LocalPluginPackageInspector.Prepared) result).value();
        if (CORE_ID.equals(prepared.plan().descriptor().id())) {
            deleteQuietly(prepared.stagedJar());
            return new StagedInstall(false, "PLUGIN_RESERVED_ID", null);
        }
        try {
            final List<Operation> previous = readOperations();
            final List<Operation> operations = withoutPlugin(previous, prepared.plan().descriptor().id());
            operations.add(Operation.install(prepared));
            writeOperations(operations);
            previous.stream().filter(operation -> operation.pluginId.equals(prepared.plan().descriptor().id()))
                .filter(operation -> operation.type.equals("INSTALL"))
                .forEach(operation -> deleteQuietly(Path.of(operation.stagedJar)));
            return new StagedInstall(true, "PLUGIN_INSTALL_PENDING", prepared.plan());
        } catch (PendingJournalInvalidException failure) {
            deleteQuietly(prepared.stagedJar());
            return new StagedInstall(false, "PLUGIN_PENDING_RECOVERY_REQUIRED", null);
        } catch (RuntimeException failure) {
            deleteQuietly(prepared.stagedJar());
            return new StagedInstall(false, "PLUGIN_PENDING_WRITE_FAILED", null);
        }
    }

    synchronized StagedUninstall stageUninstall(final String pluginId) {
        if (CORE_ID.equals(pluginId)) return new StagedUninstall(false, "PLUGIN_CORE_PROTECTED");
        try {
            final List<Operation> previous = readOperations();
            final List<Operation> operations = withoutPlugin(previous, pluginId);
            operations.add(Operation.uninstall(pluginId));
            writeOperations(operations);
            previous.stream().filter(operation -> operation.pluginId.equals(pluginId) && operation.type.equals("INSTALL"))
                .forEach(operation -> deleteQuietly(Path.of(operation.stagedJar)));
            return new StagedUninstall(true, "PLUGIN_UNINSTALL_PENDING");
        } catch (PendingJournalInvalidException failure) {
            return new StagedUninstall(false, "PLUGIN_PENDING_RECOVERY_REQUIRED");
        } catch (RuntimeException failure) {
            return new StagedUninstall(false, "PLUGIN_PENDING_WRITE_FAILED");
        }
    }

    synchronized List<Operation> operations() { return List.copyOf(readOperations()); }

    synchronized boolean recoveryRequired() {
        try {
            readOperations();
            return false;
        } catch (PendingJournalInvalidException failure) {
            return true;
        }
    }

    /**
     * Applies the journalled install and uninstall operations at startup, before plugins are loaded.
     * The current plugin directory is backed up first and restored wholesale if any single operation
     * fails, so the directory is never left half-updated. Symlinks anywhere on the touched paths are
     * rejected rather than followed. The journal is deleted only after every operation succeeded;
     * staged JARs for applied installs are then cleaned up.
     *
     * <p>An empty or absent journal is a success. Failures are reported as a status, never thrown.
     *
     * @return {@code APPLIED} when the journal was applied and cleared, {@code ROLLED_BACK} when the
     *     previous directory was restored intact, or {@code RECOVERY_REQUIRED} when the journal could
     *     not be read or the rollback itself failed and manual repair is needed
     */
    public synchronized ApplyResult apply() {
        final List<Operation> operations;
        try {
            files.rejectLinks(home);
            files.rejectLinks(staging);
            files.rejectLinks(packages);
            operations = readOperations();
        } catch (Exception failure) {
            return new ApplyResult(Status.RECOVERY_REQUIRED, "PLUGIN_PENDING_INVALID");
        }
        if (operations.isEmpty()) return new ApplyResult(Status.APPLIED, "PLUGIN_PENDING_EMPTY");
        final Path backup = staging.resolve("backup-" + UUID.randomUUID());
        try {
            Files.createDirectories(plugins);
            Files.createDirectories(backup);
            files.rejectLinks(plugins);
            files.rejectLinks(backup);
            backupCurrent(backup);
            for (Operation operation : operations) applyOne(operation);
        } catch (Exception failure) {
            try {
                restore(backup);
                return new ApplyResult(Status.ROLLED_BACK, "PLUGIN_PENDING_ROLLED_BACK");
            } catch (Exception rollbackFailure) {
                return new ApplyResult(Status.RECOVERY_REQUIRED, "PLUGIN_PENDING_RECOVERY_REQUIRED");
            }
        }
        try {
            final ConfinedPluginFiles.ParentIdentity journalParent = files.parent(journal);
            files.delete(journal, journalParent);
        } catch (IOException failure) {
            try {
                restore(backup);
                return new ApplyResult(Status.ROLLED_BACK, "PLUGIN_PENDING_ROLLED_BACK");
            } catch (Exception rollbackFailure) {
                return new ApplyResult(Status.RECOVERY_REQUIRED, "PLUGIN_PENDING_RECOVERY_REQUIRED");
            }
        }
        deleteTreeQuietly(backup);
        operations.stream().filter(operation -> operation.type.equals("INSTALL"))
            .forEach(operation -> deleteQuietly(Path.of(operation.stagedJar)));
        cleanupOrphans();
        return new ApplyResult(Status.APPLIED, "PLUGIN_PENDING_APPLIED");
    }

    private void applyOne(final Operation operation) throws Exception {
        if (operation.pluginId.equals(CORE_ID)) throw new IOException("reserved plugin ID");
        final List<Path> existing = installed(operation.pluginId);
        final ConfinedPluginFiles.ParentIdentity pluginParent = files.parent(plugins.resolve("identity"));
        if (operation.type.equals("UNINSTALL")) {
            for (Path path : existing) files.delete(path, pluginParent);
            return;
        }
        final Path stagedJar = files.confined(Path.of(operation.stagedJar));
        if (!stagedJar.startsWith(packages) || Files.isSymbolicLink(stagedJar)) throw new IOException("staged path rejected");
        files.rejectLinks(stagedJar);
        if (existing.size() > 1) throw new IOException("duplicate installed plugin ID");
        final Path target = plugins.resolve(operation.pluginId + ".jar");
        final Path temporary = plugins.resolve("." + operation.pluginId + "-" + UUID.randomUUID() + ".tmp");
        try {
            snapshotCopier.copy(stagedJar, temporary, pluginParent);
            if (!PluginJarPreflight.matches(temporary, operation.pluginId, operation.version,
                operation.descriptorSha256, operation.jarSha256, operation.jarSize)) {
                throw new IOException("staged package identity mismatch");
            }
            try (FileChannel snapshot = FileChannel.open(
                temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS
            )) {
                snapshot.force(true);
            }
            files.move(temporary, target, pluginParent, true);
            for (Path path : existing) if (!path.equals(target)) files.delete(path, pluginParent);
        } finally {
            files.delete(temporary, pluginParent);
        }
    }

    private List<Path> installed(final String pluginId) throws IOException {
        if (!Files.isDirectory(plugins, LinkOption.NOFOLLOW_LINKS)) return List.of();
        files.rejectLinks(plugins);
        final List<Path> matches = new ArrayList<>();
        try (var entries = Files.list(plugins)) {
            for (Path path : entries.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                .filter(candidate -> candidate.getFileName().toString().endsWith(".jar")).toList()) {
                if (PluginArchiveMetadata.read(path).map(PluginArchiveMetadata::id).orElse("").equals(pluginId)) matches.add(path);
            }
        }
        return matches.stream().sorted().toList();
    }

    private void backupCurrent(final Path backup) throws IOException {
        if (!Files.isDirectory(plugins, LinkOption.NOFOLLOW_LINKS)) return;
        files.rejectLinks(plugins);
        final ConfinedPluginFiles.ParentIdentity parent = files.parent(backup.resolve("identity"));
        try (var entries = Files.list(plugins)) {
            for (Path path : entries.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                .filter(candidate -> candidate.getFileName().toString().endsWith(".jar")).toList()) {
                final Path target = backup.resolve(path.getFileName());
                try (var output = files.createNew(target, parent)) { output.write(ByteBuffer.wrap(Files.readAllBytes(path))); }
            }
        }
    }

    private void restore(final Path backup) throws IOException {
        if (!Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)) throw new IOException("backup missing");
        final ConfinedPluginFiles.ParentIdentity pluginParent = files.parent(plugins.resolve("identity"));
        try (var entries = Files.list(plugins)) {
            for (Path path : entries.filter(candidate -> candidate.getFileName().toString().endsWith(".jar")).toList()) {
                files.delete(path, pluginParent);
            }
        }
        try (var entries = Files.list(backup)) {
            for (Path path : entries.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)).toList()) {
                final Path target = plugins.resolve(path.getFileName());
                final Path temporary = plugins.resolve(".restore-" + UUID.randomUUID() + ".tmp");
                try (var output = files.createNew(temporary, pluginParent)) { output.write(ByteBuffer.wrap(Files.readAllBytes(path))); }
                files.move(temporary, target, pluginParent, false);
            }
        }
        deleteTree(backup);
    }

    private List<Operation> readOperations() {
        try {
            files.rejectLinks(journal);
            final BasicFileAttributes attributes = Files.readAttributes(
                journal, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isRegularFile() || attributes.size() > 64L * 1024L) throw new IOException();
            final JsonNode root = JSON.readTree(Files.readAllBytes(journal));
            if (root == null || !root.path("format").asText().equals("turboism.plugin.pending")
                || root.path("schemaVersion").asInt() != 1 || !root.path("operations").isArray()) throw new IOException();
            final List<Operation> result = new ArrayList<>();
            for (JsonNode node : root.path("operations")) result.add(Operation.from(node));
            return result;
        } catch (NoSuchFileException missing) {
            return new ArrayList<>();
        } catch (IOException | RuntimeException failure) {
            throw new PendingJournalInvalidException(failure);
        }
    }

    private void writeOperations(final List<Operation> operations) {
        Path temporary = null;
        try {
            Files.createDirectories(staging);
            final ConfinedPluginFiles.ParentIdentity parent = files.parent(journal);
            final ObjectNode root = JSON.createObjectNode();
            root.put("format", "turboism.plugin.pending"); root.put("schemaVersion", 1);
            final ArrayNode values = root.putArray("operations");
            operations.stream().sorted(Comparator.comparing(operation -> operation.pluginId)).forEach(operation -> values.add(operation.json()));
            temporary = staging.resolve(".pending-" + UUID.randomUUID() + ".tmp");
            try (var output = files.createNew(temporary, parent)) {
                output.write(ByteBuffer.wrap(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root)));
            }
            files.move(temporary, journal, parent, true);
        } catch (IOException failure) {
            deleteQuietly(temporary);
            throw new IllegalStateException("PLUGIN_PENDING_WRITE_FAILED", failure);
        }
    }

    private void cleanupOrphans() {
        try {
            if (!Files.isDirectory(packages, LinkOption.NOFOLLOW_LINKS)) return;
            files.rejectLinks(packages);
            try (var entries = Files.list(packages)) {
                for (Path path : entries.toList()) deleteQuietly(path);
            }
        } catch (IOException ignored) { }
    }

    private static List<Operation> withoutPlugin(final List<Operation> input, final String pluginId) {
        final List<Operation> result = new ArrayList<>(input);
        result.removeIf(operation -> operation.pluginId.equals(pluginId));
        return result;
    }

    private static void copySnapshot(
        final Path source,
        final Path snapshot,
        final ConfinedPluginFiles.ParentIdentity parent
    ) throws IOException {
        parent.verify();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IOException("staged path rejected");
        }
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             FileChannel output = FileChannel.open(
                 snapshot, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS
             )) {
            final ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024);
            while (input.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) output.write(buffer);
                buffer.clear();
            }
        }
        parent.verify();
    }

    private static void deleteQuietly(final Path path) { if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { } }
    private static void deleteTreeQuietly(final Path path) { try { deleteTree(path); } catch (IOException ignored) { } }
    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("symbolic link rejected");
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    interface SnapshotCopier {
        void copy(Path source, Path snapshot, ConfinedPluginFiles.ParentIdentity parent) throws IOException;
    }

    record StagedInstall(boolean accepted, String code, PluginInstallPlan plan) { }
    record StagedUninstall(boolean accepted, String code) { }
    public enum Status { APPLIED, ROLLED_BACK, RECOVERY_REQUIRED }
    /**
     * Outcome of applying the pending-operations journal.
     *
     * @param status what happened to the plugin directory
     * @param code stable diagnostic code identifying the specific outcome, for logs and tests
     */
    public record ApplyResult(Status status, String code) { public boolean applied() { return status == Status.APPLIED; } }

    private static final class PendingJournalInvalidException extends IllegalStateException {
        PendingJournalInvalidException(final Throwable cause) { super("PLUGIN_PENDING_INVALID", cause); }
    }

    record Operation(String type, String pluginId, String stagedJar, String version,
        String rawSha256, String descriptorSha256, String jarSha256, long jarSize) {
        static Operation install(final PreparedPluginPackage prepared) {
            final var plan = prepared.plan();
            final var jar = plan.files().stream().filter(file -> file.role().equals("PLUGIN_JAR")).findFirst().orElseThrow();
            return new Operation("INSTALL", plan.descriptor().id(), prepared.stagedJar().toString(), plan.descriptor().version(),
                plan.packageIdentity().rawArchiveSha256(), plan.descriptorSha256(), jar.sha256(), jar.size());
        }
        static Operation uninstall(final String id) { return new Operation("UNINSTALL", id, "", "", "", "", "", 0); }
        ObjectNode json() {
            final ObjectNode node = JSON.createObjectNode();
            node.put("type", type); node.put("pluginId", pluginId); node.put("stagedJar", stagedJar);
            node.put("version", version); node.put("rawSha256", rawSha256); node.put("descriptorSha256", descriptorSha256);
            node.put("jarSha256", jarSha256); node.put("jarSize", jarSize); return node;
        }
        static Operation from(final JsonNode node) {
            final String type = node.path("type").asText(); final String pluginId = node.path("pluginId").asText();
            if (!(type.equals("INSTALL") || type.equals("UNINSTALL")) || pluginId.isBlank()) throw new IllegalArgumentException();
            return new Operation(type, pluginId, node.path("stagedJar").asText(), node.path("version").asText(),
                node.path("rawSha256").asText(), node.path("descriptorSha256").asText(),
                node.path("jarSha256").asText(), node.path("jarSize").asLong());
        }
    }
}
