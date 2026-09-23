package dev.turboism.distribution;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

final class ManifestPrimitives {
    private static final String PACKAGE_ID = "[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+";
    private static final String TIMESTAMP = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z";

    private ManifestPrimitives() {}

    static boolean packageId(JsonNode value) {
        if (!value.isTextual()) return false;
        String text = value.textValue();
        if (text.length() < 3 || text.length() > 255 || !text.matches(PACKAGE_ID)) return false;
        for (String segment : text.split("\\."))
            if (!dev.turboism.core.archive.ArchivePaths.safeSegment(segment)) return false;
        return true;
    }

    static boolean timestamp(JsonNode value) {
        if (!value.isTextual() || !value.textValue().matches(TIMESTAMP)) return false;
        try {
            Instant.parse(value.textValue());
            return true;
        } catch (Exception exception) { return false; }
    }

    static boolean schemaVersion(JsonNode value) {
        return value.isIntegralNumber() && java.math.BigInteger.ONE.equals(value.bigIntegerValue());
    }

    static boolean byteCount(JsonNode value) {
        return value.isIntegralNumber() && value.bigIntegerValue().signum() >= 0
            && value.bigIntegerValue().bitLength() <= 63;
    }
}
