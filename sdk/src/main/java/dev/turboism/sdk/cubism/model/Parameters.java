package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Parameters in one Cubism model. */
@PreviewApi
public interface Parameters {

    /** Returns all parameters in stable model order. */
    List<Parameter> all();

    /**
     * Finds one parameter by ID.
     *
     * @throws NoSuchElementException when the ID is absent
     */
    Parameter find(ParameterId id);

    /** Returns the parameter with the exact ID, or empty when it is absent. */
    default Optional<Parameter> findById(final ParameterId id) {
        Objects.requireNonNull(id, "id");
        return all().stream()
            .filter(parameter -> parameter.id().equals(id))
            .findFirst();
    }

    /** Convenience overload for an exact parameter ID string. */
    default Optional<Parameter> findById(final String id) {
        return findById(new ParameterId(Objects.requireNonNull(id, "id")));
    }

    /**
     * Returns parameters whose available user-facing name exactly equals {@code name}.
     * Duplicate names are preserved in stable model order.
     */
    default List<Parameter> findByName(final String name) {
        Objects.requireNonNull(name, "name");
        return filter(parameter -> parameter.name().filter(name::equals).isPresent());
    }

    /**
     * Searches IDs and available user-facing names using a case-insensitive contains match.
     * Results preserve stable model order.
     */
    default List<Parameter> search(final String text) {
        Objects.requireNonNull(text, "text");
        final String query = text.toLowerCase(Locale.ROOT);
        return filter(parameter ->
            parameter.id().value().toLowerCase(Locale.ROOT).contains(query)
                || parameter.name()
                    .map(value -> value.toLowerCase(Locale.ROOT).contains(query))
                    .orElse(false)
        );
    }

    /** Applies a developer-defined filter and returns an immutable stable-order result. */
    default List<Parameter> filter(final Predicate<Parameter> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return all().stream().filter(predicate).toList();
    }
}
