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

    /**
     * Loads one plugin's catalogs, resolving the locale afresh from the operator property, the host
     * display locale and the JVM display locale.
     *
     * <p>Prefer {@code createResolved} for plugins loaded after startup, so that every plugin shares
     * the single locale the runtime already settled on. Loading never fails on a bad catalog: an
     * unreadable or malformed catalog is recorded as {@code INVALID} through {@code diagnostics} and
     * the fallback chain is used instead. The base catalog named by {@code i18n.baseName()} is always
     * loaded as the last resort.
     *
     * @param pluginId the plugin whose catalogs are loaded; must not be {@code null} or blank
     * @param pluginClassLoader the loader the catalog resources are read from, isolating this
     *     plugin's resources from every other plugin's; must not be {@code null}
     * @param i18n the plugin's declared base name and locale list; must not be {@code null}
     * @param explicitLocale the configured locale tag, or {@code null}/{@code system} to defer to the
     *     host and JVM locales
     * @param displayLocale the Cubism host's display locale, consulted after the explicit choice
     * @param jvmDisplayLocale the JVM display locale, consulted last before the base catalog
     * @param diagnostics sink for every rejected locale and unusable catalog; must not be
     *     {@code null}
     * @return a usable catalog even when every declared locale failed to load, never {@code null}
     * @throws IllegalArgumentException if {@code pluginId} is blank
     */
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
        // A legacy explicit "base" is moved to the final position so the
        // implicit base remains the single final fallback for both forms.
        catalogIds.remove("base");
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

    /**
     * Captures the current state of this catalog and everything it has complained about so far.
     *
     * <p>A point-in-time copy with all lists defensively copied: the counters keep advancing on the
     * live instance afterwards. Missing keys and malformed patterns are listed sorted by their
     * locale-and-key composite, each carrying the total number of occurrences even though only the
     * first of each was reported to the diagnostic sink. The reported fallback chain has the
     * {@code marker} pseudo-entry appended to show what a fully unresolved key degrades to. Safe to
     * call from any thread; the counters it reads are atomic, but the snapshot is not an atomic view
     * of all of them together.
     *
     * @return the locale resolution, per-catalog load state, and accumulated warning counts, never
     *     {@code null}
     */
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

    /**
     * Load outcome of one catalog in the fallback chain.
     *
     * @param locale the catalog id, a language tag or {@code base} for the final fallback catalog
     * @param state {@code AVAILABLE} if the catalog loaded, {@code INVALID} if it was found but could
     *     not be read, {@code MISSING} if the declared resource was absent
     * @param keyCount the number of keys the catalog contributed, zero unless {@code AVAILABLE}
     */
    public record CatalogSnapshot(String locale, String state, long keyCount) {
    }

    /**
     * One key that could not be resolved anywhere in the fallback chain, with how often it was asked
     * for.
     *
     * @param key the localization key that was requested
     * @param locale the language tag active when the lookup failed
     * @param marker the placeholder text returned to the caller in place of a translation
     * @param count total lookups that failed for this key and locale; only the first emitted a
     *     diagnostic, the rest were suppressed
     */
    public record MissingKeySnapshot(
        String key,
        String locale,
        String marker,
        long count
    ) {
    }

    /**
     * One catalog entry that was found but could not be formatted as a message pattern.
     *
     * @param key the localization key whose pattern is invalid
     * @param locale the language tag active when formatting failed
     * @param code the diagnostic code reported for this failure, {@code I18N_FORMAT_INVALID}
     * @param marker the placeholder text returned to the caller in place of the formatted message
     * @param count total formatting attempts that failed for this key and locale; only the first
     *     emitted a diagnostic, the rest were suppressed
     */
    public record MalformedPatternSnapshot(
        String key,
        String locale,
        String code,
        String marker,
        long count
    ) {
    }

    /**
     * Full localization report for one plugin: which locale was chosen and why, which catalogs
     * backed it, and everything that failed to resolve.
     *
     * <p>Immutable — the compact constructor copies every list, so the report does not change as the
     * live catalog keeps serving lookups.
     *
     * @param pluginId the plugin this report describes
     * @param localeSource which input the effective locale came from, for example {@code STARTUP}
     * @param requestedLocale the locale tag originally asked for, which may differ from the one used
     * @param normalizedLocale the language tag of the locale actually in effect
     * @param fallbackChain catalog ids in consultation order, with {@code marker} appended to show
     *     the final degradation step; defensively copied
     * @param catalogs load state of each catalog in the chain; defensively copied
     * @param missingKeys keys that resolved nowhere, sorted by locale and key; defensively copied
     * @param malformedPatterns entries whose pattern would not format, sorted by locale and key;
     *     defensively copied
     * @param missingWarningsEmitted distinct missing keys that produced a diagnostic
     * @param missingWarningsSuppressed repeat missing-key lookups that were deliberately not reported
     * @param malformedWarningsEmitted distinct malformed patterns that produced a diagnostic
     * @param malformedWarningsSuppressed repeat malformed-pattern failures that were deliberately not
     *     reported
     */
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
