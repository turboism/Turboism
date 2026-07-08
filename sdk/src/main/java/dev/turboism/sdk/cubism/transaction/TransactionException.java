package dev.turboism.sdk.cubism.transaction;

/**
 * Checked exception for transaction lifecycle errors.
 * Carries SDK-safe diagnostic information.
 */
public class TransactionException extends Exception {

    private final String transactionId;
    private final int errorCode;
    private final String severity;

    public TransactionException(String transactionId, int errorCode, String severity, String message) {
        super(message);
        this.transactionId = transactionId;
        this.errorCode = errorCode;
        this.severity = severity;
    }

    public TransactionException(String transactionId, int errorCode, String severity, String message, Throwable cause) {
        super(message, cause);
        this.transactionId = transactionId;
        this.errorCode = errorCode;
        this.severity = severity;
    }

    public String transactionId() { return transactionId; }
    public int errorCode() { return errorCode; }
    public String severity() { return severity; }
}
