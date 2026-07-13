package dev.turboism.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class CanonicalJson {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Comparator<String> CODE_POINTS = CanonicalJson::compareCodePoints;

    private CanonicalJson() {}

    static String sha256Without(JsonNode value, String field) throws Exception {
        ObjectNode copy = ((ObjectNode) value).deepCopy();
        copy.remove(field);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes(copy));
        return HexFormat.of().formatHex(digest);
    }

    static byte[] bytes(JsonNode value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write(value, output);
        return output.toByteArray();
    }

    private static void write(JsonNode value, ByteArrayOutputStream output) throws Exception {
        if (value.isObject()) writeObject(value, output);
        else if (value.isArray()) writeArray(value, output);
        else if (value.isTextual()) append(JSON.writeValueAsString(value.textValue()), output);
        else if (value.isIntegralNumber()) append(value.bigIntegerValue().toString(), output);
        else if (value.isBoolean()) append(value.booleanValue() ? "true" : "false", output);
        else if (value.isNull()) append("null", output);
        else throw new IllegalArgumentException("Unsupported canonical JSON number");
    }

    private static void writeObject(JsonNode value, ByteArrayOutputStream output) throws Exception {
        output.write('{');
        List<String> names = new ArrayList<>();
        value.fieldNames().forEachRemaining(names::add);
        names.sort(CODE_POINTS);
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) output.write(',');
            String name = names.get(index);
            append(JSON.writeValueAsString(name), output);
            output.write(':');
            write(value.get(name), output);
        }
        output.write('}');
    }

    private static void writeArray(JsonNode value, ByteArrayOutputStream output) throws Exception {
        output.write('[');
        for (int index = 0; index < value.size(); index++) {
            if (index > 0) output.write(',');
            write(value.get(index), output);
        }
        output.write(']');
    }

    private static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftPoint = left.codePointAt(leftIndex);
            int rightPoint = right.codePointAt(rightIndex);
            if (leftPoint != rightPoint) return Integer.compare(leftPoint, rightPoint);
            leftIndex += Character.charCount(leftPoint);
            rightIndex += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    private static void append(String value, ByteArrayOutputStream output) {
        output.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
