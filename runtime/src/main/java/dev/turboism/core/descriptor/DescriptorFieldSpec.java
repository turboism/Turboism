package dev.turboism.core.descriptor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * One field of a plugin manifest: which tier admits it, how its presence is enforced, and how its
 * value is validated and read.
 *
 * <p>A field is described exactly once here. The reader used by {@link
 * PluginDescriptorParser#parse(JsonNode, String)} and the findings reported by {@link
 * PluginDescriptorParser#check(JsonNode, String)} therefore cannot drift apart: there is no second
 * walk over the same node that could disagree about the field's code, message or path.</p>
 */
final class DescriptorFieldSpec {

    /** How a tier enforces the presence of a field. */
    enum Presence {
        /** Absent is admissible; the reader only inspects a present value. */
        OPTIONAL,
        /** Required string; the required sweep reports a missing value before any value is read. */
        REQUIRED_STRING,
        /** Required array; the required sweep reports a missing or non-array value. */
        REQUIRED_ARRAY,
        /** Required object; the required sweep reports a missing or non-object value. */
        REQUIRED_OBJECT,
        /**
         * Required, but reported by the reader at this field's own position. Schema v3 introduced
         * {@code category} after the required sweep existed; moving it into that sweep would
         * reorder findings against every manifest already in the field, so the position is data
         * here instead of an accident of declaration order.
         */
        REQUIRED_INLINE
    }

    /** Reads one field's value, validating it where it is read. */
    @FunctionalInterface
    interface Reader {
        /**
         * @param root the manifest root object
         * @param target the descriptor under construction
         * @param findings the ordered sink this read appends to
         */
        void read(JsonNode root, DescriptorBuilder target, Findings findings);
    }

    private final String name;
    private final int sinceVersion;
    private final Presence presence;
    private final Reader reader;

    /**
     * @param name manifest field name
     * @param sinceVersion first schema version that admits the field
     * @param presence how the field's presence is enforced
     * @param reader validates and reads the field value
     */
    DescriptorFieldSpec(final String name, final int sinceVersion, final Presence presence, final Reader reader) {
        this.name = Objects.requireNonNull(name, "name");
        this.sinceVersion = sinceVersion;
        this.presence = Objects.requireNonNull(presence, "presence");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /** @return the manifest field name */
    String name() {
        return name;
    }

    /** @return the first schema version that admits this field */
    int sinceVersion() {
        return sinceVersion;
    }

    /** @return how this field's presence is enforced */
    Presence presence() {
        return presence;
    }

    /** @return the reader that validates and reads this field */
    Reader reader() {
        return reader;
    }

    /** @return true when the required sweep, not the reader, reports this field's absence */
    boolean swept() {
        return presence == Presence.REQUIRED_STRING
                || presence == Presence.REQUIRED_ARRAY
                || presence == Presence.REQUIRED_OBJECT;
    }
}
