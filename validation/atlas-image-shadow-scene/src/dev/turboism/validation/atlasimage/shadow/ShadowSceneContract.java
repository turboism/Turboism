package dev.turboism.validation.atlasimage.shadow;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** Fixed T040 identity, allowlisted fixtures and menu contract. */
final class ShadowSceneContract {
    static final String VERSION_5303 = "5303";
    static final String VERSION_5203 = "5203";
    /**
     * The scene family keeps one name across host profiles; the version suffix records
     * the exact host. The 5203 profile runs the identical UI scene but has no T039
     * shadow capture (that agent targets 5303-only bytes), so its payload carries no
     * {@code freeze.*} keys.
     */
    static final String SCENE_5303 = "atlas-image-shadow:5303";
    static final String SCENE_5203 = "atlas-image-shadow:5203";
    /**
     * Allowlisted measurement fixtures. A name and a hash are never checked independently: a pair
     * must come from the same entry, so an unknown or mixed fixture fails closed instead of being
     * measured as if it had been reviewed.
     */
    static final String FIXTURE_CIRCLE100_NAME = "atlas_mapping_100.cmo3";
    static final String FIXTURE_CIRCLE100_SHA256 =
        "2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e";
    /** The production-scale model already used by the earlier standalone performance probes. */
    static final String FIXTURE_HEAVY_NAME = "heavy.cmo3";
    static final String FIXTURE_HEAVY_SHA256 =
        "029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c";
    /**
     * The reviewed 5.2.03 host fixture (the same bytes the mcp 5203 wrapper already pins);
     * admitted only under the 5203 profile so a 5.3 run can never measure it.
     */
    static final String FIXTURE_OPACITY52_NAME = "part-opacity-fixture-52-final.cmo3";
    static final String FIXTURE_OPACITY52_SHA256 =
        "331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028";
    static final String OFFICIAL_JAR_SHA256 =
        "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166";
    static final String OFFICIAL_JAR_SHA256_5203 =
        "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd";
    static final String T039_CLASS_SHA256 =
        "ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6";
    static final String T039_SHAPE_SHA256 =
        "a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f";
    static final String T039_CLASS =
        "dev.turboism.validation.atlasimage.t039.T039ShadowAgent";
    static final String T039_SHADOW_MODE = "shadow-ready";
    static final String T039_SHADOW_OPT_IN = "T039_SHADOW_EXPLICIT_OPT_IN";
    static final String T039_SOURCE_BINDING = "target-pd";
    static final String T039_JAR_BASENAME = "Live2D_Cubism.jar";
    static final int T039_TRUSTED_PATHS_MAX = 4096;

