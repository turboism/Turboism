package dev.turboism.plugin.core;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Runtime-owned update handoff consumed by the built-in core plugin.
 *
 * <p>This is deliberately not part of the public SDK. The runtime owns network
 * transport, validation, persistence, scheduling, and cancellation; core only
 * receives immutable presentation state and invokes the explicitly user-driven
 * operations.</p>
 */
public interface CoreUpdateService extends AutoCloseable {

    String MANUAL_CHECK_ACTION_ID = "turboism.core.update.check";
    String DOWNLOAD_ACTION_ID = "turboism.core.update.download";

    enum Status {
        IDLE,
        CHECKING,
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        UNAVAILABLE,
        DISABLED,
        CLOSED
    }

    /** Immutable state safe to hand to UI code. */
    record Snapshot(
        Status status,
        String localVersion,
        Optional<String> availableVersion,
        OptionalLong availableBuildNumber,
        boolean userInitiated,
        boolean reminder
    ) {
        public Snapshot {
            status = Objects.requireNonNull(status, "status");
            localVersion = Objects.requireNonNull(localVersion, "localVersion");
            availableVersion = Objects.requireNonNull(availableVersion, "availableVersion");
            availableBuildNumber = Objects.requireNonNull(availableBuildNumber, "availableBuildNumber");
            if (availableVersion.isPresent() && availableVersion.orElseThrow().isBlank()) {
                throw new IllegalArgumentException("availableVersion must not be blank");
            }
            if (status != Status.UPDATE_AVAILABLE
                && (availableVersion.isPresent() || availableBuildNumber.isPresent())) {
                throw new IllegalArgumentException(
                    "an available candidate is only valid for UPDATE_AVAILABLE"
                );
            }
            if (availableBuildNumber.isPresent() && availableBuildNumber.getAsLong() <= 0) {
                throw new IllegalArgumentException("availableBuildNumber must be positive");
            }
            if (reminder && status != Status.UPDATE_AVAILABLE) {
                throw new IllegalArgumentException("reminder requires UPDATE_AVAILABLE");
            }
        }

        /** Creates an idle snapshot for a local framework identity. */
        public static Snapshot idle(final String localVersion) {
            return new Snapshot(
                Status.IDLE, localVersion, Optional.empty(), OptionalLong.empty(), false, false
            );
        }

        /** Returns the advertised candidate identity for display, e.g. {@code 0.43.11 (Build 5)}. */
        public Optional<String> availableIdentity() {
            if (availableVersion.isEmpty()) return Optional.empty();
            if (availableBuildNumber.isEmpty()) return availableVersion;
            return Optional.of(
                availableVersion.orElseThrow() + " (Build " + availableBuildNumber.getAsLong() + ")"
            );
        }
    }

    record Preferences(boolean automaticChecksEnabled) {
    }

    /** Result of a preference write; false never claims that the value was saved. */
    record PreferenceSaveResult(boolean saved, String message) {
        public PreferenceSaveResult {
            message = Objects.requireNonNull(message, "message");
        }

        /** Returns a successful preference-save result. */
        public static PreferenceSaveResult success() {
            return new PreferenceSaveResult(true, "Automatic update checks saved.");
        }

        /** Returns a failed preference-save result with an actionable message. */
        public static PreferenceSaveResult failed(final String message) {
            return new PreferenceSaveResult(false, Objects.requireNonNull(message, "message"));
        }
    }

    /** Whether the runtime supplied a real update implementation. */
    boolean available();

    Snapshot snapshot();

    Preferences preferences();

    /** Starts the delayed automatic-check lifecycle after UI contributions are ready. */
    void start();

    /** Starts or joins one non-blocking user-requested check. */
    CompletionStage<Snapshot> checkManual();

    /** Persists the independent Turboism automatic-check preference. */
    PreferenceSaveResult savePreferences(Preferences preferences);

    /** Registers a listener owned by the caller's disposable scope. */
    Registration subscribe(Consumer<Snapshot> listener);

    @Override
    void close();

    /** Source-compatible alias for callers that prefer verb-first terminology. */
    default CompletionStage<Snapshot> manualCheck() {
        return checkManual();
    }

    /** Source-compatible convenience for the single preference exposed by this seam. */
    default boolean automaticChecksEnabled() {
        return preferences().automaticChecksEnabled();
    }

    /** Source-compatible convenience for toggling the independent preference. */
    default PreferenceSaveResult saveAutomaticChecksEnabled(final boolean enabled) {
        return savePreferences(new Preferences(enabled));
    }

    /** Compatibility implementation for existing core-only tests and constructors. */
    static CoreUpdateService unavailable() {
        return Unavailable.INSTANCE;
    }

    final class Unavailable implements CoreUpdateService {
        private static final Unavailable INSTANCE = new Unavailable();
        private static final Snapshot SNAPSHOT = new Snapshot(
            Status.UNAVAILABLE, "unknown", Optional.empty(), OptionalLong.empty(), false, false
        );
        private static final Preferences PREFERENCES = new Preferences(true);

        private Unavailable() {
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Snapshot snapshot() {
            return SNAPSHOT;
        }

        @Override
        public Preferences preferences() {
            return PREFERENCES;
        }

        @Override
        public void start() {
        }

        @Override
        public CompletionStage<Snapshot> checkManual() {
            return CompletableFuture.completedFuture(SNAPSHOT);
        }

        @Override
        public PreferenceSaveResult savePreferences(final Preferences preferences) {
            Objects.requireNonNull(preferences, "preferences");
            return PreferenceSaveResult.failed("Automatic update checks are unavailable.");
        }

        @Override
        public Registration subscribe(final Consumer<Snapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return () -> { };
        }

        @Override
        public void close() {
        }
    }
}
