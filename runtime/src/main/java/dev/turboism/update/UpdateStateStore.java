package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Atomic persistence for the automatic-check attempt and validated cached result. */
public final class UpdateStateStore {
    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "update-state.json";

    private final Path home;
    private final Path path;
    private final Object lock;
    private final Consumer<String> diagnostic;
    private final UpdateDiscoveryParser parser = new UpdateDiscoveryParser();

    public UpdateStateStore(final Path home) {
        this(home, ignored -> { });
    }

    public UpdateStateStore(final Path home, final Consumer<String> diagnostic) {
        this.home = UpdateFileSupport.normalizeHome(home);
        this.path = this.home.resolve(FILE_NAME).normalize();
        if (!path.startsWith(this.home)) throw new IllegalArgumentException("state path escaped home");
        this.lock = UpdateFileSupport.lockFor(path);
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

/** Returns the isolated update-state file path. */
    public Path path() {
        return path;
    }

/** Reads cached state, recovering to an empty state when invalid or absent. */
    public State read() {
        synchronized (lock) {
            try {
                final ObjectNode root = UpdateFileSupport.readObject(home, path);
                if (root == null) return State.empty();
                final JsonNode schema = root.get("schemaVersion");
                if (schema == null || !schema.isIntegralNumber() || !schema.canConvertToInt()
                    || schema.intValue() != SCHEMA_VERSION) {
                    throw new IOException("state schema invalid");
                }
                final Optional<Instant> attempt = optionalInstant(root.get("lastAutomaticAttempt"));
                final Optional<String> etag = optionalEtag(root.get("etag"));
                final JsonNode cached = root.get("cachedResult");
                final Optional<UpdateDiscovery> cachedResult = cached == null || cached.isNull()
                    ? Optional.empty()
                    : Optional.of(parser.parseNode(cached));
                return new State(attempt, cachedResult, etag);
            } catch (RuntimeException | IOException failure) {
                UpdateFileSupport.report(diagnostic, "UPDATE_STATE_CORRUPT");
                return State.empty();
            }
        }
    }

/** Atomically saves the automatic-check attempt and validated cache. */
    public SaveResult save(final State state) {
        Objects.requireNonNull(state, "state");
        synchronized (lock) {
            final ObjectNode root = UpdateFileSupport.JSON.createObjectNode();
            root.put("schemaVersion", SCHEMA_VERSION);
            state.lastAutomaticAttempt().ifPresent(value -> root.put("lastAutomaticAttempt", value.toString()));
            state.etag().ifPresent(value -> root.put("etag", value));
            state.cachedResult().ifPresent(value -> root.set("cachedResult", value.toJson(UpdateFileSupport.JSON)));
            try {
                UpdateFileSupport.writeAtomic(home, path, root);
                return SaveResult.success();
            } catch (RuntimeException | IOException failure) {
                UpdateFileSupport.report(diagnostic, "UPDATE_STATE_WRITE_FAILED");
                return SaveResult.failed("Automatic update state could not be saved.");
            }
        }
    }

    private static Optional<Instant> optionalInstant(final JsonNode value) throws IOException {
        if (value == null || value.isNull()) return Optional.empty();
        if (!value.isTextual() || value.textValue().isBlank()) throw new IOException("invalid state timestamp");
        try {
            return Optional.of(Instant.parse(value.textValue()));
        } catch (DateTimeParseException invalid) {
            throw new IOException("invalid state timestamp", invalid);
        }
    }

    private static Optional<String> optionalEtag(final JsonNode value) throws IOException {
        if (value == null || value.isNull()) return Optional.empty();
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 512
            || value.textValue().indexOf('\r') >= 0 || value.textValue().indexOf('\n') >= 0) {
            throw new IOException("invalid state etag");
        }
        return Optional.of(value.textValue());
    }

    /** Immutable persisted automatic-check state and validated cache. */
    public record State(
        Optional<Instant> lastAutomaticAttempt,
        Optional<UpdateDiscovery> cachedResult,
        Optional<String> etag
    ) {
        /** Validates the persisted state components. */
        public State {
            lastAutomaticAttempt = Objects.requireNonNull(lastAutomaticAttempt, "lastAutomaticAttempt");
            cachedResult = Objects.requireNonNull(cachedResult, "cachedResult");
            etag = Objects.requireNonNull(etag, "etag");
            if (etag.isPresent() && (etag.orElseThrow().isBlank() || etag.orElseThrow().length() > 512
                || etag.orElseThrow().indexOf('\r') >= 0 || etag.orElseThrow().indexOf('\n') >= 0)) {
                throw new IllegalArgumentException("etag is invalid");
            }
        }

        /** Returns a state with no prior attempt, cache, or validator. */
        public static State empty() {
            return new State(Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /** Result of persisting automatic-update state. */
    public record SaveResult(boolean saved, String message) {
        public SaveResult {
            message = Objects.requireNonNull(message, "message");
        }

        static SaveResult success() {
            return new SaveResult(true, "Update state saved.");
        }

        static SaveResult failed(final String message) {
            return new SaveResult(false, message);
        }
    }
}