    static final String EDITOR_CLASS = "com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b";
    static final String MODELING_MENU = "建模";
    static final String TEXTURE_MENU = "纹理";
    static final String EDIT_TEXTURE_SET = "编辑纹理集...";
    static final String OK_BUTTON = "OK";
    static final String CANCEL_BUTTON = "Cancel";
    static final String EXIT_MENU = "退出";
    /**
     * The 5.2 host opens a create-atlas settings form (title {@code 新纹理集设置}: texture
     * name, width, height and default-layout controls plus exactly one OK and one Cancel)
     * between {@link #EDIT_TEXTURE_SET} and the editor when the loaded model has no texture
     * set yet; observed on the reviewed 5203 pair. Answering its OK once is part of that
     * profile's scene flow, so admission is version-gated: the 5303 contract keeps refusing
     * every post-baseline dialog on sight.
     */
    static final String NEW_ATLAS_DIALOG_TITLE = "新纹理集设置";
    /**
     * The host only runs its integer downsampling kernel when the effective atlas scale is at most
     * 0.45 ({@code com.live2d.util.f.g} keeps a Graphics2D fast path above that threshold), so the
     * scene switches the auto-layout dialog to a user-specified percentage below it. The dialog
     * itself is {@code APPLICATION_MODAL}, its OK handler reads the layout scale control's own text
     * field, and every label is matched exactly like the existing fixed menu contract.
     */
    static final String LAYOUT_BUTTON = "自动排版...";
    static final String LAYOUT_FIXED_SCALE = "用户指定";
    static final String LAYOUT_SCALE_CONTROL_CLASS = "com.live2d.ui.control.a.a.j";
    /** 40% is below the host's 0.45 kernel threshold, so the real OK rebuild reaches the kernel. */
    static final String LAYOUT_SCALE_KERNEL_PERCENT = "40";
    /** 60% is above that threshold, so a control run must take the Graphics2D fast path. */
    static final String LAYOUT_SCALE_FAST_PATH_PERCENT = "60";
    static final String LAYOUT_DIALOG_CLASS =
        "com.live2d.cubism.doc.modeling.ui.atlasEditor.a.f";
    /** Budget for the modal layout dialog to appear, apply and close; not a performance threshold. */
    static final long LAYOUT_DIALOG_TIMEOUT_SECONDS = 60L;
    /**
     * Look-and-Feel resource the host itself reads for the answers of its own option pane
     * ({@code com.live2d.util.UUOption} builds its three buttons from
     * {@code OptionPane.yesButtonText} / {@code OptionPane.noButtonText} /
     * {@code OptionPane.cancelButtonText}). Closing a document with unsaved in-memory changes asks
     * that question before the host shuts down, and the scene may only ever answer "do not save".
     */
    static final String OPTION_PANE_NO_TEXT_KEY = "OptionPane.noButtonText";
    /** Evidence slot name for the one answer this scene is allowed to give. */
    static final String EXIT_PROMPT_ANSWER = "noButtonText";

    static final String NAMED_PREFIX = "turboism.validation.atlasImageShadow.";
    /**
     * The scene's only per-job parameter: which side of the host's 0.45 kernel threshold the run
     * lands on. The queue hands the wrapper only {@code {version}} and {@code {runLabel}}, so the
     * wrapper selects this from an explicit run-label suffix and passes it as a JVM property.
     */
    static final String LAYOUT_SCALE_PROPERTY = NAMED_PREFIX + "layoutScalePercent";
    /**
     * Whether the scene re-lays out the page before confirming. {@code auto-scale} drives the host's
     * own auto-layout dialog (the kernel-path scenario); {@code preserve} opens the texture-set
     * editor and confirms without touching any layout, which is the path a user takes when merely
     * loading or keeping an existing texture set.
     */
    static final String LAYOUT_MODE_PROPERTY = NAMED_PREFIX + "layoutMode";
    static final String LAYOUT_MODE_AUTO_SCALE = "auto-scale";
    static final String LAYOUT_MODE_PRESERVE = "preserve";
    static final String OUTPUT_RELATIVE = "state/atlas-image-shadow";
    static final String T039_PREFIX = "turboism.validation.t039.";

    static final long DEFAULT_TIMEOUT_SECONDS = 900L;
    /** Time a fixed-scene callback may sit queued before the EDT counts as saturated. */
    static final long EDT_START_TIMEOUT_SECONDS = 5L;
    /** Time a started read-only query may run before the query itself counts as failed. */
    static final long EDT_QUERY_TIMEOUT_SECONDS = 5L;
    /** Startup budget for the fixture window plus the host-readiness gate; not a performance threshold. */
    static final long STARTUP_TIMEOUT_SECONDS = 240L;
    /** Consecutive prompt EDT round trips required before any UI action is driven. */
    static final int EDT_READY_ROUNDS = 3;
    /** Maximum wall time of one round trip that may count towards EDT readiness. */
    static final long EDT_READY_ROUND_MILLIS = 1000L;
    /** Time a released modal menu action may take to return once its editor has closed. */
    static final long MODAL_ACTION_LATCH_TIMEOUT_SECONDS = 5L;
    /** Showing windows outside main/editor that may be named in the bounded evidence snapshot. */
    static final int WINDOW_SAMPLE_MAX = 3;
    /** Truncation bound for one structural window class name in the evidence snapshot. */
    static final int WINDOW_CLASS_MAX_CHARS = 48;
    /**
     * Budget for the OK close poll. The host shows its own modal progress window while it applies
     * the texture set, so the poll waits for a quiet window set instead of failing on first sight;
     * the budget only bounds how long an unfinished or blocked apply may hold the run.
     */
    static final long CLOSE_POLL_TIMEOUT_SECONDS = 240L;
    /** Delay between close-poll samples; only the poll budget bounds how often we look. */
    static final long CLOSE_POLL_INTERVAL_MILLIS = 250L;

