package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.event.CubismOperationEvent;

/**
 * Override-based lifecycle hooks shared by typed Cubism model and Editor operations.
 *
 * <p>{@code before} observes synchronous intent and cannot cancel or replace generic
 * arguments. Typed hooks such as {@link ParameterHooks} remain the argument-rewriting
 * surface. {@code on} runs only when the runtime confirms the operation's semantic
 * fact; for state mutations, confirmation requires an actual change. {@code after}
 * runs after every normal completion.</p>
 */
@PreviewApi
public interface SemanticOperationHooks {

    /** Runs synchronously before the semantic operation is invoked. */
    default void beforeCubismOperation(final CubismOperationEvent event) {
    }

    /** Runs after normal completion when the runtime confirms the semantic fact. */
    default void onCubismOperationConfirmed(final CubismOperationEvent event) {
    }

    /** Runs after every normal completion, after {@code on} when confirmation occurred. */
    default void afterCubismOperation(final CubismOperationEvent event) {
    }
}
