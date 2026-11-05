package dev.turboism.config;

import dev.turboism.config.NativeOptimizationConfig.Choice;
import dev.turboism.config.NativeOptimizationConfig.Option;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Startup facts kept separately from editable preferences; never infers installation from a checkbox. */
public final class NativeOptimizationDiagnostics {
    /** Requested startup state and the outcome of the verified installation attempt. */
    public record Status(Choice choice, String state, String reason) {}

    /** Aggregate state for an option whose serving protocols did not all install. */
    public static final String STATE_PARTIAL = "partial";

    /** Protocol the clip-mask installer reports its scissor-limited clear sub-mode under. */
    public static final String PROTOCOL_CLIP_MASK_SCISSOR_CLEAR = "TURBOISM_CLIP_MASK_SCISSOR_CLEAR";

    /** Protocol the incremental-update bridge reports its glue-affecter sub-mode under. */
    public static final String PROTOCOL_INCREMENTAL_UPDATE_AFFECTERS = "TURBOISM_INCREMENTAL_UPDATE_AFFECTERS";

    /**
     * Protocol id to the options its installation outcome proves. One protocol may serve
     * several options (one weave implementing two preferences) and one option may be served
     * by several protocols (one preference implemented by two weaves), so this table is the
     * only place that decides what a report is evidence for.
     */
    private static final Map<String, List<Option>> PROTOCOL_TABLE = protocolTable();

    /** Protocols in {@link Option} order with the protocols serving them, in table order. */
    private static final Map<Option, List<String>> SERVING_PROTOCOLS = servingProtocols();

    /**
     * Protocols that only measure or validate a behaviour driven by a {@code -D} property.
     * They prove no user-visible option, so their reports are logged and then dropped;
     * listing them explicitly keeps an unmapped protocol a reviewed decision.
     */
    private static final Map<String, String> VALIDATION_ONLY = Map.of(
            "TURBOISM_CANVAS_COMPOSITE", "measurement-only canvas composite probe",
            "TURBOISM_CLIP_MASK_PROBE", "measurement-only clipping-mask probe",
            "TURBOISM_GL_ERROR_ELISION", "validation-only glGetError elision",
            "TURBOISM_SORTING_INFO_CACHE", "validation-only sorting-info reuse",
            "TURBOISM_STATE_ELISION", "validation-only redundant GL state elision");

    private static PerformanceSettingsConfig startup;
    private static final Map<PerformanceSettingsConfig.Field, String> OBSERVED =
            new EnumMap<>(PerformanceSettingsConfig.Field.class);
    /** Resolved preference per option; reports never rewrite it. */
    private static final Map<Option, Choice> REQUESTED = new EnumMap<>(Option.class);
    /** Last outcome per protocol, kept apart so two hooks serving one option cannot overwrite. */
    private static final Map<String, Report> REPORTS = new LinkedHashMap<>();

    /** One protocol's own outcome, before it is aggregated into an option. */
    private record Report(String state, String reason) {}

    /** Publishes the complete snapshot captured before any performance hook installs. */
    public static synchronized void startup(PerformanceSettingsConfig snapshot) {
        startup = snapshot;
        OBSERVED.clear();
        REQUESTED.clear();
        REPORTS.clear();
        for (Option option : Option.values()) {
            var value = snapshot.value(option.field());
            requested(option, new Choice(Boolean.parseBoolean(value.value()), value.source()));
        }
    }
    /** Clears the launch snapshot and observed statuses for isolated test suites. */
    public static synchronized void resetForTesting() {
        startup = null;
        OBSERVED.clear();
        REQUESTED.clear();
        REPORTS.clear();
    }
    /** Current launch snapshot; absent when bootstrap was not observed. */
    public static synchronized PerformanceSettingsConfig startup() {
        return startup;
    }
    /** Records a result observed from the live host, rather than inferring it from a preference. */
    public static synchronized void observed(PerformanceSettingsConfig.Field field, String result) {
        OBSERVED.put(field, result);
    }
    /** Last host observation for a non-transformer preference. */
    public static synchronized String observed(PerformanceSettingsConfig.Field field) {
        return OBSERVED.getOrDefault(field, "not-observed");
    }

    private NativeOptimizationDiagnostics() {}

