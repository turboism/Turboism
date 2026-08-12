package dev.turboism.i18n;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime implementation of one isolated plugin localization catalog. */
public final class RuntimePluginLocalization implements PluginLocalization {

    private final String pluginId;
    private final Locale locale;
    private final List<String> catalogOrder;
    private final String localeSource;
    private final String requestedLocale;
    private final Map<String, Map<String, String>> catalogs;
    private final Set<String> invalidCatalogs;
    private final List<String> fallbackCatalogs;
    private final LocalizationDiagnosticSink diagnostics;
    private final Map<String, AtomicLong> missingCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> malformedCounts = new ConcurrentHashMap<>();
    private final AtomicLong missingWarningsEmitted = new AtomicLong();
    private final AtomicLong missingWarningsSuppressed = new AtomicLong();
    private final AtomicLong malformedWarningsEmitted = new AtomicLong();
    private final AtomicLong malformedWarningsSuppressed = new AtomicLong();

    private RuntimePluginLocalization(
        final String pluginId,
        final PluginLocaleResolver.Resolution resolution,
        final List<String> catalogOrder,
        final Map<String, Map<String, String>> catalogs,
        final Set<String> invalidCatalogs,
        final LocalizationDiagnosticSink diagnostics
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        final PluginLocaleResolver.Resolution selected = Objects.requireNonNull(
            resolution,
            "resolution"
        );
        this.locale = selected.locale();
        this.catalogOrder = List.copyOf(catalogOrder);
        this.localeSource = selected.source();
        this.requestedLocale = selected.requestedLocale();
        this.catalogs = Map.copyOf(catalogs);
        this.invalidCatalogs = Set.copyOf(invalidCatalogs);
        this.fallbackCatalogs = fallbackCatalogs(locale, this.catalogOrder);
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public static RuntimePluginLocalization create(
        final String pluginId,
        final ClassLoader pluginClassLoader,
        final PluginDescriptor.I18n i18n,
        final String explicitLocale,
        final Locale displayLocale,
        final Locale jvmDisplayLocale,
        final LocalizationDiagnosticSink diagnostics
    ) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        return load(
            pluginId,
            pluginClassLoader,
            i18n,
            PluginLocaleResolver.resolveWithSource(
                pluginId,
                explicitLocale,
                displayLocale,
                jvmDisplayLocale,
                diagnostics
            ),
            diagnostics
        );
    }

    /** Builds a plugin catalog from the locale resolved once during runtime startup. */
    public static RuntimePluginLocalization createResolved(
        final String pluginId,
        final ClassLoader pluginClassLoader,
        final PluginDescriptor.I18n i18n,
        final Locale effectiveLocale,
        final LocalizationDiagnosticSink diagnostics
    ) {
        Objects.requireNonNull(effectiveLocale, "effectiveLocale");
        Objects.requireNonNull(diagnostics, "diagnostics");
        return load(
            pluginId,
            pluginClassLoader,
            i18n,
            new PluginLocaleResolver.Resolution(
                PluginLocaleResolver.normalize(effectiveLocale),
                "STARTUP",
                effectiveLocale.toLanguageTag()
            ),
            diagnostics
        );
    }

