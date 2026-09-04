package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/** Glue relations in one Cubism model. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface Glues {

    List<Glue> all();

    /** @throws NoSuchElementException when the ID is absent */
    Glue find(GlueId id);

    /**
     * Returns the exact Cubism Editor version of the verified Glue provider.
     *
     * <p>Core-only or otherwise unversioned providers return empty. Callers must compare the
     * returned text by exact equality; this is not a version range.</p>
     *
     * @return exact active Editor version, or empty when no versioned Editor provider is bound
     */
    default Optional<String> providerVersion() {
        return Optional.empty();
    }
}
