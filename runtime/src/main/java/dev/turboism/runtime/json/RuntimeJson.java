package dev.turboism.runtime.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Objects;

/**
 * The single construction point for every Jackson mapper the runtime reads or writes with.
 *
 * <p>Three profiles cover the whole runtime: {@link #standard()} for documents the runtime owns
 * and rewrites, {@link #strict()} for bytes that arrive from outside the process, and
 * {@link #canonical()} for documents whose bytes must be reproducible. Keeping the profiles here
 * means a hardening change lands once instead of in twenty-eight copies.
 *
 * <p>Every returned mapper is fully configured and safe to share: Jackson caches are
 * configuration-scoped, so one instance per profile is both cheaper and stricter than a fresh
 * mapper per call site.
 *
 * <p>Deliberately free of {@code dev.turboism} imports: it sits at the bottom of the
 * {@code :runtime} layering, so any package may use it without creating a dependency cycle.
 */
public final class RuntimeJson {

    /**
     * Duplicate object keys are rejected and trailing tokens are refused: one document, one
     * reading. A verifier that accepted either could be shown two different values for one field.
     */
    private static final ObjectMapper STRICT = configureStrict(new ObjectMapper(strictFactory()));

    /** Deterministic output ordering, so a rebuilt document is byte-identical to its predecessor. */
    private static final ObjectMapper CANONICAL =
            new ObjectMapper(new JsonFactory()).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static final ObjectMapper STANDARD = new ObjectMapper();

    private RuntimeJson() {}

    /**
     * Returns the shared default mapper.
     *
     * <p>Use it for documents the runtime itself produced. Do not use it on untrusted bytes:
     * it accepts duplicate keys and trailing tokens.
     *
     * @return the shared default mapper
     */
    public static ObjectMapper standard() {
        return STANDARD;
    }

    /**
     * Returns the shared strict mapper: duplicate keys and trailing tokens are rejected.
     *
     * <p>Use it for every document that crosses a trust boundary — manifests, catalogs, update
     * state, journals, preview reports and descriptors. The parser closes the underlying source
     * it does not own, so a caller-supplied stream is drained exactly once.
     *
     * @return the shared strict mapper
     */
    public static ObjectMapper strict() {
        return STRICT;
    }

    /**
     * Returns a strict mapper that additionally enforces the supplied stream constraints.
     *
     * <p>Use it when a call site must bound nesting depth, document length or token size tighter
     * than the Jackson defaults. The constraints are never relaxed here: a caller can only pass
     * the exact limits its format requires.
     *
     * @param constraints the read limits the parser must enforce
     * @return a strict mapper bound to {@code constraints}
     * @throws NullPointerException if {@code constraints} is {@code null}
     */
    public static ObjectMapper strict(final StreamReadConstraints constraints) {
        return configureStrict(new ObjectMapper(strictFactory(constraints)));
    }

    /**
     * Returns the shared canonical mapper: map entries and properties serialize in sorted order.
     *
     * <p>Use it for documents whose bytes are compared, hashed or committed, so the same logical
     * document always produces the same bytes.
     *
     * @return the shared canonical mapper
     */
    public static ObjectMapper canonical() {
        return CANONICAL;
    }

    /**
     * Builds a parser factory that rejects duplicate keys.
     *
     * @return a factory with duplicate-key detection enabled
     */
    public static JsonFactory strictFactory() {
        return JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    /**
     * Builds a parser factory that rejects duplicate keys and enforces {@code constraints}.
     *
     * @param constraints the read limits the parser must enforce
     * @return a factory with duplicate-key detection and the supplied limits
     * @throws NullPointerException if {@code constraints} is {@code null}
     */
    public static JsonFactory strictFactory(final StreamReadConstraints constraints) {
        return JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(Objects.requireNonNull(constraints, "constraints"))
                .build();
    }

    private static ObjectMapper configureStrict(final ObjectMapper mapper) {
        return mapper.enable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
}
