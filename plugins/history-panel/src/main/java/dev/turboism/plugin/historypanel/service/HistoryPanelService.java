package dev.turboism.plugin.historypanel.service;

import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiHostCapabilityService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only Photoshop-style history pane projected into an embedded dock panel.
 *
 * <p>The pane polls the active document's verified Undo history and re-contributes
 * the panel only when the projected state changes. Move-to remains a production
 * fail-closed boundary; the pane reports the live availability instead of mutating
 * the document through an unverified path.</p>
 */
public final class HistoryPanelService {

    public static final String PANEL_ID = "history.panel";
    public static final String PANEL_PLACEMENT = "side";
    public static final int PANEL_PRIORITY = 50;
    public static final Duration POLL_DELAY = Duration.ofSeconds(1);

    private final CubismHistory history;
    private final UiHostCapabilityService uiHost;
    private final PluginLogger logger;
    private final PluginLocalization localization;
    private final Runnable onRefresh;

    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread poller;
    private volatile Registration panel;
    private volatile String fingerprint = "";

    public HistoryPanelService(
        final CubismHistory history,
        final UiHostCapabilityService uiHost,
        final PluginLogger logger,
        final PluginLocalization localization
    ) {
        this(history, uiHost, logger, localization, () -> { });
    }

    /**
     * @param onRefresh invoked after every successful refresh so the owner can
     *                  keep row actions (undo/redo entry moves) in sync with the
     *                  latest entry set.
     */
    public HistoryPanelService(
        final CubismHistory history,
        final UiHostCapabilityService uiHost,
        final PluginLogger logger,
        final PluginLocalization localization,
        final Runnable onRefresh
    ) {
        this.history = Objects.requireNonNull(history, "history");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.localization = Objects.requireNonNull(localization, "localization");
        this.onRefresh = Objects.requireNonNull(onRefresh, "onRefresh");
    }

    /** Starts the pane and its polling; the returned handle stops both. */
    public Registration enable() {
        refresh();
        final Thread worker = new Thread(this::pollLoop, "turboism-history-panel");
        worker.setDaemon(true);
        poller = worker;
        worker.start();
        return this::close;
    }

    private void pollLoop() {
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(POLL_DELAY.toMillis());
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                return;
            }
            refresh();
        }
    }

    private void close() {
        closed.set(true);
        final Thread running = poller;
        if (running != null) {
            running.interrupt();
        }
        final Registration current = panel;
        panel = null;
        if (current != null) {
            current.close();
        }
    }

    private void refresh() {
        try {
            final HistorySnapshot snapshot = history.snapshot();
            final String nextFingerprint = fingerprint(snapshot);
            if (nextFingerprint.equals(fingerprint)) {
                return;
            }
            replacePanel(render(snapshot));
            // Only advance the fingerprint after the panel was re-contributed
            // successfully, so a transient EDT failure retries next poll.
            fingerprint = nextFingerprint;
            onRefresh.run();
            logger.info("History panel refreshed: " + nextFingerprint
                + " entries=" + snapshot.entries().size()
                + " position=" + snapshot.position());
        } catch (RuntimeException failure) {
            logger.warn("History panel refresh failed safely: " + failure.getMessage());
        }
    }

    private void replacePanel(final PanelView content) {
        // Same panel identity refreshes the existing contribution; the runtime
        // replaces it and updates the attached native content in place, so the
        // floating window stays floating and never drops back to the dock.
        panel = uiHost.contributeEmbeddedPanel(
            new EmbeddedPanelContribution(
                PANEL_ID,
                localization.text("history.panel.title"),
                PANEL_PLACEMENT,
                PANEL_PRIORITY,
                content,
                true
            )
        );
    }

    private static String fingerprint(final HistorySnapshot snapshot) {
        if (snapshot.availability() != HistorySnapshot.Availability.AVAILABLE) {
            return "unavailable";
        }
        return snapshot.generation() + ":" + snapshot.revision();
    }

    PanelView render(final HistorySnapshot snapshot) {
        if (snapshot.availability() != HistorySnapshot.Availability.AVAILABLE) {
            return PanelView.scroll(PanelView.column(
                PanelView.text(localization.text("history.panel.unavailable")),
                PanelView.text(localization.text("history.panel.unavailable.detail"))
            ));
        }
        final List<PanelView> children = new ArrayList<>();
        // Top bar carries only the entry count (centered); no undo/redo
        // buttons, no cursor/availability statistics.
        children.add(PanelView.textCentered(countLine(snapshot)));
        children.add(PanelView.separator());
        boolean first = true;
        for (final HistoryEntry entry : snapshot.entries()) {
            // Two-pixel row separator between adjacent entries only; the last
            // entry gets no trailing separator.
            if (!first) {
                children.add(PanelView.separator());
            }
            first = false;
            // Forked entries (neither undoable nor redoable) never appear in
            // the snapshot's reachable set (SDK contract: contiguous from zero),
            // so they are removed from the list by construction.
            children.add(renderEntry(snapshot.position(), entry));
        }
        // The whole undo/redo list lives inside a scroll view; rows are
        // compact with no vertical padding between them.
        return PanelView.scroll(PanelView.column(children.toArray(PanelView[]::new)));
    }

    private String countLine(final HistorySnapshot snapshot) {
        return localization.format("history.panel.count", snapshot.entries().size());
    }

    private PanelView renderEntry(final int cursor, final HistoryEntry entry) {
        final String label = (entry.index() + 1) + " " + entry.label();
        final String detail = detail(entry);
        // Checkbox with the full label + detail text: checked when the action
        // is applied (undoable), unchecked when it was undone and can be
        // redone. One wrapping toggle per entry keeps long text inside the
        // viewport width (no horizontal scrolling) and one functional
        // checkbox per row.
        final boolean applied = entry.index() < cursor;
        final boolean grayed = !applied;
        return PanelView.toggle(
            "history.entry.toggle." + entry.index(),
            label + "  —  " + detail,
            applied,
            grayed,
            "history.entry.move." + entry.index()
        );
    }

    private String detail(final HistoryEntry entry) {
        final Optional<HistoryAction> action = entry.action();
        if (action.isEmpty()) {
            return localization.text("history.entry.no-detail");
        }
        final HistoryAction value = action.orElseThrow();
        return value.targetId() + " " + value.property() + ": "
            + value.before().orElse("?") + " → " + value.after().orElse("?")
            + " (" + value.kind() + ", " + value.detailLevel() + ")";
    }
}
