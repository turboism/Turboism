package dev.turboism.keybinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

class KeyStrokeCodecTest {

    @Test
    void parsesAndFormatsCanonicalStrokes() {
        assertEquals("Ctrl+S", KeyStrokeCodec.display("Ctrl+S"));
        assertEquals("Ctrl+Shift+S", KeyStrokeCodec.display("ctrl+shift+s"));
        assertEquals("Ctrl+Alt+Shift+Meta+F12", KeyStrokeCodec.display("Meta+Shift+ctrl+alt+F12"));
        assertEquals("Ctrl+Alt+K", KeyStrokeCodec.display("Ctrl+Alt+K"));
    }

    @Test
    void parsesModifierAliases() {
        assertEquals(KeyStrokeCodec.parse("Ctrl+S"), KeyStrokeCodec.parse("control+s"));
        assertEquals(KeyStrokeCodec.parse("Alt+F4"), KeyStrokeCodec.parse("option+f4"));
        assertEquals(KeyStrokeCodec.parse("Meta+O"), KeyStrokeCodec.parse("cmd+o"));
        assertEquals(KeyStrokeCodec.parse("Meta+O"), KeyStrokeCodec.parse("command+O"));
    }

    @Test
    void parsesKeyAliases() {
        assertEquals("Ctrl+Escape", KeyStrokeCodec.display("Ctrl+Esc"));
        assertEquals("Ctrl+Delete", KeyStrokeCodec.display("Ctrl+Del"));
        assertEquals("Ctrl+Insert", KeyStrokeCodec.display("Ctrl+Ins"));
        assertEquals("Ctrl+PageUp", KeyStrokeCodec.display("Ctrl+PgUp"));
        assertEquals("Ctrl+PageDown", KeyStrokeCodec.display("Ctrl+PgDn"));
        assertEquals("Ctrl+Enter", KeyStrokeCodec.display("Ctrl+Return"));
        assertEquals("Ctrl+BackSpace", KeyStrokeCodec.display("Ctrl+Bksp"));
    }

    @Test
    void parsesSingleCharacterAndPunctuationKeys() {
        assertEquals("X", KeyStrokeCodec.display("x"));
        assertEquals("5", KeyStrokeCodec.display("5"));
        assertEquals("Minus", KeyStrokeCodec.display("-"));
        assertEquals("Equals", KeyStrokeCodec.display("="));
        assertEquals("Ctrl+Comma", KeyStrokeCodec.display("Ctrl+,"));
        assertEquals("Ctrl+Period", KeyStrokeCodec.display("Ctrl+."));
        assertEquals("Ctrl+Slash", KeyStrokeCodec.display("Ctrl+/"));
        assertEquals("Ctrl+BackSlash", KeyStrokeCodec.display("Ctrl+\\"));
        assertEquals("Ctrl+Semicolon", KeyStrokeCodec.display("Ctrl+;"));
        assertEquals("Ctrl+OpenBracket", KeyStrokeCodec.display("Ctrl+["));
        assertEquals("Ctrl+CloseBracket", KeyStrokeCodec.display("Ctrl+]"));
        assertEquals("Ctrl+BackQuote", KeyStrokeCodec.display("Ctrl+`"));
        assertEquals("Ctrl+Quote", KeyStrokeCodec.display("Ctrl+'"));
        assertEquals("Ctrl+Space", KeyStrokeCodec.display("Ctrl+Space"));
        assertEquals("Numpad3", KeyStrokeCodec.display("Numpad3"));
    }

    @Test
    void rejectsInvalidText() {
        assertThrows(IllegalArgumentException.class, () -> KeyStrokeCodec.parse(""));
        assertThrows(IllegalArgumentException.class, () -> KeyStrokeCodec.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> KeyStrokeCodec.parse("+"));
        assertThrows(IllegalArgumentException.class, () -> KeyStrokeCodec.parse("Ctrl+"));
        assertThrows(IllegalArgumentException.class, () -> KeyStrokeCodec.parse("Ctrl++"));
        assertThrows(IllegalArgumentException.class, () -> KeyStrokeCodec.parse("Ctrl+Ctrl"));
        assertThrows(IllegalArgumentException.class, () -> KeyStrokeCodec.parse("Shift"));
        assertThrows(IllegalArgumentException.class, () -> KeyStrokeCodec.parse("Ctrl+Alt"));
        assertThrows(IllegalArgumentException.class, () -> KeyStrokeCodec.parse("Ctrl+NotAKey"));
        assertThrows(IllegalArgumentException.class, () -> KeyStrokeCodec.parse("Hyper+S"));
    }

    @Test
    void normalizesLegacyModifierMasks() {
        final KeyStroke legacy = KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_MASK);
        final KeyStroke normalized = KeyStrokeCodec.normalize(legacy);
        assertEquals((InputEvent.CTRL_DOWN_MASK & normalized.getModifiers()) != 0, true);
        assertEquals(KeyStrokeCodec.parse("Ctrl+S"), normalized);
    }

    @Test
    void plainStrokeIgnoresShift() {
        assertTrue(KeyStrokeCodec.isPlain(KeyStrokeCodec.parse("X")));
        assertTrue(KeyStrokeCodec.isPlain(KeyStrokeCodec.parse("Shift+X")));
        assertFalse(KeyStrokeCodec.isPlain(KeyStrokeCodec.parse("Ctrl+X")));
        assertFalse(KeyStrokeCodec.isPlain(KeyStrokeCodec.parse("Alt+X")));
        assertFalse(KeyStrokeCodec.isPlain(KeyStrokeCodec.parse("Meta+X")));
    }

    @Test
    void keyCharFollowsShiftAndPunctuation() {
        assertEquals('s', KeyStrokeCodec.keyCharFor(KeyStrokeCodec.parse("S")));
        assertEquals('S', KeyStrokeCodec.keyCharFor(KeyStrokeCodec.parse("Shift+S")));
        assertEquals('7', KeyStrokeCodec.keyCharFor(KeyStrokeCodec.parse("7")));
        assertEquals('=', KeyStrokeCodec.keyCharFor(KeyStrokeCodec.parse("Equals")));
        assertEquals(KeyEvent.CHAR_UNDEFINED, KeyStrokeCodec.keyCharFor(KeyStrokeCodec.parse("F1")));
        // a translated modified stroke keeps the same keyChar the native event would carry
        assertEquals('s', KeyStrokeCodec.keyCharFor(KeyStrokeCodec.parse("Ctrl+S")));
    }
}
