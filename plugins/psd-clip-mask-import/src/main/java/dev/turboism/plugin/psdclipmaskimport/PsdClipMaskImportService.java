package dev.turboism.plugin.psdclipmaskimport;

import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * PSD clip-mask import workflow: plan from every relationship of the active
 * model, preview once, then commit all confirmed assignments through exactly
 * one {@link CubismModel#replaceArtMeshClipMasks} batch call. Confirming the
 * preview is the explicit overwrite policy; cancelling performs zero writes.
 *
 * <p>The plan bundle carries the active document/model identity. After
 * confirmation a current generation-bound model is obtained; the identity is
 * re-verified on that model, the plan is rebuilt by value from that same
 * model and compared with the previewed bundle, and only then is the batch
 * applied on that model. Any change aborts with zero writes. Expected-state
 * verification, host thread, all-or-nothing application, the single Undo
 * step, and refresh are the contract of the SDK batch operation; the plugin
 * never writes per-item or builds its own rollback.</p>
 */
public final class PsdClipMaskImportService {

    public static final String ACTION_ID = "psd-clip-mask-import.import";
    public static final String PREVIEW_DIALOG_ID = "psd-clip-mask-import.preview";
    public static final String TURBOISM_PANEL_ID = "turboism.panel.main";
    public static final String SECTION_ID = "psd-clip-mask-import.section";
    public static final int SECTION_ORDER = 110;

    private final CubismModelAccess models;
    private final PluginContext context;
    private final UiHostCapabilityService uiHost;
    private final PsdClipMaskPlanner planner = new PsdClipMaskPlanner();

    public PsdClipMaskImportService(
        final CubismModelAccess models,
        final PluginContext context,
        final UiHostCapabilityService uiHost
    ) {
        this.models = Objects.requireNonNull(models, "models");
        this.context = Objects.requireNonNull(context, "context");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
    }

    /**
     * Contributes this plugin's collapsible section, holding the single import button, to the shared
     * core Turboism panel rather than owning a side panel of its own.
     *
     * @return the contribution's registration; closing it removes the section
     */
    public Registration registerSection() {
        // Reuse the core Turboism tab (turboism.panel.main) like the clip-mask
        // viewer plugin instead of owning a side panel.
        return uiHost.contributeCollapsibleSection(new CollapsibleSectionContribution(
            EmbeddedPanelId.of(TURBOISM_PANEL_ID),
            SECTION_ID,
            context.localization().text("psd.clip-mask-import.title"),
            SECTION_ORDER,
            true,
            PanelView.column(
                PanelView.button(
                    "import-clip-masks",
                    context.localization().text("psd.clip-mask-import.button.import"),
                    ACTION_ID
                )
            )
        ));
    }

    /**
     * Runs the whole import: plan from the active model, show the preview, and on confirmation commit
     * every assignment in one batch.
     *
     * <p>Reports the outcome as a value and always posts a matching status notification; it does not
     * throw for an unavailable model or a failed apply. Cancelling the preview performs zero writes, as
     * does {@code NO_WRITE} when the plan is empty. Confirmation is the explicit overwrite consent: the
     * identity and plan are re-verified against a freshly obtained model, and any change since the
     * preview aborts with zero writes.
     *
     * @return the outcome plus applied, skipped and failure counts
     */
    public ImportResult importClipMasks() {
        final PlannedImport planned = buildPlannedImport();
        if (planned == null) {
            return notifyFailed(new ImportResult(ImportOutcome.FAILED, 0, 0, 1));
        }
        final PsdClipMaskPlan plan = planned.plan();
        if (plan.isEmpty()) {
            final ImportResult result = new ImportResult(
                ImportOutcome.NO_WRITE, 0, plan.skips().size(), 0
            );
            notify(
                "psd.clip-mask-import.no-write",
                "INFO",
                noWriteText(plan, result)
            );
            return result;
        }
        if (!uiHost.confirmDialog(preview(plan))) {
            final ImportResult result = new ImportResult(ImportOutcome.CANCELLED, 0, 0, 0);
            notify(
                "psd.clip-mask-import.cancelled",
                "WARNING",
                resultText(
                    context.localization().text("psd.clip-mask-import.cancelled"),
                    result
                )
            );
            return result;
        }
        final ImportResult result = commit(planned);
        if (result.outcome() == ImportOutcome.FAILED) {
            return notifyFailed(result);
        }
        // psd.clip-mask-import.overwrite-required only ever describes preview
        // conflicts; a confirmed, committed import reports .import.
        notify(
            "psd.clip-mask-import.import",
            "INFO",
            resultText(context.localization().text("psd.clip-mask-import.imported"), result)
        );
        return result;
    }

    /** Builds the previewed plan bundle, or {@code null} when unavailable (caller reports). */
    private PlannedImport buildPlannedImport() {
        try {
            final CubismModel model = models.active();
            final Identity identity = currentIdentity(model);
            final PsdClipMaskPlan plan = planner.plan(model.psdDocuments(), model.drawables().all());
            return new PlannedImport(identity, plan);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private DialogRequest preview(final PsdClipMaskPlan plan) {
        final StringBuilder body = new StringBuilder();
        body.append(context.localization().format(
            "psd.clip-mask-import.preview.header",
            plan.assignments().size() + plan.conflicts().size()
        )).append('\n');
        for (PsdClipMaskPlan.Assignment assignment : plan.assignments()) {
            body.append(context.localization().format(
                "psd.clip-mask-import.preview.relationship",
                assignment.targetArtMeshId().value(),
                ids(assignment.orderedMaskArtMeshIds()),
                sources(assignment.sourceLayers())
            )).append('\n');
        }
        if (!plan.conflicts().isEmpty()) {
            body.append('\n')
                .append(context.localization().text(
                    "psd.clip-mask-import.preview.overwrite-required"
                ))
                .append('\n');
            for (PsdClipMaskPlan.Conflict conflict : plan.conflicts()) {
                body.append(context.localization().format(
                    "psd.clip-mask-import.preview.overwrite-row",
                    conflict.targetArtMeshId().value(),
                    ids(conflict.existingMaskArtMeshIds()),
                    invertedWord(conflict.existingInverted()),
                    ids(conflict.plannedMaskArtMeshIds()),
                    invertedWord(false),
                    sources(conflict.sourceLayers())
                )).append('\n');
            }
            body.append(context.localization().text(
                "psd.clip-mask-import.preview.overwrite-note"
            )).append('\n');
        }
        if (!plan.skips().isEmpty()) {
            body.append('\n')
                .append(context.localization().format(
                    "psd.clip-mask-import.preview.skipped",
                    plan.skips().size()
                ))
                .append('\n');
            for (PsdClipMaskPlan.Skip skip : plan.skips()) {
                appendSkip(body, skip);
            }
        }
        return new DialogRequest(
            PREVIEW_DIALOG_ID,
            context.localization().text("psd.clip-mask-import.preview.title"),
            body.toString()
        );
    }

    /**
     * Deterministic NO_WRITE text: distinct wording for "no candidates" vs
     * "every candidate skipped", then one safe line per skip carrying target,
     * source refs, and the localized reason. All content is plugin-owned plan
     * data plus localized copy; raw host exception text and planner-owned
     * English detail strings never appear here.
     */
    private String noWriteText(final PsdClipMaskPlan plan, final ImportResult result) {
        final StringBuilder message = new StringBuilder();
        if (plan.skips().isEmpty()) {
            message.append(context.localization().text("psd.clip-mask-import.no-write.none"));
        } else {
            message.append(context.localization().text("psd.clip-mask-import.no-write.all-skipped"));
            for (PsdClipMaskPlan.Skip skip : plan.skips()) {
                message.append('\n');
                appendSkip(message, skip);
            }
        }
        message.append(' ').append(counts(result));
        return message.toString();
    }

    private void appendSkip(final StringBuilder body, final PsdClipMaskPlan.Skip skip) {
        body.append(context.localization().format(
            "psd.clip-mask-import.preview.skip-row",
            skip.targetArtMeshId().value(),
            sources(skip.sourceLayers()),
            context.localization().text(skipReasonKey(skip.reason()))
        )).append('\n');
    }

    /**
     * Stable lowercase key for one skip reason; keeps the localized UI free
     * of planner-owned English detail strings.
     */
    static String skipReasonKey(final PsdClipMaskPlan.SkipReason reason) {
        return "psd.clip-mask-import.skip."
            + reason.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private String invertedWord(final boolean inverted) {
        return context.localization().text(
            inverted
                ? "psd.clip-mask-import.preview.inverted"
                : "psd.clip-mask-import.preview.not-inverted"
        );
    }

    private static String sources(final List<PsdClipMaskPlan.SourceRef> sourceLayers) {
        final StringBuilder joined = new StringBuilder();
        for (int index = 0; index < sourceLayers.size(); index++) {
            if (index > 0) {
                joined.append(", ");
            }
            final PsdClipMaskPlan.SourceRef ref = sourceLayers.get(index);
            joined.append(ref.documentId()).append('/').append(ref.layerId());
        }
        return joined.toString();
    }

    private static String ids(final List<ArtMeshId> ids) {
        final StringBuilder joined = new StringBuilder();
        for (int index = 0; index < ids.size(); index++) {
            if (index > 0) {
                joined.append(", ");
            }
            joined.append(ids.get(index).value());
        }
        return joined.toString();
    }

    /**
     * Commits the previewed plan as exactly one conditional replacement batch
     * on a current generation-bound model obtained after confirmation. The
     * document/model identity is re-verified on that model and the plan is
     * rebuilt and compared by value against the previewed bundle; any change
     * aborts with zero writes. Atomicity, expected-state verification, host
     * thread, and the single Undo step are the contract of
     * {@link CubismModel#replaceArtMeshClipMasks}.
     */
    private ImportResult commit(final PlannedImport previewed) {
        try {
            final CubismModel model = models.active();
            if (!currentIdentity(model).equals(previewed.identity())) {
                return new ImportResult(ImportOutcome.FAILED, 0, 0, 1);
            }
            final PlannedImport revalidated = revalidate(model);
            if (revalidated == null || !revalidated.equals(previewed)) {
                return new ImportResult(ImportOutcome.FAILED, 0, 0, 1);
            }
            final PsdClipMaskPlan plan = revalidated.plan();
            model.replaceArtMeshClipMasks(toReplacements(plan));
            return new ImportResult(
                ImportOutcome.APPLIED,
                plan.assignments().size() + plan.conflicts().size(),
                plan.skips().size(),
                0
            );
        } catch (RuntimeException failure) {
            return new ImportResult(ImportOutcome.FAILED, 0, previewed.plan().skips().size(), 1);
        }
    }

    /** Rebuilds the plan bundle by value from the given current model. */
    private PlannedImport revalidate(final CubismModel model) {
        try {
            return new PlannedImport(
                currentIdentity(model),
                planner.plan(model.psdDocuments(), model.drawables().all())
            );
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Assignments carry an empty expected state (the planner guarantees no
     * conflict); conflicts carry the existing masks/inversion as the expected
     * state. The backend must verify every expected state before any write.
     */
    private static List<ClipMaskReplacement> toReplacements(final PsdClipMaskPlan plan) {
        final List<ClipMaskReplacement> replacements = new ArrayList<>();
        for (PsdClipMaskPlan.Assignment assignment : plan.assignments()) {
            replacements.add(new ClipMaskReplacement(
                assignment.targetArtMeshId(),
                List.of(),
                false,
                assignment.orderedMaskArtMeshIds(),
                false
            ));
        }
        for (PsdClipMaskPlan.Conflict conflict : plan.conflicts()) {
            replacements.add(new ClipMaskReplacement(
                conflict.targetArtMeshId(),
                conflict.existingMaskArtMeshIds(),
                conflict.existingInverted(),
                conflict.plannedMaskArtMeshIds(),
                false
            ));
        }
        return List.copyOf(replacements);
    }

    private Identity currentIdentity(final CubismModel model) {
        final DocumentSnapshot document = context.cubism().activeDocument()
            .orElseThrow(() -> new IllegalStateException("no active document"));
        final String documentModelId = document.model()
            .map(dev.turboism.sdk.cubism.ModelSnapshot::modelId)
            .orElseThrow(() -> new IllegalStateException("active document has no model"));
        final ModelId modelId = model.id();
        if (!documentModelId.equals(modelId.value())) {
            throw new IllegalStateException("active model does not match the active document");
        }
        return new Identity(new DocumentId(document.documentId()), modelId);
    }

    private ImportResult notifyFailed(final ImportResult result) {
        notify(
            "psd.clip-mask-import.stale-or-write",
            "WARNING",
            resultText(context.localization().text("psd.clip-mask-import.failed"), result)
        );
        return result;
    }

    /** Localized message with the stable counts; never raw host exception text. */
    private String resultText(final String message, final ImportResult result) {
        return message + " " + counts(result);
    }

    private String counts(final ImportResult result) {
        return context.localization().format(
            "psd.clip-mask-import.counts",
            result.applied(),
            result.skipped(),
            result.failures()
        );
    }

    private void notify(final String id, final String severity, final String message) {
        uiHost.notifyStatus(new StatusNotification(
            id,
            severity,
            message == null || message.isBlank()
                ? context.localization().text("psd.clip-mask-import.failed")
                : message
        ));
    }

    /** Outcome of one import invocation. */
    public enum ImportOutcome {
        APPLIED,
        CANCELLED,
        NO_WRITE,
        FAILED
    }

    /** Stable typed result with applied/skipped/failure counts. */
    public record ImportResult(ImportOutcome outcome, int applied, int skipped, int failures) {
    }

    /** The previewed plan plus the active document/model identity it was built from. */
    private record PlannedImport(Identity identity, PsdClipMaskPlan plan) {
    }

    private record Identity(DocumentId documentId, ModelId modelId) {
    }
}
