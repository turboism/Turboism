package dev.turboism.pluginmanagement;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, cryptographically verified representation of the Plugin Directory v2 catalog.
 *
 * @param format catalog wire format ({@code "turboism.plugin.catalog"})
 * @param schemaVersion catalog schema version ({@code 2})
 * @param catalogVersion monotonically increasing catalog version ({@code >= 1})
 * @param publishedAt UTC publication timestamp
 * @param catalogSha256 lowercase hex SHA-256 of the verified catalog bytes
 * @param keyId trusted signing key identifier that verified the catalog
 * @param plugins immutable list of cataloged plugins
 */
public record PluginCatalog(
        String format,
        int schemaVersion,
        long catalogVersion,
        Instant publishedAt,
        String catalogSha256,
        String keyId,
        List<CatalogPlugin> plugins) {

    /** Canonical catalog wire format identifier. */
    public static final String FORMAT = "turboism.plugin.catalog";

    /** Canonical signature envelope wire format identifier. */
    public static final String SIGNATURE_FORMAT = "turboism.plugin.catalog.signature";

    /** Supported schema version for both the catalog and its signature envelope. */
    public static final int SCHEMA_VERSION = 2;

    /** Required signature algorithm. */
    public static final String ALGORITHM_ED25519 = "Ed25519";

    public PluginCatalog {
        format = Objects.requireNonNull(format, "format");
        if (!FORMAT.equals(format)) {
            throw new IllegalArgumentException("unsupported catalog format: " + format);
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported catalog schemaVersion: " + schemaVersion);
        }
        if (catalogVersion < 1L) {
            throw new IllegalArgumentException("catalogVersion must be positive");
        }
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
        catalogSha256 = Objects.requireNonNull(catalogSha256, "catalogSha256");
        keyId = Objects.requireNonNull(keyId, "keyId");
        plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
    }

    /** Finds a cataloged plugin by its exact plugin identifier. */
    public Optional<CatalogPlugin> findPlugin(final String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return plugins.stream().filter(plugin -> plugin.id().equals(pluginId)).findFirst();
    }

    /**
     * One plugin entry in the verified v2 catalog.
     *
     * @param id unique dotted plugin identifier
     * @param slug unique lowercase kebab-case slug
     * @param name default English display name
     * @param summary default English summary
     * @param trust publication trust tier ({@code "official"} or {@code "reviewed-third-party"})
     * @param author publisher display name
     * @param license license identifier or label
     * @param repository HTTPS repository URL
     * @param support HTTPS support URL
     * @param localizations optional localized name/summary entries keyed by locale tag
     * @param releases retained releases ordered by strictly ascending version
     */
    public record CatalogPlugin(
            String id,
            String slug,
            String name,
            String summary,
            String trust,
            String author,
            String license,
            String repository,
            String support,
            Map<String, CatalogLocalization> localizations,
            List<CatalogRelease> releases) {
        /** Official first-party trust tier value. */
        public static final String TRUST_OFFICIAL = "official";

        /** Reviewed third-party trust tier value. */
        public static final String TRUST_REVIEWED_THIRD_PARTY = "reviewed-third-party";

        public CatalogPlugin {
            id = Objects.requireNonNull(id, "id");
            slug = Objects.requireNonNull(slug, "slug");
            name = Objects.requireNonNull(name, "name");
            summary = Objects.requireNonNull(summary, "summary");
            trust = Objects.requireNonNull(trust, "trust");
            author = Objects.requireNonNull(author, "author");
            license = Objects.requireNonNull(license, "license");
            repository = Objects.requireNonNull(repository, "repository");
            support = Objects.requireNonNull(support, "support");
            localizations = Map.copyOf(Objects.requireNonNull(localizations, "localizations"));
            releases = List.copyOf(Objects.requireNonNull(releases, "releases"));
            if (releases.isEmpty()) {
                throw new IllegalArgumentException("plugin must contain at least one release");
            }
        }

        /** Returns {@code true} when this entry has {@code trust == "official"}. */
        public boolean isOfficial() {
            return TRUST_OFFICIAL.equals(trust);
        }
    }

    /**
     * Localized display name and summary for one locale.
     *
     * @param name localized display name
     * @param summary localized summary
     */
    public record CatalogLocalization(String name, String summary) {
        public CatalogLocalization {
            name = Objects.requireNonNull(name, "name");
            summary = Objects.requireNonNull(summary, "summary");
        }
    }

    /**
     * One immutable plugin release in the verified v2 catalog.
     *
     * @param version strict {@code MAJOR.MINOR.PATCH} release version
     * @param channel release channel ({@code "stable"} or {@code "preview"})
     * @param status release availability status ({@code "active"} or {@code "yanked"})
     * @param publishedAt UTC release publication timestamp
     * @param category release category token
     * @param tags immutable ordered tag tokens
     * @param turboismApi required Turboism API version or half-open range
     * @param requiresCubism whether this release requires a Cubism Editor host
     * @param cubismVersions supported exact Cubism Editor versions when {@code requiresCubism} is true
     * @param platforms supported platform identifiers
     * @param dependencies normalized plugin dependencies
     * @param permissions normalized plugin permissions
     * @param releaseUrl HTTPS release page URL
     * @param sourceRevision optional 40-hex source commit SHA
     * @param artifact immutable JAR artifact metadata
     */
    public record CatalogRelease(
            String version,
            String channel,
            String status,
            Instant publishedAt,
            String category,
            List<String> tags,
            String turboismApi,
            boolean requiresCubism,
            List<String> cubismVersions,
            List<String> platforms,
            List<CatalogDependency> dependencies,
            List<CatalogPermission> permissions,
            String releaseUrl,
            Optional<String> sourceRevision,
            CatalogArtifact artifact) {
        /** Stable release channel value. */
        public static final String CHANNEL_STABLE = "stable";

        /** Preview release channel value. */
        public static final String CHANNEL_PREVIEW = "preview";

        /** Active release status value. */
        public static final String STATUS_ACTIVE = "active";

        /** Yanked release status value. */
        public static final String STATUS_YANKED = "yanked";

        public CatalogRelease {
            version = Objects.requireNonNull(version, "version");
            channel = Objects.requireNonNull(channel, "channel");
            status = Objects.requireNonNull(status, "status");
            publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
            category = Objects.requireNonNull(category, "category");
            tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
            turboismApi = Objects.requireNonNull(turboismApi, "turboismApi");
            cubismVersions = List.copyOf(Objects.requireNonNull(cubismVersions, "cubismVersions"));
            platforms = List.copyOf(Objects.requireNonNull(platforms, "platforms"));
            dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
            permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
            releaseUrl = Objects.requireNonNull(releaseUrl, "releaseUrl");
            sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
            artifact = Objects.requireNonNull(artifact, "artifact");
        }

        /** Returns {@code true} only when this release has {@code status == "active"}. */
        public boolean isActive() {
            return STATUS_ACTIVE.equals(status);
        }

        /** Returns {@code true} when this release was yanked and must not be offered for update. */
        public boolean isYanked() {
            return STATUS_YANKED.equals(status);
        }
    }

    /**
     * Normalized plugin dependency declared by a catalog release.
     *
     * @param id target plugin identifier
     * @param version required version or half-open range
     * @param type dependency requirement type ({@code "required"} or {@code "optional"})
     * @param ordering load ordering hint ({@code "none"}, {@code "before"}, or {@code "after"})
     * @param reason optional human-readable reason
     */
    public record CatalogDependency(String id, String version, String type, String ordering, Optional<String> reason) {
        /** Required dependency type value. */
        public static final String TYPE_REQUIRED = "required";

        /** Optional dependency type value. */
        public static final String TYPE_OPTIONAL = "optional";

        public CatalogDependency {
            id = Objects.requireNonNull(id, "id");
            version = Objects.requireNonNull(version, "version");
            type = Objects.requireNonNull(type, "type");
            ordering = Objects.requireNonNull(ordering, "ordering");
            reason = Objects.requireNonNull(reason, "reason");
        }

        /** Returns {@code true} when this dependency is required. */
        public boolean isRequired() {
            return TYPE_REQUIRED.equals(type);
        }
    }

    /**
     * Normalized permission requirement declared by a catalog release.
     *
     * @param id permission identifier
     * @param scope permission scope ({@code "application"} or {@code "user"})
     * @param reason human-readable reason
     */
    public record CatalogPermission(String id, String scope, String reason) {
        public CatalogPermission {
            id = Objects.requireNonNull(id, "id");
            scope = Objects.requireNonNull(scope, "scope");
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Immutable JAR artifact descriptor bound to a catalog release.
     *
     * @param mediaType exact archive media type ({@code "application/java-archive"})
     * @param fileName archive file name ending in {@code .jar}
     * @param url immutable HTTPS GitHub Release download URL
     * @param sha256 lowercase 64-hex SHA-256 of the JAR bytes
     * @param descriptorSha256 lowercase 64-hex SHA-256 of the embedded {@code META-INF/turboism/plugin.json}
     * @param size exact byte length of the JAR (`1..16 MiB`)
     */
    public record CatalogArtifact(
            String mediaType, String fileName, String url, String sha256, String descriptorSha256, long size) {
        /** Required archive media type. */
        public static final String MEDIA_TYPE = "application/java-archive";

        public CatalogArtifact {
            mediaType = Objects.requireNonNull(mediaType, "mediaType");
            fileName = Objects.requireNonNull(fileName, "fileName");
            url = Objects.requireNonNull(url, "url");
            sha256 = Objects.requireNonNull(sha256, "sha256");
            descriptorSha256 = Objects.requireNonNull(descriptorSha256, "descriptorSha256");
            if (size <= 0L) {
                throw new IllegalArgumentException("size must be positive");
            }
        }
    }
}
