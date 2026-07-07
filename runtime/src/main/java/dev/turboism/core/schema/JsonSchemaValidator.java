package dev.turboism.core.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Validates a JSON node against a Turboism schema.
 */
public interface JsonSchemaValidator {

    /**
     * Validates the given node and returns a list of structured errors.
     * The list is empty if the node is valid.
     */
    List<SchemaValidationError> validate(JsonNode node);

    /**
     * Validates the given node with an explicit source identifier.
     */
    default List<SchemaValidationError> validate(JsonNode node, String source) {
        return validate(node);
    }
}
