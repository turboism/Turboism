package dev.turboism.sdk.task;

import java.util.Objects;
import java.util.Optional;

final class TaskContracts {

    static final int MAX_TASK_ID_LENGTH = 128;

    private TaskContracts() {
    }

    static String requireText(
        final String value,
        final String name,
        final int maxLength
    ) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    static <T> Optional<T> requireOptional(final Optional<T> value, final String name) {
        return Objects.requireNonNull(value, name);
    }

    static void validateFailure(
        final TaskRunOutcomeStatus status,
        final Optional<TaskFailure> failure
    ) {
        final boolean required = status == TaskRunOutcomeStatus.FAILED
            || status == TaskRunOutcomeStatus.TIMED_OUT;
        if (failure.isPresent() != required) {
            throw new IllegalArgumentException("run failure presence does not match status " + status);
        }
    }

    static void validateFailure(
        final TaskOutcomeStatus status,
        final Optional<TaskFailure> failure
    ) {
        final boolean required = status == TaskOutcomeStatus.FAILED
            || status == TaskOutcomeStatus.TIMED_OUT
            || status == TaskOutcomeStatus.REJECTED;
        if (failure.isPresent() != required) {
            throw new IllegalArgumentException("task failure presence does not match status " + status);
        }
    }
}
