package dev.turboism.plugin.parameterbatchtransfer.service;

import dev.turboism.plugin.parameterbatchtransfer.b1.domain.BatchTransferOutcome;
import dev.turboism.plugin.parameterbatchtransfer.b1.domain.BatchTransferRow;
import dev.turboism.plugin.parameterbatchtransfer.b1.domain.BoundParameterSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan;
import dev.turboism.sdk.plugin.PluginLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure batch-transfer logic over the SDK model API.
 *
 * <p>No Swing types: the dialog renders a {@link Session} and collects
 * {@link BatchTransferRow}s, {@link #apply} executes them one row at a time.
 * Each non-no-op row is one native Editor edit/Undo unit.</p>
 */
public final class ParameterBatchTransferService {

    private final PluginLogger logger;

    /** Creates the service without diagnostic logging (headless tests). */
    public ParameterBatchTransferService() {
        this(null);
    }

    /** Creates the service with an optional diagnostic logger (nullable). */
    public ParameterBatchTransferService(final PluginLogger logger) {
        this.logger = logger;
    }

    /** Bound parameter snapshots of one owner plus the model-wide candidate list. */
    public record Session(
        List<BoundParameterSnapshot> bound,
        List<BoundParameterSnapshot> candidates
    ) {
        public Session {
            bound = List.copyOf(Objects.requireNonNull(bound, "bound"));
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        }
    }

    /**
     * Builds the transfer session for one owner object: its current bindings and
     * the model-wide parameter candidate list.
     */
    public Session sessionFor(final CubismModel model, final ParameterBindingTarget owner) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(owner, "owner");
        final List<BoundParameterSnapshot> bound = bindingsFor(model, owner).stream()
            .map(binding -> BoundParameterSnapshot.of(parameterFor(model, binding.parameterId()), binding))
            .toList();
        final List<BoundParameterSnapshot> candidates = model.parameters().all().stream()
            .map(parameter -> BoundParameterSnapshot.of(parameter, null))
            .toList();
        return new Session(bound, candidates);
    }

    /**
     * Per-row target candidates: a morph source is offered only morph targets; a
     * non-morph source (normal or combined) is offered every non-morph parameter,
     * excluding parameters already bound to the owner (other than the source
     * itself), sorted by lowercase label.
     */
    public List<BoundParameterSnapshot> targetCandidates(
        final Session session,
        final BoundParameterSnapshot source
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(source, "source");
        final Set<ParameterId> boundElsewhere = session.bound().stream()
            .map(BoundParameterSnapshot::parameterId)
            .filter(id -> !id.equals(source.parameterId()))
            .collect(Collectors.toSet());
        return session.candidates().stream()
            .filter(candidate -> source.morph() == candidate.morph())
            .filter(candidate -> !boundElsewhere.contains(candidate.parameterId()))
            .sorted(Comparator.comparing(candidate -> candidate.label().toLowerCase(Locale.ROOT)))
            .toList();
    }

    /**
     * Applies each non-no-op row as one atomic transfer on the owner. Rows whose target equals the
     * source are skipped (the SDK plan constructor rejects identical parameters). BLEND_SHAPE rows
     * transfer every source Morph Target point in one whole-binding operation, while KEYFORM_GRID
     * rows run the existing keyform batch transfer with the requested inversion. Duplicate
     * non-no-op destinations fail closed before any row is edited. A failing row increments
     * {@code failed} and execution continues.
     */
    public BatchTransferOutcome apply(
        final CubismModel model,
        final ParameterBindingTarget owner,
        final List<BatchTransferRow> rows
    ) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(rows, "rows");
        final List<BatchTransferRow> activeRows = rows.stream()
            .filter(row -> !row.snapshot().parameterId().equals(row.target()))
            .toList();
        final Set<ParameterId> destinations = new java.util.HashSet<>();
        if (activeRows.stream().map(BatchTransferRow::target).anyMatch(id -> !destinations.add(id))) {
            final IllegalStateException failure = new IllegalStateException(
                "Non-no-op destination parameters must be unique."
            );
            activeRows.forEach(row -> logApplyFailed(row, failure));
            return BatchTransferOutcome.of(0, activeRows.size());
        }

        int applied = 0;
        int failed = 0;
        for (final BatchTransferRow row : rows) {
            final ParameterId from = row.snapshot().parameterId();
            if (from.equals(row.target())) {
                continue;
            }
            try {
                if (row.snapshot().family() == ParameterBindingFamily.BLEND_SHAPE) {
                    morphTransfer(model, owner, row);
                } else {
                    transferKeyform(model, owner, row);
                }
                applied++;
            } catch (RuntimeException failure) {
                logApplyFailed(row, failure);
                failed++;
            }
        }
        return BatchTransferOutcome.of(applied, failed);
    }

