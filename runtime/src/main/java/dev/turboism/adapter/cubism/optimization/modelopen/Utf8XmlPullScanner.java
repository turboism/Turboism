package dev.turboism.adapter.cubism.optimization.modelopen;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Seekable UTF-8 XML pull scanner over an in-memory byte array with direct numeric array parsing
 * and local JDOM subtree materialization.
 *
 * <p>Unsupported XML constructs such as DOCTYPE declarations, non-UTF-8 encodings, or custom named
 * entities throw {@link StreamingAbortException} so callers fail closed to the native JDOM path.</p>
 */
final class Utf8XmlPullScanner {

    static final int EVENT_EOF = 0;
    static final int EVENT_PI = 1;
    static final int EVENT_START_ELEMENT = 2;
    static final int EVENT_END_ELEMENT = 3;

    private static final int BOM_BYTE_0 = 0xEF;
    private static final int BOM_BYTE_1 = 0xBB;
    private static final int BOM_BYTE_2 = 0xBF;
    private static final int BOM_LENGTH = 3;

    private static final int BYTE_MASK = 0xFF;
    private static final int ASCII_MAX = 0x7F;
    private static final int UTF8_2BYTE_MASK = 0xE0;
    private static final int UTF8_2BYTE_PREFIX = 0xC0;
    private static final int UTF8_3BYTE_MASK = 0xF0;
    private static final int UTF8_3BYTE_PREFIX = 0xE0;
    private static final int UTF8_4BYTE_MASK = 0xF8;
    private static final int UTF8_4BYTE_PREFIX = 0xF0;
    private static final int UTF8_CONT_MASK = 0xC0;
    private static final int UTF8_CONT_PREFIX = 0x80;
    private static final int UTF8_PAYLOAD_6 = 0x3F;
    private static final int UTF8_PAYLOAD_5 = 0x1F;
    private static final int UTF8_PAYLOAD_4 = 0x0F;
    private static final int UTF8_PAYLOAD_3 = 0x07;
    private static final int SHIFT_6 = 6;
    private static final int SHIFT_12 = 12;
    private static final int SHIFT_18 = 18;

    private static final int INITIAL_ATTR_CAPACITY = 8;
    private static final int SYMBOL_TABLE_SIZE = 512;
    private static final int SYMBOL_TABLE_MASK = SYMBOL_TABLE_SIZE - 1;
    private static final int MAX_CACHED_SYMBOL_BYTES = 64;
    private static final int FNV_OFFSET_BASIS = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;

    private static final byte BYTE_LT = '<';
    private static final byte BYTE_GT = '>';
    private static final byte BYTE_SLASH = '/';
    private static final byte BYTE_QUESTION = '?';
    private static final byte BYTE_BANG = '!';
    private static final byte BYTE_DASH = '-';
    private static final byte BYTE_LBRACKET = '[';
    private static final byte BYTE_RBRACKET = ']';
    private static final byte BYTE_EQ = '=';
    private static final byte BYTE_DQUOTE = '"';
    private static final byte BYTE_SQUOTE = '\'';
    private static final byte BYTE_AMP = '&';
    private static final byte BYTE_SEMI = ';';
    private static final byte BYTE_HASH = '#';
    private static final byte BYTE_SPACE = ' ';
    private static final byte BYTE_TAB = '\t';
    private static final byte BYTE_CR = '\r';
    private static final byte BYTE_LF = '\n';
    private static final byte BYTE_PLUS = '+';
    private static final byte BYTE_DOT = '.';
    private static final byte BYTE_ZERO = '0';
    private static final byte BYTE_NINE = '9';
    private static final byte BYTE_EXP_LOWER = 'e';
    private static final byte BYTE_EXP_UPPER = 'E';

    private static final int COMMENT_PREFIX_LEN = 4;
    private static final int COMMENT_SUFFIX_LEN = 3;
    private static final int CDATA_PREFIX_LEN = 9;
    private static final int CDATA_SUFFIX_LEN = 3;
    private static final int MIN_ENTITY_LEN = 2;
    private static final int ENTITY_LT_LEN = 2;
    private static final int ENTITY_AMP_LEN = 3;
    private static final int ENTITY_QUOT_LEN = 4;

    private static final int RADIX_TEN = 10;
    private static final int RADIX_HEX = 16;
    private static final int MAX_EXACT_DOUBLE_DIGITS = 15;
    private static final int MAX_EXACT_POW10 = 22;
    private static final int MAX_EXPLICIT_EXP_GUARD = MAX_EXACT_POW10 * RADIX_TEN;
    private static final int MAX_SAFE_FAST_INT_DIGITS = 9;
    private static final float POSITIVE_ZERO = 0.0f;
    private static final float NEGATIVE_ZERO = -0.0f;
    private static final long FLOAT_TIE_LOW29_MASK = 0x1FFF_FFFFL;
    private static final long FLOAT_TIE_GUARD_LOW = 0x0FFF_FFFEL;
    private static final long FLOAT_TIE_GUARD_HIGH = 0x1000_0002L;
    private static final double[] POW10_TABLE = buildPow10Table();

    private static final float[] EMPTY_FLOATS = new float[0];
    private static final int[] EMPTY_INTS = new int[0];
    private static final double[] EMPTY_DOUBLES = new double[0];
    private static final short[] EMPTY_SHORTS = new short[0];
    private static final long[] EMPTY_LONGS = new long[0];
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final char[] EMPTY_CHARS = new char[0];
    private static final boolean[] EMPTY_BOOLEANS = new boolean[0];

    private static final String XML_DECL_TARGET = "xml";
    private static final String ENCODING_ATTR_PREFIX = "encoding";
    private static final String UTF8_CANONICAL = "utf-8";
    private static final String UTF8_COMPACT = "utf8";
    private static final byte[] CDATA_HEADER = "![CDATA[".getBytes(StandardCharsets.US_ASCII);

    private final byte[] data;
    private final int length;
    private int pos;

    private int eventType;
    private int elementStartPos;
    private String tagName;
    private boolean emptyElement;
    private String piTarget;
    private String piData;

    private String[] attrNames = new String[INITIAL_ATTR_CAPACITY];
    private String[] attrValues = new String[INITIAL_ATTR_CAPACITY];
    private int attrCount;

    private final StringBuilder scratch = new StringBuilder();
    private final byte[][] symbolBytes = new byte[SYMBOL_TABLE_SIZE][];
    private final String[] symbolStrings = new String[SYMBOL_TABLE_SIZE];

