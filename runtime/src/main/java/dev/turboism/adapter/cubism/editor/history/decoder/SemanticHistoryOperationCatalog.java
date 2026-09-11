package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.sdk.cubism.history.HistoryEditContext;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Catalog of normalized domain operations admitted for projection.
 *
 * <p>This is the single source of truth for which target/property pairs the runtime trusts, and
 * for what kind of value each one carries. The native-entry mapping layer reads it instead of
 * keeping its own copy of the channel names, so a property cannot be recognised by the decoder and
 * forgotten by the mapping.</p>
 */
public final class SemanticHistoryOperationCatalog {

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

    /**
     * Looks up the admitted descriptor for a projected target and property.
     *
     * @param targetType the projected target type
     * @param property   the projected property name
     * @return the descriptor, or empty when the pair is not admitted
     */
    public static Optional<Descriptor> descriptor(
        final String targetType,
        final String property
    ) {
        return Optional.ofNullable(DESCRIPTORS.get(new Key(targetType, property)));
    }

    /**
     * Calculates whether the pair is an admitted appearance channel.
     *
     * <p>Appearance means a form-scoped scalar fact: opacity, draw order and the two colours. A
     * geometry channel such as vertex positions is form-scoped as well, so geometry is excluded by
     * its value kind rather than by scope — which is what keeps a move or a mesh edit from ever
     * being reported as a colour change. An object-scoped fact such as a name is excluded by scope,
     * not by kind, so the two exclusions are deliberately separate.</p>
     *
     * @param targetType the projected target type
     * @param property   the projected property name
     * @return whether an appearance change on this channel is a fact the runtime trusts
     */
    public static boolean isAppearanceChannel(final String targetType, final String property) {
        return descriptor(targetType, property)
            .filter(Descriptor::formScoped)
            .filter(descriptor -> descriptor.valueKind() != ValueKind.BOUNDED_COLLECTION)
            .isPresent();
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

    /**
     * One admitted target/property fact, with the scopes it is trusted in and its value kind.
     *
     * @param targetType      the projected target type
     * @param property        the projected property name
     * @param supportedScopes the edit-context scopes this fact may appear in
     * @param valueKind       the kind of value the channel carries
     */
    public record Descriptor(
        String targetType,
        String property,
        Set<HistoryEditContext.Kind> supportedScopes,
        ValueKind valueKind
    ) {
        /**
         * Reads whether this fact is trusted in one edit context.
         *
         * @param scope the edit-context kind to test
         * @return whether this fact is admitted in that scope
         */
        public boolean supports(final HistoryEditContext.Kind scope) {
            return supportedScopes.contains(scope);
        }

        /** {@return whether this fact is admitted at form scope rather than object scope} */
        public boolean formScoped() {
            return !Collections.disjoint(supportedScopes, FORM_SCOPES);
        }
    }

    /** The kind of value an admitted channel carries. */
    public enum ValueKind {
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
