package dev.turboism.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasAutoLayoutSelection;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Persists the runtime-owned texture-atlas automatic-layout selection into the canonical
 * {@code config.json} under {@code "textureAtlas": {"algorithmId": ..., "parallel": ...}}.
 * Read failures resolve to the native default; write failures are reported through the
 * diagnostic sink. Neither escapes into callers: selection persistence must never break
 * automatic-layout dispatch.
 */
public final class ConfigTextureAtlasSelectionStore implements TextureAtlasAutoLayoutSelection.Persistence {

    private final RuntimeConfigRepository config;
    private final Consumer<String> diagnostic;

    public ConfigTextureAtlasSelectionStore(final Path turboismHome, final Consumer<String> diagnostic) {
        this(new RuntimeConfigRepository(turboismHome, diagnostic), diagnostic);
    }

    public ConfigTextureAtlasSelectionStore(
        final RuntimeConfigRepository config,
        final Consumer<String> diagnostic
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    @Override
    public TextureAtlasLayoutSelection load() {
        final JsonNode section;
        try {
            section = config.read().path("textureAtlas");
        } catch (RuntimeException failure) {
            report("RUNTIME_CONFIG_UNREADABLE");
            return TextureAtlasLayoutSelection.nativeDefault();
        }
        final JsonNode algorithmNode = section.path("algorithmId");
        // A present-but-null algorithmId was written by an explicit native choice,
        // while an absent key means no selection was ever made (the unset state
        // that one-time migrations may still fill).
        final String algorithmId = algorithmNode.isTextual()
            ? algorithmNode.asText()
            : section.has("algorithmId")
                ? TextureAtlasLayoutSelection.NATIVE_ALGORITHM_ID
                : null;
        return new TextureAtlasLayoutSelection(
            algorithmId,
            section.path("parallel").asBoolean(false)
        );
    }

    @Override
    public void save(final TextureAtlasLayoutSelection selection) {
        Objects.requireNonNull(selection, "selection");
        try {
            config.update(root -> {
                final ObjectNode section = root.putObject("textureAtlas");
                if (selection.algorithmId() == null) {
                    section.putNull("algorithmId");
                } else {
                    section.put("algorithmId", selection.algorithmId());
                }
                section.put("parallel", selection.parallel());
                return root;
            });
        } catch (RuntimeException failure) {
            report("RUNTIME_CONFIG_WRITE_FAILED");
        }
    }

    private void report(final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
        }
    }
}
