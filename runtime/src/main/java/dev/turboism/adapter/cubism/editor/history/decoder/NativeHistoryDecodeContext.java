package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Per-top-level-entry decode budgets and identity-cycle protection. */
final class NativeHistoryDecodeContext {

    static final int MAX_DEPTH = 4;
    static final int MAX_NODES = 64;
    static final int MAX_STRING_LENGTH = 256;

    private final VerifiedMemberResolver resolver;
    private final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
    private final boolean livePostStateAllowed;
    private final Set<Object> livePostStateWithheld =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private int nodes;

    /** Creates a context that refuses every live-target read, which is the safe default. */
    NativeHistoryDecodeContext(final VerifiedMemberResolver resolver) {
        this(resolver, false);
    }

    /**
     * Creates a context that may read a live target as an entry's post state.
     *
     * @param livePostStateAllowed whether the caller has proved the entry being decoded is the
     *     undo manager's current tip, so no later edit has overwritten it
     */
    NativeHistoryDecodeContext(
        final VerifiedMemberResolver resolver,
        final boolean livePostStateAllowed
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.livePostStateAllowed = livePostStateAllowed;
    }

    VerifiedMemberResolver resolver() {
        return resolver;
    }

    /**
     * Calculates whether an entry's live target may be read as that entry's post state.
     *
     * <p>The host never stores a {@code SimpleUndo}'s post state: the constructor assigns
     * {@code targetData} and {@code undoData} only, and {@code redoData} is filled in lazily inside
     * {@code undo()}. A freshly committed entry therefore has no historical post state at all, and
     * the live target is the only value shaped like one. Reading it is sound only while both of
     * these hold, and the caller has to prove each:</p>
     *
     * <ul>
     *   <li>the entry is still the undo manager's current tip, so no later edit replaced it;</li>
     *   <li>no later sibling inside the same entry writes the same object, so the live value is
     *       still this entry's own result and not a later sibling's.</li>
     * </ul>
     *
     * <p>The value is read immediately and never retained, so a detail built from it carries
     * bounded scalars only and cannot leak the host object.</p>
     *
     * @param entry the native entry being decoded
     * @return whether the live target may stand in for this entry's post state
     */
    boolean mayReadLiveTarget(final Object entry) {
        return livePostStateAllowed
            && entry != null
            && !livePostStateWithheld.contains(entry);
    }

    /**
     * Withholds the live-target read for one entry a caller proved is not its object's last
     * writer.
     *
     * @param entry the native entry whose live target must not be read
     */
    void withholdLivePostState(final Object entry) {
        if (entry != null) livePostStateWithheld.add(entry);
    }

    boolean enter(final Object entry, final int depth) {
        if (entry == null || depth > MAX_DEPTH || nodes >= MAX_NODES || visited.containsKey(entry)) {
            return false;
        }
        visited.put(entry, Boolean.TRUE);
        nodes++;
        return true;
    }

    String boundedLabel(final String value) {
        final String text = Objects.requireNonNull(value, "value").strip();
        final String nonBlank = text.isEmpty() ? "History entry" : text;
        return nonBlank.length() <= MAX_STRING_LENGTH
            ? nonBlank
            : nonBlank.substring(0, MAX_STRING_LENGTH);
    }

    ProjectedValue safeValue(final Object value) {
        if (value == null) return new ProjectedValue(java.util.Optional.empty(), false);
        final String text;
        if (value instanceof String string) {
            text = string;
        } else if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            text = String.valueOf(value);
        } else if (value instanceof Enum<?> enumeration) {
            text = enumeration.name();
        } else {
            return new ProjectedValue(java.util.Optional.empty(), false);
        }
        if (text.length() <= MAX_STRING_LENGTH) {
            return new ProjectedValue(java.util.Optional.of(text), false);
        }
        return new ProjectedValue(
            java.util.Optional.of(text.substring(0, MAX_STRING_LENGTH)),
            true
        );
    }

    String className(final Object value) {
        if (value == null) return "unknown";
        final String name = value.getClass().getName();
        return name.length() <= MAX_STRING_LENGTH
            ? name
            : name.substring(0, MAX_STRING_LENGTH);
    }

    record ProjectedValue(java.util.Optional<String> value, boolean truncated) {
    }
}
