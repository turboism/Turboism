package dev.turboism.distribution;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class ManifestPrimitives {
    private static final String PACKAGE_ID = "[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+";
    private static final String TIMESTAMP = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?Z";

    private ManifestPrimitives() {}

    static boolean packageId(JsonNode value) {
        if (!value.isTextual()) return false;
        String text = value.textValue();
        if (text.length() < 3 || text.length() > 255 || !text.matches(PACKAGE_ID)) return false;
        for (String segment : text.split("\\.")) if (!safeSegment(segment)) return false;
        return true;
    }

    static boolean timestamp(JsonNode value) {
        if (!value.isTextual() || !value.textValue().matches(TIMESTAMP)) return false;
        try {
            Instant instant = Instant.parse(value.textValue());
            return DateTimeFormatter.ISO_INSTANT.format(instant).equals(value.textValue());
        } catch (Exception exception) { return false; }
    }

    static boolean byteCount(JsonNode value) {
        return value.isIntegralNumber() && value.bigIntegerValue().signum() >= 0
            && value.bigIntegerValue().bitLength() <= 63;
    }

    static boolean relativePath(String value) {
        if (value == null || value.isEmpty() || !Normalizer.isNormalized(value, Normalizer.Form.NFC)) return false;
        if (value.getBytes(StandardCharsets.UTF_8).length > 1024 || value.startsWith("/") || value.endsWith("/")) return false;
        if (value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || drivePrefix(value)) return false;
        for (int index = 0; index < value.length();) {
            int point = value.codePointAt(index);
            if (point == 0 || Character.isISOControl(point)) return false;
            index += Character.charCount(point);
        }
        for (String segment : value.split("/", -1)) if (!safeSegment(segment)) return false;
        return true;
    }

    private static boolean safeSegment(String segment) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        if (segment.endsWith(".") || segment.endsWith(" ")) return false;
        int dot = segment.indexOf('.');
        String base = segment.substring(0, dot < 0 ? segment.length() : dot).toUpperCase(Locale.ROOT);
        return !base.matches("CON|PRN|AUX|NUL|(?:COM|LPT)[1-9]");
    }

    private static boolean drivePrefix(String value) {
        return value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
    }
}
