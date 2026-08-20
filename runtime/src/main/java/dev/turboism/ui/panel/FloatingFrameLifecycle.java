package dev.turboism.ui.panel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Generation-local ownership and idempotence for floating-frame close cleanup. */
public final class FloatingFrameLifecycle {

    private static final class Anchor {
        private final Object siblingAnchor;
        private final Object originalBox;

        private Anchor(final Object siblingAnchor, final Object originalBox) {
            this.siblingAnchor = siblingAnchor;
            this.originalBox = originalBox;
        }
    }

    private final Map<Object, Map<Object, Anchor>> entriesByFrame = new IdentityHashMap<>();
    private final Map<Object, Object> frameByPalette = new IdentityHashMap<>();

    /**
     * Records where a palette came from so its floating frame can put it back on close.
     *
     * <p>A palette belongs to exactly one frame: re-remembering it under a different frame
     * detaches it from the previous frame first, and drops that frame's entry once it holds
     * nothing. Frames, palettes and anchors are tracked by identity, not equality.
     *
     * @param frame the floating frame now hosting the palette
     * @param palette the palette that was floated
     * @param siblingAnchor the component the palette sat next to before floating, used to restore
     *     its position; may be {@code null} when none was recorded
     * @param originalBox the container the palette was taken from; may be {@code null}
     * @throws NullPointerException if {@code frame} or {@code palette} is {@code null}
     */
    public synchronized void remember(
        final Object frame,
        final Object palette,
        final Object siblingAnchor,
        final Object originalBox
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(palette, "palette");
        final Object previousFrame = frameByPalette.put(palette, frame);
        if (previousFrame != null && previousFrame != frame) {
            final Map<Object, Anchor> previousEntries = entriesByFrame.get(previousFrame);
            if (previousEntries != null) {
                previousEntries.remove(palette);
                if (previousEntries.isEmpty()) {
                    entriesByFrame.remove(previousFrame);
                }
            }
        }
        entriesByFrame.computeIfAbsent(frame, ignored -> new IdentityHashMap<>())
            .put(palette, new Anchor(siblingAnchor, originalBox));
    }

    /**
     * Claims the cleanup work for a closing frame, exactly once.
     *
     * <p>The frame's entries are removed as they are returned, so a duplicate close callback for
     * the same frame yields an empty list rather than restoring a palette twice. A palette that
     * has since been re-remembered under a different frame is left alone.
     *
     * @param frame the frame being closed
     * @return an immutable list of the palettes this call owns and where to put them back; empty
     *     when another call already claimed them or the frame was never remembered
     * @throws NullPointerException if {@code frame} is {@code null}
     */
    public synchronized List<Entry> beginClose(final Object frame) {
        Objects.requireNonNull(frame, "frame");
        final Map<Object, Anchor> entries = entriesByFrame.remove(frame);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        final List<Entry> result = new ArrayList<>(entries.size());
        for (Map.Entry<Object, Anchor> entry : entries.entrySet()) {
            if (frameByPalette.get(entry.getKey()) == frame) {
                frameByPalette.remove(entry.getKey());
                result.add(new Entry(
                    entry.getKey(),
                    entry.getValue().siblingAnchor,
                    entry.getValue().originalBox
                ));
            }
        }
        return List.copyOf(result);
    }

    /** Removes one palette from its frame without starting a frame close. */
    public synchronized void forget(final Object palette) {
        Objects.requireNonNull(palette, "palette");
        final Object frame = frameByPalette.remove(palette);
        if (frame == null) {
            return;
        }
        final Map<Object, Anchor> entries = entriesByFrame.get(frame);
        if (entries != null) {
            entries.remove(palette);
            if (entries.isEmpty()) {
                entriesByFrame.remove(frame);
            }
        }
    }

    /**
     * One palette's restoration target, as claimed by {@link #beginClose(Object)}.
     *
     * @param palette the palette to restore; never {@code null}
     * @param siblingAnchor the component it should be reinserted next to, or {@code null} if none
     *     was recorded
     * @param originalBox the container it should be reinserted into, or {@code null} if none was
     *     recorded
     * @throws NullPointerException if {@code palette} is {@code null}
     */
    public record Entry(Object palette, Object siblingAnchor, Object originalBox) {
        public Entry {
            Objects.requireNonNull(palette, "palette");
        }
    }
}
