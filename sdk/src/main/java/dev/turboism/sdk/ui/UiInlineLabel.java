package dev.turboism.sdk.ui;

import dev.turboism.sdk.ui.resource.UiIconRef;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, toolkit-neutral inline label made from literal text and typed icon runs.
 *
 * <p>An icon run always carries localized fallback text. The fallback is part of the immutable
 * label and is used both when Runtime cannot resolve the icon and when an accessible name is
 * created. Text is retained as literal text; it is never interpreted as HTML, a URL, or a native
 * resource address.</p>
 */
public final class UiInlineLabel {
    /** Maximum number of text/icon runs in one label. */
    public static final int MAX_RUNS = 32;
    /** Maximum literal text or icon fallback length in one run. */
    public static final int MAX_RUN_TEXT_LENGTH = 256;
    /** Maximum concatenated literal/fallback text length in one label. */
    public static final int MAX_TOTAL_TEXT_LENGTH = 4_096;

    private final List<Run> runs;
    private final String fallbackText;

    /**
     * Creates an immutable label from a defensive snapshot of the supplied runs.
     *
     * @param runs text and icon runs in display order
     * @throws NullPointerException if the list, an element, or an element field is {@code null}
     * @throws IllegalArgumentException if the label is empty, over the run/text bounds, or has no
     *     meaningful fallback text
     */
    public UiInlineLabel(final List<? extends Run> runs) {
        final List<Run> snapshot = List.copyOf(Objects.requireNonNull(runs, "runs"));
        if (snapshot.isEmpty() || snapshot.size() > MAX_RUNS) {
            throw new IllegalArgumentException("runs must contain between 1 and " + MAX_RUNS + " entries");
        }

        final StringBuilder fallback = new StringBuilder();
        for (Run run : snapshot) {
            Objects.requireNonNull(run, "run");
            final String text = run instanceof TextRun textRun
                ? textRun.text()
                : ((IconRun) run).fallbackText();
            if (fallback.length() > MAX_TOTAL_TEXT_LENGTH - text.length()) {
                throw new IllegalArgumentException(
                    "concatenated label text exceeds " + MAX_TOTAL_TEXT_LENGTH + " characters"
                );
            }
            fallback.append(text);
        }
        if (fallback.toString().isBlank()) {
            throw new IllegalArgumentException("label must contain meaningful text or icon fallback text");
        }

        this.runs = snapshot;
        this.fallbackText = fallback.toString();
    }

    /** Creates an immutable label from the supplied runs. */
    public UiInlineLabel(final Run... runs) {
        this(Arrays.asList(Objects.requireNonNull(runs, "runs")));
    }

    /** Creates an immutable label from the supplied runs. */
    public static UiInlineLabel of(final Run... runs) {
        return new UiInlineLabel(runs);
    }

    /** Creates an immutable label from the supplied runs. */
    public static UiInlineLabel of(final List<? extends Run> runs) {
        return new UiInlineLabel(runs);
    }

    /** Creates a literal-text-only inline label. */
    public static UiInlineLabel text(final String value) {
        return new UiInlineLabel(new TextRun(value));
    }

    /** Creates an icon-only inline label with its mandatory localized fallback text. */
    public static UiInlineLabel icon(final UiIconRef icon, final String fallbackText) {
        return new UiInlineLabel(new IconRun(icon, fallbackText));
    }

    /** Creates a literal text run. */
    public static TextRun textRun(final String value) {
        return new TextRun(value);
    }

    /** Creates an icon run with its mandatory localized fallback text. */
    public static IconRun iconRun(final UiIconRef icon, final String fallbackText) {
        return new IconRun(icon, fallbackText);
    }

    /** Returns the immutable run sequence in display order. */
    public List<Run> runs() {
        return runs;
    }

    /**
     * Returns literal text with each icon replaced by its required localized fallback text.
     *
     * <p>This is the safe accessible-name/fallback representation. It does not indicate that an
     * icon is unavailable; Runtime may still display the icon while retaining this text for
     * accessibility.</p>
     */
    public String fallbackText() {
        return fallbackText;
    }

    /** Alias for {@link #fallbackText()} used when constructing accessible names. */
    public String accessibleText() {
        return fallbackText;
    }

    /** Returns whether this label contains no icon runs. */
    public boolean isTextOnly() {
        return runs.stream().noneMatch(IconRun.class::isInstance);
    }

    @Override
    public boolean equals(final Object other) {
        return this == other || other instanceof UiInlineLabel label && runs.equals(label.runs);
    }

    @Override
    public int hashCode() {
        return runs.hashCode();
    }

    @Override
    public String toString() {
        return "UiInlineLabel[runs=" + runs + "]";
    }

    /** One literal text or typed icon run. */
    public sealed interface Run permits TextRun, IconRun { }

    /** A literal text run; its contents are never treated as markup. */
    public record TextRun(String text) implements Run {
        public TextRun {
            Objects.requireNonNull(text, "text");
            if (text.length() > MAX_RUN_TEXT_LENGTH) {
                throw new IllegalArgumentException(
                    "text exceeds " + MAX_RUN_TEXT_LENGTH + " characters"
                );
            }
        }
    }

    /** A typed icon run with mandatory localized text for fallback and accessibility. */
    public record IconRun(UiIconRef icon, String fallbackText) implements Run {
        public IconRun {
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(fallbackText, "fallbackText");
            if (fallbackText.isBlank() || fallbackText.length() > MAX_RUN_TEXT_LENGTH) {
                throw new IllegalArgumentException(
                    "fallbackText must be bounded non-blank text"
                );
            }
        }

    }
}