    private static RuntimePluginLocalization load(
        final String pluginId,
        final ClassLoader pluginClassLoader,
        final PluginDescriptor.I18n i18n,
        final PluginLocaleResolver.Resolution resolution,
        final LocalizationDiagnosticSink diagnostics
    ) {
        requireText(pluginId, "pluginId");
        Objects.requireNonNull(pluginClassLoader, "pluginClassLoader");
        final PluginDescriptor.I18n descriptorI18n = Objects.requireNonNull(i18n, "i18n");
        // baseName() implicitly declares the base catalog: it is always
        // loaded exactly once as the final fallback, even when locales()
        // omits it (current official form). Legacy explicit "base" dedupes.
        final LinkedHashSet<String> catalogIds = new LinkedHashSet<>(descriptorI18n.locales());
        catalogIds.add("base");
        final List<String> catalogOrder = List.copyOf(catalogIds);
        final Map<String, Map<String, String>> catalogs = new LinkedHashMap<>();
        final Set<String> invalidCatalogs = new LinkedHashSet<>();
        for (String catalogId : catalogOrder) {
            final String resourcePath = catalogPath(descriptorI18n.baseName(), catalogId);
            final LocalizationDiagnosticSink catalogDiagnostics = diagnostic -> {
                invalidCatalogs.add(catalogId);
                diagnostics.record(diagnostic);
            };
            Utf8PluginCatalog.load(
                pluginId,
                pluginClassLoader,
                resourcePath,
                catalogDiagnostics
            ).ifPresent(values -> {
                invalidCatalogs.remove(catalogId);
                catalogs.put(catalogId, values);
            });
        }
        return new RuntimePluginLocalization(
            pluginId,
            resolution,
            catalogOrder,
            catalogs,
            invalidCatalogs,
            diagnostics
        );
    }

    @Override
    public Locale locale() {
        return locale;
    }

    @Override
    public String text(final String key) {
        final String normalizedKey = requireText(key, "key");
        final Optional<String> value = lookup(normalizedKey);
        if (value.isPresent()) {
            return value.orElseThrow();
        }
        warnMissingOnce(normalizedKey);
        return marker(normalizedKey);
    }

    @Override
    public String format(final String key, final Object... arguments) {
        final String normalizedKey = requireText(key, "key");
        final Optional<String> pattern = lookup(normalizedKey);
        if (pattern.isEmpty()) {
            warnMissingOnce(normalizedKey);
            return marker(normalizedKey);
        }
        try {
            final MessageFormat formatter = new MessageFormat(pattern.orElseThrow(), locale);
            return formatter.format(arguments == null ? new Object[0] : arguments);
        } catch (IllegalArgumentException exception) {
            warnMalformedOnce(normalizedKey);
            return marker(normalizedKey);
        }
    }

    @Override
    public boolean contains(final String key) {
        return lookup(requireText(key, "key")).isPresent();
    }

    private Optional<String> lookup(final String key) {
        for (String catalogId : fallbackCatalogs) {
            final String value = catalogs.getOrDefault(catalogId, Map.of()).get(key);
            if (value != null) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private void warnMissingOnce(final String key) {
        final String warningId = locale.toLanguageTag() + "\u0000" + key;
        final long count = missingCounts
            .computeIfAbsent(warningId, ignored -> new AtomicLong())
            .incrementAndGet();
        if (count == 1) {
            missingWarningsEmitted.incrementAndGet();
            diagnostics.record(new LocalizationDiagnostic(
                "I18N_MISSING_KEY",
                pluginId,
                key,
                locale.toLanguageTag(),
                "Localization key is missing from the active fallback chain."
            ));
        } else {
            missingWarningsSuppressed.incrementAndGet();
        }
    }

    private void warnMalformedOnce(final String key) {
        final String warningId = locale.toLanguageTag() + "\u0000" + key;
        final long count = malformedCounts
            .computeIfAbsent(warningId, ignored -> new AtomicLong())
            .incrementAndGet();
        if (count == 1) {
            malformedWarningsEmitted.incrementAndGet();
            diagnostics.record(new LocalizationDiagnostic(
                "I18N_FORMAT_INVALID",
                pluginId,
                key,
                locale.toLanguageTag(),
                "Localization pattern is invalid for the active locale."
            ));
        } else {
            malformedWarningsSuppressed.incrementAndGet();
        }
    }

    public ReportSnapshot reportSnapshot() {
        final List<CatalogSnapshot> catalogSnapshots = catalogOrder.stream()
            .map(catalogId -> {
                final Map<String, String> values = catalogs.get(catalogId);
                final String state = values != null
                    ? "AVAILABLE"
                    : invalidCatalogs.contains(catalogId) ? "INVALID" : "MISSING";
                return new CatalogSnapshot(
                    catalogId,
                    state,
                    values == null ? 0 : values.size()
                );
            })
            .toList();
        final List<String> fallback = new ArrayList<>(fallbackCatalogs);
        fallback.add("marker");
        final List<MissingKeySnapshot> missing = missingCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> {
                final String[] parts = entry.getKey().split("\\u0000", 2);
                return new MissingKeySnapshot(
                    parts.length == 2 ? parts[1] : "<unknown>",
                    parts[0],
                    marker(parts.length == 2 ? parts[1] : "unknown"),
                    entry.getValue().get()
                );
            })
            .toList();
        final List<MalformedPatternSnapshot> malformed = malformedCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> {
                final String[] parts = entry.getKey().split("\\u0000", 2);
                final String key = parts.length == 2 ? parts[1] : "<unknown>";
                return new MalformedPatternSnapshot(
                    key,
                    parts[0],
                    "I18N_FORMAT_INVALID",
                    marker(key),
                    entry.getValue().get()
                );
            })
            .toList();
        return new ReportSnapshot(
            pluginId,
            localeSource,
            requestedLocale,
            locale.toLanguageTag(),
            List.copyOf(fallback),
            catalogSnapshots,
            missing,
            malformed,
            missingWarningsEmitted.get(),
            missingWarningsSuppressed.get(),
            malformedWarningsEmitted.get(),
            malformedWarningsSuppressed.get()
        );
    }

