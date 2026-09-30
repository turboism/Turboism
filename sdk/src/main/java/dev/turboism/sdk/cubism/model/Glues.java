package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/** Glue relations in one Cubism model. */
@CubismEditor({
    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03",
    "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
})
public interface Glues {

    /** Returns every glue relation in the model, in host order. */
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