    /**
     * Wall-time budgets a production-scale fixture can outgrow. They stay fail-closed overrides: an
     * out-of-range value is rejected, and the defaults are exactly the values the Circle100 scene
     * has always used, so an unset property cannot silently relax a budget.
     */
    static final long MAX_BUDGET_SECONDS = 3600L;
    static final String STARTUP_SECONDS_PROPERTY = NAMED_PREFIX + "startupSeconds";
    static final String CLOSE_POLL_SECONDS_PROPERTY = NAMED_PREFIX + "closePollSeconds";
    static final String LAYOUT_DIALOG_SECONDS_PROPERTY = NAMED_PREFIX + "layoutDialogSeconds";
    /** Dwell between EDITOR CONFIRMED and OK — gives lazily-triggered atlas
     *  work (e.g. deferred per-page texture generation) time to run before
     *  the editor is closed. Zero disables; bounded by MAX_BUDGET_SECONDS. */
    static final String EDITOR_SETTLE_SECONDS_PROPERTY = NAMED_PREFIX + "editorSettleSeconds";
    /**
     * Read-only menu-tree enumeration written next to the stage evidence. The host's
     * localization strings are not readable offline, so discovering a real menu label
     * (e.g. the export path) requires one instrumented run. Off by default.
     */
    static final String MENU_DUMP_PROPERTY = NAMED_PREFIX + "menuDump";
    static final int MENU_DUMP_MAX_DEPTH = 8;
    static final int MENU_DUMP_MAX_LINES = 4096;
    static final String MENU_DUMP_FILE = "menu-tree.txt";
    /**
     * Export-path label discovery: clicks the host's own moc3 export menu item, captures
     * the dialog it raises, writes its component tree, then closes the dialog through a
     * plain window-close event — no export is ever confirmed. Off by default; pairs
     * naturally with {@link #MENU_DUMP_PROPERTY} but is independent.
     */
    static final String EXPORT_PROBE_PROPERTY = NAMED_PREFIX + "exportProbe";
    /**
     * Run the export probe after the editor round-trip instead of before it — mirrors the
     * real user order (open texture set, OK, then export) so the export pipeline observes
     * atlas instances whose caches were produced by the editor pass. Off by default; only
     * meaningful together with {@link #EXPORT_PROBE_PROPERTY}.
     */
    static final String EXPORT_AFTER_EDITOR_PROPERTY = NAMED_PREFIX + "exportProbeAfterEditor";
    /**
     * Reopen the texture-set editor once after the first OK round-trip — exercises the
     * "user reopens the atlas" path so cache-reuse verdicts can be observed on second-open
     * atlas instances. Off by default.
     */
    static final String EDITOR_REOPEN_PROPERTY = NAMED_PREFIX + "editorReopen";
    static final String FILE_MENU = "文件";
    static final String RUNTIME_EXPORT_MENU = "导出运行时文件";
    static final String EXPORT_MOC3_ITEM = "导出为moc3文件...";
    static final long EXPORT_DIALOG_TIMEOUT_SECONDS = 60L;
    /**
     * The export item starts the host's own export pipeline immediately — a modal
     * progress window saturates the EDT for the whole processing phase, so the probe
     * tolerates EDT timeouts while observing and only bounds total observe time.
     */
    static final long EXPORT_OBSERVE_TIMEOUT_SECONDS = 1800L;
    static final int EXPORT_DUMP_MAX_DEPTH = 24;
    static final int EXPORT_DUMP_MAX_LINES = 4096;
    static final String EXPORT_DUMP_FILE = "export-dialog.txt";
    static final String EXPORT_PROGRESS_CLASS = "jp.noids.framework.e.a.f";
    /**
     * Consecutive empty window observations required after the export progress window closes
     * before the probe treats the pipeline as done. The export's trailing dialogs (warnings,
     * the settings form) surface a beat after processing ends; a single empty poll between the
     * progress window and those dialogs would leave them unclaimed to block later stages.
     */
    static final int EXPORT_QUIET_POLLS = 8;
    /**
     * Bound on distinct blocking-dialog component dumps taken while the editor close poll is
     * wedged. Diagnostics only; each modal dialog is dumped at most once per run.
     */
    static final int CLOSE_DIALOG_DUMP_MAX = 8;
    /**
     * Observation window after the native exit is posted. The click is fired without waiting
     * because the host may terminate the JVM inside its own exit action, and the observation only
     * bounds how long a host dialog or a stuck exit sequence may be named in the evidence.
     */
    static final long EXIT_PROBE_SECONDS = 20L;
    /** Truncation bound for one structural thread name in the exit evidence snapshot. */
    static final int THREAD_NAME_MAX_CHARS = 48;

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern SAFE_HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SAFE_CLASS = Pattern.compile("[A-Za-z0-9_$.]{1,160}");

