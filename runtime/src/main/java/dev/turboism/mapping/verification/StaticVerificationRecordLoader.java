package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.schema.SchemaValidationError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Parses static verification records from one immutable in-memory byte snapshot. */
final class StaticVerificationRecordLoader {

    private final ObjectMapper mapper;
    private final StaticVerificationRecordValidator validator;

    StaticVerificationRecordLoader() {
        this(new ObjectMapper(), new StaticVerificationRecordValidator());
    }

    StaticVerificationRecordLoader(
        final ObjectMapper mapper,
        final StaticVerificationRecordValidator validator
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    LoadedRecord load(final Path recordPath) throws IOException {
        Objects.requireNonNull(recordPath, "recordPath");
        final byte[] bytes = Files.readAllBytes(recordPath);
        final JsonNode root = mapper.readTree(bytes);
        final List<SchemaValidationError> errors = validator.validate(root, recordPath.toString());
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid static verification record: " + errors);
        }
        final JsonNode artifactNode = root.get("artifact");
        final HostArtifactFingerprint artifact = new HostArtifactFingerprint(
            root.get("cubismVersion").asText(),
            artifactNode.get("size").asLong(),
            artifactNode.get("sha256").asText()
        );
        final List<String> capabilityIds = new ArrayList<>();
        root.get("capabilityIds").forEach(node -> capabilityIds.add(node.asText()));
        final List<StaticSelector> selectors = new ArrayList<>();
        root.get("selectors").forEach(node -> selectors.add(selector(node)));
        final StaticVerificationRecord record = new StaticVerificationRecord(
            root.get("verificationId").asText(),
            root.get("adapterSliceId").asText(),
            capabilityIds,
            root.get("cubismVersion").asText(),
            root.get("profileId").asText(),
            artifact,
            root.get("evidencePath").asText(),
            root.get("owner").asText(),
            root.get("verifiedBy").asText(),
            Instant.parse(root.get("verifiedAt").asText()),
            root.get("safeMode").asText(),
            selectors
        );
        return new LoadedRecord(record, HexFormat.of().formatHex(
            HostArtifactDigest.sha256Digest().digest(bytes)
        ));
    }

    private StaticSelector selector(final JsonNode node) {
        final StaticSelector.Kind kind = StaticSelector.Kind.valueOf(node.get("kind").asText().toUpperCase());
        final String memberName = node.hasNonNull("memberName") ? node.get("memberName").asText() : "";
        final String descriptor = node.hasNonNull("descriptor") ? node.get("descriptor").asText() : "";
        return new StaticSelector(
            node.get("mappingId").asText(),
            node.get("alias").asText(),
            kind,
            node.get("ownerInternalName").asText(),
            memberName,
            descriptor,
            node.get("requiredAccessFlags").asInt(),
            node.get("forbiddenAccessFlags").asInt()
        );
    }

    record LoadedRecord(StaticVerificationRecord record, String sha256) {
        LoadedRecord {
            record = Objects.requireNonNull(record, "record");
            sha256 = Objects.requireNonNull(sha256, "sha256");
        }
    }
}
