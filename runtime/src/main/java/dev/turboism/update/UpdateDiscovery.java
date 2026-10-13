package dev.turboism.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Validated, immutable projection of one {@code api.turboism.dev} release-channel document.
 *
 * <p>Only the fields needed to decide whether a newer product build exists are
 * retained. Asset URLs and free-form notes from the response are deliberately not
 * carried into the client: the download entry uses a fixed first-party page.</p>
 */
public record UpdateDiscovery(int schemaVersion, String channel, Status status, Optional<Candidate> candidate) {
    public static final int SCHEMA_VERSION = 1;
    public static final String CHANNEL_STABLE = "stable";
    public static final String CHANNEL_BETA = "beta";
    public static final String CHANNEL_NIGHTLY = "nightly";

    /** Maximum accepted build number; mirrors the JSON safe-integer bound. */
    public static final long MAX_BUILD_NUMBER = 9_007_199_254_740_991L;

    /** Returns whether {@code channel} is one of the supported product release channels. */
    public static boolean isSupportedChannel(final String channel) {
        return CHANNEL_STABLE.equals(channel) || CHANNEL_BETA.equals(channel) || CHANNEL_NIGHTLY.equals(channel);
    }

    /** Published state of the requested release channel. */
    public enum Status {
        READY,
        NOT_PUBLISHED,
        UNAVAILABLE
    }

    public UpdateDiscovery {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported discovery schema version");
        }
        channel = Objects.requireNonNull(channel, "channel");
        if (channel.isBlank() || channel.length() > 32) {
            throw new IllegalArgumentException("channel is invalid");
        }
        status = Objects.requireNonNull(status, "status");
        candidate = Objects.requireNonNull(candidate, "candidate");
        if ((status == Status.READY) != candidate.isPresent()) {
            throw new IllegalArgumentException("only a ready document carries a release candidate");
        }
    }

    /** Returns the advertised candidate, present only for a ready document. */
    public Optional<Candidate> cachedCandidate() {
        return candidate;
    }

    /** True when the channel exists but has no published build yet. */
    public boolean notPublished() {
        return status == Status.NOT_PUBLISHED;
    }

    /**
     * Serializes the validated record in the same reduced wire shape the parser
     * accepts, so a cached restart result is re-validated by one contract.
     */
    ObjectNode toJson(final ObjectMapper mapper) {
        final ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", schemaVersion);
        root.put("channel", channel);
        root.put("status", statusWireName());
        candidate.ifPresent(value -> {
            final ObjectNode node = root.putObject("release");
            node.put("version", value.versionText());
            if (value.buildNumber().isPresent()) {
                node.put("buildNumber", value.buildNumber().getAsLong());
            }
            value.publishedAt().ifPresent(instant -> node.put("publishedAt", instant.toString()));
        });
        return root;
    }

    String statusWireName() {
        return status.name().toLowerCase(Locale.ROOT);
    }

    /** One published product build offered by the requested channel. */
    public record Candidate(
            UpdateVersion version, String versionText, OptionalLong buildNumber, Optional<Instant> publishedAt) {
        /** Creates a candidate whose wire version text equals the canonical numeric version. */
        public Candidate(
                final UpdateVersion version, final OptionalLong buildNumber, final Optional<Instant> publishedAt) {
            this(Objects.requireNonNull(version, "version"), version.toString(), buildNumber, publishedAt);
        }

        public Candidate {
            version = Objects.requireNonNull(version, "version");
            versionText = Objects.requireNonNull(versionText, "versionText");
            if (versionText.isBlank()) {
                throw new IllegalArgumentException("versionText must not be blank");
            }
            buildNumber = Objects.requireNonNull(buildNumber, "buildNumber");
            publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
            if (buildNumber.isPresent()) {
                final long value = buildNumber.getAsLong();
                if (value <= 0 || value > MAX_BUILD_NUMBER) {
                    throw new IllegalArgumentException("buildNumber is outside the allowed range");
                }
            }
        }

        /** Human-readable candidate identity, including the recorded build number when present. */
        public String identity() {
            return buildNumber.isPresent() ? versionText + " (Build " + buildNumber.getAsLong() + ")" : versionText;
        }
    }
}
