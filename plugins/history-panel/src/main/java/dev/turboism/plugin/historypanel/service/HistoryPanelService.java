package dev.turboism.plugin.historypanel.service;

import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryGroup;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiInlineLabel;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconRef;
import dev.turboism.sdk.ui.UiHostCapabilityService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Base64;

/**
 * Photoshop-style history pane projected into an embedded dock panel.
 *
 * <p>The pane polls the active document's verified Undo history and re-contributes
 * the panel only when the projected state changes. Entry actions are registered by
 * the owner against the same snapshot and use the typed Undo/Redo service; the pane
 * reports unavailable state instead of using an unverified mutation path.</p>
 */
public final class HistoryPanelService {

    public static final String PANEL_ID = "history.panel";
    public static final String PANEL_PLACEMENT = "side";
    public static final int PANEL_PRIORITY = 50;
    public static final Duration POLL_DELAY = Duration.ofSeconds(1);

    private static final String POLL_TASK_ID = "history-panel-poll";

    private final CubismHistory history;
    private final UiHostCapabilityService uiHost;
    private final PluginTaskScheduler tasks;
    private final PluginLogger logger;
    private static final String PROPERTY_VERTEX_POSITIONS = "vertexPositions";

    private final PluginLocalization localization;
    private final HistoryRelationLabelFormatter relationLabelFormatter;
    private final Runnable onRefresh;

    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile TaskHandle pollTask;
    private volatile Registration panel;
    private volatile String fingerprint = "";

    public HistoryPanelService(
        final CubismHistory history,
        final UiHostCapabilityService uiHost,
        final PluginTaskScheduler tasks,
        final PluginLogger logger,
        final PluginLocalization localization
    ) {
        this(history, uiHost, tasks, logger, localization, () -> { });
    }

    /**
     * @param onRefresh invoked after every successful refresh so the owner can
     *                  keep row actions (undo/redo entry moves) in sync with the
     *                  latest entry set.
     */
    public HistoryPanelService(
        final CubismHistory history,
        final UiHostCapabilityService uiHost,
        final PluginTaskScheduler tasks,
        final PluginLogger logger,
        final PluginLocalization localization,
        final Runnable onRefresh
    ) {
        this.history = Objects.requireNonNull(history, "history");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.tasks = tasks;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.localization = Objects.requireNonNull(localization, "localization");
        this.relationLabelFormatter = new HistoryRelationLabelFormatter(this.localization);
        this.onRefresh = Objects.requireNonNull(onRefresh, "onRefresh");
    }

    /** Starts the pane and its polling; the returned handle stops both. */
    public Registration enable() {
        refresh();
        final PluginTaskScheduler scheduler = tasks;
        if (scheduler != null) {
            try {
                final TaskSubmission submission = scheduler.scheduleWithFixedDelay(
                    new FixedDelayTaskRequest(
                        new TaskId(POLL_TASK_ID),
                        PluginTaskKind.LOW_FREQUENCY_REFRESH,
                        PluginTaskPriority.LOW,
                        POLL_DELAY,
                        POLL_DELAY,
                        ignored -> {
                            if (!closed.get()) {
                                refresh();
                            }
                        }
                    )
                );
                if (submission.accepted()) {
                    pollTask = submission.handle();
                } else {
                    logger.warn("History panel poller not accepted: "
                        + submission.rejectionReason().map(Object::toString).orElse("unknown"));
                }
            } catch (RuntimeException rejected) {
                logger.warn("History panel poller rejected: " + rejected.getClass().getSimpleName());
            }
        }
        return this::close;
    }

