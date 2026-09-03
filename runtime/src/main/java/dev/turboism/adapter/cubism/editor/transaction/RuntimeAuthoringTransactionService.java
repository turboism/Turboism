package dev.turboism.adapter.cubism.editor.transaction;

import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Runtime bridge from the public synchronous service to one current Editor binding. */
public final class RuntimeAuthoringTransactionService implements AuthoringTransactionService {

    private static final String BINDING_UNAVAILABLE =
        "cubism.authoring.transactions.binding-unavailable";

    private final EditorAuthoringTransactionCoordinator coordinator;
    private final Supplier<Optional<EditorAuthoringTransactionCoordinator.Binding>> binding;

    /**
     * Creates a service whose binding supplier is evaluated exactly once per root invocation.
     *
     * @param coordinator Runtime-owned ambient transaction coordinator
     * @param binding current plugin/document/model/thread binding supplier
     */
    public RuntimeAuthoringTransactionService(
        final EditorAuthoringTransactionCoordinator coordinator,
        final Supplier<Optional<EditorAuthoringTransactionCoordinator.Binding>> binding
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    @Override
    public <T> AuthoringTransactionResult<T> execute(
        final AuthoringTransactionOptions options,
        final AuthoringTransactionWork<T> work
    ) {
        final AuthoringTransactionOptions checkedOptions = Objects.requireNonNull(
            options,
            "options"
        );
        final AuthoringTransactionWork<T> checkedWork = Objects.requireNonNull(work, "work");
        final Optional<EditorAuthoringTransactionCoordinator.Binding> current;
        try {
            current = Objects.requireNonNull(binding.get(), "binding supplier result");
        } catch (RuntimeException failure) {
            return AuthoringTransactionResult.unavailable(BINDING_UNAVAILABLE);
        }
        if (current.isEmpty()) {
            return AuthoringTransactionResult.unavailable(BINDING_UNAVAILABLE);
        }
        return coordinator.execute(current.orElseThrow(), checkedOptions, checkedWork);
    }
}
