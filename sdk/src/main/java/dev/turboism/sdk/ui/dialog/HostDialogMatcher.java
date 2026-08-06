package dev.turboism.sdk.ui.dialog;

import java.util.Objects;
import java.util.Optional;

/**
 * Target condition for a host dialog; all-empty matches any confirmation dialog.
 *
 * <p>The runtime matches against the host JVM's own AWT window tree only; the
 * matcher never carries coordinates, native handles, or Swing types.</p>
 */
public record HostDialogMatcher(
    Optional<String> windowClassPrefix,
    Optional<Integer> optionType
) {

    public HostDialogMatcher {
        windowClassPrefix = windowClassPrefix.map(String::strip).filter(prefix -> !prefix.isEmpty());
        Objects.requireNonNull(windowClassPrefix, "windowClassPrefix");
        Objects.requireNonNull(optionType, "optionType");
        if (optionType.isPresent() && (optionType.get() < 0 || optionType.get() > 3)) {
            throw new IllegalArgumentException("optionType must be 0..3: " + optionType.get());
        }
    }

    /** Matches any confirmation dialog regardless of window class or option type. */
    public static HostDialogMatcher anyConfirmation() {
        return new HostDialogMatcher(Optional.empty(), Optional.empty());
    }
}
