package dev.turboism.userfile;

import dev.turboism.sdk.ui.UserFileRequest;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Runtime-only chooser seam for the opaque user-file flow. */
@FunctionalInterface
public interface UserFileGrantSource {

    /**
     * Runs one user-file chooser.
     *
     * @param request the chooser configuration
     * @return a stage completing with the user's decision; the stage completes normally even
     *         for {@link Canceled} and {@link Unavailable} outcomes
     */
    CompletionStage<Decision> request(UserFileRequest request);

    /**
     * @param path the path every request resolves to
     * @return a source that always completes with a {@link Selected} decision for {@code path}
     */
    static UserFileGrantSource fixedSelection(final Path path) {
        final Decision decision = Decision.selected(path);
        return ignored -> CompletableFuture.completedFuture(decision);
    }

    /**
     * @return a source that always completes with a {@link Canceled} decision
     */
    static UserFileGrantSource canceled() {
        return ignored -> CompletableFuture.completedFuture(Decision.canceled());
    }

    /**
     * @return a source that always completes with an {@link Unavailable} decision
     */
    static UserFileGrantSource unavailable() {
        return ignored -> CompletableFuture.completedFuture(Decision.unavailable());
    }

    /** Terminal outcome of one chooser request. */
    sealed interface Decision permits Selected, Canceled, Unavailable {

        /**
         * @param path the chosen path, non-null
         * @return a selected decision
         * @throws NullPointerException if {@code path} is null
         */
        static Decision selected(final Path path) {
            return new Selected(Objects.requireNonNull(path, "path"));
        }

        /**
         * @return the canceled decision — the user dismissed the chooser
         */
        static Decision canceled() {
            return Canceled.INSTANCE;
        }

        /**
         * @return the unavailable decision — no chooser exists on this host
         */
        static Decision unavailable() {
            return Unavailable.INSTANCE;
        }
    }

    /** The user picked {@code path}. */
    record Selected(Path path) implements Decision {
        public Selected {
            path = Objects.requireNonNull(path, "path");
        }
    }

    /** The user dismissed the chooser without selecting. */
    enum Canceled implements Decision {
        INSTANCE
    }

    /** No chooser exists on this host. */
    enum Unavailable implements Decision {
        INSTANCE
    }
}
