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

    public record Entry(Object palette, Object siblingAnchor, Object originalBox) {
        public Entry {
            Objects.requireNonNull(palette, "palette");
        }
    }
}