    Utf8XmlPullScanner(final byte[] data) {
        if (data == null) {
            throw new StreamingAbortException("Null XML byte buffer");
        }
        this.data = data;
        this.length = data.length;
        this.pos = 0;
        if (length >= BOM_LENGTH
                && (data[0] & BYTE_MASK) == BOM_BYTE_0
                && (data[1] & BYTE_MASK) == BOM_BYTE_1
                && (data[2] & BYTE_MASK) == BOM_BYTE_2) {
            this.pos = BOM_LENGTH;
        }
    }

    int getPos() {
        return pos;
    }

    void seek(final int offset) {
        this.pos = offset;
    }

    int getElementStartPos() {
        return elementStartPos;
    }

    String getTagName() {
        return tagName;
    }

    boolean isEmptyElement() {
        return emptyElement;
    }

    String getPiTarget() {
        return piTarget;
    }

    String getPiData() {
        return piData;
    }

    int getAttrCount() {
        return attrCount;
    }

    String getAttrName(final int index) {
        return attrNames[index];
    }

    String getAttrValue(final int index) {
        return attrValues[index];
    }

    /** Returns the value of {@code name} on the current start element, or {@code null} when absent. */
    String getAttributeValue(final String name) {
        for (int i = 0; i < attrCount; i++) {
            if (name.equals(attrNames[i])) {
                return attrValues[i];
            }
        }
        return null;
    }

    /**
     * Advances to the next structural tag, processing instruction, end tag, or EOF, skipping
     * inter-element whitespace and comments.
     */
    int nextTagOrPi() {
        while (true) {
            skipWhitespace();
            if (pos >= length) {
                eventType = EVENT_EOF;
                return EVENT_EOF;
            }
            if (data[pos] != BYTE_LT) {
                throw new StreamingAbortException("Unexpected character data between elements");
            }
            if (pos + 1 >= length) {
                throw new StreamingAbortException("Truncated tag start");
            }
            final byte next = data[pos + 1];
            if (next == BYTE_BANG) {
                if (isCommentStart(pos)) {
                    skipComment();
                    continue;
                }
                throw new StreamingAbortException("Unsupported declaration or CDATA outside element text");
            }
            if (next == BYTE_QUESTION) {
                readProcessingInstruction();
                if (XML_DECL_TARGET.equalsIgnoreCase(piTarget)) {
                    verifyXmlDeclarationEncoding(piData);
                    continue;
                }
                eventType = EVENT_PI;
                return EVENT_PI;
            }
            if (next == BYTE_SLASH) {
                readEndTag();
                eventType = EVENT_END_ELEMENT;
                return EVENT_END_ELEMENT;
            }
            readStartTag();
            eventType = EVENT_START_ELEMENT;
            return EVENT_START_ELEMENT;
        }
    }

    /** Advances to the next child start tag or enclosing end tag, ignoring PIs and comments. */
    int nextChildOrEnd() {
        while (true) {
            final int ev = nextTagOrPi();
            if (ev == EVENT_PI) {
                continue;
            }
            return ev;
        }
    }

    /**
     * Reads the start tag at the current {@code pos}, populating tag metadata and attributes.
     */
    void readStartTagAtCurrentPos() {
        skipWhitespace();
        if (pos >= length || data[pos] != BYTE_LT) {
            throw new StreamingAbortException("Expected start tag at seek offset");
        }
        readStartTag();
        eventType = EVENT_START_ELEMENT;
    }

    /**
     * Reads all text and CDATA content of the current element up to and including its closing tag.
     */
    String readElementText() {
        if (emptyElement) {
            return "";
        }
        final int fastStart = pos;
        int scan = pos;
        boolean plainAscii = true;
        while (scan < length) {
            final byte b = data[scan];
            if (b == BYTE_LT) {
                break;
            }
            if (b == BYTE_AMP || b == BYTE_CR || (b & BYTE_MASK) > ASCII_MAX) {
                plainAscii = false;
                break;
            }
            scan++;
        }
        if (plainAscii && scan + 1 < length && data[scan] == BYTE_LT && data[scan + 1] == BYTE_SLASH) {
            final String text =
                    scan == fastStart ? "" : new String(data, fastStart, scan - fastStart, StandardCharsets.US_ASCII);
            pos = scan;
            readEndTag();
            return text;
        }

        scratch.setLength(0);
        while (true) {
            if (pos >= length) {
                throw new StreamingAbortException("Unexpected EOF inside element text");
            }
            final byte b = data[pos];
            if (b == BYTE_LT) {
                if (pos + 1 >= length) {
                    throw new StreamingAbortException("Truncated tag inside element text");
                }
                final byte next = data[pos + 1];
                if (next == BYTE_SLASH) {
                    readEndTag();
                    return scratch.toString();
                }
                if (next == BYTE_BANG) {
                    if (isCommentStart(pos)) {
                        skipComment();
                        continue;
                    }
                    if (isCdataStart(pos)) {
                        appendCdataToScratch();
                        continue;
                    }
                    throw new StreamingAbortException("Unsupported markup inside element text");
                }
                if (next == BYTE_QUESTION) {
                    readProcessingInstruction();
                    continue;
                }
                throw new StreamingAbortException("Unexpected child element inside text element");
            }
            if (b == BYTE_AMP) {
                appendEntityToScratch();
            } else if (b == BYTE_CR) {
                pos++;
                if (pos < length && data[pos] == BYTE_LF) {
                    pos++;
                }
                scratch.append('\n');
            } else {
                appendUtf8CharToScratch();
            }
        }
    }

