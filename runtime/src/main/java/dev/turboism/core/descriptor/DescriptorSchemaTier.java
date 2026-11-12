package dev.turboism.core.descriptor;

import dev.turboism.core.descriptor.DescriptorFieldSpec.Presence;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One admitted {@code turboism.plugin.meta} schema version, as data: the fields it admits, in the
 * order they are read, and for each field how it is required, validated and turned into one
 * descriptor component.
 *
 * <p>The tiers are cumulative. {@link #V3} appends two fields to {@link #V2}, {@link #V4} two more,
 * and {@link #V5} one more, so "which field does version N admit" is a position in this table and
 * never a second {@code Set.of(...)} block or {@code if (version >= n)} ladder that could disagree
 * with it.</p>
 */
final class DescriptorSchemaTier {

    /** Root field naming the schema a document claims to follow. */
    static final String SCHEMA_VERSION_FIELD = "schemaVersion";

    /** Root field naming the manifest format; admitted by every tier. */
    static final String FORMAT_FIELD = "format";

    /** The one admitted value of {@link #FORMAT_FIELD}. */
    static final String FORMAT = "turboism.plugin.meta";

    /** Oldest admitted schema version. */
    static final int OLDEST_VERSION = 2;

    /** Newest admitted schema version. */
    static final int NEWEST_VERSION = 5;

    /** Fields admitted by one {@code authors[]} entry. */
    static final Set<String> ALLOWED_AUTHOR_FIELDS = Set.of("name", "email");

    /** Fields admitted by the {@code lifecycle} object. */
    static final Set<String> ALLOWED_LIFECYCLE_FIELDS = Set.of("hotReload");

    /** Fields admitted by the {@code i18n} object. */
    static final Set<String> ALLOWED_I18N_FIELDS = Set.of("baseName", "locales");

    /** Fields admitted by the {@code environment} object. */
    static final Set<String> ALLOWED_ENVIRONMENT_FIELDS = Set.of("requiresCubism", "ui");

    /** Fields admitted by one {@code dependencies[]} entry. */
    static final Set<String> ALLOWED_DEPENDENCY_FIELDS = Set.of("id", "version", "type", "ordering", "reason");

    /** Fields admitted by one {@code permissions[]} entry. */
    static final Set<String> ALLOWED_PERMISSION_FIELDS = Set.of("id", "scope", "reason");

    // abiSha256 stays admitted but unused: descriptors published before the event ABI
    // digest was retired must keep loading, and route matching is governed by
    // (id, contractVersion, eventType) plus the shared SDK loader.
    /** Fields admitted by one {@code eventExports[]} entry. */
    static final Set<String> ALLOWED_EVENT_EXPORT_FIELDS = Set.of("id", "contractVersion", "eventType", "abiSha256");

    /** Fields admitted by one {@code eventImports[]} entry. */
    static final Set<String> ALLOWED_EVENT_IMPORT_FIELDS =
            Set.of("provider", "eventId", "contractVersion", "eventType", "abiSha256", "required");

    /** Fields admitted by one {@code eventContracts[]} entry. */
    static final Set<String> ALLOWED_EVENT_CONTRACT_FIELDS = Set.of("id", "version", "artifact", "sha256");

    private static final List<DescriptorFieldSpec> V2_FIELDS = List.of(
            new DescriptorFieldSpec("id", 2, Presence.REQUIRED_STRING, PluginDescriptorParser::readId),
            new DescriptorFieldSpec("name", 2, Presence.REQUIRED_STRING, PluginDescriptorParser::readName),
            new DescriptorFieldSpec("version", 2, Presence.REQUIRED_STRING, PluginDescriptorParser::readVersion),
            new DescriptorFieldSpec("description", 2, Presence.OPTIONAL, PluginDescriptorParser::readDescription),
            new DescriptorFieldSpec("entrypoints", 2, Presence.REQUIRED_ARRAY, PluginDescriptorParser::readEntrypoints),
            new DescriptorFieldSpec(
                    "turboismApi", 2, Presence.REQUIRED_STRING, PluginDescriptorParser::readTurboismApi),
            new DescriptorFieldSpec("authors", 2, Presence.REQUIRED_ARRAY, PluginDescriptorParser::readAuthors),
            new DescriptorFieldSpec("license", 2, Presence.OPTIONAL, PluginDescriptorParser::readLicense),
            new DescriptorFieldSpec("website", 2, Presence.REQUIRED_STRING, PluginDescriptorParser::readWebsite),
            new DescriptorFieldSpec("resources", 2, Presence.REQUIRED_ARRAY, PluginDescriptorParser::readResources),
            new DescriptorFieldSpec("i18n", 2, Presence.REQUIRED_OBJECT, PluginDescriptorParser::readI18n),
            new DescriptorFieldSpec("dependencies", 2, Presence.OPTIONAL, PluginDescriptorParser::readDependencies),
            new DescriptorFieldSpec("permissions", 2, Presence.OPTIONAL, PluginDescriptorParser::readPermissions),
            new DescriptorFieldSpec("capabilities", 2, Presence.OPTIONAL, PluginDescriptorParser::readCapabilities),
            new DescriptorFieldSpec("environment", 2, Presence.OPTIONAL, PluginDescriptorParser::readEnvironment),
            new DescriptorFieldSpec("lifecycle", 2, Presence.OPTIONAL, PluginDescriptorParser::readLifecycle));

    private static final List<DescriptorFieldSpec> V3_FIELDS = append(
            V2_FIELDS,
            List.of(
                    new DescriptorFieldSpec(
                            "category", 3, Presence.REQUIRED_INLINE, PluginDescriptorParser::readCategory),
                    new DescriptorFieldSpec("tags", 3, Presence.OPTIONAL, PluginDescriptorParser::readTags)));

    private static final List<DescriptorFieldSpec> V4_FIELDS = append(
            V3_FIELDS,
            List.of(
                    new DescriptorFieldSpec(
                            "eventExports", 4, Presence.OPTIONAL, PluginDescriptorParser::readEventExports),
                    new DescriptorFieldSpec(
                            "eventImports", 4, Presence.OPTIONAL, PluginDescriptorParser::readEventImports)));

    private static final List<DescriptorFieldSpec> V5_FIELDS = append(
            V4_FIELDS,
            List.of(new DescriptorFieldSpec(
                    "eventContracts", 5, Presence.OPTIONAL, PluginDescriptorParser::readEventContracts)));

    /** Schema v2 contract: identity, packaging, dependencies, permissions and host requirements. */
    static final DescriptorSchemaTier V2 = new DescriptorSchemaTier(2, V2_FIELDS);

    /** Schema v3 contract: v2 plus required {@code category} and bounded {@code tags}. */
    static final DescriptorSchemaTier V3 = new DescriptorSchemaTier(3, V3_FIELDS);

    /** Schema v4 contract: v3 plus public event exports and imports. */
    static final DescriptorSchemaTier V4 = new DescriptorSchemaTier(4, V4_FIELDS);

    /** Schema v5 contract: v4 plus embedded public event contract artifacts. */
    static final DescriptorSchemaTier V5 = new DescriptorSchemaTier(5, V5_FIELDS);

    private final int version;
    private final List<DescriptorFieldSpec> fields;
    private final List<DescriptorFieldSpec> sweptFields;
    private final Set<String> allowedFieldNames;

    private DescriptorSchemaTier(final int version, final List<DescriptorFieldSpec> fields) {
        this.version = version;
        this.fields = List.copyOf(fields);
        final List<DescriptorFieldSpec> swept = new ArrayList<>();
        final Set<String> allowed = new LinkedHashSet<>();
        allowed.add(FORMAT_FIELD);
        allowed.add(SCHEMA_VERSION_FIELD);
        for (DescriptorFieldSpec field : this.fields) {
            allowed.add(field.name());
            if (field.swept()) {
                swept.add(field);
            }
        }
        this.sweptFields = List.copyOf(swept);
        this.allowedFieldNames = Set.copyOf(allowed);
    }

    /**
     * @param version declared schema version
     * @return the tier that governs the document, or empty when no tier admits that version
     */
    static Optional<DescriptorSchemaTier> forVersion(final int version) {
        return switch (version) {
            case 2 -> Optional.of(V2);
            case 3 -> Optional.of(V3);
            case 4 -> Optional.of(V4);
            case 5 -> Optional.of(V5);
            default -> Optional.empty();
        };
    }

    /** @return the schema version this tier governs */
    int version() {
        return version;
    }

    /** @return the admitted fields, in the order their values are read */
    List<DescriptorFieldSpec> fields() {
        return fields;
    }

    /** @return the root field names this tier admits, including {@code format} and {@code schemaVersion} */
    Set<String> allowedFieldNames() {
        return allowedFieldNames;
    }

    /**
     * @return the required fields whose absence the required sweep reports before any value is
     *     read, in manifest order
     */
    List<DescriptorFieldSpec> sweptFields() {
        return sweptFields;
    }

    private static List<DescriptorFieldSpec> append(
            final List<DescriptorFieldSpec> base, final List<DescriptorFieldSpec> added) {
        final List<DescriptorFieldSpec> fields = new ArrayList<>(base);
        fields.addAll(added);
        return List.copyOf(fields);
    }
}
