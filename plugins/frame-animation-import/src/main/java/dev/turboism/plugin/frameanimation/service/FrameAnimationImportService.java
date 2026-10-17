package dev.turboism.plugin.frameanimation.service;

import dev.turboism.plugin.frameanimation.domain.FrameAnimationImportPlan;
import dev.turboism.plugin.frameanimation.domain.FrameAnimationImportResult;
import dev.turboism.plugin.frameanimation.domain.FrameItem;
import dev.turboism.plugin.frameanimation.slice.FrameImageProcessor;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.plugin.PluginLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * High-level orchestration service for frame animation import within an atomic authoring transaction.
 */
public final class FrameAnimationImportService {

    private final PluginLogger logger;

    public FrameAnimationImportService() {
        this(null);
    }

    public FrameAnimationImportService(final PluginLogger logger) {
        this.logger = logger;
    }

    /**
     * Executes the frame animation import plan atomically inside an authoring transaction.
     *
     * @param model active Cubism model
     * @param plan validated import plan
     * @param transactions authoring transaction coordinator
     * @return transaction result containing the FrameAnimationImportResult
     */
    public AuthoringTransactionResult<FrameAnimationImportResult> execute(
            final CubismModel model,
            final FrameAnimationImportPlan plan,
            final AuthoringTransactionService transactions) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(transactions, "transactions");

        final AuthoringTransactionOptions options =
                AuthoringTransactionOptions.of("Import Frame Animation: " + plan.partName());

        return transactions.execute(options, () -> assemble(model, plan));
    }

    private FrameAnimationImportResult assemble(final CubismModel model, final FrameAnimationImportPlan plan) {
        if (logger != null) {
            logger.info("Starting frame animation import: " + plan.partName() + " ("
                    + plan.frames().size() + " frames)");
        }

        // 1. Create container Part for all frame meshes
        final Part part = model.parts().create(plan.partName(), null, -1);

        // Determine placement center on canvas (auto-center to model canvas center if 0,0)
        float targetCenterX = plan.centerX();
        float targetCenterY = plan.centerY();
        if (targetCenterX == 0f && targetCenterY == 0f) {
            try {
                final var canvas = model.canvas();
                targetCenterX = canvas.widthPixels() / 2.0f - canvas.originXPixels();
                targetCenterY = canvas.heightPixels() / 2.0f - canvas.originYPixels();
            } catch (Exception e) {
                // Fallback to (0, 0) if canvas metrics are unexposed
            }
        }

        final List<Drawable> createdMeshes = new ArrayList<>(plan.frames().size());
        final List<ArtMeshId> createdMeshIds = new ArrayList<>(plan.frames().size());

        // 2. Create ArtMeshes, register and bind textures
        for (int i = 0; i < plan.frames().size(); i++) {
            final FrameItem frame = plan.frames().get(i);

            // Construct normalized source geometry matching image dimensions (eliminating phase shift)
            final ArtMeshGeometry sourceGeometry =
                    FrameMeshAssembler.createSourceGeometry(frame.width(), frame.height(), plan.flipVertical());

            // Calculate visible canvas coordinates centered on model canvas
            final List<Point2> defaultPositions = FrameMeshAssembler.createDefaultPositions(
                    frame.width(), frame.height(), plan.anchor(), targetCenterX, targetCenterY);

            // Create ArtMesh under part with separate source and default geometry
            final Drawable mesh = model.drawables()
                    .createWithSourceGeometry(frame.name(), part, -1, sourceGeometry, defaultPositions);
            mesh.setDrawOrder(plan.drawOrder());

            // Register texture and bind to mesh
            final AuthorizedImageContent grant =
                    FrameImageProcessor.toAuthorizedImage(frame.image(), "frame_" + i + "_" + frame.name());
            model.textures().registerImageAndBind(grant, mesh.id());

            createdMeshes.add(mesh);
            createdMeshIds.add(mesh.id());
        }

        // 3. Create or resolve parameter, bind keyframes and opacities
        final Parameter parameter = FrameKeyformBinder.getOrCreateParameter(model, plan.parameterConfig());
        FrameKeyformBinder.bindFrameOpacities(
                model, parameter, createdMeshes, plan.displayStrategy(), plan.bindingMode());

        if (logger != null) {
            logger.info("Frame animation import finished: created " + createdMeshes.size() + " meshes bound to "
                    + parameter.id().value());
        }

        return new FrameAnimationImportResult(
                part.id(), parameter.id(), createdMeshIds, plan.frames().size());
    }
}