    public record CatalogSnapshot(String locale, String state, long keyCount) {
    }

    public record MissingKeySnapshot(
        String key,
        String locale,
        String marker,
        long count
    ) {
    }

    public record MalformedPatternSnapshot(
        String key,
        String locale,
        String code,
        String marker,
        long count
    ) {
    }

    public record ReportSnapshot(
        String pluginId,
        String localeSource,
        String requestedLocale,
        String normalizedLocale,
        List<String> fallbackChain,
        List<CatalogSnapshot> catalogs,
        List<MissingKeySnapshot> missingKeys,
        List<MalformedPatternSnapshot> malformedPatterns,
        long missingWarningsEmitted,
        long missingWarningsSuppressed,
        long malformedWarningsEmitted,
        long malformedWarningsSuppressed
    ) {
        public ReportSnapshot {
            fallbackChain = List.copyOf(fallbackChain);
            catalogs = List.copyOf(catalogs);
            missingKeys = List.copyOf(missingKeys);
            malformedPatterns = List.copyOf(malformedPatterns);
        }
    }

    private static List<String> fallbackCatalogs(
        final Locale locale,
        final List<String> available
    ) {
        final Set<String> supported = Set.copyOf(available);
        final LinkedHashSet<String> ids = new LinkedHashSet<>();
        exactCatalogId(locale, supported).ifPresent(ids::add);
        if (!locale.getScript().isBlank()) {
            supportedCatalogId(locale.getLanguage() + "-" + locale.getScript(), supported)
                .or(() -> supportedCatalogId(locale.getLanguage() + "_" + locale.getScript(), supported))
                .ifPresent(ids::add);
        }
        supportedCatalogId(locale.getLanguage(), supported).ifPresent(ids::add);
        if (supported.contains("base")) {
            ids.add("base");
        }
        return List.copyOf(ids);
    }

    private static Optional<String> exactCatalogId(
        final Locale locale,
        final Set<String> supported
    ) {
        if (!locale.getCountry().isBlank() || !locale.getVariant().isBlank()) {
            return Optional.empty();
        }
        if (!locale.getScript().isBlank()) {
            return supportedCatalogId(locale.getLanguage() + "-" + locale.getScript(), supported)
                .or(() -> supportedCatalogId(locale.getLanguage() + "_" + locale.getScript(), supported));
        }
        return supportedCatalogId(locale.getLanguage(), supported);
    }

    private static Optional<String> supportedCatalogId(
        final String candidate,
        final Set<String> supported
    ) {
        return supported.contains(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    private static String catalogPath(final String baseName, final String catalogId) {
        return "base".equals(catalogId)
            ? baseName + ".properties"
            : baseName + "_" + catalogId.replace('-', '_') + ".properties";
    }

    private static String marker(final String key) {
        return "⟦" + key + "⟧";
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
