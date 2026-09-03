package dev.turboism.sdk.cubism.transaction;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable terminal result of a synchronous authoring transaction callback.
 *
 * @param outcome typed terminal outcome
 * @param value callback value when the callback completed normally; a normal {@code null} value is
 *     represented as an empty optional
 * @param receipt transaction and history evidence when a Runtime scope was opened or evaluated
 * @param diagnosticId stable diagnostic identity for failures that require follow-up
 * @param <T> callback value type
 */
public record AuthoringTransactionResult<T>(
    AuthoringTransactionOutcome outcome,
    Optional<T> value,
    Optional<AuthoringTransactionReceipt> receipt,
    Optional<String> diagnosticId
) {

    /** Validates cross-field result invariants. */
    public AuthoringTransactionResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        value = Objects.requireNonNull(value, "value");
        receipt = Objects.requireNonNull(receipt, "receipt");
        diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId")
            .map(AuthoringTransactionResult::requireDiagnosticId);

        final boolean successful = outcome == AuthoringTransactionOutcome.COMMITTED
            || outcome == AuthoringTransactionOutcome.NO_CHANGE;
        if (successful && receipt.isEmpty()) {
            throw new IllegalArgumentException("successful authoring transaction requires a receipt");
        }
        if (!successful && value.isPresent()) {
            throw new IllegalArgumentException("failed authoring transaction must not expose a value");
        }
        if (outcome == AuthoringTransactionOutcome.COMMITTED
            && receipt.orElseThrow().historyEntryId().isEmpty()) {
            throw new IllegalArgumentException(
                "committed authoring transaction requires a history entry identity"
            );
        }
        if (outcome == AuthoringTransactionOutcome.NO_CHANGE
            && receipt.orElseThrow().historyEntryId().isPresent()) {
            throw new IllegalArgumentException(
                "no-change authoring transaction must not identify a history entry"
            );
        }
        if (outcome == AuthoringTransactionOutcome.UNAVAILABLE && receipt.isPresent()) {
            throw new IllegalArgumentException(
                "unavailable authoring transaction must not expose a receipt"
            );
        }
        if ((outcome == AuthoringTransactionOutcome.UNAVAILABLE
            || outcome == AuthoringTransactionOutcome.RECOVERY_FAILED)
            && diagnosticId.isEmpty()) {
            throw new IllegalArgumentException(outcome + " requires a diagnostic identity");
        }
        if (successful && diagnosticId.isPresent()) {
            throw new IllegalArgumentException(
                "successful authoring transaction must not expose a failure diagnostic"
            );
        }
    }

    /**
     * Creates a successful changed result.
     *
     * @param value callback value, possibly {@code null}
     * @param receipt committed transaction receipt with a history entry identity
     * @param <T> callback value type
     * @return committed result
     */
    public static <T> AuthoringTransactionResult<T> committed(
        final T value,
        final AuthoringTransactionReceipt receipt
    ) {
        return successful(AuthoringTransactionOutcome.COMMITTED, value, receipt);
    }

    /**
     * Creates a successful read-only or all-no-op result.
     *
     * @param value callback value, possibly {@code null}
     * @param receipt no-change transaction receipt
     * @param <T> callback value type
     * @return no-change result
     */
    public static <T> AuthoringTransactionResult<T> noChange(
        final T value,
        final AuthoringTransactionReceipt receipt
    ) {
        return successful(AuthoringTransactionOutcome.NO_CHANGE, value, receipt);
    }

    /**
     * Creates a result proving that callback failure was fully rolled back.
     *
     * @param receipt recovery evidence
     * @param diagnosticId optional diagnostic identity for the callback failure
     * @param <T> callback value type
     * @return rolled-back result
     */
    public static <T> AuthoringTransactionResult<T> rolledBack(
        final AuthoringTransactionReceipt receipt,
        final Optional<String> diagnosticId
    ) {
        return failed(
            AuthoringTransactionOutcome.ROLLED_BACK,
            Optional.of(Objects.requireNonNull(receipt, "receipt")),
            diagnosticId
        );
    }

    /**
     * Creates a stale-precondition rejection.
     *
     * @param receipt available preflight evidence, when captured
     * @param diagnosticId optional diagnostic identity
     * @param <T> callback value type
     * @return stale rejection
     */
    public static <T> AuthoringTransactionResult<T> rejectedStale(
        final Optional<AuthoringTransactionReceipt> receipt,
        final Optional<String> diagnosticId
    ) {
        return failed(AuthoringTransactionOutcome.REJECTED_STALE, receipt, diagnosticId);
    }

    /**
     * Creates a scope or nesting rejection.
     *
     * @param receipt available preflight evidence, when captured
     * @param diagnosticId optional diagnostic identity
     * @param <T> callback value type
     * @return scope rejection
     */
    public static <T> AuthoringTransactionResult<T> rejectedScope(
        final Optional<AuthoringTransactionReceipt> receipt,
        final Optional<String> diagnosticId
    ) {
        return failed(AuthoringTransactionOutcome.REJECTED_SCOPE, receipt, diagnosticId);
    }

    /**
     * Creates a recovery failure result.
     *
     * @param receipt available recovery evidence, when captured
     * @param diagnosticId required diagnostic identity
     * @param <T> callback value type
     * @return recovery-failed result
     */
    public static <T> AuthoringTransactionResult<T> recoveryFailed(
        final Optional<AuthoringTransactionReceipt> receipt,
        final String diagnosticId
    ) {
        return failed(
            AuthoringTransactionOutcome.RECOVERY_FAILED,
            receipt,
            Optional.of(diagnosticId)
        );
    }

    /**
     * Creates the typed unavailable result used by the default service.
     *
     * @param diagnosticId required diagnostic identity
     * @param <T> callback value type
     * @return unavailable result
     */
    public static <T> AuthoringTransactionResult<T> unavailable(final String diagnosticId) {
        return failed(
            AuthoringTransactionOutcome.UNAVAILABLE,
            Optional.empty(),
            Optional.of(diagnosticId)
        );
    }

    /**
     * Reports whether the callback completed normally.
     *
     * @return {@code true} for committed and no-change results
     */
    public boolean successful() {
        return outcome == AuthoringTransactionOutcome.COMMITTED
            || outcome == AuthoringTransactionOutcome.NO_CHANGE;
    }

    /**
     * Reports whether the callback committed at least one changed authoring write.
     *
     * @return {@code true} only for a committed mutation
     */
    public boolean changed() {
        return outcome == AuthoringTransactionOutcome.COMMITTED;
    }

    private static <T> AuthoringTransactionResult<T> successful(
        final AuthoringTransactionOutcome outcome,
        final T value,
        final AuthoringTransactionReceipt receipt
    ) {
        return new AuthoringTransactionResult<>(
            outcome,
            Optional.ofNullable(value),
            Optional.of(Objects.requireNonNull(receipt, "receipt")),
            Optional.empty()
        );
    }

    private static <T> AuthoringTransactionResult<T> failed(
        final AuthoringTransactionOutcome outcome,
        final Optional<AuthoringTransactionReceipt> receipt,
        final Optional<String> diagnosticId
    ) {
        return new AuthoringTransactionResult<>(
            outcome,
            Optional.empty(),
            Objects.requireNonNull(receipt, "receipt"),
            Objects.requireNonNull(diagnosticId, "diagnosticId")
        );
    }

    private static String requireDiagnosticId(final String value) {
        final String checked = Objects.requireNonNull(value, "diagnosticId").strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("diagnosticId must not be blank");
        }
        if (checked.length() > 128) {
            throw new IllegalArgumentException("diagnosticId must not exceed 128 characters");
        }
        if (checked.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("diagnosticId must not contain control characters");
        }
        return checked;
    }
}
