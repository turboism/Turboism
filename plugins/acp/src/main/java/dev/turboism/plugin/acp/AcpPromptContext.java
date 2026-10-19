package dev.turboism.plugin.acp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Offline role, curated modeling summary, and verified documentation entry points. */
final class AcpPromptContext {
    static final String REFERENCE = loadReference();

    private AcpPromptContext() {}

    private static String loadReference() {
        try (var input = AcpPromptContext.class.getResourceAsStream("/context/modeling-reference.md")) {
            if (input == null) throw new IllegalStateException("Missing modeling reference context");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not load modeling reference context", failure);
        }
    }
}
