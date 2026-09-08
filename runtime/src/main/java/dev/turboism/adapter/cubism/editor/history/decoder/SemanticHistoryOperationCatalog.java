package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.sdk.cubism.history.HistoryEditContext;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Runtime-internal catalog of normalized domain operations admitted for projection. */
final class SemanticHistoryOperationCatalog {

    private static final Set<HistoryEditContext.Kind> FORM_SCOPES = Set.of(
        HistoryEditContext.Kind.DEFAULT_FORM,
        HistoryEditContext.Kind.KEYFORM
    );
    private static final Set<HistoryEditContext.Kind> OBJECT_SCOPE = Set.of(
        HistoryEditContext.Kind.OBJECT
    );

    private static final Map<Key, Descriptor> DESCRIPTORS = Map.ofEntries(
        form("ART_MESH", "opacity", ValueKind.NUMBER),
        form("ART_MESH", "drawOrder", ValueKind.INTEGER),
        form("ART_MESH", "multiplyColor", ValueKind.COLOR),
        form("ART_MESH", "screenColor", ValueKind.COLOR),
        form("ART_MESH", "vertexPositions", ValueKind.BOUNDED_COLLECTION),
        object("ART_MESH", "name", ValueKind.TEXT),
        object("ART_MESH", "id", ValueKind.TEXT),
        object("ART_MESH", "visible", ValueKind.BOOLEAN),
        object("ART_MESH", "locked", ValueKind.BOOLEAN),
        object("ART_MESH", "labelColor", ValueKind.COLOR),
        object("PARAMETER", "value", ValueKind.NUMBER),
        object("GLUE", "name", ValueKind.TEXT),
        object("GLUE", "id", ValueKind.TEXT),
        object("GLUE", "intensity", ValueKind.NUMBER),
        object("GLUE", "drawableA", ValueKind.TARGET_ID),
        object("GLUE", "drawableB", ValueKind.TARGET_ID)
    );

    private SemanticHistoryOperationCatalog() { }

    static Optional<Descriptor> descriptor(
        final String targetType,
        final String property
    ) {
        return Optional.ofNullable(DESCRIPTORS.get(new Key(targetType, property)));
    }

    private static Map.Entry<Key, Descriptor> form(
        final String targetType,
        final String property,
        final ValueKind valueKind
    ) {
        return Map.entry(
            new Key(targetType, property),
            new Descriptor(targetType, property, FORM_SCOPES, valueKind)
        );
    }

    private static Map.Entry<Key, Descriptor> object(
        final String targetType,
        final String property,
        final ValueKind valueKind
    ) {
        return Map.entry(
            new Key(targetType, property),
            new Descriptor(targetType, property, OBJECT_SCOPE, valueKind)
        );
    }

    record Descriptor(
        String targetType,
        String property,
        Set<HistoryEditContext.Kind> supportedScopes,
        ValueKind valueKind
    ) {
        boolean supports(final HistoryEditContext.Kind scope) {
            return supportedScopes.contains(scope);
        }
    }

    enum ValueKind {
        TEXT,
        NUMBER,
        INTEGER,
        BOOLEAN,
        COLOR,
        TARGET_ID,
        BOUNDED_COLLECTION
    }

    private record Key(String targetType, String property) { }
}
