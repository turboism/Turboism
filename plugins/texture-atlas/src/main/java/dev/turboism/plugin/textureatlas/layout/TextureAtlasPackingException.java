package dev.turboism.plugin.textureatlas.layout;

import java.util.Objects;

/** Typed plugin-local planning failure; it does not imply any host mutation. */
public final class TextureAtlasPackingException extends IllegalArgumentException {

    public enum Reason {
        NO_CANDIDATE_PLAN,
        INVALID_RESERVED_SIZE
    }

    private final String textureId;
    private final Reason reason;

    TextureAtlasPackingException(final String textureId, final Reason reason) {
        super(message(textureId, reason));
        this.textureId = Objects.requireNonNull(textureId, "textureId");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public String textureId() {
        return textureId;
    }

    public Reason reason() {
        return reason;
    }

    private static String message(final String textureId, final Reason reason) {
        return switch (Objects.requireNonNull(reason, "reason")) {
            case NO_CANDIDATE_PLAN ->
                "No approved deterministic candidate produced an atlas plan: " + textureId;
            case INVALID_RESERVED_SIZE ->
                "Texture size plus padding exceeds the supported integer geometry: " + textureId;
        };
    }
}