    /** Records the resolved preference without replacing an observed installation outcome. */
    public static synchronized void requested(Option option, Choice choice) {
        REQUESTED.put(option, choice);
    }

    /**
     * Records one contributor's actual result for the configurable native optimizations.
     * The outcome is kept per protocol and aggregated per option in {@link #snapshot()}, so
     * two hooks serving one preference can neither overwrite nor reorder each other.
     */
    public static synchronized void installed(String protocol, String state, String reason) {
        if (!PROTOCOL_TABLE.containsKey(protocol)) return;
        final Report previous = REPORTS.get(protocol);
        if ("pending".equals(state) && previous != null && "installed".equals(previous.state())) {
            return;
        }
        REPORTS.put(protocol, new Report(state, reason));
    }

    /** Detached startup diagnostics; missing entries mean no proven installation attempt. */
    public static synchronized Map<Option, Status> snapshot() {
        final Map<Option, Status> statuses = new EnumMap<>(Option.class);
        for (Option option : Option.values()) {
            final List<String> protocols = SERVING_PROTOCOLS.get(option);
            if (REQUESTED.containsKey(option) || protocols.stream().anyMatch(REPORTS::containsKey)) {
                statuses.put(option, aggregate(option, protocols));
            }
        }
        return Map.copyOf(statuses);
    }

    /** Protocol ids that prove at least one option, in declaration order. */
    public static Map<String, List<Option>> protocols() {
        return PROTOCOL_TABLE;
    }

    /** Validation-only protocols with the behaviour they measure; they prove no option. */
    public static Map<String, String> validationOnlyProtocols() {
        return VALIDATION_ONLY;
    }

    /** Options one protocol proves; empty for a validation-only or unknown protocol. */
    public static List<Option> optionsServedBy(String protocol) {
        return PROTOCOL_TABLE.getOrDefault(protocol, List.of());
    }

    /**
     * Reduces one option's protocol reports to the user-visible status: all serving
     * protocols installed, some installed ({@link #STATE_PARTIAL} listing every protocol
     * that did not install with its own reason), or none installed, where the most
     * significant report and the preference precedence of a single report decide.
     */
    private static Status aggregate(final Option option, final List<String> protocols) {
        final Choice choice = REQUESTED.get(option);
        final List<String> notInstalled = new ArrayList<>();
        String installedReason = null;
        int installed = 0;
        for (final String protocol : protocols) {
            final Report report = REPORTS.get(protocol);
            if (report == null) {
                notInstalled.add(protocol + "=not-attempted");
            } else if ("installed".equals(report.state())) {
                installed++;
                if (installedReason == null) installedReason = report.reason();
            } else {
                notInstalled.add(protocol + "=" + report.reason());
            }
        }
        if (protocols.isEmpty()) return new Status(choice, "not-installed", defaultReason(choice));
        if (installed == protocols.size()) return new Status(choice, "installed", installedReason);
        if (installed > 0) return new Status(choice, STATE_PARTIAL, String.join(", ", notInstalled));
        return noneInstalled(protocols, choice);
    }

    /** No serving protocol installed: one deterministic report stands for the option. */
    private static Status noneInstalled(final List<String> protocols, final Choice choice) {
        Report reported = null;
        for (final String protocol : protocols) {
            final Report report = REPORTS.get(protocol);
            if (report != null && (reported == null || precedence(report.state()) > precedence(reported.state()))) {
                reported = report;
            }
        }
        if (reported == null) return new Status(choice, "not-installed", defaultReason(choice));
        return new Status(
                choice,
                reported.state(),
                choice == null || choice.enabled() ? reported.reason() : "preference-disabled");
    }

    /** Significance of a non-installed report, so merging never depends on arrival order. */
    private static int precedence(final String state) {
        return switch (state) {
            case "failed" -> 3;
            case "not-installed" -> 2;
            case "pending" -> 1;
            default -> 0;
        };
    }

    /** A preference that never reached an install attempt still reports why. */
    private static String defaultReason(final Choice choice) {
        return choice == null || choice.enabled() ? "not-attempted" : "preference-disabled";
    }