    /**
     * Parses a space-separated {@code float[]} in a single forward byte pass without allocating an
     * intermediate text {@link String} on the fast path.
     */
    float[] readFloatArray(final int count) {
        if (emptyElement) {
            if (count == 0) {
                return EMPTY_FLOATS;
            }
            throw new StreamingAbortException("Empty float-array element with non-zero count");
        }
        final int startPos = pos;
        final byte[] buf = this.data;
        final int len = this.length;
        int p = startPos;
        if (count == 0) {
            if (p + 1 < len && buf[p] == BYTE_LT && buf[p + 1] == BYTE_SLASH) {
                pos = p;
                readEndTag();
                return EMPTY_FLOATS;
            }
            return FloatArrayDeserializerBridge.parseFloatArray(count, readElementText(), false);
        }

        final float[] result = new float[count];
        for (int index = 0; index < count; index++) {
            if (p >= len) {
                pos = startPos;
                return FloatArrayDeserializerBridge.parseFloatArray(count, readElementText(), false);
            }
            final int tokenStart = p;
            boolean negative = false;
            final byte signByte = buf[p];
            if (signByte == BYTE_DASH) {
                negative = true;
                p++;
            } else if (signByte == BYTE_PLUS) {
                p++;
            }

            long mantissa = 0L;
            int sigDigits = 0;
            int fracDigits = 0;
            boolean seenDigit = false;
            boolean tokenFallback = false;

            while (p < len) {
                final byte ch = buf[p];
                if (ch >= BYTE_ZERO && ch <= BYTE_NINE) {
                    seenDigit = true;
                    final int digit = ch - BYTE_ZERO;
                    if (mantissa != 0L || digit != 0) {
                        sigDigits++;
                        if (sigDigits > MAX_EXACT_DOUBLE_DIGITS) {
                            tokenFallback = true;
                            break;
                        }
                        mantissa = mantissa * RADIX_TEN + digit;
                    }
                    p++;
                } else {
                    break;
                }
            }

            if (!tokenFallback && p < len && buf[p] == BYTE_DOT) {
                p++;
                final int fracStart = p;
                while (p < len) {
                    final byte ch = buf[p];
                    if (ch >= BYTE_ZERO && ch <= BYTE_NINE) {
                        seenDigit = true;
                        final int digit = ch - BYTE_ZERO;
                        if (mantissa != 0L || digit != 0) {
                            sigDigits++;
                            if (sigDigits > MAX_EXACT_DOUBLE_DIGITS) {
                                tokenFallback = true;
                                break;
                            }
                            mantissa = mantissa * RADIX_TEN + digit;
                        }
                        p++;
                    } else {
                        break;
                    }
                }
                fracDigits = p - fracStart;
            }

            int explicitExp = 0;
            if (!tokenFallback && seenDigit && p < len) {
                final byte expMarker = buf[p];
                if (expMarker == BYTE_EXP_LOWER || expMarker == BYTE_EXP_UPPER) {
                    p++;
                    if (p >= len) {
                        tokenFallback = true;
                    } else {
                        boolean expNeg = false;
                        final byte expSign = buf[p];
                        if (expSign == BYTE_DASH) {
                            expNeg = true;
                            p++;
                        } else if (expSign == BYTE_PLUS) {
                            p++;
                        }
                        final int expDigitStart = p;
                        while (p < len) {
                            final byte ec = buf[p];
                            if (ec >= BYTE_ZERO && ec <= BYTE_NINE) {
                                if (explicitExp > MAX_EXPLICIT_EXP_GUARD) {
                                    tokenFallback = true;
                                    break;
                                }
                                explicitExp = explicitExp * RADIX_TEN + (ec - BYTE_ZERO);
                                p++;
                            } else {
                                break;
                            }
                        }
                        if (p == expDigitStart) {
                            tokenFallback = true;
                        } else if (expNeg) {
                            explicitExp = -explicitExp;
                        }
                    }
                }
            }

            final byte delim;
            if (!tokenFallback && seenDigit && p < len && ((delim = buf[p]) == BYTE_SPACE || delim == BYTE_LT)) {
                if (delim == BYTE_LT && (p + 1 >= len || buf[p + 1] != BYTE_SLASH)) {
                    pos = startPos;
                    return FloatArrayDeserializerBridge.parseFloatArray(count, readElementText(), false);
                }
                final float fastValue = tryConvertExactFloat(negative, mantissa, explicitExp - fracDigits);
                result[index] = Float.isNaN(fastValue) ? parseFloatFallback(tokenStart, p) : fastValue;
            } else {
                // Delegate individual edge tokens without aborting the single pass unless markup,
                // entities, CRLF, or non-ASCII bytes require full XML text normalization.
                while (p < len) {
                    final byte b = buf[p];
                    if (b == BYTE_SPACE || b == BYTE_LT) {
                        break;
                    }
                    if (b == BYTE_AMP || b == BYTE_CR || (b & BYTE_MASK) > ASCII_MAX) {
                        pos = startPos;
                        return FloatArrayDeserializerBridge.parseFloatArray(count, readElementText(), false);
                    }
                    p++;
                }
                if (p >= len || (buf[p] == BYTE_LT && (p + 1 >= len || buf[p + 1] != BYTE_SLASH))) {
                    pos = startPos;
                    return FloatArrayDeserializerBridge.parseFloatArray(count, readElementText(), false);
                }
                result[index] = parseFloatBytes(tokenStart, p);
            }

            if (index + 1 < count) {
                if (buf[p] == BYTE_SPACE) {
                    p++;
                } else {
                    throw new StreamingAbortException("Float array token underflow");
                }
            }
        }

        if (buf[p] == BYTE_LT) {
            pos = p;
            readEndTag();
            return result;
        }
        final int textEnd = findSimpleTextEnd(p);
        if (textEnd < 0) {
            pos = startPos;
            return FloatArrayDeserializerBridge.parseFloatArray(count, readElementText(), false);
        }
        pos = textEnd;
        readEndTag();
        return result;
    }