    private void close() {
        closed.set(true);
        final TaskHandle active = pollTask;
        pollTask = null;
        if (active != null) {
            try {
                active.close();
            } catch (RuntimeException ignored) {
                // The runtime disposable ownership may already have cleaned it up;
                // cancelling is best effort.
            }
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
        return snapshot.generation() + ":" + snapshot.revision() + ":" + snapshot.entries().hashCode();
    }

    PanelView render(final HistorySnapshot snapshot) {
        if (snapshot.availability() != HistorySnapshot.Availability.AVAILABLE) {
            return PanelView.scroll(PanelView.column(
                PanelView.text(localization.text("history.panel.unavailable")),
                PanelView.text(localization.text("history.panel.unavailable.detail"))
            ));
        }
        final List<PanelView> children = new ArrayList<>();
        // Rows start immediately: no top bar, no undo/redo buttons, no
        // cursor/availability statistics. An empty document still needs one
        // child: the panel column requires a non-empty placeholder row.
        if (snapshot.entries().isEmpty()) {
            children.add(PanelView.text(localization.text("history.panel.empty")));
        }
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

    private PanelView renderEntry(final int cursor, final HistoryEntry entry) {
        final HistoryEntryDetail semantic = entry.detail();
        final Optional<String> stableId = entry.entryId().map(id -> id.value());
        final String identity = stableId.map(HistoryPanelService::encodedEntryId)
            .orElse("unavailable." + entry.index());
        final String unavailable = stableId.isEmpty()
            ? " · " + localization.text("history.entry.navigation.unavailable")
            : "";
        final boolean applied = entry.index() < cursor;
        final boolean grayed = !applied || stableId.isEmpty();
        final String actionId = stableId.map(HistoryPanelService::moveActionId)
            .orElse("history.entry.unavailable." + entry.index());
        final Optional<UiInlineLabel> richLabel = richInlineLabel(semantic, entry.index(), unavailable)
            .or(() -> promotedChildLabel(semantic, entry.index(), unavailable));
        if (richLabel.isPresent()) {
            return PanelView.toggle(
                "history.entry.toggle." + identity,
                richLabel.orElseThrow(),
                applied,
                grayed,
                actionId
            );
        }
        final String label = (entry.index() + 1) + " "
            + detailHeadline(semantic, entry.label()) + unavailable;
        return PanelView.toggle(
            "history.entry.toggle." + identity,
            label,
            applied,
            grayed,
            actionId
        );
    }

    private Optional<UiInlineLabel> richInlineLabel(
        final HistoryEntryDetail detail,
        final int entryIndex,
        final String unavailable
    ) {
        if (detail.detailLevel() == HistoryAction.DetailLevel.LABEL_ONLY
            || detail.changes().size() != 1) {
            return Optional.empty();
        }
        final HistoryChange change = detail.changes().get(0);
        if (change.relation().isPresent()) {
            return richRelationInlineLabel(detail, change, entryIndex, unavailable);
        }
        if (detail.targets().size() != 1
            || change.targetIndex().filter(index -> index == 0).isEmpty()
            || change.context().kind() == HistoryEditContext.Kind.UNKNOWN
            || change.context().kind() == HistoryEditContext.Kind.KEYFORM
                && change.context().coordinates().isEmpty()) {
            return Optional.empty();
        }
        final HistoryTarget target = detail.targets().get(0);
        final Optional<IconSpec> icon = iconFor(target);
        final Optional<String> displayName = target.displayName().filter(name -> !name.isBlank());
        final boolean vertexMove = change.operation() == HistoryChange.Operation.SET
            && change.property().filter(PROPERTY_VERTEX_POSITIONS::equals).isPresent();
        final Optional<String> action = vertexMove
            ? Optional.of(localization.text("history.entry.action.vertex-move"))
            : richAction(change);
        if (icon.isEmpty() || displayName.isEmpty() || action.isEmpty()) {
            return Optional.empty();
        }

        final IconSpec iconSpec = icon.orElseThrow();
        final List<UiInlineLabel.Run> runs = new ArrayList<>();
        if (change.operation() == HistoryChange.Operation.MOVE || vertexMove) {
            // "N [icon] A 移动": the moved object leads, matching how the action reads.
            appendBoundedTextRuns(runs, (entryIndex + 1) + " ");
            runs.add(UiInlineLabel.iconRun(
                new UiIconRef(iconSpec.icon()),
                localization.text(iconSpec.fallbackKey())
            ));
            appendBoundedTextRuns(runs, " " + displayName.orElseThrow()
                + " " + action.orElseThrow());
        } else {
            appendBoundedTextRuns(runs, (entryIndex + 1) + " " + action.orElseThrow() + " ");
            runs.add(UiInlineLabel.iconRun(
                new UiIconRef(iconSpec.icon()),
                localization.text(iconSpec.fallbackKey())
            ));
            appendBoundedTextRuns(runs, " " + displayName.orElseThrow());
        }
        appendRichMetadata(runs, detail, unavailable);
        return Optional.of(UiInlineLabel.of(runs));
    }

    /**
     * A host group whose own detail carries no direct change can still prove exactly one
     * child edit (for example a Parts-tree drag the host recorded as one grouped move, or a
     * vertex edit bundled with the host's unmapped selection-undo child). When exactly one
     * untruncated child carries changes, that child headlines the row instead of falling back
     * to the raw host label; unmapped siblings stay represented by the partial metadata.
     */
    private Optional<UiInlineLabel> promotedChildLabel(
        final HistoryEntryDetail detail,
        final int entryIndex,
        final String unavailable
    ) {
        if (detail.detailLevel() == HistoryAction.DetailLevel.LABEL_ONLY
            || !detail.changes().isEmpty()) {
            return Optional.empty();
        }
        final HistoryGroup group = detail.group().orElse(null);
        if (group == null || group.truncated()) {
            return Optional.empty();
        }
        HistoryEntryDetail proven = null;
        for (final HistoryEntryDetail child : group.children()) {
            if (child.detailLevel() == HistoryAction.DetailLevel.LABEL_ONLY
                || child.changes().isEmpty()) {
                continue;
            }
            if (proven != null) {
                return Optional.empty();
            }
            proven = child;
        }
        if (proven == null) {
            return Optional.empty();
        }
        return richInlineLabel(proven, entryIndex, unavailable);
    }

    private Optional<UiInlineLabel> richRelationInlineLabel(
        final HistoryEntryDetail detail,
        final HistoryChange change,
        final int entryIndex,
        final String unavailable
    ) {
        final Optional<Integer> targetIndex = change.targetIndex();
        if (targetIndex.isEmpty()
            || targetIndex.orElseThrow() >= detail.targets().size()) {
            return Optional.empty();
        }
        final Optional<HistoryRelationChange> relation = change.relation();
        if (relation.isEmpty()) {
            return Optional.empty();
        }
        final Optional<UiInlineLabel> relationLabel = relationLabelFormatter.format(
            relation.orElseThrow(),
            detail.targets().get(targetIndex.orElseThrow())
        );
        if (relationLabel.isEmpty()) {
            return Optional.empty();
        }

        final List<UiInlineLabel.Run> runs = new ArrayList<>();
        appendBoundedTextRuns(runs, (entryIndex + 1) + " ");
        runs.addAll(relationLabel.orElseThrow().runs());
        appendRichMetadata(runs, detail, unavailable);
        return Optional.of(UiInlineLabel.of(runs));
    }

    private void appendRichMetadata(
        final List<UiInlineLabel.Run> runs,
        final HistoryEntryDetail detail,
        final String unavailable
    ) {
        final StringBuilder metadata = new StringBuilder();
        if (detail.origin().kind() == HistoryOrigin.Kind.TURBOISM) {
            metadata.append(" · ").append(localization.format(
                "history.entry.origin.turboism",
                detail.origin().producerId().orElse("Turboism")
            ));
        }
        if (detail.detailLevel() == HistoryAction.DetailLevel.PARTIAL) {
            metadata.append(" · ").append(localization.text("history.entry.level.partial"));
        }
        metadata.append(unavailable);
        if (!metadata.isEmpty()) {
            appendBoundedTextRuns(runs, metadata.toString());
        }
    }

    static void appendBoundedTextRuns(
        final List<UiInlineLabel.Run> runs,
        final String text
    ) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + UiInlineLabel.MAX_RUN_TEXT_LENGTH, text.length());
            if (end < text.length()
                && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            if (end == start) {
                end = text.offsetByCodePoints(start, 1);
            }
            runs.add(UiInlineLabel.textRun(text.substring(start, end)));
            start = end;
        }
    }

