package dev.turboism.plugin.turboismwithfx;

import dev.turboism.protocol.json.StrictJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict non-secret JSON codec for custom provider profiles. */
final class FxProviderProfileCodec {

    private FxProviderProfileCodec() {
    }

    static String encode(final List<FxProviderProfile> profiles) {
        final ArrayList<Object> values = new ArrayList<>();
        for (FxProviderProfile profile : profiles) {
            if (profile.kind() != FxProviderProfile.Kind.OPENAI_COMPATIBLE) continue;
            final LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("id", profile.id());
            value.put("name", profile.name());
            value.put("endpoint", profile.endpoint());
            value.put("apiKeyEnvironment", profile.apiKeyEnvironment());
            value.put("defaultModel", profile.defaultModel());
            value.put("manualModels", profile.manualModels());
            values.add(value);
        }
        return StrictJson.stringify(values);
    }

    static List<FxProviderProfile> decode(final String value) {
        if (value == null || value.isBlank()) return List.of();
        final Object parsed = StrictJson.parse(value.getBytes(StandardCharsets.UTF_8));
        if (!(parsed instanceof List<?> raw)) {
            throw new IllegalArgumentException("provider profile JSON must be an array");
        }
        final ArrayList<FxProviderProfile> profiles = new ArrayList<>();
        for (Object item : raw) {
            final Map<String, Object> profile = object(item);
            profiles.add(new FxProviderProfile(
                text(profile.get("id")),
                text(profile.get("name")),
                FxProviderProfile.Kind.OPENAI_COMPATIBLE,
                "",
                text(profile.get("endpoint")),
                optionalText(profile.get("apiKeyEnvironment")),
                text(profile.get("defaultModel")),
                strings(profile.get("manualModels"))
            ));
        }
        return List.copyOf(profiles);
    }

    private static Map<String, Object> object(final Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("provider profile must be an object");
        }
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("provider profile key is invalid");
            }
            result.put(text, item);
        });
        return result;
    }

    private static String text(final Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("provider profile text is invalid");
        }
        return text;
    }

    private static String optionalText(final Object value) {
        return value instanceof String text ? text : "";
    }

    private static List<String> strings(final Object value) {
        if (!(value instanceof List<?> raw)) return List.of();
        final ArrayList<String> result = new ArrayList<>();
        for (Object item : raw) result.add(text(item));
        return result;
    }
}