    /**
     * Parses a space-separated {@code int[]} in a single forward byte pass without allocating an
     * intermediate text {@link String} on the fast path.
     */
    int[] readIntArray(final int count) {
        if (emptyElement) {
            if (count == 0) {
                return EMPTY_INTS;
            }
            throw new StreamingAbortException("Empty int-array element with non-zero count");
        }
        final int startPos = pos;
        final byte[] buf = this.data;
        final int len = this.length;
        int p = startPos;
        if (count == 0) {
            if (p + 1 < len && buf[p] == BYTE_LT && buf[p + 1] == BYTE_SLASH) {
                pos = p;
                readEndTag();
                return EMPTY_INTS;
            }
            return IntArrayDeserializerBridge.parseIntArray(count, readElementText(), false);
        }

        final int[] result = new int[count];
        for (int index = 0; index < count; index++) {
            if (p >= len) {
                pos = startPos;
                return IntArrayDeserializerBridge.parseIntArray(count, readElementText(), false);
            }
            final int tokenStart = p;
            boolean negative = false;
            final byte firstByte = buf[p];
            if (firstByte == BYTE_DASH) {
                negative = true;
                p++;
            } else if (firstByte == BYTE_PLUS) {
                p++;
            }
            final int digitStart = p;
            int accum = 0;
            boolean tokenFallback = false;
            while (p < len) {
                final byte ch = buf[p];
                if (ch >= BYTE_ZERO && ch <= BYTE_NINE) {
                    if (p - digitStart >= MAX_SAFE_FAST_INT_DIGITS) {
                        tokenFallback = true;
                        break;
                    }
                    accum = accum * RADIX_TEN + (ch - BYTE_ZERO);
                    p++;
                } else {
                    break;
                }
            }

            final byte delim;
            if (!tokenFallback && p > digitStart && p < len && ((delim = buf[p]) == BYTE_SPACE || delim == BYTE_LT)) {
                if (delim == BYTE_LT && (p + 1 >= len || buf[p + 1] != BYTE_SLASH)) {
                    pos = startPos;
                    return IntArrayDeserializerBridge.parseIntArray(count, readElementText(), false);
                }
                result[index] = negative ? -accum : accum;
            } else {
                // Delegate individual edge tokens without aborting the single pass unless markup,
                // entities, CRLF, or non-ASCII bytes require full XML text normalization.
                while (p < len) {
                    final byte b = buf[p];
                    if (b == BYTE_SPACE || b == BYTE_LT) {
                        break;
                    }
                    if (b == BYTE_AMP || b == BYTE_CR || (b & BYTE_MASK) > ASCII_MAX) {
                        pos = startPos;
                        return IntArrayDeserializerBridge.parseIntArray(count, readElementText(), false);
                    }
                    p++;
                }
                if (p >= len || (buf[p] == BYTE_LT && (p + 1 >= len || buf[p + 1] != BYTE_SLASH))) {
                    pos = startPos;
                    return IntArrayDeserializerBridge.parseIntArray(count, readElementText(), false);
                }
                result[index] = parseIntBytes(tokenStart, p);
            }

            if (index + 1 < count) {
                if (buf[p] == BYTE_SPACE) {
                    p++;
                } else {
                    throw new StreamingAbortException("Int array token underflow");
                }
            }
        }

        if (buf[p] == BYTE_LT) {
            pos = p;
            readEndTag();
            return result;
        }
        final int textEnd = findSimpleTextEnd(p);
        if (textEnd < 0) {
            pos = startPos;
            return IntArrayDeserializerBridge.parseIntArray(count, readElementText(), false);
        }
        pos = textEnd;
        readEndTag();
        return result;
    }

    double[] readDoubleArray(final int count) {
        final String text = readElementText();
        if (count == 0) {
            return EMPTY_DOUBLES;
        }
        final double[] result = new double[count];
        int cursor = 0;
        final int len = text.length();
        for (int index = 0; index < count; index++) {
            final int sep = text.indexOf(' ', cursor);
            final int end = sep >= 0 ? sep : len;
            result[index] = Double.parseDouble(text.substring(cursor, end));
            cursor = sep >= 0 ? sep + 1 : len + 1;
        }
        return result;
    }

    short[] readShortArray(final int count) {
        final String text = readElementText();
        if (count == 0) {
            return EMPTY_SHORTS;
        }
        final short[] result = new short[count];
        int cursor = 0;
        final int len = text.length();
        for (int index = 0; index < count; index++) {
            final int sep = text.indexOf(' ', cursor);
            final int end = sep >= 0 ? sep : len;
            result[index] = Short.parseShort(text.substring(cursor, end));
            cursor = sep >= 0 ? sep + 1 : len + 1;
        }
        return result;
    }

    long[] readLongArray(final int count) {
        final String text = readElementText();
        if (count == 0) {
            return EMPTY_LONGS;
        }
        final long[] result = new long[count];
        int cursor = 0;
        final int len = text.length();
        for (int index = 0; index < count; index++) {
            final int sep = text.indexOf(' ', cursor);
            final int end = sep >= 0 ? sep : len;
            result[index] = Long.parseLong(text.substring(cursor, end));
            cursor = sep >= 0 ? sep + 1 : len + 1;
        }
        return result;
    }

    byte[] readByteArray(final int count) {
        final String text = readElementText();
        if (count == 0) {
            return EMPTY_BYTES;
        }
        final byte[] result = new byte[count];
        int cursor = 0;
        final int len = text.length();
        for (int index = 0; index < count; index++) {
            final int sep = text.indexOf(' ', cursor);
            final int end = sep >= 0 ? sep : len;
            result[index] = Byte.parseByte(text.substring(cursor, end));
            cursor = sep >= 0 ? sep + 1 : len + 1;
        }
        return result;
    }

    char[] readCharArray(final int count) {
        final String text = readElementText();
        if (count == 0) {
            return EMPTY_CHARS;
        }
        final char[] result = new char[count];
        int cursor = 0;
        final int len = text.length();
        for (int index = 0; index < count; index++) {
            final int sep = text.indexOf(' ', cursor);
            final int end = sep >= 0 ? sep : len;
            result[index] = text.charAt(cursor);
            cursor = sep >= 0 ? sep + 1 : len + 1;
            if (cursor > end + 1) {
                break;
            }
        }
        return result;
    }

    boolean[] readBoolArray(final int count) {
        final String text = readElementText();
        if (count == 0) {
            return EMPTY_BOOLEANS;
        }
        final boolean[] result = new boolean[count];
        int cursor = 0;
        final int len = text.length();
        for (int index = 0; index < count; index++) {
            final int sep = text.indexOf(' ', cursor);
            final int end = sep >= 0 ? sep : len;
            result[index] = Boolean.parseBoolean(text.substring(cursor, end));
            cursor = sep >= 0 ? sep + 1 : len + 1;
        }
        return result;
    }