    private Optional<String> richAction(final HistoryChange change) {
        return switch (change.operation()) {
            case SET -> change.property()
                .flatMap(this::property)
                .map(property -> localization.format("history.entry.action.set", property));
            case ADD -> change.property().isEmpty()
                ? Optional.of(localization.text("history.entry.action.add"))
                : Optional.empty();
            case REMOVE -> change.property().isEmpty()
                ? Optional.of(localization.text("history.entry.action.remove"))
                : Optional.empty();
            case MOVE -> Optional.of(localization.text("history.entry.action.move"));
            case UNKNOWN -> Optional.empty();
        };
    }

    private Optional<IconSpec> iconFor(final HistoryTarget target) {
        return switch (target.type()) {
            case "ART_MESH" -> Optional.of(new IconSpec(CubismIcon.ART_MESH, "history.icon.art-mesh"));
            case "WARP_DEFORMER" -> Optional.of(
                new IconSpec(CubismIcon.WARP_DEFORMER, "history.icon.warp-deformer")
            );
            case "ROTATION_DEFORMER" -> Optional.of(
                new IconSpec(CubismIcon.ROTATION_DEFORMER, "history.icon.rotation-deformer")
            );
            default -> Optional.empty();
        };
    }

    private record IconSpec(CubismIcon icon, String fallbackKey) { }