    private ShadowSceneContract() {}

    /**
     * Two-value host allowlist. The version selects the official JAR hash, the scene
     * identity and whether the T039 shadow capture is part of the run; anything else
     * must fail closed instead of driving an unreviewed host.
     */
    static String requireVersion(final String value) {
        if (!VERSION_5303.equals(value) && !VERSION_5203.equals(value)) {
            throw new IllegalArgumentException("scene version must be 5303 or 5203");
        }
        return value;
    }

    static String sceneFor(final String version) {
        return VERSION_5203.equals(version) ? SCENE_5203 : SCENE_5303;
    }

    static String jarSha256For(final String version) {
        return VERSION_5203.equals(version) ? OFFICIAL_JAR_SHA256_5203 : OFFICIAL_JAR_SHA256;
    }

    /**
     * Answering the create-atlas form is only part of the 5203 scene flow; on 5303 the
     * reviewed contract never produces that dialog, so it must keep failing closed.
     */
    static boolean allowsNewAtlasDialog(final String version) {
        return VERSION_5203.equals(version);
    }

    /**
     * The 5203 profile has no T039 shadow agent; any {@code turboism.validation.t039.*}
     * property arriving on that host would mean the launch was assembled for the wrong
     * profile, so the whole keyspace must be absent rather than merely unpinned.
     */
    static void requireT039Absent() {
        for (final String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith(T039_PREFIX)) {
                throw new IllegalArgumentException(
                    "T039 property " + key + " must be absent on this host profile");
            }
        }
    }

    static String requireTaskId(final String value, final String label) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is not a bounded task id");
        }
        return value;
    }

    static String requireHash(final String value, final String label) {
        if (value == null || !SAFE_HASH.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is not a lowercase SHA-256");
        }
        return value;
    }

    static String requireClassName(final String value, final String label) {
        if (value == null || !SAFE_CLASS.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is not a bounded class name");
        }
        return value;
    }

    static String requiredProperty(final String key) {
        final String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }

    /**
     * Two-value allowlist. The scale decides which side of the kernel threshold a real run lands on,
     * so anything else must fail closed instead of silently collecting the wrong kind of evidence.
     */
    static String requireLayoutScalePercent(final String value) {
        if (!LAYOUT_SCALE_KERNEL_PERCENT.equals(value)
                && !LAYOUT_SCALE_FAST_PATH_PERCENT.equals(value)) {
            throw new IllegalArgumentException(
                "layoutScalePercent must be 40 (kernel path) or 60 (fast-path control)");
        }
        return value;
    }

    /** Two-value allowlist; an unknown mode must fail closed instead of driving the wrong path. */
    static String requireLayoutMode(final String value) {
        if (!LAYOUT_MODE_AUTO_SCALE.equals(value) && !LAYOUT_MODE_PRESERVE.equals(value)) {
            throw new IllegalArgumentException(
                "layoutMode must be auto-scale (host auto-layout) or preserve (no layout change)");
        }
        return value;
    }

    static String layoutMode() {
        return requireLayoutMode(System.getProperty(
            LAYOUT_MODE_PROPERTY, LAYOUT_MODE_AUTO_SCALE));
    }

    /** Reads the wrapper-selected layout scale; the fixed default is the kernel path. */
    static String layoutScalePercent() {
        return requireLayoutScalePercent(System.getProperty(
            LAYOUT_SCALE_PROPERTY, LAYOUT_SCALE_KERNEL_PERCENT));
    }

    static void requireNamedFixture(final String fixture, final String fixtureName,
                                    final String taskId, final String allowedName) {
        final String expectedName = taskId + "-" + allowedName;
        if (!expectedName.equals(fixtureName)) {
            throw new IllegalArgumentException(
                "fixtureName is not the Runner-expanded allowlisted basename");
        }
        final Path path = Path.of(fixture).toAbsolutePath().normalize();
        final Path fileName = path.getFileName();
        if (fileName == null || !fixtureName.equals(fileName.toString())) {
            throw new IllegalArgumentException("fixture path basename differs from fixtureName");
        }
    }

    /**
     * Returns the allowlisted fixture name only when the Runner-expanded name and the hash belong
     * to the same entry. The runtime name is always {@code taskId + "-" + fixtureName}, so the pair
     * is matched on that exact expansion rather than on the bare file name. Each host profile has
     * its own reviewed pair set: the 5203 fixture is never admissible on a 5303 run and vice versa.
     */
    static String requireAllowlistedFixture(final String version, final String taskId,
                                            final String fixtureName,
                                            final String fixtureSha256) {
        requireTaskId(taskId, "taskId");
        requireHash(fixtureSha256, "fixtureSha256");
        if (VERSION_5203.equals(version)) {
            if ((taskId + "-" + FIXTURE_OPACITY52_NAME).equals(fixtureName)
                    && FIXTURE_OPACITY52_SHA256.equals(fixtureSha256)) {
                return FIXTURE_OPACITY52_NAME;
            }
            throw new IllegalArgumentException(
                "fixtureName/fixtureSha256 is not an allowlisted 5203 measurement fixture pair");
        }
        if ((taskId + "-" + FIXTURE_CIRCLE100_NAME).equals(fixtureName)
                && FIXTURE_CIRCLE100_SHA256.equals(fixtureSha256)) {
            return FIXTURE_CIRCLE100_NAME;
        }
        if ((taskId + "-" + FIXTURE_HEAVY_NAME).equals(fixtureName)
                && FIXTURE_HEAVY_SHA256.equals(fixtureSha256)) {
            return FIXTURE_HEAVY_NAME;
        }
        throw new IllegalArgumentException(
            "fixtureName/fixtureSha256 is not an allowlisted measurement fixture pair");
    }

    /** Strict boolean flag; anything but true/false fails closed. */
    static boolean booleanProperty(final String key, final boolean fallback) {
        final String value = System.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }

    /** Bounded seconds override allowing zero (dwell/disabled); out-of-range
     *  values are rejected like {@link #secondsProperty}. */
    static long settleSecondsProperty(final String key, final long fallback) {
        final String value = System.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        final long seconds;
        try {
            seconds = Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " must be an integer number of seconds");
        }
        if (seconds < 0L || seconds > MAX_BUDGET_SECONDS) {
            throw new IllegalArgumentException(
                key + " must be between 0 and " + MAX_BUDGET_SECONDS + " seconds");
        }
        return seconds;
    }

    /** Bounded seconds override; the fallback is used only when the property is unset. */
    static long secondsProperty(final String key, final long fallback) {
        final String value = System.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        final long seconds;
        try {
            seconds = Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " must be an integer number of seconds");
        }
        if (seconds < 1L || seconds > MAX_BUDGET_SECONDS) {
            throw new IllegalArgumentException(
                key + " must be between 1 and " + MAX_BUDGET_SECONDS + " seconds");
        }
        return seconds;
    }

    static void requireT039FixedValues(final String profile, final String runId,
                                       final String expectedRunId, final String sourceBinding,
                                       final String trustedSourcePaths,
                                       final String jarSha256, final String classSha256,
                                       final String shapeSha256, final String loaderClass,
                                       final String helperSha256, final String t038HelperSha256,
                                       final String shadowMode, final String shadowOptIn) {
        if (!"5303".equals(profile)) throw new IllegalArgumentException("T039 profile must be 5303");
        if (!expectedRunId.equals(runId)) throw new IllegalArgumentException("T039 runId differs from taskId");
        requireT039SourceBinding(sourceBinding, trustedSourcePaths);
        if (!OFFICIAL_JAR_SHA256.equals(requireHash(jarSha256, "T039 jarSha256"))) {
            throw new IllegalArgumentException("T039 official JAR hash mismatch");
        }
        if (!T039_CLASS_SHA256.equals(requireHash(classSha256, "T039 classSha256"))) {
            throw new IllegalArgumentException("T039 class hash mismatch");
        }
        if (!T039_SHAPE_SHA256.equals(requireHash(shapeSha256, "T039 shapeSha256"))) {
            throw new IllegalArgumentException("T039 shape hash mismatch");
        }
        requireClassName(loaderClass, "T039 loaderClass");
        requireHash(helperSha256, "T039 helperSha256");
        requireHash(t038HelperSha256, "T039 t038HelperSha256");
        if (!T039_SHADOW_MODE.equals(shadowMode)) {
            throw new IllegalArgumentException("T039 shadowMode must be shadow-ready");
        }
        if (!T039_SHADOW_OPT_IN.equals(shadowOptIn)) {
            throw new IllegalArgumentException("T039 shadow opt-in is missing");
        }
    }

    /**
     * Launch-shape gate for the official 5303 target-pd binding.
     *
     * <p>The runtime wrapper intentionally emits no {@code t039.codeSource}: the
     * observed source is read inside the real JVM from the target
     * {@code ProtectionDomain} by the T039 agent. This gate therefore validates the
     * candidate list both sides agree on, and stays a pure string check so the
     * offline build exercises exactly the launch-time contract.</p>
     */
    static void requireT039SourceBinding(final String sourceBinding,
                                         final String trustedSourcePaths) {
        if (!T039_SOURCE_BINDING.equals(sourceBinding)) {
            throw new IllegalArgumentException("T039 source binding must be target-pd");
        }
        if (trustedSourcePaths == null || trustedSourcePaths.isBlank()
                || trustedSourcePaths.length() > T039_TRUSTED_PATHS_MAX
                || trustedSourcePaths.indexOf('\n') >= 0
                || trustedSourcePaths.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("T039 trusted source candidates are missing or unsafe");
        }
        final String candidate = withoutDrivePrefix(trustedSourcePaths);
        if (candidate.isEmpty() || candidate.indexOf(';') >= 0 || candidate.indexOf(':') >= 0) {
            throw new IllegalArgumentException("T039 trusted source candidate must be exactly one path");
        }
        if (candidate.charAt(0) != '/' && candidate.charAt(0) != '\\') {
            throw new IllegalArgumentException("T039 trusted source candidate must be absolute");
        }
        if (!candidate.endsWith(T039_JAR_BASENAME)) {
            throw new IllegalArgumentException(
                "T039 trusted source candidate must name Live2D_Cubism.jar");
        }
    }

    private static String withoutDrivePrefix(final String value) {
        if (value.length() > 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':'
                && (value.charAt(2) == '\\' || value.charAt(2) == '/')) {
            return value.substring(2);
        }
        return value;
    }
}