    /**
     * Skips the body and closing tag of the current element in a tight byte scan without allocating
     * strings or objects.
     */
    void skipCurrentElementBody() {
        if (emptyElement) {
            return;
        }
        int depth = 1;
        while (depth > 0) {
            while (pos < length && data[pos] != BYTE_LT) {
                pos++;
            }
            if (pos + 1 >= length) {
                throw new StreamingAbortException("Unexpected EOF while skipping element body");
            }
            final byte next = data[pos + 1];
            if (next == BYTE_SLASH) {
                pos += 2;
                while (pos < length && data[pos] != BYTE_GT) {
                    pos++;
                }
                if (pos >= length) {
                    throw new StreamingAbortException("Unterminated end tag while skipping");
                }
                pos++;
                depth--;
            } else if (next == BYTE_BANG) {
                if (isCommentStart(pos)) {
                    skipComment();
                } else if (isCdataStart(pos)) {
                    skipCdata();
                } else {
                    throw new StreamingAbortException("Unsupported markup while skipping element body");
                }
            } else if (next == BYTE_QUESTION) {
                skipProcessingInstruction();
            } else {
                pos++;
                boolean selfClosing = false;
                while (pos < length) {
                    final byte b = data[pos];
                    if (b == BYTE_DQUOTE || b == BYTE_SQUOTE) {
                        pos++;
                        while (pos < length && data[pos] != b) {
                            pos++;
                        }
                        if (pos >= length) {
                            throw new StreamingAbortException("Unterminated attribute quote while skipping");
                        }
                        pos++;
                    } else if (b == BYTE_GT) {
                        selfClosing = pos > 0 && data[pos - 1] == BYTE_SLASH;
                        pos++;
                        break;
                    } else {
                        pos++;
                    }
                }
                if (!selfClosing) {
                    depth++;
                }
            }
        }
    }

    /**
     * Materializes the current element and its entire local subtree into a JDOM {@code Element}
     * through {@code builder}.
     */
    Object materializeCurrentElement(final JdomSubtreeBuilder builder) throws Throwable {
        final Object element = builder.newElement(tagName);
        for (int i = 0; i < attrCount; i++) {
            builder.addAttribute(element, attrNames[i], attrValues[i]);
        }
        if (emptyElement) {
            return element;
        }
        materializeChildrenInto(element, builder);
        return element;
    }

    private void materializeChildrenInto(final Object parentElement, final JdomSubtreeBuilder builder)
            throws Throwable {
        while (true) {
            if (pos >= length) {
                throw new StreamingAbortException("Unexpected EOF while materializing JDOM subtree");
            }
            if (data[pos] != BYTE_LT) {
                final String text = readTextChunkUntilMarkup();
                if (!text.isEmpty()) {
                    builder.addText(parentElement, text);
                }
                continue;
            }
            if (pos + 1 >= length) {
                throw new StreamingAbortException("Truncated tag while materializing JDOM subtree");
            }
            final byte next = data[pos + 1];
            if (next == BYTE_SLASH) {
                readEndTag();
                return;
            }
            if (next == BYTE_BANG) {
                if (isCommentStart(pos)) {
                    skipComment();
                    continue;
                }
                if (isCdataStart(pos)) {
                    scratch.setLength(0);
                    appendCdataToScratch();
                    builder.addCdata(parentElement, scratch.toString());
                    continue;
                }
                throw new StreamingAbortException("Unsupported declaration inside JDOM subtree");
            }
            if (next == BYTE_QUESTION) {
                readProcessingInstruction();
                builder.addProcessingInstruction(parentElement, piTarget, piData);
                continue;
            }
            readStartTag();
            final Object child = materializeCurrentElement(builder);
            builder.addChild(parentElement, child);
        }
    }

    private String readTextChunkUntilMarkup() {
        final int fastStart = pos;
        int scan = pos;
        boolean plainAscii = true;
        while (scan < length && data[scan] != BYTE_LT) {
            final byte b = data[scan];
            if (b == BYTE_AMP || b == BYTE_CR || (b & BYTE_MASK) > ASCII_MAX) {
                plainAscii = false;
                break;
            }
            scan++;
        }
        if (plainAscii) {
            pos = scan;
            return scan == fastStart ? "" : new String(data, fastStart, scan - fastStart, StandardCharsets.US_ASCII);
        }
        scratch.setLength(0);
        while (pos < length && data[pos] != BYTE_LT) {
            final byte b = data[pos];
            if (b == BYTE_AMP) {
                appendEntityToScratch();
            } else if (b == BYTE_CR) {
                pos++;
                if (pos < length && data[pos] == BYTE_LF) {
                    pos++;
                }
                scratch.append('\n');
            } else {
                appendUtf8CharToScratch();
            }
        }
        return scratch.toString();
    }

    private void readStartTag() {
        elementStartPos = pos;
        pos++;
        tagName = readNameInterned();
        attrCount = 0;
        while (true) {
            skipWhitespace();
            if (pos >= length) {
                throw new StreamingAbortException("Unterminated start tag");
            }
            final byte b = data[pos];
            if (b == BYTE_GT) {
                pos++;
                emptyElement = false;
                return;
            }
            if (b == BYTE_SLASH) {
                if (pos + 1 >= length || data[pos + 1] != BYTE_GT) {
                    throw new StreamingAbortException("Expected '>' after '/' in empty tag");
                }
                pos += 2;
                emptyElement = true;
                return;
            }
            final String attrName = readNameInterned();
            skipWhitespace();
            if (pos >= length || data[pos] != BYTE_EQ) {
                throw new StreamingAbortException("Expected '=' after attribute name");
            }
            pos++;
            skipWhitespace();
            final String attrVal = readAttributeValue();
            if (attrCount == attrNames.length) {
                final int nextCap = attrNames.length << 1;
                attrNames = Arrays.copyOf(attrNames, nextCap);
                attrValues = Arrays.copyOf(attrValues, nextCap);
            }
            attrNames[attrCount] = attrName;
            attrValues[attrCount] = attrVal;
            attrCount++;
        }
    }

    private void readEndTag() {
        pos += 2;
        tagName = readNameInterned();
        skipWhitespace();
        if (pos >= length || data[pos] != BYTE_GT) {
            throw new StreamingAbortException("Expected '>' at end tag");
        }
        pos++;
    }

    private void readProcessingInstruction() {
        pos += 2;
        piTarget = readNameInterned();
        skipWhitespace();
        final int dataStart = pos;
        while (pos + 1 < length) {
            if (data[pos] == BYTE_QUESTION && data[pos + 1] == BYTE_GT) {
                piData = new String(data, dataStart, pos - dataStart, StandardCharsets.UTF_8);
                pos += 2;
                return;
            }
            pos++;
        }
        throw new StreamingAbortException("Unterminated processing instruction");
    }

