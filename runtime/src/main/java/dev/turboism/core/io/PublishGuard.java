package dev.turboism.core.io;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Caller-supplied check that runs between "the staged bytes are durable" and "the staged bytes
 * are visible at the target".
 *
 * <p>Confinement — which paths a caller may name, whether the parent directory is still the
 * directory it was when the write started, whether the staged bytes still hash to the value the
 * caller verified — is policy and stays with the caller. This interface is the whole of the
 * module's participation in that policy: one hook before the rename and one after it, so a
 * caller can observe the gap between publication and the directory force.
 *
 * <p>An exception thrown by {@link #beforePublish} aborts the publication unchanged: the staged
 * file is deleted, the target keeps its previous bytes, and the caller's exception type reaches the
 * caller as thrown. An exception thrown by {@link #afterPublish} propagates the same way, but the
 * new bytes are already published because the rename has happened.
 */
@FunctionalInterface
public interface PublishGuard {

    /** The guard that always passes. */
    PublishGuard NONE = (staged, target) -> {};

    /**
     * Vets the staged file. Runs after the payload is written and forced, before the rename.
     *
     * @param staged the fully written sibling temporary file
     * @param target the path that is about to be replaced
     * @throws IOException when the publication must be abandoned; the target is untouched
     */
    void beforePublish(Path staged, Path target) throws IOException;

    /**
     * Post-condition of a completed publication. Runs after the rename, before the directory
     * force requested by {@link WriteOptions.Durability#FILE_AND_DIRECTORY}.
     *
     * @param staged the sibling temporary file, which no longer exists
     * @param target the path that now holds the new bytes
     * @throws IOException when the caller's post-condition does not hold; the new bytes stay
     *     published because the rename has already happened
     */
    default void afterPublish(Path staged, Path target) throws IOException {}
}
