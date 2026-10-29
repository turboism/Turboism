package dev.turboism.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only proof of concept for selecting unchanged files in a future signed framework update.
 *
 * <p>Entries MUST come from an independently verified and signed release manifest. This class
 * neither trusts network data nor downloads, removes, patches or replaces any installed file.
 * A production updater must re-check every path and digest after staging and before application.
 */
public final class FrameworkDeltaPlanner {
    private static final int MAX_ENTRIES = 512;
    private static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern OWNED_JAR = Pattern.compile(
            "(?:turboism-agent\\.jar|plugins/[a-z0-9-]+\\.jar|graal/lib/[A-Za-z0-9._-]+\\.jar)");

    private FrameworkDeltaPlanner() {}

    public enum Action {
        REUSE,
        DOWNLOAD
    }

    /** Only framework-owned JAR payloads are admitted by this deliberately narrow prototype. */
    public record Entry(String relativePath, long bytes, String sha256) {
        public Entry {
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(sha256, "sha256");
            if (!OWNED_JAR.matcher(relativePath).matches() || relativePath.contains("..")) {
                throw new IllegalArgumentException("not an approved framework JAR path: " + relativePath);
            }
            if (bytes < 0 || bytes > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("payload size is outside prototype bounds");
            }
            if (!SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("SHA-256 must be 64 lowercase hex characters");
            }
        }
    }

    public record Decision(Entry entry, Action action) {}

    public record Plan(List<Decision> decisions, long bytesToDownload) {
        public Plan {
            decisions = List.copyOf(decisions);
        }
    }

    /** Plan file downloads without mutating the installation or any user-managed path. */
    public static Plan plan(final Path installationRoot, final List<Entry> manifestEntries) throws IOException {
        Objects.requireNonNull(installationRoot, "installationRoot");
        Objects.requireNonNull(manifestEntries, "manifestEntries");
        if (manifestEntries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("too many manifest entries");
        }
        final Path root = installationRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)
                || (Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("installation root is not a safe directory");
        }

        final List<Decision> decisions = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        long bytesToDownload = 0;
        for (final Entry entry : manifestEntries) {
            Objects.requireNonNull(entry, "manifest entry");
            if (!seen.add(entry.relativePath().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("duplicate case-insensitive manifest path");
            }
            final Path target = root.resolve(entry.relativePath()).normalize();
            if (!target.startsWith(root)) {
                throw new IOException("manifest path escaped installation root");
            }
            rejectSymlinkChain(root, target);
            final boolean unchanged;
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                unchanged = false;
            } else if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("installed target is not a regular file");
            } else {
                unchanged = Files.size(target) == entry.bytes() && sha256(target).equals(entry.sha256());
            }
            final Action action = unchanged ? Action.REUSE : Action.DOWNLOAD;
            decisions.add(new Decision(entry, action));
            if (action == Action.DOWNLOAD) bytesToDownload += entry.bytes();
        }
        return new Plan(decisions, bytesToDownload);
    }

    private static void rejectSymlinkChain(final Path root, final Path target) throws IOException {
        Path current = root;
        for (final Path component : root.relativize(target)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("symlink in framework update target path");
            }
        }
    }

    private static String sha256(final Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(path)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