    private String detailHeadline(final HistoryEntryDetail detail, final String hostLabel) {
        final boolean labelOnly = detail.detailLevel() == HistoryAction.DetailLevel.LABEL_ONLY;
        // A group detail only carries a root change when the decoder hoisted a proven
        // single-subject group — exactly one change naming the shared fact. Aggregated groups
        // still headline their summary alone.
        final boolean hoisted = detail.group().isEmpty() || detail.changes().size() == 1;
        final Optional<String> semanticChange = labelOnly || !hoisted ? Optional.empty()
            : detail.changes().stream()
                .map(change -> change(detail, change))
                .flatMap(Optional::stream)
                .findFirst();
        final String level = localization.text(
            "history.entry.level." + detail.detailLevel().name().toLowerCase(Locale.ROOT)
        );
        final int affected = detail.targets().size();
        final StringBuilder result = new StringBuilder(labelOnly ? hostLabel
            : semanticChange.orElse(detail.summary()));
        if (detail.origin().kind() == HistoryOrigin.Kind.TURBOISM) {
            result.append(" · ").append(localization.format(
                "history.entry.origin.turboism",
                detail.origin().producerId().orElse("Turboism")
            ));
        }
        result.append(" · ").append(level)
            .append(" · ").append(localization.format("history.entry.affected", affected));
        if (semanticChange.isPresent() && detail.changes().size() > 1) {
            result.append(" +").append(detail.changes().size() - 1);
        } else if (detail.detailLevel() == HistoryAction.DetailLevel.LABEL_ONLY) {
            result.append(" · ").append(localization.text("history.entry.no-detail"));
        }
        return result.toString();
    }


    private Optional<String> change(
        final HistoryEntryDetail detail,
        final HistoryChange change
    ) {
        final Optional<HistoryTarget> target = change.targetIndex()
            .filter(index -> index < detail.targets().size())
            .map(detail.targets()::get);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        final Optional<String> targetText = target(target.orElseThrow());
        final Optional<String> contextText = context(change.context());
        if (targetText.isEmpty() || contextText.isEmpty()) {
            return Optional.empty();
        }
        if (change.operation() == HistoryChange.Operation.SET) {
            final Optional<String> propertyText = change.property().flatMap(this::property);
            if (propertyText.isEmpty()) {
                return Optional.empty();
            }
            if (change.before().isPresent() && change.after().isPresent()) {
                return Optional.of(localization.format(
                    "history.entry.change.set",
                    targetText.orElseThrow(),
                    contextText.orElseThrow(),
                    propertyText.orElseThrow(),
                    change.before().orElseThrow(),
                    change.after().orElseThrow()
                ));
            }
            if (change.after().isPresent()) {
                return Optional.of(localization.format(
                    "history.entry.change.set-after",
                    targetText.orElseThrow(),
                    contextText.orElseThrow(),
                    propertyText.orElseThrow(),
                    change.after().orElseThrow()
                ));
            }
        }
        if (change.operation() == HistoryChange.Operation.ADD) {
            return Optional.of(localization.format(
                "history.entry.change.add",
                targetText.orElseThrow(),
                contextText.orElseThrow()
            ));
        }
        if (change.operation() == HistoryChange.Operation.REMOVE) {
            return Optional.of(localization.format(
                "history.entry.change.remove",
                targetText.orElseThrow(),
                contextText.orElseThrow()
            ));
        }
        if (change.operation() == HistoryChange.Operation.MOVE) {
            final String amount;
            if (change.before().isPresent() && change.after().isPresent()) {
                amount = " " + change.before().orElseThrow() + "→" + change.after().orElseThrow();
            } else {
                amount = change.after().map(value -> " " + value).orElse("");
            }
            return Optional.of(localization.format(
                "history.entry.change.move",
                targetText.orElseThrow(),
                contextText.orElseThrow(),
                amount
            ));
        }
        return Optional.empty();
    }