    private void skipProcessingInstruction() {
        pos += 2;
        while (pos + 1 < length) {
            if (data[pos] == BYTE_QUESTION && data[pos + 1] == BYTE_GT) {
                pos += 2;
                return;
            }
            pos++;
        }
        throw new StreamingAbortException("Unterminated processing instruction");
    }

    private String readNameInterned() {
        final int start = pos;
        int hash = FNV_OFFSET_BASIS;
        boolean ascii = true;
        while (pos < length) {
            final byte b = data[pos];
            if (isNameDelimiter(b)) {
                break;
            }
            if ((b & BYTE_MASK) > ASCII_MAX) {
                ascii = false;
            }
            hash = (hash ^ (b & BYTE_MASK)) * FNV_PRIME;
            pos++;
        }
        final int len = pos - start;
        if (len <= 0) {
            throw new StreamingAbortException("Empty XML name");
        }
        if (len <= MAX_CACHED_SYMBOL_BYTES) {
            final int slot = hash & SYMBOL_TABLE_MASK;
            final byte[] cached = symbolBytes[slot];
            if (cached != null && cached.length == len && bytesMatch(cached, start, len)) {
                return symbolStrings[slot];
            }
            final String decoded = ascii
                    ? new String(data, start, len, StandardCharsets.US_ASCII)
                    : new String(data, start, len, StandardCharsets.UTF_8);
            final byte[] copy = new byte[len];
            System.arraycopy(data, start, copy, 0, len);
            symbolBytes[slot] = copy;
            symbolStrings[slot] = decoded;
            return decoded;
        }
        return ascii
                ? new String(data, start, len, StandardCharsets.US_ASCII)
                : new String(data, start, len, StandardCharsets.UTF_8);
    }

    private String readAttributeValue() {
        if (pos >= length) {
            throw new StreamingAbortException("Unexpected EOF before attribute quote");
        }
        final byte quote = data[pos];
        if (quote != BYTE_DQUOTE && quote != BYTE_SQUOTE) {
            throw new StreamingAbortException("Attribute value must be quoted");
        }
        pos++;
        final int start = pos;
        int scan = pos;
        boolean plainAscii = true;
        while (scan < length) {
            final byte b = data[scan];
            if (b == quote) {
                break;
            }
            if (b == BYTE_AMP || b == BYTE_CR || b == BYTE_LF || b == BYTE_TAB || (b & BYTE_MASK) > ASCII_MAX) {
                plainAscii = false;
                break;
            }
            scan++;
        }
        if (plainAscii && scan < length && data[scan] == quote) {
            pos = scan + 1;
            return scan == start ? "" : new String(data, start, scan - start, StandardCharsets.US_ASCII);
        }
        scratch.setLength(0);
        while (pos < length) {
            final byte b = data[pos];
            if (b == quote) {
                pos++;
                return scratch.toString();
            }
            if (b == BYTE_AMP) {
                appendEntityToScratch();
            } else if (b == BYTE_CR) {
                pos++;
                if (pos < length && data[pos] == BYTE_LF) {
                    pos++;
                }
                scratch.append(' ');
            } else if (b == BYTE_LF || b == BYTE_TAB) {
                pos++;
                scratch.append(' ');
            } else {
                appendUtf8CharToScratch();
            }
        }
        throw new StreamingAbortException("Unterminated attribute value");
    }

    private void appendEntityToScratch() {
        pos++;
        final int start = pos;
        while (pos < length && data[pos] != BYTE_SEMI) {
            pos++;
        }
        if (pos >= length) {
            throw new StreamingAbortException("Unterminated XML entity");
        }
        final int len = pos - start;
        pos++;
        if (len < MIN_ENTITY_LEN) {
            throw new StreamingAbortException("Invalid empty XML entity");
        }
        if (data[start] == BYTE_HASH) {
            final int codePoint = parseNumericEntity(start + 1, len - 1);
            scratch.appendCodePoint(codePoint);
            return;
        }
        if (len == ENTITY_LT_LEN && data[start + 1] == 't') {
            if (data[start] == 'l') {
                scratch.append('<');
                return;
            }
            if (data[start] == 'g') {
                scratch.append('>');
                return;
            }
        } else if (len == ENTITY_AMP_LEN && data[start] == 'a' && data[start + 1] == 'm' && data[start + 2] == 'p') {
            scratch.append('&');
            return;
        } else if (len == ENTITY_QUOT_LEN) {
            if (data[start] == 'q' && data[start + 1] == 'u' && data[start + 2] == 'o' && data[start + 3] == 't') {
                scratch.append('"');
                return;
            }
            if (data[start] == 'a' && data[start + 1] == 'p' && data[start + 2] == 'o' && data[start + 3] == 's') {
                scratch.append('\'');
                return;
            }
        }
        throw new StreamingAbortException("Unsupported XML named entity");
    }

    private int parseNumericEntity(final int start, final int len) {
        if (len <= 0) {
            throw new StreamingAbortException("Empty numeric character reference");
        }
        int idx = start;
        final int end = start + len;
        final int radix;
        if (data[idx] == 'x' || data[idx] == 'X') {
            radix = RADIX_HEX;
            idx++;
        } else {
            radix = RADIX_TEN;
        }
        if (idx >= end) {
            throw new StreamingAbortException("Missing digits in numeric character reference");
        }
        int value = 0;
        while (idx < end) {
            final int digit = Character.digit(data[idx++] & BYTE_MASK, radix);
            if (digit < 0) {
                throw new StreamingAbortException("Invalid digit in numeric character reference");
            }
            value = value * radix + digit;
            if (value > Character.MAX_CODE_POINT) {
                throw new StreamingAbortException("Numeric character reference out of range");
            }
        }
        return value;
    }

    private void appendCdataToScratch() {
        pos += CDATA_PREFIX_LEN;
        while (pos + 2 < length) {
            if (data[pos] == BYTE_RBRACKET && data[pos + 1] == BYTE_RBRACKET && data[pos + 2] == BYTE_GT) {
                pos += CDATA_SUFFIX_LEN;
                return;
            }
            if (data[pos] == BYTE_CR) {
                pos++;
                if (pos < length && data[pos] == BYTE_LF) {
                    pos++;
                }
                scratch.append('\n');
            } else {
                appendUtf8CharToScratch();
            }
        }
        throw new StreamingAbortException("Unterminated CDATA section");
    }

