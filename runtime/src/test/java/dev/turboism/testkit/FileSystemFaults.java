package dev.turboism.testkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Real filesystem fault injection for suites that must prove a fail-closed persistence guard without
 * relying on symbolic links or POSIX permissions.
 *
 * <p>Two faults are offered. {@link #createDirectoryReparsePoint} makes one directory resolve to
 * another so a guard can be proven never to write through a redirected path. {@link
 * #denyDirectoryWrites} makes a directory reject new children so a failed publication can be proven
 * to leave the previous bytes intact.
 *
 * <p>Both faults are reversible and leave the machine as they found it. The reparse point is deleted
 * without touching its target, and the write denial restores the exact permission state it found.
 * Neither fault removes an entry the current call did not create.
 *
 * <p>The faults are not universal. Symbolic links need {@code SeCreateSymbolicLinkPrivilege}, which
 * Windows grants only to an elevated account or under Developer Mode; elsewhere this type falls back
 * to a Windows directory junction and reports that. A write denial needs a permission model that
 * applies to the current account, so a POSIX root account cannot use it.
 */
public final class FileSystemFaults {

    private static final String WINDOWS_OS_NAME_PREFIX = "windows";
    private static final String COMMAND_INTERPRETER = "cmd.exe";
    private static final String AUTO_RUN_SWITCH = "/d";
    private static final String STRIP_OUTER_QUOTES_SWITCH = "/s";
    private static final String COMMAND_SWITCH = "/c";
    private static final String MKLINK_COMMAND = "mklink";
    private static final String JUNCTION_SWITCH = "/J";
    private static final String JUNCTION_OUTPUT_FILE_PREFIX = "turboism-junction-";
    private static final String JUNCTION_OUTPUT_FILE_SUFFIX = ".log";
    private static final long JUNCTION_TIMEOUT_SECONDS = 30L;
    private static final long PROCESS_STOP_TIMEOUT_SECONDS = 5L;
    private static final long MAX_JUNCTION_OUTPUT_BYTES = 4096L;

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
     * <p>This call creates a symbolic link when the host grants the privilege. A Windows host without
     * it gets a directory junction instead: a real reparse point that resolves the same way but is not
     * a symbolic link. The returned {@link ReparsePointKind} names the contract this host established.
     *
     * <p>This call refuses an occupied path instead of removing it, and it removes only the entry it
     * created itself.
     *
     * @param link the reparse point path to create; it must not exist yet
     * @param target the existing directory it must resolve to
     * @return the created reparse point and its kind
     * @throws IOException when the path is occupied, or no reparse point can be established
     */
    public static ReparsePoint createDirectoryReparsePoint(final Path link, final Path target) throws IOException {
        final Path absoluteLink = link.toAbsolutePath().normalize();
        final Path absoluteTarget = target.toAbsolutePath().normalize();
        if (!Files.isDirectory(absoluteTarget)) {
            throw new IOException("a reparse point target must be an existing directory: " + absoluteTarget);
        }
        final Path parent = absoluteLink.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("a reparse point path must have an existing parent directory: " + absoluteLink);
        }
        if (Files.exists(absoluteLink, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("reparse point path is occupied by an entry this call does not own: " + absoluteLink);
        }
        if (createSymbolicLink(absoluteLink, absoluteTarget)) {
            return new ReparsePoint(absoluteLink, absoluteTarget, ReparsePointKind.SYMBOLIC_LINK);
        }
        if (!isWindows()) {
            throw new IOException("symbolic links are unavailable: " + absoluteLink);
        }
        createWindowsJunction(absoluteLink, absoluteTarget);
        return new ReparsePoint(absoluteLink, absoluteTarget, ReparsePointKind.DIRECTORY_JUNCTION);
    }

    /**
     * Creates the symbolic link at a path the caller has just verified to be free.
     *
     * @return false when the host refuses symbolic links, so the caller may try a directory junction
     * @throws IOException when another writer took the path first, or the created link is unusable
     */
    private static boolean createSymbolicLink(final Path link, final Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (FileAlreadyExistsException occupied) {
            // Another writer owns this path; removing it would destroy state this call never created.
            throw new IOException("reparse point path was taken by another writer: " + link, occupied);
        } catch (UnsupportedOperationException | FileSystemException notPermitted) {
            return false;
        }
        if (Files.isSymbolicLink(link)) {
            return true;
        }
        // This call created the entry, so removing it cannot lose another writer's data.
        Files.delete(link);
        throw new IOException("the created link is not reported as a symbolic link: " + link);
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
     * owner that is removed again on close. This call proves the denial works before it hands the
     * handle out, so a host where the fault cannot be established reports that fact instead of
     * letting a test pass on a write that was never blocked.
     *
     * <p>A POSIX root account bypasses mode bits and cannot use this fault; that host gets an
     * {@link IOException} naming the reason, not a silently ineffective denial.
     *
     * @param directory the existing directory that must stop accepting new entries
     * @return the reversible denial; closing it restores the permission state found on entry
     * @throws IOException when the directory exposes no permission model, or when the denial is not
     *     effective for the current account
     */
    public static WriteDenial denyDirectoryWrites(final Path directory) throws IOException {
        final Path target = directory.toAbsolutePath().normalize();
        final PosixFileAttributeView posix =
                Files.getFileAttributeView(target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        final WriteDenial denial = posix != null ? new PosixWriteDenial(target, posix) : aclWriteDenial(target);
        try {
            denial.verifyEffective();
        } catch (IOException | RuntimeException | Error notEstablished) {
            closeRestoring(denial, notEstablished);
            throw notEstablished;
        }
        return denial;
    }

    /**
     * Restores a denial this call owns after the handoff failed, keeping the primary error intact so
     * the caller sees why the fault could not be established.
     */
    private static void closeRestoring(final WriteDenial denial, final Throwable primary) {
        try {
            denial.close();
        } catch (IOException | RuntimeException cleanup) {
            primary.addSuppressed(cleanup);
        }
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
        final Path output =
                Files.createTempFile(link.getParent(), JUNCTION_OUTPUT_FILE_PREFIX, JUNCTION_OUTPUT_FILE_SUFFIX);
        Process process = null;
        try {
            process = new ProcessBuilder(
                            COMMAND_INTERPRETER,
                            AUTO_RUN_SWITCH,
                            STRIP_OUTER_QUOTES_SWITCH,
                            COMMAND_SWITCH,
                            junctionCommand(link, target))
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            final boolean finished;
            try {
                finished = process.waitFor(JUNCTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while creating a directory junction: " + link, interrupted);
            }
            if (!finished) {
                throw new IOException("the directory junction command did not finish within " + JUNCTION_TIMEOUT_SECONDS
                        + " seconds: " + link);
            }
            if (process.exitValue() != 0 || !Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("no reparse point can be created at " + link + ": " + capturedOutput(output));
            }
        } catch (IOException failure) {
            stop(process);
            removeJunctionThisCallCreated(link, target, failure);
            throw failure;
        } finally {
            stop(process);
            deleteOutputFileQuietly(output);
        }
    }

    /**
     * Deletes the captured output of a command this call ran. The file lives in the caller's own
     * temporary directory, so a leftover cannot affect an assertion, while a delete failure must never
     * replace the failure that explains why the junction was not created.
     */
    private static void deleteOutputFileQuietly(final Path output) {
        try {
            Files.deleteIfExists(output);
        } catch (IOException ignored) {
            // The primary failure carries the reason this call did not produce a reparse point.
        }
    }

    /**
     * Builds one command line whose paths the command interpreter reads as data, so a space or a
     * shell metacharacter in either path cannot change the meaning of the command.
     */
    private static String junctionCommand(final Path link, final Path target) {
        return MKLINK_COMMAND + " " + JUNCTION_SWITCH + " " + quoteForCommandInterpreter(link) + " "
                + quoteForCommandInterpreter(target);
    }

    private static String quoteForCommandInterpreter(final Path path) {
        return "\"" + path + "\"";
    }

    /**
     * Reads the captured command output with a size bound, so a runaway child cannot exhaust memory.
     */
    private static String capturedOutput(final Path output) throws IOException {
        if (Files.size(output) > MAX_JUNCTION_OUTPUT_BYTES) {
            return "command output exceeded " + MAX_JUNCTION_OUTPUT_BYTES + " bytes";
        }
        return new String(Files.readAllBytes(output), commandOutputCharset()).trim();
    }

    /** The console code page of the command interpreter, which differs from the JVM default. */
    private static Charset commandOutputCharset() {
        final String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding == null || nativeEncoding.isBlank()) {
            return Charset.defaultCharset();
        }
        try {
            return Charset.forName(nativeEncoding);
        } catch (RuntimeException unsupported) {
            return Charset.defaultCharset();
        }
    }

    /**
     * Removes a junction only when it resolves to the requested target, because that resolution
     * proves this call created the entry.
     */
    private static void removeJunctionThisCallCreated(final Path link, final Path target, final Throwable primary) {
        try {
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)
                    && link.toRealPath().equals(target.toRealPath())) {
                Files.delete(link);
            }
        } catch (IOException cleanup) {
            primary.addSuppressed(cleanup);
        }
    }

    /** Ends a child process this call started, so a timeout or an interrupt leaves nothing running. */
    private static void stop(final Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroyForcibly();
        try {
            process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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
            final IOException ineffective = new IOException(
                    "the write denial is not effective; this account bypasses directory permissions: " + directory);
            try {
                Files.deleteIfExists(probe);
            } catch (IOException cleanup) {
                ineffective.addSuppressed(cleanup);
            }
            throw ineffective;
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