    private static Map<String, List<Option>> protocolTable() {
        final Map<String, List<Option>> table = new LinkedHashMap<>();
        table.put("TURBOISM_MESH_TRIANGULATION_HASH", List.of(Option.MESH_TRIANGULATION_HASH));
        table.put("TURBOISM_TRIANGULATION_EDGE_INDEX", List.of(Option.TRIANGULATION_EDGE_INDEX));
        table.put("TURBOISM_ATLAS_TILE_BBOX", List.of(Option.ATLAS_TILE_BBOX));
        table.put("TURBOISM_ATLAS_CACHE_REUSE", List.of(Option.ATLAS_CACHE_REUSE));
        table.put("TURBOISM_MODEL_UPDATE_SKIP", List.of(Option.MODEL_UPDATE_SKIP));
        table.put("TURBOISM_INCREMENTAL_UPDATE", List.of(Option.INCREMENTAL_UPDATE));
        table.put(PROTOCOL_INCREMENTAL_UPDATE_AFFECTERS, List.of(Option.INCREMENTAL_UPDATE_AFFECTERS));
        table.put("TURBOISM_UNIFORM_LOCATION", List.of(Option.UNIFORM_LOCATION_CACHE));
        // The upload-elision preference is implemented by two independent weaves: the
        // skipped-frame elision refuses when the uniform-location cache is enabled, the
        // line-renderer split installs in that combination.
        table.put("TURBOISM_UPLOAD_ELISION", List.of(Option.UPLOAD_ELISION));
        table.put("TURBOISM_LINE_RENDERER_UPLOAD_SPLIT", List.of(Option.UPLOAD_ELISION));
        table.put("TURBOISM_MESH_SETUP_ELISION", List.of(Option.MESH_SETUP_ELISION));
        table.put("TURBOISM_INPUT_PATH", List.of(Option.INPUT_PATH_ELISION));
        table.put("TURBOISM_DEFERRED_GL_ERROR_CHECK", List.of(Option.MESA_GL_THREAD));
        table.put("TURBOISM_ASYNC_READBACK", List.of(Option.ASYNC_READBACK));
        table.put("TURBOISM_MESH_INTERPOLATION", List.of(Option.MESH_INTERPOLATION));
        table.put("TURBOISM_SHADER_UNIFORM", List.of(Option.SHADER_UNIFORM));
        table.put("TURBOISM_MATRIX_SCRATCH", List.of(Option.MATRIX_SCRATCH));
        table.put("TURBOISM_IMAGE_ARCHIVE_REUSE", List.of(Option.IMAGE_ARCHIVE_REUSE));
        table.put("TURBOISM_FLOAT_ARRAY_PARSE_CACHE", List.of(Option.FLOAT_ARRAY_PARSE_CACHE));
        table.put("TURBOISM_TEXTURE_UPLOAD_PREPARATION", List.of(Option.TEXTURE_UPLOAD_PREPARATION));
        table.put("TURBOISM_WARP_POSITION_PROJECTION", List.of(Option.WARP_POSITION_PROJECTION));
        table.put("TURBOISM_REINIT_PAIR_ELISION", List.of(Option.REINIT_PAIR_ELISION));
        table.put("TURBOISM_CLIP_MASK_DEDUPE", List.of(Option.CLIP_MASK_DEDUPLICATION));
        table.put(PROTOCOL_CLIP_MASK_SCISSOR_CLEAR, List.of(Option.CLIP_MASK_SCISSOR_CLEAR));
        table.put(
                "TURBOISM_EXTENDED_INTERPOLATION_INCREMENTAL_SETUP",
                List.of(Option.EXTENDED_INTERPOLATION_INCREMENTAL_SETUP));
        table.put("TURBOISM_MODEL_INSTANCE_COALESCE", List.of(Option.MODEL_INSTANCE_COALESCE));
        table.put("TURBOISM_INSPECTOR_PROPERTY_BYPASS", List.of(Option.INSPECTOR_PROPERTY_BYPASS));
        return Collections.unmodifiableMap(table);
    }

    private static Map<Option, List<String>> servingProtocols() {
        final Map<Option, List<String>> serving = new EnumMap<>(Option.class);
        for (Option option : Option.values()) serving.put(option, new ArrayList<>());
        PROTOCOL_TABLE.forEach((protocol, options) ->
                options.forEach(option -> serving.get(option).add(protocol)));
        final Map<Option, List<String>> immutable = new EnumMap<>(Option.class);
        serving.forEach((option, protocols) -> immutable.put(option, List.copyOf(protocols)));
        return Collections.unmodifiableMap(immutable);
    }
}
