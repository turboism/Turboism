package dev.turboism.sdk.cubism.transaction;

/**
 * Synchronous work performed inside an Editor authoring transaction scope.
 *
 * <p>The callback may use the natural Cubism object API. It must finish on the transaction thread,
 * must not retain an internal transaction handle, and must not open a second root authoring
 * transaction.</p>
 *
 * @param <T> callback result type
 */
@FunctionalInterface
public interface AuthoringTransactionWork<T> {

    /**
     * Runs the authoring work synchronously.
     *
     * @return callback value, which may be {@code null}
     * @throws Exception when the callback cannot complete; Runtime then applies its verified abort
     *     and recovery policy
     */
    T run() throws Exception;
}
