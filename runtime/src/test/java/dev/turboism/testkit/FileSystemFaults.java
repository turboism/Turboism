package dev.turboism.testkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Real filesystem fault injection for suites that must prove a fail-closed persistence guard on
 * every host, not only on hosts that grant symbolic links and POSIX permissions.
 *
 * <p>Two faults are offered. {@link #createDirectoryReparsePoint} makes one directory resolve to
 * another so a guard can be proven never to write through a redirected path; a symbolic link is
 * used when the host allows it, otherwise a Windows directory junction is used and the caller is
 * told which reparse contract it must assert, so no caller silently assumes the two are
 * interchangeable. {@link #denyDirectoryWrites} makes a directory reject new children so a failed
 * publication can be proven to leave the previous bytes intact.
 *
 * <p>Every fault is reversible and never leaves the machine changed: the reparse point is deleted
 * without touching its target, and the write denial restores the exact permission state it found.
 */
public final class FileSystemFaults {

    private static final String WINDOWS_OS_NAME_PREFIX = "windows";
    private static final String COMMAND_INTERPRETER = "cmd";
    private static final String COMMAND_INTERPRETER_SWITCH = "/c";
    private static final String MKLINK_COMMAND = "mklink";
    private static final String JUNCTION_SWITCH = "/J";
    private static final long JUNCTION_TIMEOUT_SECONDS = 30L;

    /** Directory permissions that permit traversal and listing but forbid creating a child. */
    private static final Set<PosixFilePermission> DIRECTORY_WRITE_DENIED = PosixFilePermissions.fromString("r-x------");

    /**
     * Directory rights Windows checks before it creates a child entry. The denial stays on the
     * directory object itself, so the files it already holds keep their own access.
     */
    private static final Set<AclEntryPermission> ACL_WRITE_DENIED = EnumSet.of(
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.ADD_FILE,
            AclEntryPermission.ADD_SUBDIRECTORY);

    private static final String DENIAL_PROBE_PREFIX = ".turboism-write-denial-probe-";

    private FileSystemFaults() {}

    /** The reparse-point type a host could actually create. */
    public enum ReparsePointKind {
        /** A symbolic link: {@link Files#isSymbolicLink} reports it as one. */
        SYMBOLIC_LINK,
        /** A Windows directory junction: a reparse point that {@link Files#isSymbolicLink} does not report. */
        DIRECTORY_JUNCTION
    }

    /**
     * One created reparse point plus the type the caller must assert against.
     *
     * @param link the created reparse point path
     * @param target the directory it resolves to
     * @param kind the type this host could create
     */
    public record ReparsePoint(Path link, Path target, ReparsePointKind kind) {}

    /**
     * Redirects {@code link} to {@code target} with the strongest reparse point the host permits.
     *
     * <p>A symbolic link is created when the host grants the privilege. Windows hosts without it
     * (Developer Mode disabled, no elevation) fall back to a directory junction, which is a real
     * reparse point that resolves the same way but is not a symbolic link. The returned
     * {@link ReparsePointKind} names the contract that was actually established.
     *
     * @param link the reparse point path to create; it must not exist yet
     * @param target the existing directory it must resolve to
     * @return the created reparse point and its kind
     * @throws IOException when no reparse point can be created, or when neither type was established
     */
    public static ReparsePoint createDirectoryReparsePoint(final Path link, final Path target) throws IOException {
        final Path absoluteLink = link.toAbsolutePath().normalize();
        final Path absoluteTarget = target.toAbsolutePath().normalize();
        if (!Files.isDirectory(absoluteTarget)) {
            throw new IOException("a reparse point target must be an existing directory: " + absoluteTarget);
        }
        final Optional<IOException> symbolicLinkFailure = tryCreateSymbolicLink(absoluteLink, absoluteTarget);
        if (symbolicLinkFailure.isEmpty()) {
            return new ReparsePoint(absoluteLink, absoluteTarget, ReparsePointKind.SYMBOLIC_LINK);
        }
        if (!isWindows()) {
            throw new IOException("symbolic links are unavailable: " + absoluteLink, symbolicLinkFailure.get());
        }
        createWindowsJunction(absoluteLink, absoluteTarget);
        return new ReparsePoint(absoluteLink, absoluteTarget, ReparsePointKind.DIRECTORY_JUNCTION);
    }

    /**
     * Attempts a symbolic link and reports why it is unavailable.
     *
     * @return empty when the link exists and is reported as a symbolic link
     */
    private static Optional<IOException> tryCreateSymbolicLink(final Path link, final Path target) {
        try {
            Files.createSymbolicLink(link, target);
            if (Files.isSymbolicLink(link)) {
                return Optional.empty();
            }
            Files.deleteIfExists(link);
            return Optional.of(new IOException("the created link is not reported as a symbolic link: " + link));
        } catch (IOException | UnsupportedOperationException notPermitted) {
            deleteQuietly(link);
            return Optional.of(new IOException("symbolic links are unavailable: " + link, notPermitted));
        }
    }

    private static void deleteQuietly(final Path link) {
        try {
            Files.deleteIfExists(link);
        } catch (IOException ignored) {
            // A link that was never created leaves nothing to roll back.
        }
    }

    /**
     * Removes a reparse point without touching the directory it resolves to.
     *
     * @param link the reparse point to delete
     * @throws IOException when the entry cannot be removed
     */
    public static void deleteReparsePoint(final Path link) throws IOException {
        Files.deleteIfExists(link);
    }

    /**
     * Pins the reparse contract this host established, so no suite treats a directory junction and
     * a symbolic link as interchangeable.
     *
     * <p>A symbolic link must be reported as one. A junction is not a symbolic link, and a guard that
     * tests only {@link Files#isSymbolicLink} therefore cannot reject it; a suite that runs on such a
     * host must name that difference instead of hiding it.
     *
     * @param point the reparse point created for the case
     * @throws IOException when the reparse point cannot be resolved
     */
    public static void assertReparseContract(final ReparsePoint point) throws IOException {
        assertTrue(
                point.link().toRealPath().startsWith(point.target().toRealPath()),
                "the reparse point must resolve into the directory it was created for");
        if (point.kind() == ReparsePointKind.SYMBOLIC_LINK) {
            assertTrue(Files.isSymbolicLink(point.link()), "a symbolic link must be reported as one");
        } else {
            assertFalse(
                    Files.isSymbolicLink(point.link()),
                    "a directory junction is not a symbolic link, so only a real-path check can reject it");
        }
    }

    /**
     * Proves the reparse point redirects outside {@code guardedRoot} and pins the contract this host
     * established.
     *
     * @param point the reparse point created for the case
     * @param guardedRoot the directory the reparse point must escape
     * @throws IOException when the reparse point cannot be resolved
     */
    public static void assertRedirectsOutside(final ReparsePoint point, final Path guardedRoot) throws IOException {
        assertFalse(
                point.link().toRealPath().startsWith(guardedRoot.toRealPath()),
                "the injected reparse point must resolve outside the guarded root");
        assertReparseContract(point);
    }

    /**
     * Makes {@code directory} reject every new child until the returned handle is closed.
     *
     * <p>A POSIX host loses the write bit on the directory; an ACL host gains a denial entry for the
     * owner that is removed again on close. The denial is verified before it is returned, so a host
     * where the fault cannot be established reports that fact instead of letting a test pass on a
     * write that was never blocked.
     *
     * @param directory the existing directory that must stop accepting new entries
     * @return the reversible denial; closing it restores the permission state found on entry
     * @throws IOException when the directory exposes no permission model, or when the denial is not
     *     effective on this host
     */
    public static WriteDenial denyDirectoryWrites(final Path directory) throws IOException {
        final Path target = directory.toAbsolutePath().normalize();
        final PosixFileAttributeView posix =
                Files.getFileAttributeView(target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        final WriteDenial denial = posix != null ? new PosixWriteDenial(target, posix) : aclWriteDenial(target);
        try {
            denial.verifyEffective();
        } catch (IOException ineffective) {
            denial.close();
            throw ineffective;
        }
        return denial;
    }

    private static WriteDenial aclWriteDenial(final Path directory) throws IOException {
        final AclFileAttributeView acl =
                Files.getFileAttributeView(directory, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            throw new IOException("no permission model can deny writes on this file system: " + directory);
        }
        final UserPrincipal owner = acl.getOwner();
        if (owner == null) {
            throw new IOException("the directory has no owner to deny writes for: " + directory);
        }
        return new AclWriteDenial(directory, acl, owner);
    }

    private static void createWindowsJunction(final Path link, final Path target) throws IOException {
        final ProcessBuilder builder = new ProcessBuilder(
                        COMMAND_INTERPRETER,
                        COMMAND_INTERPRETER_SWITCH,
                        MKLINK_COMMAND,
                        JUNCTION_SWITCH,
                        link.toString(),
                        target.toString())
                .redirectErrorStream(true);
        final Process process;
        try {
            process = builder.start();
        } catch (IOException unavailable) {
            throw new IOException("no reparse point can be created at " + link, unavailable);
        }
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        final boolean finished;
        try {
            finished = process.waitFor(JUNCTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while creating a directory junction: " + link, interrupted);
        }
        if (!finished) {
            process.destroy();
            throw new IOException("the directory junction command did not finish: " + link);
        }
        if (process.exitValue() != 0 || !Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("no reparse point can be created at " + link + ": " + output);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith(WINDOWS_OS_NAME_PREFIX);
    }

    /** A directory write denial that restores the permission state it found on creation. */
    public interface WriteDenial extends AutoCloseable {

        /** Proves the denial blocks a new child entry. */
        void verifyEffective() throws IOException;

        /** Restores the permission state found when the denial was created. */
        @Override
        void close() throws IOException;
    }

    private abstract static class RestoringWriteDenial implements WriteDenial {

        private final Path directory;

        RestoringWriteDenial(final Path directory) {
            this.directory = directory;
        }

        @Override
        public final void verifyEffective() throws IOException {
            final Path probe = directory.resolve(DENIAL_PROBE_PREFIX + UUID.randomUUID());
            try {
                Files.createFile(probe);
            } catch (IOException denied) {
                return;
            }
            Files.deleteIfExists(probe);
            throw new IOException(
                    "the write denial is not effective; this host bypasses directory permissions: " + directory);
        }
    }

    private static final class PosixWriteDenial extends RestoringWriteDenial {

        private final PosixFileAttributeView view;
        private final Set<PosixFilePermission> original;

        PosixWriteDenial(final Path directory, final PosixFileAttributeView view) throws IOException {
            super(directory);
            this.view = view;
            this.original = Set.copyOf(view.readAttributes().permissions());
            view.setPermissions(DIRECTORY_WRITE_DENIED);
        }

        @Override
        public void close() throws IOException {
            view.setPermissions(original);
        }
    }

    private static final class AclWriteDenial extends RestoringWriteDenial {

        private final AclFileAttributeView view;
        private final List<AclEntry> original;

        AclWriteDenial(final Path directory, final AclFileAttributeView view, final UserPrincipal owner)
                throws IOException {
            super(directory);
            this.view = view;
            this.original = new ArrayList<>(view.getAcl());
            final List<AclEntry> denied = new ArrayList<>(original.size() + 1);
            denied.add(AclEntry.newBuilder()
                    .setType(AclEntryType.DENY)
                    .setPrincipal(owner)
                    .setPermissions(ACL_WRITE_DENIED)
                    .build());
            denied.addAll(original);
            view.setAcl(denied);
        }

        @Override
        public void close() throws IOException {
            view.setAcl(original);
        }
    }
}
