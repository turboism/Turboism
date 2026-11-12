package dev.turboism.core.descriptor.validation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.schema.JsonSchemaValidator;
import dev.turboism.core.schema.SchemaValidationError;
import java.util.List;

/**
 * Validator adapter for {@code turboism.plugin.meta} schema versions 2 through 5.
 *
 * <p>The rules themselves live in {@link PluginDescriptorParser}, which validates a manifest while
 * it reads it; this type only selects the schema version a document is held to, so the schema
 * fixture harness and the metadata CLI report exactly the findings admission reports.</p>
 */
public final class PluginMetaValidator implements JsonSchemaValidator {

    private final int schemaVersion;

    /** Schema v2 contract; retained for existing fixture and CLI tooling. */
    public PluginMetaValidator() {
        this(2);
    }

    /** Schema v3 contract: v2 fields plus required {@code category} and optional {@code tags}. */
    public static PluginMetaValidator v3() {
        return new PluginMetaValidator(3);
    }

    /** Schema v4 contract: v3 classification plus public event exports and imports. */
    public static PluginMetaValidator v4() {
        return new PluginMetaValidator(4);
    }

    /** Schema v5 contract: v4 events plus embedded public event contract artifacts. */
    public static PluginMetaValidator v5() {
        return new PluginMetaValidator(5);
    }

    /** Validator for the declared schema version; versions other than 2-5 fail closed as v2. */
    public static PluginMetaValidator forSchemaVersion(final int schemaVersion) {
        return switch (schemaVersion) {
            case 3 -> v3();
            case 4 -> v4();
            case 5 -> v5();
            default -> new PluginMetaValidator();
        };
    }

    private PluginMetaValidator(final int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    @Override
    public List<SchemaValidationError> validate(final JsonNode node) {
        return validate(node, "");
    }

    @Override
    public List<SchemaValidationError> validate(final JsonNode node, final String source) {
        return new PluginDescriptorParser().check(node, source, schemaVersion);
    }
}
