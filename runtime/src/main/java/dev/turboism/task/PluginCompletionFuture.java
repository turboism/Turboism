package dev.turboism.task;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runtime-owned controller for a plugin-facing completion stage. The controller
 * is never exposed through the SDK handle; plugins receive only the controlled
 * stage view.
 */
public final class PluginCompletionFuture<T> {

    private final ControlledFuture<T> stage;

    public PluginCompletionFuture(final Consumer<Runnable> dispatcher) {
        this(dispatcher, () -> true);
    }

    public PluginCompletionFuture(
        final Consumer<Runnable> dispatcher,
        final BooleanSupplier continuationAdmission
    ) {
        this.stage = new ControlledFuture<>(dispatcher, continuationAdmission);
    }

    public boolean settle(final T value) {
        return stage.runtimeSettle(value);
    }

    public boolean settleExceptionally(final Throwable failure) {
        return stage.runtimeSettleExceptionally(Objects.requireNonNull(failure, "failure"));
    }

    public CompletionStage<T> stage() {
        return stage;
    }

    private static final class ControlledFuture<T> extends CompletableFuture<T> {

        private final Consumer<Runnable> dispatcher;
        private final BooleanSupplier continuationAdmission;
        private final Executor executor;

        private ControlledFuture(
            final Consumer<Runnable> dispatcher,
            final BooleanSupplier continuationAdmission
        ) {
            this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
            this.continuationAdmission = Objects.requireNonNull(
                continuationAdmission,
                "continuationAdmission"
            );
            this.executor = dispatcher::accept;
        }

        private boolean runtimeSettle(final T value) {
            return super.complete(value);
        }

        private boolean runtimeSettleExceptionally(final Throwable failure) {
            return super.completeExceptionally(failure);
        }

        @Override
        public Executor defaultExecutor() {
            throw new UnsupportedOperationException("Plugin completion executor is runtime-owned");
        }

        @Override
        public <U> CompletableFuture<U> newIncompleteFuture() {
            return new ControlledFuture<>(dispatcher, continuationAdmission);
        }

        @Override
        public boolean complete(final T value) {
            throw readOnly();
        }

        @Override
        public boolean completeExceptionally(final Throwable failure) {
            throw readOnly();
        }

        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public void obtrudeValue(final T value) {
            throw readOnly();
        }

        @Override
        public void obtrudeException(final Throwable failure) {
            throw readOnly();
        }

        @Override
        public CompletableFuture<T> completeAsync(final Supplier<? extends T> supplier) {
            throw readOnly();
        }

        @Override
        public CompletableFuture<T> completeAsync(
            final Supplier<? extends T> supplier,
            final Executor ignored
        ) {
            throw readOnly();
        }

        @Override
        public CompletableFuture<T> orTimeout(final long timeout, final TimeUnit unit) {
            throw readOnly();
        }

        @Override
        public CompletableFuture<T> completeOnTimeout(
            final T value,
            final long timeout,
            final TimeUnit unit
        ) {
            throw readOnly();
        }

        @Override
        public <U> CompletableFuture<U> thenApply(
            final Function<? super T, ? extends U> action
        ) {
            requireContinuationAdmission();
            return super.thenApplyAsync(action, executor);
        }

        @Override
        public <U> CompletableFuture<U> thenApplyAsync(
            final Function<? super T, ? extends U> action
        ) {
            requireContinuationAdmission();
            return super.thenApplyAsync(action, executor);
        }

        @Override
        public <U> CompletableFuture<U> thenApplyAsync(
            final Function<? super T, ? extends U> action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.thenApplyAsync(action, executor);
        }

        @Override
        public CompletableFuture<Void> thenAccept(final Consumer<? super T> action) {
            requireContinuationAdmission();
            return super.thenAcceptAsync(action, executor);
        }

        @Override
        public CompletableFuture<Void> thenAcceptAsync(final Consumer<? super T> action) {
            requireContinuationAdmission();
            return super.thenAcceptAsync(action, executor);
        }

        @Override
        public CompletableFuture<Void> thenAcceptAsync(
            final Consumer<? super T> action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.thenAcceptAsync(action, executor);
        }

        @Override
        public CompletableFuture<Void> thenRun(final Runnable action) {
            requireContinuationAdmission();
            return super.thenRunAsync(action, executor);
        }

