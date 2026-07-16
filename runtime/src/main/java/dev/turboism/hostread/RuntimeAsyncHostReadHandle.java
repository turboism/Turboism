package dev.turboism.hostread;

import dev.turboism.sdk.hostread.AsyncHostReadError;
import dev.turboism.sdk.hostread.AsyncHostReadErrorCode;
import dev.turboism.sdk.hostread.AsyncHostReadHandle;
import dev.turboism.sdk.hostread.AsyncHostReadIntent;
import dev.turboism.sdk.hostread.AsyncHostReadResult;
import dev.turboism.sdk.hostread.AsyncHostReadStatus;
import dev.turboism.sdk.hostread.AsyncHostReadValue;
import dev.turboism.task.PluginCompletionFuture;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

final class RuntimeAsyncHostReadHandle implements AsyncHostReadHandle {

    private final RuntimeAsyncHostReadService owner;
    private final AsyncHostReadIntent intent;
    private final PluginCompletionFuture<AsyncHostReadResult> completion;
    private final AtomicReference<AsyncHostReadStatus> status =
        new AtomicReference<>(AsyncHostReadStatus.QUEUED);
    private volatile SharedAsyncHostReadLane.OperationCancellation operationCancellation = () -> { };
    private volatile AutoCloseable deadlineCancellation = () -> { };

    RuntimeAsyncHostReadHandle(
        final RuntimeAsyncHostReadService owner,
        final AsyncHostReadIntent intent
    ) {
        this.owner = owner;
        this.intent = intent;
        this.completion = new PluginCompletionFuture<>(
            owner::dispatchContinuation,
            owner::acceptsContinuations
        );
    }

    @Override
    public AsyncHostReadIntent intent() {
        return intent;
    }

    @Override
    public AsyncHostReadStatus status() {
        return status.get();
    }

    @Override
    public boolean cancel() {
        return owner.cancelOwned(this);
    }

    @Override
    public CompletionStage<AsyncHostReadResult> completion() {
        return completion.stage();
    }

    @Override
    public void close() {
        cancel();
    }

    boolean ownedBy(final RuntimeAsyncHostReadService candidate) {
        return owner == candidate;
    }

    void attach(
        final SharedAsyncHostReadLane.OperationCancellation operationCancellation,
        final AutoCloseable deadlineCancellation
    ) {
        this.operationCancellation = operationCancellation;
        this.deadlineCancellation = deadlineCancellation;
    }

    void physicalAdmitted() {
        owner.physicalAdmitted();
    }

    void physicalExited() {
        owner.physicalExited();
    }

    boolean beginRunning() {
        return status.compareAndSet(AsyncHostReadStatus.QUEUED, AsyncHostReadStatus.RUNNING);
    }

    boolean succeed(final AsyncHostReadValue value) {
        return proposeSettlement(
            AsyncHostReadStatus.SUCCEEDED,
            AsyncHostReadResult.success(intent, value)
        );
    }

    boolean fail(final AsyncHostReadError error) {
        return proposeSettlement(
            AsyncHostReadStatus.FAILED,
            failedResult(error)
        );
    }

    void timeout() {
        failImmediately(owner.error(AsyncHostReadErrorCode.TIMEOUT), true);
    }

    boolean cancelInternal() {
        while (true) {
            final AsyncHostReadStatus current = status.get();
            if (terminal(current)) {
                return false;
            }
            if (status.compareAndSet(current, AsyncHostReadStatus.CANCELED)) {
                settleTerminal(new AsyncHostReadResult(
                    intent,
                    AsyncHostReadStatus.CANCELED,
                    Optional.empty(),
                    Optional.of(owner.error(AsyncHostReadErrorCode.CANCELED))
                ), true);
                return true;
            }
        }
    }

    void cancelFromSharedLane() {
        cancelInternal();
    }

    private boolean proposeSettlement(
        final AsyncHostReadStatus terminalStatus,
        final AsyncHostReadResult result
    ) {
        if (terminal(status.get())) {
            return false;
        }
        owner.dispatchContinuation(() -> {
            if (status.compareAndSet(AsyncHostReadStatus.RUNNING, terminalStatus)) {
                closeDeadline();
                owner.operationFinished(this);
                completion.settle(result);
            }
        });
        return true;
    }

    private boolean failImmediately(
        final AsyncHostReadError error,
        final boolean cancelPhysical
    ) {
        while (true) {
            final AsyncHostReadStatus current = status.get();
            if (terminal(current)) {
                return false;
            }
            if (status.compareAndSet(current, AsyncHostReadStatus.FAILED)) {
                settleTerminal(failedResult(error), cancelPhysical);
                return true;
            }
        }
    }

    private AsyncHostReadResult failedResult(final AsyncHostReadError error) {
        return new AsyncHostReadResult(
            intent,
            AsyncHostReadStatus.FAILED,
            Optional.empty(),
            Optional.of(error)
        );
    }

    private void settleTerminal(
        final AsyncHostReadResult result,
        final boolean cancelPhysical
    ) {
        closeDeadline();
        owner.operationFinished(this);
        if (cancelPhysical) {
            operationCancellation.cancel();
        }
        owner.dispatchContinuation(() -> completion.settle(result));
    }

    private void closeDeadline() {
        try {
            deadlineCancellation.close();
        } catch (Exception ignored) {
        }
    }

    private static boolean terminal(final AsyncHostReadStatus status) {
        return status == AsyncHostReadStatus.SUCCEEDED
            || status == AsyncHostReadStatus.FAILED
            || status == AsyncHostReadStatus.CANCELED;
    }
}