    private void skipCdata() {
        pos += CDATA_PREFIX_LEN;
        while (pos + 2 < length) {
            if (data[pos] == BYTE_RBRACKET && data[pos + 1] == BYTE_RBRACKET && data[pos + 2] == BYTE_GT) {
                pos += CDATA_SUFFIX_LEN;
                return;
            }
            pos++;
        }
        throw new StreamingAbortException("Unterminated CDATA section");
    }

    private void skipComment() {
        pos += COMMENT_PREFIX_LEN;
        while (pos + 2 < length) {
            if (data[pos] == BYTE_DASH && data[pos + 1] == BYTE_DASH && data[pos + 2] == BYTE_GT) {
                pos += COMMENT_SUFFIX_LEN;
                return;
            }
            pos++;
        }
        throw new StreamingAbortException("Unterminated XML comment");
    }

    private void appendUtf8CharToScratch() {
        final int b0 = data[pos++] & BYTE_MASK;
        if (b0 <= ASCII_MAX) {
            scratch.append((char) b0);
            return;
        }
        if ((b0 & UTF8_2BYTE_MASK) == UTF8_2BYTE_PREFIX) {
            if (pos >= length) {
                throw new StreamingAbortException("Truncated 2-byte UTF-8 sequence");
            }
            final int b1 = data[pos++] & BYTE_MASK;
            if ((b1 & UTF8_CONT_MASK) != UTF8_CONT_PREFIX) {
                throw new StreamingAbortException("Invalid 2-byte UTF-8 continuation");
            }
            scratch.append((char) (((b0 & UTF8_PAYLOAD_5) << SHIFT_6) | (b1 & UTF8_PAYLOAD_6)));
            return;
        }
        if ((b0 & UTF8_3BYTE_MASK) == UTF8_3BYTE_PREFIX) {
            if (pos + 1 >= length) {
                throw new StreamingAbortException("Truncated 3-byte UTF-8 sequence");
            }
            final int b1 = data[pos++] & BYTE_MASK;
            final int b2 = data[pos++] & BYTE_MASK;
            if ((b1 & UTF8_CONT_MASK) != UTF8_CONT_PREFIX || (b2 & UTF8_CONT_MASK) != UTF8_CONT_PREFIX) {
                throw new StreamingAbortException("Invalid 3-byte UTF-8 continuation");
            }
            scratch.append((char)
                    (((b0 & UTF8_PAYLOAD_4) << SHIFT_12) | ((b1 & UTF8_PAYLOAD_6) << SHIFT_6) | (b2 & UTF8_PAYLOAD_6)));
            return;
        }
        if ((b0 & UTF8_4BYTE_MASK) == UTF8_4BYTE_PREFIX) {
            if (pos + 2 >= length) {
                throw new StreamingAbortException("Truncated 4-byte UTF-8 sequence");
            }
            final int b1 = data[pos++] & BYTE_MASK;
            final int b2 = data[pos++] & BYTE_MASK;
            final int b3 = data[pos++] & BYTE_MASK;
            if ((b1 & UTF8_CONT_MASK) != UTF8_CONT_PREFIX
                    || (b2 & UTF8_CONT_MASK) != UTF8_CONT_PREFIX
                    || (b3 & UTF8_CONT_MASK) != UTF8_CONT_PREFIX) {
                throw new StreamingAbortException("Invalid 4-byte UTF-8 continuation");
            }
            final int cp = ((b0 & UTF8_PAYLOAD_3) << SHIFT_18)
                    | ((b1 & UTF8_PAYLOAD_6) << SHIFT_12)
                    | ((b2 & UTF8_PAYLOAD_6) << SHIFT_6)
                    | (b3 & UTF8_PAYLOAD_6);
            scratch.appendCodePoint(cp);
            return;
        }
        throw new StreamingAbortException("Invalid UTF-8 lead byte");
    }

    private int findSimpleTextEnd(final int start) {
        int scan = start;
        while (scan < length) {
            final byte b = data[scan];
            if (b == BYTE_LT) {
                return (scan + 1 < length && data[scan + 1] == BYTE_SLASH) ? scan : -1;
            }
            if (b == BYTE_AMP || b == BYTE_CR || (b & BYTE_MASK) > ASCII_MAX) {
                return -1;
            }
            scan++;
        }
        return -1;
    }

    /**
     * Converts an exact decimal mantissa and base-10 exponent to a float, or returns {@code NaN}
     * when double-rounding, subnormal scaling, or overflow requires JDK fallback.
     */
    private static float tryConvertExactFloat(final boolean negative, final long mantissa, final int exp10) {
        if (mantissa == 0L) {
            return negative ? NEGATIVE_ZERO : POSITIVE_ZERO;
        }
        if (exp10 < -MAX_EXACT_POW10 || exp10 > MAX_EXACT_POW10) {
            return Float.NaN;
        }
        final double exactDouble =
                exp10 >= 0 ? ((double) mantissa * POW10_TABLE[exp10]) : ((double) mantissa / POW10_TABLE[-exp10]);
        if (exactDouble < Float.MIN_NORMAL || exactDouble > Float.MAX_VALUE) {
            return Float.NaN;
        }
        // Reject values near a float midpoint where double-rounding could shift the final ULP.
        final long low29 = Double.doubleToRawLongBits(exactDouble) & FLOAT_TIE_LOW29_MASK;
        if (low29 > FLOAT_TIE_GUARD_LOW && low29 < FLOAT_TIE_GUARD_HIGH) {
            return Float.NaN;
        }
        final float value = (float) exactDouble;
        return negative ? -value : value;
    }

