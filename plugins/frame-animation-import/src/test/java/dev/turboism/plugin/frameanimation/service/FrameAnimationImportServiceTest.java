package dev.turboism.plugin.frameanimation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.frameanimation.domain.AnchorPoint;
import dev.turboism.plugin.frameanimation.domain.DisplayStrategy;
import dev.turboism.plugin.frameanimation.domain.FrameAnimationImportPlan;
import dev.turboism.plugin.frameanimation.domain.FrameAnimationImportResult;
import dev.turboism.plugin.frameanimation.domain.FrameItem;
import dev.turboism.plugin.frameanimation.domain.KeyformBindingMode;
import dev.turboism.plugin.frameanimation.domain.ParameterConfig;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.ModelImageBinding;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBindingOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FrameAnimationImportServiceTest {

    @Test
    void executesImportPlanSuccessfully() {
        final FakeModel model = new FakeModel();
        final FakeTransactionService transactions = new FakeTransactionService();
        final FrameAnimationImportService service = new FrameAnimationImportService();

        final List<FrameItem> frames = List.of(
                new FrameItem(0, "Frame_0", new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)),
                new FrameItem(1, "Frame_1", new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)),
                new FrameItem(2, "Frame_2", new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)));

        final FrameAnimationImportPlan plan = new FrameAnimationImportPlan(
                "RunAnimation",
                frames,
                ParameterConfig.ofFrames("PARAM_RUN", "Run", 3, true),
                DisplayStrategy.STEP_FLIPBOOK,
                KeyformBindingMode.LOCAL_NEIGHBORHOOD,
                AnchorPoint.CENTER,
                0f,
                0f,
                500);

        final AuthoringTransactionResult<FrameAnimationImportResult> txResult =
                service.execute(model, plan, transactions);

        assertEquals(AuthoringTransactionOutcome.COMMITTED, txResult.outcome());
        assertTrue(txResult.value().isPresent());
        final FrameAnimationImportResult result = txResult.value().get();
        assertNotNull(result);
        assertEquals(new PartId("Part_RunAnimation"), result.partId());
        assertEquals(new ParameterId("PARAM_RUN"), result.parameterId());
        assertEquals(3, result.frameCount());
        assertEquals(3, result.createdMeshIds().size());

        // Verify model state
        assertEquals(1, model.parts.createdParts.size());
        assertEquals(3, model.drawables.createdMeshes.size());
        assertEquals(3, model.textures.registeredImages.size());

        // Check parameter creation
        final Parameter createdParam = model.parameters.find(new ParameterId("PARAM_RUN"));
        assertNotNull(createdParam);
        assertEquals(0f, createdParam.getMinimumValue());
        assertEquals(2f, createdParam.getMaximumValue());
        assertTrue(createdParam.repeat().orElse(false));

        // Check bindings recorded
        assertEquals(3, model.bindings.size());
    }

    // --- Fake implementations for isolated unit testing ---

    private static final class FakeTransactionService implements AuthoringTransactionService {
        @Override
        public <T> AuthoringTransactionResult<T> execute(
                final AuthoringTransactionOptions options, final AuthoringTransactionWork<T> work) {
            try {
                final T res = work.run();
                final HistorySnapshot history = HistorySnapshot.unavailable();
                final AuthoringTransactionReceipt receipt = new AuthoringTransactionReceipt(
                        "tx-1", options.label(), history, history, Optional.of("entry-1"));
                return AuthoringTransactionResult.committed(res, receipt);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final class FakeModel implements CubismModel {
        final FakeParts parts = new FakeParts();
        final FakeDrawables drawables = new FakeDrawables();
        final FakeModelTextures textures = new FakeModelTextures();
        final FakeParameters parameters = new FakeParameters();

        record BindingCall(
                ParameterId parameterId, ParameterBindingTarget target, List<ParameterBindingPoint> points) {}

        final List<BindingCall> bindings = new ArrayList<>();

        @Override
        public ModelId id() {
            return new ModelId("fake-model");
        }

        @Override
        public Parts parts() {
            return parts;
        }

        @Override
        public Drawables drawables() {
            return drawables;
        }

        @Override
        public ModelTextures textures() {
            return textures;
        }

        @Override
        public Parameters parameters() {
            return parameters;
        }

        @Override
        public ParameterBindingOperations parameterBindings(final ParameterId parameterId) {
            return new ParameterBindingOperations() {
                @Override
                public void bind(final ParameterBindingTarget target, final List<ParameterBindingPoint> points) {
                    bindings.add(new BindingCall(parameterId, target, points));
                }

                @Override
                public void createPoint(final ParameterBindingTarget target, final ParameterBindingPoint point) {}

                @Override
                public void movePoint(
                        final ParameterBindingTarget target,
                        final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId,
                        final float value) {}

                @Override
                public void deletePoint(
                        final ParameterBindingTarget target,
                        final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId) {}

                @Override
                public void unbind(final ParameterBindingTarget target) {}
            };
        }

        @Override
        public Deformers deformers() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Glues glues() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update() {}
    }

    private static final class FakeParts implements Parts {
        final List<Part> createdParts = new ArrayList<>();

        @Override
        public List<Part> all() {
            return createdParts;
        }

        @Override
        public Part find(final PartId id) {
            return createdParts.stream()
                    .filter(p -> p.id().equals(id))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public Part create(final String name, final Part parent, final int index) {
            final FakePart p = new FakePart(new PartId("Part_" + name), name);
            createdParts.add(p);
            return p;
        }
    }

    private static final class FakePart implements Part {
        private final PartId id;
        private String name;
        private float opacity = 1.0f;

        FakePart(final PartId id, final String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public PartId id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void setName(final String name) {
            this.name = name;
        }

        @Override
        public boolean visible() {
            return true;
        }

        @Override
        public boolean locked() {
            return false;
        }

        @Override
        public float getOpacity() {
            return opacity;
        }

        @Override
        public void setOpacity(final float opacity) {
            this.opacity = opacity;
        }

        @Override
        public int parentIndex() {
            return -1;
        }
    }

    private static final class FakeDrawables implements Drawables {
        final List<Drawable> createdMeshes = new ArrayList<>();
        int counter = 0;

        @Override
        public List<Drawable> all() {
            return createdMeshes;
        }

        @Override
        public Drawable find(final ArtMeshId id) {
            return createdMeshes.stream()
                    .filter(d -> d.id().equals(id))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public Drawable createWithSourceGeometry(
                final String name,
                final Part parent,
                final int index,
                final ArtMeshGeometry sourceGeometry,
                final List<Point2> defaultPositions) {
            final FakeDrawable d = new FakeDrawable(new ArtMeshId("Mesh_" + (counter++)), name);
            createdMeshes.add(d);
            return d;
        }
    }

    private static final class FakeDrawable implements Drawable {
        private final ArtMeshId id;
        private final String name;
        private float opacity = 1.0f;
        private int drawOrder = 500;

        FakeDrawable(final ArtMeshId id, final String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public ArtMeshId id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public float getOpacity() {
            return opacity;
        }

        @Override
        public void setOpacity(final float opacity) {
            this.opacity = opacity;
        }

        @Override
        public int drawOrder() {
            return drawOrder;
        }

        @Override
        public void setDrawOrder(final int drawOrder) {
            this.drawOrder = drawOrder;
        }

        @Override
        public byte constantFlag() {
            return 0;
        }

        @Override
        public byte dynamicFlag() {
            return 0;
        }

        @Override
        public BlendMode blendMode() {
            return BlendMode.NORMAL;
        }

        @Override
        public int textureIndex() {
            return 0;
        }

        @Override
        public int renderOrder() {
            return 0;
        }

        @Override
        public IntSequence masks() {
            return null;
        }

        @Override
        public FloatSequence vertexPositions() {
            return null;
        }

        @Override
        public FloatSequence vertexUvs() {
            return null;
        }

        @Override
        public IntSequence indices() {
            return null;
        }

        @Override
        public Color multiplyColor() {
            return null;
        }

        @Override
        public Color screenColor() {
            return null;
        }

        @Override
        public int parentPartIndex() {
            return 0;
        }

        @Override
        public int parentDeformerIndex() {
            return 0;
        }

        @Override
        public IntSequence parameters() {
            return null;
        }
    }

    private static final class FakeModelTextures implements ModelTextures {
        final List<AuthorizedImageContent> registeredImages = new ArrayList<>();
        int counter = 0;

        @Override
        public List<dev.turboism.sdk.cubism.model.RawTexture> rawImages() {
            return List.of();
        }

        @Override
        public List<dev.turboism.sdk.cubism.model.ModelImageGroup> modelImageGroups() {
            return List.of();
        }

        @Override
        public List<dev.turboism.sdk.cubism.model.AtlasTexture> textureAtlases() {
            return List.of();
        }

        @Override
        public void addModelImageGroup(final String name) {}

        @Override
        public void removeModelImage(final ModelImageId id) {}

        @Override
        public dev.turboism.sdk.cubism.id.TextureAtlasId addTextureAtlas(
                final String name, final int widthPixels, final int heightPixels) {
            return null;
        }

        @Override
        public void removeTextureAtlas(final dev.turboism.sdk.cubism.id.TextureAtlasId id) {}

        @Override
        public void removeRawImage(final RawImageId id) {}

        @Override
        public ModelImageBinding registerImageAndBind(final AuthorizedImageContent image, final ArtMeshId target) {
            registeredImages.add(image);
            final int idx = counter++;
            return new ModelImageBinding(
                    "test-scope",
                    new ModelId("model-1"),
                    1L,
                    new RawImageId("raw_" + idx),
                    new ModelImageId("model_img_" + idx),
                    Optional.of(target));
        }
    }

    private static final class FakeParameters implements Parameters {
        final List<Parameter> list = new ArrayList<>();

        @Override
        public List<Parameter> all() {
            return list;
        }

        @Override
        public Parameter find(final ParameterId id) {
            return list.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
        }

        @Override
        public Parameter create(
                final ParameterDefinition definition,
                final Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> folderId) {
            final FakeParameter p = new FakeParameter(definition);
            list.add(p);
            return p;
        }
    }

    private static final class FakeParameter implements Parameter {
        private final ParameterDefinition def;
        private float value;

        FakeParameter(final ParameterDefinition def) {
            this.def = def;
            this.value = def.defaultValue();
        }

        @Override
        public ParameterId id() {
            return def.id();
        }

        @Override
        public Optional<String> name() {
            return Optional.of(def.name());
        }

        @Override
        public ParameterType type() {
            return def.type();
        }

        @Override
        public Optional<Boolean> repeat() {
            return Optional.of(def.repeat());
        }

        @Override
        public float getValue() {
            return value;
        }

        @Override
        public void setValue(final float value) {
            this.value = value;
        }

        @Override
        public float getMinimumValue() {
            return def.minimumValue();
        }

        @Override
        public float getMaximumValue() {
            return def.maximumValue();
        }

        @Override
        public float getDefaultValue() {
            return def.defaultValue();
        }
    }
}
