package dev.turboism.validation.theme;

import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceBase;
import dev.turboism.sdk.appearance.AppearancePalette;
import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.appearance.AppearanceStatus;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.UIManager;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task-local exerciser for the theme appearance apply/restore matrix on an exact
 * Cubism host. It never modifies production behavior: it only observes the SDK
 * appearance service (the same runtime seam the ui-theme plugin uses) and
 * records machine-readable UIManager snapshots to the plugin log.
 */
public final class ThemeHostValidationPlugin implements TurboismPlugin {

    private static final String FLAG = "exerciser.flag";
    private static final long FLAG_TIMEOUT_MILLIS = 240_000L;
    private static final long SETTLE_STEP_MILLIS = 2_000L;

    private static final String[] SAMPLE_KEYS = {
        "CubismCommon.blue",
        "CubismCommon.background",
        "CubismCommon.surface",
        "CubismCommon.inputBackground",
        "CubismCommon.foreground",
        "CubismCommon.mutedForeground",
        "CubismCommon.selectionBackground",
        "CubismCommon.selectionForeground",
        "CubismCommon.border",
        "CubismCommon.gl.viewArea.background"
    };

    private PluginLogger logger;
    private AppearanceService appearance;
    private Path stateDir;

    @Override
    public void init(final PluginContext context) {
        this.logger = context.logger();
        this.appearance = context.appearance();
        this.stateDir = context.paths().stateDir();
        final Map<String, String> nativeSnapshot = stableSnapshot(30_000L);
        logger.info("THEME_EXERCISER_NATIVE " + render(nativeSnapshot));
        final Thread exerciser = new Thread(this::runWhenFlagged, "theme-host-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
        logger.info("THEME_EXERCISER_READY stateDir=" + stateDir);
    }

    @Override
    public void enable() {
        logger.info("THEME_EXERCISER_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("THEME_EXERCISER_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("THEME_EXERCISER_SHUTDOWN");
    }

    private void runWhenFlagged() {
        final Path flag = stateDir.resolve(FLAG);
        final long deadline = System.currentTimeMillis() + FLAG_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(flag)) {
                runMatrix();
                return;
            }
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warn("THEME_EXERCISER_FLAG_TIMEOUT flag=" + flag);
        Runtime.getRuntime().halt(2);
    }

    private void runMatrix() {
        final Map<String, String> before = capture();
        logger.info("THEME_MATRIX_BEFORE " + render(before));

        final AppearanceStatus initial = appearance.current().toCompletableFuture().join();
        logger.info("THEME_MATRIX_INITIAL status=" + initial);

        final AppearanceApplyResult applied = appearance.apply(new AppearanceRequest(
            "turboism.validation.nord",
            AppearanceBase.DARK,
            nordPalette(),
            initial.revision()
        )).toCompletableFuture().join();
        logger.info("THEME_MATRIX_APPLY outcome=" + applied.outcome()
            + " revision=" + applied.status().revision()
            + " source=" + applied.status().source());

        final Map<String, String> during = capture();
        logger.info("THEME_MATRIX_AFTER " + render(during));

        final AppearanceRestoreResult restored = appearance.restoreOwnedAppearance()
            .toCompletableFuture().join();
        logger.info("THEME_MATRIX_RESTORE outcome=" + restored.outcome()
            + " revision=" + restored.status().revision()
            + " source=" + restored.status().source());

        final Map<String, String> after = capture();
        logger.info("THEME_MATRIX_RESTORED " + render(after));

        final boolean applyOk = applied.outcome() == AppearanceApplyResult.Outcome.APPLIED;
        final boolean restoreOk = restored.outcome() == AppearanceRestoreResult.Outcome.RESTORED;
        final boolean themed = nordPresent(during);
        final boolean reverted = after.equals(before);
        final boolean baselineStable = before.equals(stableSnapshot(8_000L));
        final boolean pass = applyOk && restoreOk && themed && reverted && baselineStable;

        logger.info("THEME_MATRIX_RESULT status=" + (pass ? "PASS" : "FAIL")
            + " apply=" + applyOk
            + " restore=" + restoreOk
            + " themed=" + themed
            + " reverted=" + reverted
            + " baselineStable=" + baselineStable
            + " applyOutcome=" + applied.outcome()
            + " restoreOutcome=" + restored.outcome());
        try {
            Thread.sleep(3_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(0);
    }

    private boolean nordPresent(final Map<String, String> values) {
        final Map<String, String> expected = nordDefaults();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            final String actual = values.get(entry.getKey());
            if (actual == null || !actual.equalsIgnoreCase(entry.getValue())) {
                logger.warn("THEME_MATRIX_MISMATCH key=" + entry.getKey()
                    + " expected=" + entry.getValue() + " actual=" + actual);
                return false;
            }
        }
        return true;
    }

    private Map<String, String> stableSnapshot(final long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        Map<String, String> previous = null;
        int stableRuns = 0;
        while (System.currentTimeMillis() < deadline) {
            final Map<String, String> current = capture();
            if (current.equals(previous)) {
                stableRuns++;
                if (stableRuns >= 3) {
                    return current;
                }
            } else {
                stableRuns = 1;
            }
            previous = current;
            try {
                Thread.sleep(SETTLE_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return current;
            }
        }
        return previous == null ? Map.of() : previous;
    }

    private Map<String, String> capture() {
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String key : SAMPLE_KEYS) {
            final Object value = UIManager.getDefaults().get(key);
            if (value instanceof Color color) {
                values.put(key, hex(color));
            } else if (value != null) {
                values.put(key, value.toString());
            } else {
                values.put(key, "null");
            }
        }
        return Map.copyOf(values);
    }

    private static String hex(final Color color) {
        return String.format("#%02X%02X%02X",
            color.getRed(), color.getGreen(), color.getBlue());
    }

    private static AppearancePalette nordPalette() {
        return new AppearancePalette(
            "#88C0D0", "#2E3440", "#3B4252", "#434C5E", "#D8DEE9",
            "#616E88", "#5E81AC", "#ECEFF4", "#4C566A", "#242933"
        );
    }

    private static Map<String, String> nordDefaults() {
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("CubismCommon.blue", "#88C0D0");
        values.put("CubismCommon.background", "#2E3440");
        values.put("CubismCommon.surface", "#3B4252");
        values.put("CubismCommon.inputBackground", "#434C5E");
        values.put("CubismCommon.foreground", "#D8DEE9");
        values.put("CubismCommon.mutedForeground", "#616E88");
        values.put("CubismCommon.selectionBackground", "#5E81AC");
        values.put("CubismCommon.selectionForeground", "#ECEFF4");
        values.put("CubismCommon.border", "#4C566A");
        values.put("CubismCommon.gl.viewArea.background", "#242933");
        return Map.copyOf(values);
    }

    private static String render(final Map<String, String> values) {
        final StringBuilder output = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (output.length() > 0) {
                output.append(',');
            }
            output.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return output.toString();
    }
}