        @Override
        public CompletableFuture<Void> thenRunAsync(final Runnable action) {
            requireContinuationAdmission();
            return super.thenRunAsync(action, executor);
        }

        @Override
        public CompletableFuture<Void> thenRunAsync(
            final Runnable action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.thenRunAsync(action, executor);
        }

        @Override
        public <U, V> CompletableFuture<V> thenCombine(
            final CompletionStage<? extends U> other,
            final BiFunction<? super T, ? super U, ? extends V> action
        ) {
            requireContinuationAdmission();
            return super.thenCombineAsync(other, action, executor);
        }

        @Override
        public <U, V> CompletableFuture<V> thenCombineAsync(
            final CompletionStage<? extends U> other,
            final BiFunction<? super T, ? super U, ? extends V> action
        ) {
            requireContinuationAdmission();
            return super.thenCombineAsync(other, action, executor);
        }

        @Override
        public <U, V> CompletableFuture<V> thenCombineAsync(
            final CompletionStage<? extends U> other,
            final BiFunction<? super T, ? super U, ? extends V> action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.thenCombineAsync(other, action, executor);
        }

        @Override
        public <U> CompletableFuture<Void> thenAcceptBoth(
            final CompletionStage<? extends U> other,
            final BiConsumer<? super T, ? super U> action
        ) {
            requireContinuationAdmission();
            return super.thenAcceptBothAsync(other, action, executor);
        }

        @Override
        public <U> CompletableFuture<Void> thenAcceptBothAsync(
            final CompletionStage<? extends U> other,
            final BiConsumer<? super T, ? super U> action
        ) {
            requireContinuationAdmission();
            return super.thenAcceptBothAsync(other, action, executor);
        }

        @Override
        public <U> CompletableFuture<Void> thenAcceptBothAsync(
            final CompletionStage<? extends U> other,
            final BiConsumer<? super T, ? super U> action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.thenAcceptBothAsync(other, action, executor);
        }

        @Override
        public CompletableFuture<Void> runAfterBoth(
            final CompletionStage<?> other,
            final Runnable action
        ) {
            requireContinuationAdmission();
            return super.runAfterBothAsync(other, action, executor);
        }

        @Override
        public CompletableFuture<Void> runAfterBothAsync(
            final CompletionStage<?> other,
            final Runnable action
        ) {
            requireContinuationAdmission();
            return super.runAfterBothAsync(other, action, executor);
        }

        @Override
        public CompletableFuture<Void> runAfterBothAsync(
            final CompletionStage<?> other,
            final Runnable action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.runAfterBothAsync(other, action, executor);
        }

        @Override
        public <U> CompletableFuture<U> applyToEither(
            final CompletionStage<? extends T> other,
            final Function<? super T, U> action
        ) {
            requireContinuationAdmission();
            return super.applyToEitherAsync(other, action, executor);
        }

        @Override
        public <U> CompletableFuture<U> applyToEitherAsync(
            final CompletionStage<? extends T> other,
            final Function<? super T, U> action
        ) {
            requireContinuationAdmission();
            return super.applyToEitherAsync(other, action, executor);
        }

        @Override
        public <U> CompletableFuture<U> applyToEitherAsync(
            final CompletionStage<? extends T> other,
            final Function<? super T, U> action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.applyToEitherAsync(other, action, executor);
        }

        @Override
        public CompletableFuture<Void> acceptEither(
            final CompletionStage<? extends T> other,
            final Consumer<? super T> action
        ) {
            requireContinuationAdmission();
            return super.acceptEitherAsync(other, action, executor);
        }

        @Override
        public CompletableFuture<Void> acceptEitherAsync(
            final CompletionStage<? extends T> other,
            final Consumer<? super T> action
        ) {
            requireContinuationAdmission();
            return super.acceptEitherAsync(other, action, executor);
        }

        @Override
        public CompletableFuture<Void> acceptEitherAsync(
            final CompletionStage<? extends T> other,
            final Consumer<? super T> action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.acceptEitherAsync(other, action, executor);
        }

        @Override
        public CompletableFuture<Void> runAfterEither(
            final CompletionStage<?> other,
            final Runnable action
        ) {
            requireContinuationAdmission();
            return super.runAfterEitherAsync(other, action, executor);
        }

