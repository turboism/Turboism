package dev.turboism.i18n;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

final class Utf8PluginCatalog {

    private static final byte[] UTF8_BOM = {
        (byte) 0xEF, (byte) 0xBB, (byte) 0xBF
    };

    private Utf8PluginCatalog() {
    }

    static Optional<Map<String, String>> load(
        final String pluginId,
        final ClassLoader pluginClassLoader,
        final String resourcePath,
        final LocalizationDiagnosticSink diagnostics
    ) {
        final List<byte[]> resources;
        try {
            resources = PluginCatalogResources.readLocal(pluginClassLoader, resourcePath);
        } catch (IOException exception) {
            record(
                diagnostics,
                "I18N_CATALOG_LOAD_FAILED",
                pluginId,
                "",
                "Catalog could not be read from the isolated plugin classloader."
            );
            return Optional.empty();
        }
        if (resources.isEmpty()) {
            return Optional.empty();
        }
        if (resources.size() != 1) {
            record(
                diagnostics,
                "I18N_CATALOG_DUPLICATE_RESOURCE",
                pluginId,
                "",
                "Multiple plugin-local resources exist for one catalog path."
            );
            return Optional.empty();
        }
        try {
            return Optional.of(parse(resources.get(0)));
        } catch (CatalogException exception) {
            record(
                diagnostics,
                exception.code,
                pluginId,
                exception.key,
                exception.getMessage()
            );
            return Optional.empty();
        }
    }

    private static Map<String, String> parse(final byte[] bytes) throws CatalogException {
        if (startsWithBom(bytes)) {
            throw new CatalogException(
                "I18N_CATALOG_BOM",
                "",
                "Catalog must be UTF-8 without BOM."
            );
        }
        final String text = decode(bytes);
        final Map<String, String> values = new LinkedHashMap<>();
        for (String line : logicalLines(text)) {
            final String stripped = line.stripLeading();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("!")) {
                continue;
            }
            final Properties property = new Properties();
            try {
                property.load(new StringReader(line + "\n"));
            } catch (IOException | IllegalArgumentException exception) {
                throw new CatalogException(
                    "I18N_CATALOG_LOAD_FAILED",
                    "",
                    "Catalog contains an invalid properties entry."
                );
            }
            if (property.isEmpty()) {
                continue;
            }
            if (property.size() != 1) {
                throw new CatalogException(
                    "I18N_CATALOG_LOAD_FAILED",
                    "",
                    "Catalog logical entry produced an invalid property count."
                );
            }
            final String key = property.stringPropertyNames().iterator().next();
            if (values.containsKey(key)) {
                throw new CatalogException(
                    "I18N_CATALOG_DUPLICATE_KEY",
                    key,
                    "Catalog contains a duplicate localization key."
                );
            }
            values.put(key, property.getProperty(key));
        }
        return Map.copyOf(values);
    }

    private static String decode(final byte[] bytes) throws CatalogException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new CatalogException(
                "I18N_CATALOG_INVALID_UTF8",
                "",
                "Catalog contains malformed UTF-8."
            );
        }
    }

    private static List<String> logicalLines(final String text) throws CatalogException {
        final List<String> lines = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean continuing = false;
        for (String physical : text.split("\\R", -1)) {
            final String part = continuing ? physical.stripLeading() : physical;
            current.append(part);
            final int trailingSlashes = trailingBackslashes(current);
            if ((trailingSlashes & 1) == 1) {
                current.setLength(current.length() - 1);
                continuing = true;
            } else {
                lines.add(current.toString());
                current.setLength(0);
                continuing = false;
            }
        }
        if (continuing) {
            throw new CatalogException(
                "I18N_CATALOG_LOAD_FAILED",
                "",
                "Catalog ends with an incomplete continuation."
            );
        }
        return lines;
    }

    private static int trailingBackslashes(final CharSequence value) {
        int count = 0;
        for (int index = value.length() - 1; index >= 0 && value.charAt(index) == '\\'; index--) {
            count++;
        }
        return count;
    }

    private static boolean startsWithBom(final byte[] bytes) {
        if (bytes.length < UTF8_BOM.length) {
            return false;
        }
        for (int index = 0; index < UTF8_BOM.length; index++) {
            if (bytes[index] != UTF8_BOM[index]) {
                return false;
            }
        }
        return true;
    }

    private static void record(
        final LocalizationDiagnosticSink diagnostics,
        final String code,
        final String pluginId,
        final String key,
        final String message
    ) {
        diagnostics.record(new LocalizationDiagnostic(code, pluginId, key, "", message));
    }

    private static final class CatalogException extends Exception {
        private final String code;
        private final String key;

        private CatalogException(
            final String code,
            final String key,
            final String message
        ) {
            super(message);
            this.code = code;
            this.key = key;
        }
    }
}