    private Optional<String> context(final HistoryEditContext context) {
        return switch (context.kind()) {
            case OBJECT, DOCUMENT -> Optional.of("");
            case DEFAULT_FORM -> Optional.of(localization.text("history.entry.context.default-form"));
            case KEYFORM -> {
                if (context.coordinates().isEmpty()) {
                    yield Optional.empty();
                }
                final List<String> coordinates = new ArrayList<>();
                for (final var coordinate : context.coordinates()) {
                    final Optional<String> parameter = coordinate.parameter().displayName()
                        .or(coordinate.parameter()::id);
                    if (parameter.isEmpty()) {
                        yield Optional.empty();
                    }
                    coordinates.add(localization.format(
                        "history.entry.context.coordinate",
                        parameter.orElseThrow(),
                        coordinate.value()
                    ));
                }
                yield Optional.of(localization.format(
                    "history.entry.context.keyform",
                    String.join(", ", coordinates)
                ));
            }
            case UNKNOWN -> Optional.empty();
        };
    }

    private Optional<String> property(final String property) {
        final String key = switch (property) {
            case "value" -> "history.property.value";
            case "name" -> "history.property.name";
            case "id" -> "history.property.id";
            case "intensity" -> "history.property.intensity";
            case "drawableA" -> "history.property.drawable-a";
            case "drawableB" -> "history.property.drawable-b";
            case "opacity" -> "history.property.opacity";
            case "drawOrder" -> "history.property.draw-order";
            case "multiplyColor" -> "history.property.multiply-color";
            case "screenColor" -> "history.property.screen-color";
            case "vertexPositions" -> "history.property.vertex-positions";
            case "controlPointPositions" -> "history.property.control-point-positions";
            case "angle" -> "history.property.angle";
            case "origin" -> "history.property.origin";
            case "scale" -> "history.property.scale";
            case "reflectX" -> "history.property.reflect-x";
            case "reflectY" -> "history.property.reflect-y";
            case "translation" -> "history.property.translation";
            default -> null;
        };
        return key == null ? Optional.empty() : Optional.of(localization.text(key));
    }

    private Optional<String> target(final HistoryTarget target) {
        final String key = switch (target.type()) {
            case "ART_MESH" -> "history.target.art-mesh";
            case "PARAMETER" -> "history.target.parameter";
            case "PART" -> "history.target.part";
            case "WARP_DEFORMER" -> "history.target.warp-deformer";
            case "ROTATION_DEFORMER" -> "history.target.rotation-deformer";
            case "GLUE" -> "history.target.glue";
            case "DOCUMENT" -> "history.target.document";
            default -> null;
        };
        if (key == null) {
            return Optional.empty();
        }
        final String type = localization.text(key);
        return Optional.of(target.displayName().or(target::id)
            .map(name -> localization.format("history.entry.target.named", type, name))
            .orElse(type));
    }

    /** Returns the URL-safe action identifier bound to a stable history entry ID. */
    public static String moveActionId(final String entryId) {
        return "history.entry.move." + encodedEntryId(entryId);
    }

    private static String encodedEntryId(final String entryId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            Objects.requireNonNull(entryId, "entryId").getBytes(StandardCharsets.UTF_8)
        );
    }
}