        @Override
        public CompletableFuture<Void> runAfterEitherAsync(
            final CompletionStage<?> other,
            final Runnable action
        ) {
            requireContinuationAdmission();
            return super.runAfterEitherAsync(other, action, executor);
        }

        @Override
        public CompletableFuture<Void> runAfterEitherAsync(
            final CompletionStage<?> other,
            final Runnable action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.runAfterEitherAsync(other, action, executor);
        }

        @Override
        public <U> CompletableFuture<U> thenCompose(
            final Function<? super T, ? extends CompletionStage<U>> action
        ) {
            requireContinuationAdmission();
            return super.thenComposeAsync(action, executor);
        }

        @Override
        public <U> CompletableFuture<U> thenComposeAsync(
            final Function<? super T, ? extends CompletionStage<U>> action
        ) {
            requireContinuationAdmission();
            return super.thenComposeAsync(action, executor);
        }

        @Override
        public <U> CompletableFuture<U> thenComposeAsync(
            final Function<? super T, ? extends CompletionStage<U>> action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.thenComposeAsync(action, executor);
        }

        @Override
        public CompletableFuture<T> whenComplete(
            final BiConsumer<? super T, ? super Throwable> action
        ) {
            requireContinuationAdmission();
            return super.whenCompleteAsync(action, executor);
        }

        @Override
        public CompletableFuture<T> whenCompleteAsync(
            final BiConsumer<? super T, ? super Throwable> action
        ) {
            requireContinuationAdmission();
            return super.whenCompleteAsync(action, executor);
        }

        @Override
        public CompletableFuture<T> whenCompleteAsync(
            final BiConsumer<? super T, ? super Throwable> action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.whenCompleteAsync(action, executor);
        }

        @Override
        public <U> CompletableFuture<U> handle(
            final BiFunction<? super T, Throwable, ? extends U> action
        ) {
            requireContinuationAdmission();
            return super.handleAsync(action, executor);
        }

        @Override
        public <U> CompletableFuture<U> handleAsync(
            final BiFunction<? super T, Throwable, ? extends U> action
        ) {
            requireContinuationAdmission();
            return super.handleAsync(action, executor);
        }

        @Override
        public <U> CompletableFuture<U> handleAsync(
            final BiFunction<? super T, Throwable, ? extends U> action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.handleAsync(action, executor);
        }

        @Override
        public CompletableFuture<T> exceptionally(
            final Function<Throwable, ? extends T> action
        ) {
            requireContinuationAdmission();
            return super.exceptionallyAsync(action, executor);
        }

        @Override
        public CompletableFuture<T> exceptionallyAsync(
            final Function<Throwable, ? extends T> action
        ) {
            requireContinuationAdmission();
            return super.exceptionallyAsync(action, executor);
        }

        @Override
        public CompletableFuture<T> exceptionallyAsync(
            final Function<Throwable, ? extends T> action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.exceptionallyAsync(action, executor);
        }

        @Override
        public CompletableFuture<T> exceptionallyCompose(
            final Function<Throwable, ? extends CompletionStage<T>> action
        ) {
            requireContinuationAdmission();
            return super.exceptionallyComposeAsync(action, executor);
        }

        @Override
        public CompletableFuture<T> exceptionallyComposeAsync(
            final Function<Throwable, ? extends CompletionStage<T>> action
        ) {
            requireContinuationAdmission();
            return super.exceptionallyComposeAsync(action, executor);
        }

        @Override
        public CompletableFuture<T> exceptionallyComposeAsync(
            final Function<Throwable, ? extends CompletionStage<T>> action,
            final Executor ignored
        ) {
            requireContinuationAdmission();
            return super.exceptionallyComposeAsync(action, executor);
        }

        @Override
        public CompletionStage<T> minimalCompletionStage() {
            requireContinuationAdmission();
            return this;
        }

        private void requireContinuationAdmission() {
            if (!continuationAdmission.getAsBoolean()) {
                throw new IllegalStateException(
                    "Plugin continuation registration is unavailable during scope close."
                );
            }
        }

        private static UnsupportedOperationException readOnly() {
            return new UnsupportedOperationException(
                "Plugin task completion is runtime-owned and read-only"
            );
        }
    }
}
