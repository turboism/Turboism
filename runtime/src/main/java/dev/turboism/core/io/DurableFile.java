package dev.turboism.core.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One target path plus the policy under which its bytes are published.
 *
 * <p>Immutable and reusable; it holds no state between calls. Publication is always
 * "stage a sibling, make it durable, rename it over the target", so on return the target holds
 * either the exact new bytes or the exact old bytes, never a mixture and never a truncated file.
 * The order inside one call is:
 *
 * <ol>
 *   <li>create an empty owner-only-or-inherited temporary file in the target's own directory,
 *   <li>write the payload and force the file,
 *   <li>{@link PublishGuard#beforePublish},
 *   <li>rename over the target ({@code ATOMIC_MOVE} when the file system supports it),
 *   <li>{@link PublishGuard#afterPublish},
 *   <li>force the directory when {@link WriteOptions.Durability#FILE_AND_DIRECTORY} was requested.
 * </ol>
 *
 * <p>The temporary file is a sibling of the target on purpose: it is the same volume, so the
 * rename is a rename and not a copy, and it inherits the same directory permission model. It is
 * deleted on every failure path, which leaves the previous target bytes untouched.
 *
 * <p>Confinement — which paths a caller may name, whether a parent directory is still the one the
 * caller vetted, whether the staged bytes still hash to the verified value — is the caller's
 * policy and is expressed through {@link PublishGuard}. This type never decides whether a path is
 * allowed, and its parent directory must already exist.
 */
public final class DurableFile {

    private final Path target;
    private final WriteOptions options;
    private final PublishGuard guard;
    private final boolean createOnce;

    private DurableFile(
            final Path target, final WriteOptions options, final PublishGuard guard, final boolean createOnce) {
        this.target = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        this.options = Objects.requireNonNull(options, "options");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.createOnce = createOnce;
    }

    /**
     * A target that may be replaced.
     *
     * @param target the file to publish at; its parent directory must exist
     * @return a handle using {@link WriteOptions#DEFAULT} and {@link PublishGuard#NONE}
     * @throws NullPointerException if {@code target} is {@code null}
     */
    public static DurableFile at(final Path target) {
        return new DurableFile(target, WriteOptions.DEFAULT, PublishGuard.NONE, false);
    }

    /**
     * An immutable target: publishing fails with {@code ALREADY_EXISTS} instead of replacing it.
     *
     * @param target the file to publish at; it must not exist yet
     * @return a handle using {@link WriteOptions#DEFAULT} and {@link PublishGuard#NONE}
     * @throws NullPointerException if {@code target} is {@code null}
     */
    public static DurableFile createOnce(final Path target) {
        return new DurableFile(target, WriteOptions.DEFAULT, PublishGuard.NONE, true);
    }

    /**
     * @param newOptions the policy to publish under
     * @return a copy of this handle with only the options changed
     * @throws NullPointerException if {@code newOptions} is {@code null}
     */
    public DurableFile withOptions(final WriteOptions newOptions) {
        return new DurableFile(target, newOptions, guard, createOnce);
    }

    /**
     * @param newGuard the check that must pass between "the bytes are durable" and "the bytes are
     *     visible"
     * @return a copy of this handle with the guard installed
     * @throws NullPointerException if {@code newGuard} is {@code null}
     */
    public DurableFile guardedBy(final PublishGuard newGuard) {
        return new DurableFile(target, options, newGuard, createOnce);
    }

    /**
     * Publishes {@code bytes} at the target.
     *
     * <p>See the class documentation for the order. On any failure the temporary file is deleted
     * and the previous target bytes are untouched.
     *
     * @param bytes the exact bytes to publish
     * @throws DurableWriteException with a closed {@code Failure} set; never a bare JDK type
     * @throws NullPointerException if {@code bytes} is {@code null}
     */
    public void replace(final byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        publish(staged -> DurableFiles.writeBytes(staged, bytes, false), false);
    }

    /**
     * As {@link #replace} but checks the caller's interrupt flag between chunks and again before
     * publishing, so a cancelled task abandons the write instead of finishing it.
     *
     * <p>The interrupt status is left set: this method observes the caller's cancellation instead
     * of consuming it.
     *
     * @param bytes the exact bytes to publish
     * @throws DurableWriteException with a closed {@code Failure} set; never a bare JDK type
     * @throws InterruptedException when the calling thread was interrupted; the temporary file is
     *     deleted and the target is untouched
     * @throws NullPointerException if {@code bytes} is {@code null}
     */
    public void replaceCancellable(final byte[] bytes) throws IOException, InterruptedException {
        Objects.requireNonNull(bytes, "bytes");
        try {
            publish(staged -> DurableFiles.writeBytes(staged, bytes, true), true);
        } catch (DurableFiles.InterruptPending interrupted) {
            throw new InterruptedException("durable publication was cancelled before it replaced " + target);
        }
    }

    /**
     * Publishes a payload the caller streams into the write. The module owns the sink, forces the
     * file and closes it; the producer must not close the stream itself.
     *
     * @param producer writes the exact bytes to publish
     * @throws DurableWriteException with a closed {@code Failure} set; never a bare JDK type
     * @throws NullPointerException if {@code producer} is {@code null}
     */
    public void replaceStreaming(final OutputStreamConsumer producer) throws IOException {
        Objects.requireNonNull(producer, "producer");
        publish(staged -> DurableFiles.writeStreaming(staged, producer), false);
    }

    /**
     * Publishes a temporary file another component produced (an encoder output, an unpacked
     * target). The file is forced before it is renamed and is deleted when publication fails.
     *
     * <p>The staged file must live on the target's file system for
     * {@link WriteOptions.Atomicity#REQUIRED} to be satisfiable; a staged file elsewhere reports
     * {@code ATOMIC_REPLACE_UNAVAILABLE} rather than turning into a copy nobody asked for.
     *
     * @param staged the file to publish; it is consumed by this call
     * @throws DurableWriteException with a closed {@code Failure} set; never a bare JDK type
     * @throws NullPointerException if {@code staged} is {@code null}
     */
    public void adopt(final Path staged) throws IOException {
        Objects.requireNonNull(staged, "staged");
        final Path parent = DurableFiles.requireParent(target);
        if (createOnce) {
            DurableFiles.requireAbsent(target);
        }
        boolean published = false;
        try {
            DurableFiles.forceFile(staged);
            guard.beforePublish(staged, target);
            DurableFiles.move(staged, target, options, createOnce);
            published = true;
            guard.afterPublish(staged, target);
            forceDirectoryWhenRequested(parent);
        } finally {
            if (!published) {
                DurableFiles.deleteQuietly(staged);
            }
        }
    }

    /**
     * Appends {@code bytes} to the target and forces the file. No staging, no rename, no atomicity
     * claim: this is the shape a download part-file or an append-only log needs, not a document
     * publication.
     *
     * @param bytes the exact bytes to append
     * @throws DurableWriteException with {@code IO_FAILURE} for any filesystem problem
     * @throws NullPointerException if {@code bytes} is {@code null}
     */
    public void append(final byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        DurableFiles.append(target, bytes);
    }

    private void publish(final StagedContent content, final boolean cancellable) throws IOException {
        final Path parent = DurableFiles.requireParent(target);
        if (createOnce) {
            DurableFiles.requireAbsent(target);
        }
        final Path staged = DurableFiles.createStaged(target, options.permissions());
        boolean published = false;
        try {
            content.writeTo(staged);
            if (cancellable) {
                DurableFiles.checkInterrupted();
            }
            // The guard runs while the previous target bytes are still visible: a rejected
            // publication never exposes the staged file.
            guard.beforePublish(staged, target);
            DurableFiles.move(staged, target, options, createOnce);
            published = true;
            guard.afterPublish(staged, target);
            forceDirectoryWhenRequested(parent);
        } finally {
            if (!published) {
                DurableFiles.deleteQuietly(staged);
            }
        }
    }

    private void forceDirectoryWhenRequested(final Path parent) throws IOException {
        if (options.durability() == WriteOptions.Durability.FILE_AND_DIRECTORY) {
            DurableFiles.forceDirectory(parent);
        }
    }

    /** Producer of the payload; the module supplies the sink and forces it after the producer returns. */
    @FunctionalInterface
    public interface OutputStreamConsumer {

        /**
         * @param out the staged file's sink; do not close it, the module forces and closes it
         * @throws IOException when the payload cannot be produced anymore
         */
        void accept(OutputStream out) throws IOException;
    }

    /** Where a {@link DurableFile} gets its payload from once the staged file exists. */
    @FunctionalInterface
    private interface StagedContent {

        /** Writes the payload into {@code staged} and forces it. */
        void writeTo(Path staged) throws IOException;
    }
}
