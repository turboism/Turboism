package dev.turboism.adapter.cubism.editor.history.decoder;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Bounded typed value codecs used only after an exact semantic descriptor matches. */
final class SemanticHistoryValueCodec {

    private static final Pattern RGB = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern RGBA = Pattern.compile("#[0-9a-fA-F]{8}");

    private SemanticHistoryValueCodec() { }

    static Optional<String> color(final String value) {
        final String normalized = Objects.requireNonNull(value, "value").strip();
        if (!RGB.matcher(normalized).matches() && !RGBA.matcher(normalized).matches()) {
            return Optional.empty();
        }
        return Optional.of(normalized.toLowerCase(Locale.ROOT));
    }

    static String rgb(final int red, final int green, final int blue) {
        return String.format(
            Locale.ROOT,
            "#%02x%02x%02x",
            channel(red),
            channel(green),
            channel(blue)
        );
    }

    private static int channel(final int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("color channel must be between 0 and 255");
        }
        return value;
    }
}