/**
     * Transfers one KEYFORM_GRID row through the runtime's atomic clamped operation.
     * The source values are read only for the {@code PBT_APPLY} diagnostic; all mutation,
     * inversion, clamping, duplicate rejection, and undo handling remain in the runtime.
     */
    private void transferKeyform(
        final CubismModel model,
        final ParameterBindingTarget owner,
        final BatchTransferRow row
    ) {
        final ParameterId from = row.snapshot().parameterId();
        final ParameterId to = row.target();
        final Parameter targetParameter = model.parameters().find(to);
        final float minimum = targetParameter.getMinimumValue();
        final float maximum = targetParameter.getMaximumValue();
        final List<ParameterBinding> bindings = model.parameters().find(from)
            .getParameterBindings().stream()
            .filter(binding -> binding.target().equals(owner))
            .filter(binding -> binding.family() == ParameterBindingFamily.KEYFORM_GRID)
            .toList();
        final List<ParameterBindingPoint> points = bindings.isEmpty()
            ? List.of()
            : bindings.get(0).points();
        final ArrayList<Float> before = new ArrayList<>(points.size());
        final ArrayList<Float> after = new ArrayList<>(points.size());
        final ArrayList<Float> clampedFrom = new ArrayList<>();
        for (ParameterBindingPoint point : points) {
            // + 0.0F normalizes -0.0F back to +0.0F (negating 0 stays 0).
            final float requested = row.invert() ? -point.value() + 0.0F : point.value();
            final float mapped = clamp(requested, minimum, maximum);
            before.add(point.value());
            after.add(mapped);
            if (Float.compare(mapped, requested) != 0) clampedFrom.add(requested);
        }
        logApply(row, minimum, maximum, before, after, clampedFrom);
        model.parameterBindingBatch().transferClamped(new ParameterBindingTransferPlan(
            from,
            to,
            List.of(owner),
            row.invert()
        ));
    }

    private void logApply(
        final BatchTransferRow row,
        final float minimum,
        final float maximum,
        final List<Float> before,
        final List<Float> after,
        final List<Float> clampedFrom
    ) {
        if (logger == null) return;
        logger.info("PBT_APPLY from=" + row.snapshot().parameterId().value()
            + " to=" + row.target().value()
            + " range=[" + minimum + "," + maximum + "]"
            + " inverted=" + row.invert()
            + " before=" + before
            + " after=" + after
            + " clampedFrom=" + clampedFrom);
    }

    private void logApplyFailed(final BatchTransferRow row, final RuntimeException failure) {
        if (logger == null) return;
        logger.error("PBT_APPLY_FAILED from=" + row.snapshot().parameterId().value()
            + " to=" + row.target().value()
            + " family=" + row.snapshot().family()
            + " inverted=" + row.invert(), failure);
    }

/**
     * Transfers one BLEND_SHAPE row through the runtime's whole-binding atomic operation.
     * The runtime preflights every Morph Target point, maps it independently, and commits the
     * row in one Editor edit/Undo unit. Deformers have no Morph Targets and fail closed.
     */
    private static void morphTransfer(
        final CubismModel model,
        final ParameterBindingTarget owner,
        final BatchTransferRow row
    ) {
        model.parameterBindingBatch().transferMorphClamped(new ParameterBindingTransferPlan(
            row.snapshot().parameterId(),
            row.target(),
            List.of(owner),
            row.invert()
        ));
    }

    private static float clamp(final float value, final float minimum, final float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static List<ParameterBinding> bindingsFor(
        final CubismModel model,
        final ParameterBindingTarget owner
    ) {
        try {
            return switch (owner.type()) {
                case ART_MESH -> model.drawables()
                    .find(new ArtMeshId(owner.id()))
                    .getParameterBindings();
                case WARP_DEFORMER, ROTATION_DEFORMER -> {
                    final Deformer deformer = model.deformers().all().stream()
                        .filter(value -> value.id().equals(new DeformerId(owner.id())))
                        .findFirst()
                        .orElse(null);
                    yield deformer == null ? List.of() : deformer.getParameterBindings();
                }
                default -> List.of();
            };
        } catch (RuntimeException unavailable) {
            // Object vanished or the backend lacks the projection: treat as unbound.
            return List.of();
        }
    }

    private static Parameter parameterFor(final CubismModel model, final ParameterId parameterId) {
        return model.parameters().all().stream()
            .filter(parameter -> parameter.id().equals(parameterId))
            .findFirst()
            .orElse(null);
    }
}
