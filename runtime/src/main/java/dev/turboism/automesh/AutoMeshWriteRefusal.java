package dev.turboism.automesh;

/**
 * Refusal of one write that the Editor would not accept.
 *
 * <p>The writer raises this before or instead of a partial replacement, so the caller reports one
 * localized reason and no target changes. {@link #reasonCode()} is the stable machine token a
 * caller matches on; {@link #getMessage()} is the text the user reads.</p>
 */
public final class AutoMeshWriteRefusal extends Exception {

    private static final long serialVersionUID = 1L;

    private final String reasonCode;

    /**
     * Creates the refusal.
     *
     * @param reasonCode stable machine token of the refusal
     * @param message user-facing explanation
     */
    public AutoMeshWriteRefusal(final String reasonCode, final String message) {
        super(message);
        this.reasonCode = java.util.Objects.requireNonNull(reasonCode, "reasonCode");
    }

    /** @return the stable machine token of this refusal */
    public String reasonCode() {
        return reasonCode;
    }
}
