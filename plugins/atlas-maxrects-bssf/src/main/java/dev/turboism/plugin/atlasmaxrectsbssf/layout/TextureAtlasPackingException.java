package dev.turboism.plugin.atlasmaxrectsbssf.layout;

import java.util.Objects;

/** Typed plugin-local planning failure; it does not imply any host mutation. */
public final class TextureAtlasPackingException extends IllegalArgumentException {

    /**
     * Why the planner rejected a texture placement.
     *
     * <p>{@code PAGE_BUDGET_EXHAUSTED} means the issued atlas page budget could not place every
     * texture, {@code ITEM_DOES_NOT_FIT} means the texture cannot fit inside one issued page,
     * and {@code INVALID_RESERVED_SIZE} means the texture's size plus padding exceeds the
     * supported integer geometry.</p>
     */
    public enum Reason {
        PAGE_BUDGET_EXHAUSTED,
        ITEM_DOES_NOT_FIT,
        INVALID_RESERVED_SIZE
    }

    private final String textureId;
    private final Reason reason;

    TextureAtlasPackingException(final String textureId, final Reason reason) {
        super(message(textureId, reason));
        this.textureId = Objects.requireNonNull(textureId, "textureId");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** @return the ID of the texture whose placement was rejected. */
    public String textureId() {
        return textureId;
    }

    /** @return why placement was rejected: page budget exhausted, item too large for one page, or
     *     a reserved size that overflows the supported integer geometry. */
    public Reason reason() {
        return reason;
    }

    private static String message(final String textureId, final Reason reason) {
        return switch (Objects.requireNonNull(reason, "reason")) {
            case PAGE_BUDGET_EXHAUSTED ->
                "The issued atlas page budget cannot place every texture: " + textureId;
            case ITEM_DOES_NOT_FIT ->
                "The texture cannot fit inside one issued atlas page: " + textureId;
            case INVALID_RESERVED_SIZE ->
                "Texture size plus padding exceeds the supported integer geometry: " + textureId;
        };
    }
}
