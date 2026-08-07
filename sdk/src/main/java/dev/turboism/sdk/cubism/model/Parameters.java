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

    /**
     * Creates a new parameter in the root parameter folder and returns it.
     *
     * <p>The write is undoable and generation-bound. The definition ID must be
     * unique in the active model.</p>
     *
     * @throws IllegalArgumentException when the ID is already present or the definition is invalid
     */
    default Parameter create(final ParameterDefinition definition) {
        return create(definition, java.util.Optional.empty());
    }

    /**
     * Creates a new parameter in the requested parameter folder and returns it.
     *
     * @throws IllegalArgumentException when the ID is already present or the definition is invalid
     * @throws NoSuchElementException when the folder is absent
     */
    default Parameter create(
        final ParameterDefinition definition,
        final java.util.Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> folderId
    ) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(folderId, "folderId");
        throw new UnsupportedOperationException("Parameter creation is unavailable.");
    }

    /**
     * Duplicates one parameter next to the source parameter and returns the copy.
     *
     * <p>The copy receives a fresh unique ID and copies the definition fields of
     * the source.</p>
     *
     * @throws NoSuchElementException when the source parameter is absent
     */
    default Parameter copy(final ParameterId id) {
        Objects.requireNonNull(id, "id");
        throw new UnsupportedOperationException("Parameter duplication is unavailable.");
    }

    /**
     * Deletes one parameter, including its keyform bindings, Morph Targets, and physics references.
     *
     * @throws NoSuchElementException when the parameter is absent
     */
    default void remove(final ParameterId id) {
        Objects.requireNonNull(id, "id");
        throw new UnsupportedOperationException("Parameter deletion is unavailable.");
    }

    /**
     * Creates several parameters in the root parameter folder as one undo unit and returns them.
     *
     * <p>The whole batch shares a single Undo entry: either every definition is
     * applied or (on failure) the envelope is rolled back.</p>
     *
     * @throws IllegalArgumentException when an ID is already present, duplicated within
     *                                  the batch, or a definition is invalid
     */
    default List<Parameter> createMany(final List<ParameterDefinition> definitions) {
        return createMany(definitions, java.util.Optional.empty());
    }

    /**
     * Creates several parameters in the requested parameter folder as one undo unit and returns them.
     *
     * @throws IllegalArgumentException when an ID is already present, duplicated within
     *                                  the batch, or a definition is invalid
     * @throws NoSuchElementException   when the folder is absent
     */
    default List<Parameter> createMany(
        final List<ParameterDefinition> definitions,
        final java.util.Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> folderId
    ) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(folderId, "folderId");
        throw new UnsupportedOperationException("Batch parameter creation is unavailable.");
    }

    /**
     * Deletes several parameters as one undo unit.
     *
     * @throws NoSuchElementException when any parameter is absent
     */
    default void removeMany(final List<ParameterId> ids) {
        Objects.requireNonNull(ids, "ids");
        throw new UnsupportedOperationException("Batch parameter deletion is unavailable.");
    }
}
