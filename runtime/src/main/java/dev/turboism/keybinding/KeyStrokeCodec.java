package dev.turboism.keybinding;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.swing.KeyStroke;

/**
 * Canonical {@code "Ctrl+Alt+Shift+S"}-style text form for {@link KeyStroke}s.
 *
 * <p>Only key-code strokes (the shape produced by {@link KeyStroke#getKeyStrokeForEvent} for
 * {@link KeyEvent#KEY_PRESSED}/{@link KeyEvent#KEY_RELEASED}) are representable; key-typed
 * strokes are rejected. Modifier text uses the extended {@code *_DOWN_MASK} bits, which is
 * also the canonical comparison form: {@link #normalize(KeyStroke)} rebuilds any stroke so
 * persisted text and live event strokes compare equal regardless of which modifier mask
 * flavor a given JDK puts into an event.</p>
 */
public final class KeyStrokeCodec {

    private static final Map<String, Integer> KEYS_BY_NAME;
    private static final Map<Integer, String> NAMES_BY_KEY;
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("ESC", "ESCAPE"),
            Map.entry("DEL", "DELETE"),
            Map.entry("INS", "INSERT"),
            Map.entry("PGUP", "PAGEUP"),
            Map.entry("PGDN", "PAGEDOWN"),
            Map.entry("RETURN", "ENTER"),
            Map.entry("BKSP", "BACKSPACE"),
            Map.entry("-", "MINUS"),
            Map.entry("=", "EQUALS"),
            Map.entry(",", "COMMA"),
            Map.entry(".", "PERIOD"),
            Map.entry("/", "SLASH"),
            Map.entry("\\", "BACKSLASH"),
            Map.entry(";", "SEMICOLON"),
            Map.entry("[", "OPENBRACKET"),
            Map.entry("]", "CLOSEBRACKET"),
            Map.entry("`", "BACKQUOTE"),
            Map.entry("'", "QUOTE"),
            Map.entry("CONTROL", "CTRL"));

    static {
        final Map<String, Integer> byName = new LinkedHashMap<>();
        final Map<Integer, String> byKey = new LinkedHashMap<>();
        for (Field field : KeyEvent.class.getFields()) {
            final String name = field.getName();
            if (!name.startsWith("VK_") || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                final int code = field.getInt(null);
                final String canonical = canonicalName(name.substring(3));
                byName.putIfAbsent(canonical.toUpperCase(Locale.ROOT), code);
                byKey.putIfAbsent(code, canonical);
            } catch (IllegalAccessException ignored) {
                // public static fields are always readable; a reflective miss just drops the name
            }
        }
        KEYS_BY_NAME = Map.copyOf(byName);
        NAMES_BY_KEY = Map.copyOf(byKey);
    }

    private KeyStrokeCodec() {}

    /**
     * Parses {@code text} such as {@code "Ctrl+S"} or {@code "Alt+Shift+F4"} into a key-code
     * stroke. Modifier names are case-insensitive ({@code ctrl}, {@code control}, {@code alt},
     * {@code option}, {@code shift}, {@code meta}, {@code cmd}, {@code command}); the final
     * segment is a single character or a key name taken from {@link KeyEvent}'s {@code VK_*}
     * inventory (underscores/spaces are ignored, and common aliases like {@code esc},
     * {@code del}, {@code pgup} are accepted).
     *
     * @throws IllegalArgumentException if the text is blank, has an unknown key or modifier,
     *     or names no non-modifier key
     */
    public static KeyStroke parse(final String text) {
        Objects.requireNonNull(text, "text");
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("keystroke must not be blank");
        }
        final String[] segments = trimmed.split("\\+", -1);
        if (segments.length == 0 || segments[segments.length - 1].isEmpty()) {
            throw new IllegalArgumentException("keystroke must name a key: " + text);
        }
        int modifiers = 0;
        for (int i = 0; i < segments.length - 1; i++) {
            modifiers |= modifier(segment(segments[i]), text);
        }
        final int keyCode = keyCode(segment(segments[segments.length - 1]), text);
        if (keyCode == KeyEvent.VK_CONTROL
                || keyCode == KeyEvent.VK_ALT
                || keyCode == KeyEvent.VK_SHIFT
                || keyCode == KeyEvent.VK_META) {
            throw new IllegalArgumentException("keystroke must end with a non-modifier key: " + text);
        }
        return KeyStroke.getKeyStroke(keyCode, modifiers);
    }

    /** Canonical text form of {@code stroke}: {@code "Ctrl+Alt+Shift+S"}. */
    public static String encode(final KeyStroke stroke) {
        Objects.requireNonNull(stroke, "stroke");
        final KeyStroke normalized = normalize(stroke);
        final StringBuilder text = new StringBuilder();
        final int modifiers = normalized.getModifiers();
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) text.append("Ctrl+");
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) text.append("Alt+");
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) text.append("Shift+");
        if ((modifiers & InputEvent.META_DOWN_MASK) != 0) text.append("Meta+");
        final String name = NAMES_BY_KEY.get(normalized.getKeyCode());
        text.append(
                name != null
                        ? name
                        : "0x" + Integer.toHexString(normalized.getKeyCode()).toUpperCase(Locale.ROOT));
        return text.toString();
    }

    /**
     * Rebuilds {@code stroke} into the canonical comparison form: {@code *_DOWN_MASK}
     * modifiers only, pressed position, undefined char. Returns {@code null} unchanged.
     */
    public static KeyStroke normalize(final KeyStroke stroke) {
        if (stroke == null) {
            return null;
        }
        if (stroke.getKeyCode() == KeyEvent.VK_UNDEFINED) {
            return stroke;
        }
        return KeyStroke.getKeyStroke(stroke.getKeyCode(), downMasks(stroke.getModifiers()));
    }

    /**
     * Reduces any modifier mask flavor to the canonical {@code *_DOWN_MASK} bits, dropping
     * mouse-button and alt-graph bits so strokes compare across JDK internals.
     */
    public static int downMasks(final int modifiers) {
        int out = 0;
        if ((modifiers & (InputEvent.CTRL_DOWN_MASK | InputEvent.CTRL_MASK)) != 0) out |= InputEvent.CTRL_DOWN_MASK;
        if ((modifiers & (InputEvent.ALT_DOWN_MASK | InputEvent.ALT_MASK)) != 0) out |= InputEvent.ALT_DOWN_MASK;
        if ((modifiers & (InputEvent.SHIFT_DOWN_MASK | InputEvent.SHIFT_MASK)) != 0) out |= InputEvent.SHIFT_DOWN_MASK;
        if ((modifiers & (InputEvent.META_DOWN_MASK | InputEvent.META_MASK)) != 0) out |= InputEvent.META_DOWN_MASK;
        return out;
    }

    /** Whether the stroke carries no Ctrl/Alt/Meta modifier — Shift alone still counts as plain. */
    public static boolean isPlain(final KeyStroke stroke) {
        return (downMasks(stroke.getModifiers())
                        & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK))
                == 0;
    }

    /** Validates and normalizes persisted/UI text; returns the canonical text form. */
    public static String display(final String text) {
        return encode(parse(text));
    }

    /**
     * The character a synthesized press for {@code stroke} should carry: letters follow the
     * shift state, digits and common punctuation map to their unshifted glyph, everything
     * else is {@link KeyEvent#CHAR_UNDEFINED}.
     */
    public static char keyCharFor(final KeyStroke stroke) {
        final int code = stroke.getKeyCode();
        final boolean shift = (stroke.getModifiers() & InputEvent.SHIFT_DOWN_MASK) != 0;
        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) {
            return (char) (shift ? 'A' + code - KeyEvent.VK_A : 'a' + code - KeyEvent.VK_A);
        }
        if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) {
            return (char) ('0' + code - KeyEvent.VK_0);
        }
        return switch (code) {
            case KeyEvent.VK_SPACE -> ' ';
            case KeyEvent.VK_COMMA -> ',';
            case KeyEvent.VK_PERIOD -> '.';
            case KeyEvent.VK_SLASH -> '/';
            case KeyEvent.VK_BACK_SLASH -> '\\';
            case KeyEvent.VK_SEMICOLON -> ';';
            case KeyEvent.VK_EQUALS -> '=';
            case KeyEvent.VK_MINUS -> '-';
            case KeyEvent.VK_OPEN_BRACKET -> '[';
            case KeyEvent.VK_CLOSE_BRACKET -> ']';
            case KeyEvent.VK_BACK_QUOTE -> '`';
            case KeyEvent.VK_QUOTE -> '\'';
            default -> KeyEvent.CHAR_UNDEFINED;
        };
    }

    private static int modifier(final String token, final String whole) {
        return switch (token) {
            case "CTRL", "CONTROL" -> InputEvent.CTRL_DOWN_MASK;
            case "ALT", "OPTION" -> InputEvent.ALT_DOWN_MASK;
            case "SHIFT" -> InputEvent.SHIFT_DOWN_MASK;
            case "META", "CMD", "COMMAND" -> InputEvent.META_DOWN_MASK;
            default -> throw new IllegalArgumentException("unknown modifier '" + token + "' in keystroke: " + whole);
        };
    }

    private static int keyCode(final String token, final String whole) {
        String name = token;
        if (name.length() == 1) {
            final char c = Character.toUpperCase(name.charAt(0));
            if (Character.isLetterOrDigit(c)) {
                name = String.valueOf(c);
            }
        }
        name = ALIASES.getOrDefault(name, name);
        final Integer code = KEYS_BY_NAME.get(name);
        if (code == null) {
            throw new IllegalArgumentException("unknown key '" + token + "' in keystroke: " + whole);
        }
        return code;
    }

    /** Segment text comparable against {@link #canonicalName}: separators removed, uppercase. */
    private static String segment(final String raw) {
        return raw.replaceAll("[\\s_]+", "").toUpperCase(Locale.ROOT);
    }

    /** {@code PAGE_UP → PageUp}, {@code S → S}, {@code F12 → F12}, {@code NUMPAD3 → Numpad3}. */
    private static String canonicalName(final String vkName) {
        if (vkName.length() == 1) {
            return vkName;
        }
        final StringBuilder name = new StringBuilder();
        for (String part : vkName.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            name.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                name.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return name.toString();
    }
}
