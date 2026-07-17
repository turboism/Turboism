package dev.turboism.userfile;

import dev.turboism.sdk.ui.UserFileRequest;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Runtime-only chooser seam for the opaque user-file flow. */
@FunctionalInterface
public interface UserFileGrantSource {

    CompletionStage<Decision> request(UserFileRequest request);

    static UserFileGrantSource fixedSelection(final Path path) {
        final Decision decision = Decision.selected(path);
        return ignored -> CompletableFuture.completedFuture(decision);
    }

    static UserFileGrantSource canceled() {
        return ignored -> CompletableFuture.completedFuture(Decision.canceled());
    }

    static UserFileGrantSource unavailable() {
        return ignored -> CompletableFuture.completedFuture(Decision.unavailable());
    }

    sealed interface Decision permits Selected, Canceled, Unavailable {

        static Decision selected(final Path path) {
            return new Selected(Objects.requireNonNull(path, "path"));
        }

        static Decision canceled() {
            return Canceled.INSTANCE;
        }

        static Decision unavailable() {
            return Unavailable.INSTANCE;
        }
    }

    record Selected(Path path) implements Decision {
        public Selected {
            path = Objects.requireNonNull(path, "path");
        }
    }

    enum Canceled implements Decision {
        INSTANCE
    }

    enum Unavailable implements Decision {
        INSTANCE
    }
}