    private float parseFloatBytes(final int start, final int end) {
        int s = start;
        int e = end;
        while (s < e && (data[s] & BYTE_MASK) <= BYTE_SPACE) {
            s++;
        }
        while (e > s && (data[e - 1] & BYTE_MASK) <= BYTE_SPACE) {
            e--;
        }
        if (s >= e) {
            return Float.parseFloat("");
        }
        final int trimmedStart = s;
        boolean negative = false;
        final byte signByte = data[s];
        if (signByte == '-') {
            negative = true;
            s++;
        } else if (signByte == '+') {
            s++;
        }
        if (s >= e) {
            return parseFloatFallback(trimmedStart, e);
        }
        final byte first = data[s];
        if ((first < '0' || first > '9') && first != '.') {
            return parseFloatFallback(trimmedStart, e);
        }

        long mantissa = 0L;
        int sigDigits = 0;
        int fracDigits = 0;
        boolean seenDigit = false;
        boolean seenDot = false;
        int idx = s;
        while (idx < e) {
            final byte ch = data[idx];
            if (ch >= '0' && ch <= '9') {
                seenDigit = true;
                final int digit = ch - '0';
                if (mantissa != 0L || digit != 0) {
                    sigDigits++;
                    if (sigDigits > MAX_EXACT_DOUBLE_DIGITS) {
                        return parseFloatFallback(trimmedStart, e);
                    }
                    mantissa = mantissa * RADIX_TEN + digit;
                }
                if (seenDot) {
                    fracDigits++;
                }
                idx++;
            } else if (ch == '.') {
                if (seenDot) {
                    return parseFloatFallback(trimmedStart, e);
                }
                seenDot = true;
                idx++;
            } else {
                break;
            }
        }
        if (!seenDigit) {
            return parseFloatFallback(trimmedStart, e);
        }

        int explicitExp = 0;
        if (idx < e) {
            final byte expMarker = data[idx];
            if (expMarker != 'e' && expMarker != 'E') {
                return parseFloatFallback(trimmedStart, e);
            }
            idx++;
            if (idx >= e) {
                return parseFloatFallback(trimmedStart, e);
            }
            boolean expNeg = false;
            final byte expSign = data[idx];
            if (expSign == '-') {
                expNeg = true;
                idx++;
            } else if (expSign == '+') {
                idx++;
            }
            if (idx >= e) {
                return parseFloatFallback(trimmedStart, e);
            }
            while (idx < e) {
                final byte ec = data[idx];
                if (ec < '0' || ec > '9') {
                    return parseFloatFallback(trimmedStart, e);
                }
                if (explicitExp > MAX_EXACT_POW10 * RADIX_TEN) {
                    return parseFloatFallback(trimmedStart, e);
                }
                explicitExp = explicitExp * RADIX_TEN + (ec - '0');
                idx++;
            }
            if (expNeg) {
                explicitExp = -explicitExp;
            }
        }

        final float fastValue = tryConvertExactFloat(negative, mantissa, explicitExp - fracDigits);
        return Float.isNaN(fastValue) ? parseFloatFallback(trimmedStart, e) : fastValue;
    }

    private float parseFloatFallback(final int start, final int end) {
        return Float.parseFloat(new String(data, start, end - start, StandardCharsets.US_ASCII));
    }

    private int parseIntBytes(final int start, final int end) {
        if (start >= end) {
            return Integer.parseInt("");
        }
        int idx = start;
        boolean negative = false;
        final byte first = data[idx];
        if (first == '-') {
            negative = true;
            idx++;
        } else if (first == '+') {
            idx++;
        }
        final int digitCount = end - idx;
        if (digitCount <= 0 || digitCount > MAX_SAFE_FAST_INT_DIGITS) {
            return Integer.parseInt(new String(data, start, end - start, StandardCharsets.US_ASCII));
        }
        int accum = 0;
        while (idx < end) {
            final byte ch = data[idx++];
            if (ch < '0' || ch > '9') {
                return Integer.parseInt(new String(data, start, end - start, StandardCharsets.US_ASCII));
            }
            accum = accum * RADIX_TEN + (ch - '0');
        }
        return negative ? -accum : accum;
    }

    private void skipWhitespace() {
        while (pos < length) {
            final byte b = data[pos];
            if (b == BYTE_SPACE || b == BYTE_LF || b == BYTE_CR || b == BYTE_TAB) {
                pos++;
            } else {
                return;
            }
        }
    }

    private boolean isCommentStart(final int offset) {
        return offset + COMMENT_PREFIX_LEN <= length
                && data[offset + 1] == BYTE_BANG
                && data[offset + 2] == BYTE_DASH
                && data[offset + 3] == BYTE_DASH;
    }

    private boolean isCdataStart(final int offset) {
        if (offset + CDATA_PREFIX_LEN > length) {
            return false;
        }
        for (int i = 0; i < CDATA_HEADER.length; i++) {
            if (data[offset + 1 + i] != CDATA_HEADER[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean bytesMatch(final byte[] cached, final int start, final int len) {
        for (int i = 0; i < len; i++) {
            if (cached[i] != data[start + i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNameDelimiter(final byte b) {
        return b == BYTE_SPACE
                || b == BYTE_TAB
                || b == BYTE_LF
                || b == BYTE_CR
                || b == BYTE_GT
                || b == BYTE_SLASH
                || b == BYTE_EQ
                || b == BYTE_QUESTION;
    }

    private static void verifyXmlDeclarationEncoding(final String declData) {
        final int encIdx = declData.indexOf(ENCODING_ATTR_PREFIX);
        if (encIdx < 0) {
            return;
        }
        int qStart = declData.indexOf('"', encIdx);
        char quote = '"';
        final int sqStart = declData.indexOf('\'', encIdx);
        if (sqStart >= 0 && (qStart < 0 || sqStart < qStart)) {
            qStart = sqStart;
            quote = '\'';
        }
        if (qStart < 0) {
            return;
        }
        final int qEnd = declData.indexOf(quote, qStart + 1);
        if (qEnd < 0) {
            return;
        }
        final String encoding = declData.substring(qStart + 1, qEnd).trim().toLowerCase();
        if (!UTF8_CANONICAL.equals(encoding) && !UTF8_COMPACT.equals(encoding)) {
            throw new StreamingAbortException("Unsupported XML encoding: " + encoding);
        }
    }

    private static double[] buildPow10Table() {
        final double[] table = new double[MAX_EXACT_POW10 + 1];
        double current = 1.0d;
        for (int i = 0; i < table.length; i++) {
            table[i] = current;
            current *= RADIX_TEN;
        }
        return table;
    }

    /** Callback sink for materializing a local JDOM subtree without compile-time JDOM coupling. */
    interface JdomSubtreeBuilder {
        Object newElement(String name) throws Throwable;

        void addAttribute(Object element, String name, String value) throws Throwable;

        void addText(Object element, String text) throws Throwable;

        void addCdata(Object element, String text) throws Throwable;

        void addProcessingInstruction(Object element, String target, String data) throws Throwable;

        void addChild(Object parent, Object child) throws Throwable;
    }

    /** Signals an unsupported XML feature or parse error that must fail closed to native JDOM. */
    static final class StreamingAbortException extends RuntimeException {
        StreamingAbortException(final String message) {
            super(message);
        }
    }
}
