package dev.turboism.sdk.cubism.transaction;

import java.util.Objects;
import java.util.Optional;

/**
 * Typed terminal result of one synchronous authoring-transaction request.
 *
 * @param outcome terminal transaction outcome
 * @param value callback value for successful completion, empty when the callback returned
 *     {@code null} or the transaction did not complete successfully
 * @param receipt immutable transaction and history evidence when a scope was created
 * @param diagnosticId stable diagnostic identity for rejected, unavailable, or failed outcomes
 * @param <T> callback result type
 */
public record AuthoringTransactionResult<T>(
    AuthoringTransactionOutcome outcome,
    Optional<T> value,
    Optional<AuthoringTransactionReceipt> receipt,
    Optional<String> diagnosticId
) {

    /** Validates cross-field invariants for the terminal outcome. */
    public AuthoringTransactionResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        value = Objects.requireNonNull(value, "value");
        receipt = Objects.requireNonNull(receipt, "receipt");
        diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId")
            .map(AuthoringTransactionResult::normalizedDiagnosticId);

        final boolean successful = outcome == AuthoringTransactionOutcome.COMMITTED
            || outcome == AuthoringTransactionOutcome.NO_CHANGE;
        if (successful && receipt.isEmpty()) {
            throw new IllegalArgumentException("successful transaction result requires a receipt");
        }
        if (successful && diagnosticId.isPresent()) {
            throw new IllegalArgumentException("successful transaction result cannot carry a diagnosticId");
        }
        if (!successful && value.isPresent()) {
            throw new IllegalArgumentException("unsuccessful transaction result cannot carry a value");
        }
        if (!successful && diagnosticId.isEmpty()) {
            throw new IllegalArgumentException("unsuccessful transaction result requires a diagnosticId");
        }
        if (outcome == AuthoringTransactionOutcome.UNAVAILABLE && receipt.isPresent()) {
            throw new IllegalArgumentException("unavailable transaction result cannot carry a receipt");
        }
        if ((outcome == AuthoringTransactionOutcome.ROLLED_BACK
                || outcome == AuthoringTransactionOutcome.RECOVERY_FAILED)
            && receipt.isEmpty()) {
            throw new IllegalArgumentException(outcome + " transaction result requires a receipt");
        }

        final Optional<String> entryId = receipt.flatMap(AuthoringTransactionReceipt::historyEntryId);
        if (outcome == AuthoringTransactionOutcome.COMMITTED && entryId.isEmpty()) {
            throw new IllegalArgumentException("committed transaction result requires a history entry ID");
        }
        if (outcome != AuthoringTransactionOutcome.COMMITTED && entryId.isPresent()) {
            throw new IllegalArgumentException(
                "only a committed transaction result may carry a history entry ID"
            );
        }
    }

    /**
     * Creates a successful changed result.
     *
     * @param value callback value, which may be {@code null}
     * @param receipt committed transaction receipt with a history-entry identity
     * @param <T> callback result type
     * @return committed result
     */
    public static <T> AuthoringTransactionResult<T> committed(
        final T value,
        final AuthoringTransactionReceipt receipt
    ) {
        return new AuthoringTransactionResult<>(
            AuthoringTransactionOutcome.COMMITTED,
            Optional.ofNullable(value),
            Optional.of(Objects.requireNonNull(receipt, "receipt")),
            Optional.empty()
        );
    }

    /**
     * Creates a successful read-only or all-no-op result.
     *
     * @param value callback value, which may be {@code null}
     * @param receipt transaction receipt without a history-entry identity
     * @param <T> callback result type
     * @return no-change result
     */
    public static <T> AuthoringTransactionResult<T> noChange(
        final T value,
        final AuthoringTransactionReceipt receipt
    ) {
        return new AuthoringTransactionResult<>(
            AuthoringTransactionOutcome.NO_CHANGE,
            Optional.ofNullable(value),
            Optional.of(Objects.requireNonNull(receipt, "receipt")),
            Optional.empty()
        );
    }

    /**
     * Creates a result for a callback failure whose original state was proven restored.
     *
     * @param receipt recovery receipt
     * @param diagnosticId diagnostic evidence identity
     * @param <T> callback result type
     * @return rolled-back result
     */
    public static <T> AuthoringTransactionResult<T> rolledBack(
        final AuthoringTransactionReceipt receipt,
        final String diagnosticId
    ) {
        return failed(AuthoringTransactionOutcome.ROLLED_BACK, Optional.of(receipt), diagnosticId);
    }

    /**
     * Creates a stale-precondition rejection.
     *
     * @param receipt optional preflight receipt
     * @param diagnosticId diagnostic evidence identity
     * @param <T> callback result type
     * @return stale rejection
     */
    public static <T> AuthoringTransactionResult<T> rejectedStale(
        final Optional<AuthoringTransactionReceipt> receipt,
        final String diagnosticId
    ) {
        return failed(AuthoringTransactionOutcome.REJECTED_STALE, receipt, diagnosticId);
    }

    /**
     * Creates a scope or nesting rejection.
     *
     * @param receipt optional preflight receipt
     * @param diagnosticId diagnostic evidence identity
     * @param <T> callback result type
     * @return scope rejection
     */
    public static <T> AuthoringTransactionResult<T> rejectedScope(
        final Optional<AuthoringTransactionReceipt> receipt,
        final String diagnosticId
    ) {
        return failed(AuthoringTransactionOutcome.REJECTED_SCOPE, receipt, diagnosticId);
    }

    /**
     * Creates a recovery failure result.
     *
     * @param receipt recovery evidence
     * @param diagnosticId diagnostic evidence identity
     * @param <T> callback result type
     * @return recovery-failed result
     */
    public static <T> AuthoringTransactionResult<T> recoveryFailed(
        final AuthoringTransactionReceipt receipt,
        final String diagnosticId
    ) {
        return failed(AuthoringTransactionOutcome.RECOVERY_FAILED, Optional.of(receipt), diagnosticId);
    }

    /**
     * Creates a typed unavailable result without invoking authoring work.
     *
     * @param diagnosticId stable unavailability identity
     * @param <T> callback result type
     * @return unavailable result
     */
    public static <T> AuthoringTransactionResult<T> unavailable(final String diagnosticId) {
        return failed(AuthoringTransactionOutcome.UNAVAILABLE, Optional.empty(), diagnosticId);
    }

    /**
     * @return {@code true} when the callback completed as committed or no-change work
     */
    public boolean successful() {
        return outcome == AuthoringTransactionOutcome.COMMITTED
            || outcome == AuthoringTransactionOutcome.NO_CHANGE;
    }

    /**
     * @return {@code true} only when authoring state changed and committed
     */
    public boolean changed() {
        return outcome == AuthoringTransactionOutcome.COMMITTED;
    }

    private static <T> AuthoringTransactionResult<T> failed(
        final AuthoringTransactionOutcome outcome,
        final Optional<AuthoringTransactionReceipt> receipt,
        final String diagnosticId
    ) {
        return new AuthoringTransactionResult<>(
            outcome,
            Optional.empty(),
            Objects.requireNonNull(receipt, "receipt"),
            Optional.of(normalizedDiagnosticId(diagnosticId))
        );
    }

    private static String normalizedDiagnosticId(final String value) {
        final String normalized = Objects.requireNonNull(value, "diagnosticId").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("diagnosticId must not be blank");
        }
        if (normalized.length() > AuthoringTransactionReceipt.MAX_ID_LENGTH) {
            throw new IllegalArgumentException(
                "diagnosticId must not exceed "
                    + AuthoringTransactionReceipt.MAX_ID_LENGTH + " characters"
            );
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("diagnosticId must not contain control characters");
        }
        return normalized;
    }
}
