package dev.turboism.plugin.frameanimation;

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
import dev.turboism.plugin.frameanimation.domain.SpriteSheetConfig;
import dev.turboism.plugin.frameanimation.service.FrameAnimationImportService;
import dev.turboism.plugin.frameanimation.slice.NaturalOrderComparator;
import dev.turboism.plugin.frameanimation.slice.SpriteSheetSlicer;
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
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class NumbersAssetEndToEndTest {

    private static final File ASSET_ZIP = resolveAssetZip();

    private static File resolveAssetZip() {
        final String envPath = System.getenv("NUMBERS_ASSET_ZIP");
        if (envPath != null && !envPath.isBlank()) {
            return new File(envPath);
        }
        final File inDocs = new File(System.getProperty("user.home"), "Documents/numbers_1_7_animation_assets.zip");
        return inDocs.exists() ? inDocs : null;
    }

    @Test
    void verifiesSpriteSheetImportFromNumbersZip() throws Exception {
        if (ASSET_ZIP == null || !ASSET_ZIP.exists()) {
            return;
        }

        BufferedImage spriteSheet = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(ASSET_ZIP))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith("sprite_sheet.png")) {
                    spriteSheet = ImageIO.read(zis);
                    break;
                }
            }
        }
        assertNotNull(spriteSheet, "sprite_sheet.png must be present in zip");

        // 1. Slice sprite sheet into 7 frames
        final SpriteSheetConfig config = SpriteSheetConfig.of(2, 4, 7);
        final List<FrameItem> frames = SpriteSheetSlicer.slice(spriteSheet, config);
        assertEquals(7, frames.size());

        // 2. Prepare import plan
        final FrameAnimationImportPlan plan = new FrameAnimationImportPlan(
                "Part_NumbersSheet",
                frames,
                ParameterConfig.ofFrames("PARAM_NUMBERS_SHEET", "Numbers Sheet Animation", 7, true),
                DisplayStrategy.STEP_FLIPBOOK,
                KeyformBindingMode.LOCAL_NEIGHBORHOOD,
                AnchorPoint.CENTER,
                0f,
                0f,
                500);

        final HarnessModel model = new HarnessModel();
        final HarnessTransactions transactions = new HarnessTransactions();
        final FrameAnimationImportService service = new FrameAnimationImportService();

        // 3. Execute atomic import
        final AuthoringTransactionResult<FrameAnimationImportResult> txRes = service.execute(model, plan, transactions);
        assertEquals(AuthoringTransactionOutcome.COMMITTED, txRes.outcome());
        assertTrue(txRes.value().isPresent());

        final FrameAnimationImportResult result = txRes.value().get();
        assertEquals(7, result.frameCount());
        assertEquals(7, result.createdMeshIds().size());
        assertEquals(new PartId("Part_Part_NumbersSheet"), result.partId());
        assertEquals(new ParameterId("PARAM_NUMBERS_SHEET"), result.parameterId());

        // 4. Verify created model objects
        assertEquals(1, model.parts.created.size());
        assertEquals(7, model.drawables.created.size());
        assertEquals(7, model.textures.registered.size());
        assertEquals(7, model.bindings.size());

        final Parameter param = model.parameters.find(new ParameterId("PARAM_NUMBERS_SHEET"));
        assertNotNull(param);
        assertEquals(0.0f, param.getMinimumValue());
        assertEquals(6.0f, param.getMaximumValue());
        assertTrue(param.repeat().orElse(false));
    }

    @Test
    void verifiesMultiImageSequenceImportFromNumbersZip() throws Exception {
        if (ASSET_ZIP == null || !ASSET_ZIP.exists()) {
            return;
        }

        final Map<String, byte[]> frameBytes = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(ASSET_ZIP))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                final String name = entry.getName();
                if (name.contains("frame_") && name.endsWith(".png")) {
                    final String fileName = new File(name).getName();
                    frameBytes.put(fileName, zis.readAllBytes());
                }
            }
        }
        assertEquals(7, frameBytes.size(), "must find 7 frame png files");

        // Sort names in natural numerical order
        final List<String> sortedNames = new ArrayList<>(frameBytes.keySet());
        sortedNames.sort(NaturalOrderComparator.INSTANCE);
        assertEquals(
                List.of(
                        "frame_1.png",
                        "frame_2.png",
                        "frame_3.png",
                        "frame_4.png",
                        "frame_5.png",
                        "frame_6.png",
                        "frame_7.png"),
                sortedNames);

        final List<FrameItem> frames = new ArrayList<>();
        for (int i = 0; i < sortedNames.size(); i++) {
            final String name = sortedNames.get(i);
            final BufferedImage img = ImageIO.read(new ByteArrayInputStream(frameBytes.get(name)));
            assertNotNull(img);
            assertEquals(1254, img.getWidth(), "original width is 1254");
            assertEquals(1254, img.getHeight(), "original height is 1254");
            frames.add(new FrameItem(i, name, img));
        }

        final FrameAnimationImportPlan plan = new FrameAnimationImportPlan(
                "Part_NumbersSeq",
                frames,
                ParameterConfig.ofFrames("PARAM_NUMBERS_SEQ", "Numbers Sequence", 7, false),
                DisplayStrategy.CROSSFADE,
                KeyformBindingMode.LOCAL_NEIGHBORHOOD,
                AnchorPoint.CENTER,
                0f,
                0f,
                500);

        final HarnessModel model = new HarnessModel();
        final HarnessTransactions transactions = new HarnessTransactions();
        final FrameAnimationImportService service = new FrameAnimationImportService();

        // Execute import
        final AuthoringTransactionResult<FrameAnimationImportResult> txRes = service.execute(model, plan, transactions);
        assertEquals(AuthoringTransactionOutcome.COMMITTED, txRes.outcome());
        assertTrue(txRes.value().isPresent());

        final FrameAnimationImportResult result = txRes.value().get();
        assertEquals(7, result.frameCount());
        assertEquals(7, result.createdMeshIds().size());

        // Verify adaptive scaling was applied to oversized 1254x1254 images and all grants succeeded
        for (final AuthorizedImageContent grant : model.textures.registered) {
            assertTrue((long) grant.width() * grant.height() <= AuthorizedImageContent.MAX_RGBA_BYTES / 4L);
        }
    }

    // --- Harness ---

    private static final class HarnessTransactions implements AuthoringTransactionService {
        @Override
        public <T> AuthoringTransactionResult<T> execute(
                final AuthoringTransactionOptions options, final AuthoringTransactionWork<T> work) {
            try {
                final T res = work.run();
                final HistorySnapshot h = HistorySnapshot.unavailable();
                return AuthoringTransactionResult.committed(
                        res, new AuthoringTransactionReceipt("tx-id", options.label(), h, h, Optional.of("entry-1")));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final class HarnessModel implements CubismModel {
        final HarnessParts parts = new HarnessParts();
        final HarnessDrawables drawables = new HarnessDrawables();
        final HarnessTextures textures = new HarnessTextures();
        final HarnessParams parameters = new HarnessParams();

        record BindingCall(
                ParameterId parameterId, ParameterBindingTarget target, List<ParameterBindingPoint> points) {}

        final List<BindingCall> bindings = new ArrayList<>();

        @Override
        public ModelId id() {
            return new ModelId("model-1");
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
            return null;
        }

        @Override
        public Glues glues() {
            return null;
        }

        @Override
        public void update() {}
    }

    private static final class HarnessParts implements Parts {
        final List<Part> created = new ArrayList<>();

        @Override
        public List<Part> all() {
            return created;
        }

        @Override
        public Part find(final PartId id) {
            return created.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
        }

        @Override
        public Part create(final String name, final Part parent, final int index) {
            final HarnessPart p = new HarnessPart(new PartId("Part_" + name), name);
            created.add(p);
            return p;
        }
    }

    private static final class HarnessPart implements Part {
        private final PartId id;
        private String name;

        HarnessPart(final PartId id, final String name) {
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
            return 1.0f;
        }

        @Override
        public void setOpacity(final float opacity) {}

        @Override
        public int parentIndex() {
            return -1;
        }
    }

    private static final class HarnessDrawables implements Drawables {
        final List<Drawable> created = new ArrayList<>();
        int counter = 0;

        @Override
        public List<Drawable> all() {
            return created;
        }

        @Override
        public Drawable find(final ArtMeshId id) {
            return created.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
        }

        @Override
        public Drawable createWithSourceGeometry(
                final String name,
                final Part parent,
                final int index,
                final ArtMeshGeometry sourceGeometry,
                final List<Point2> defaultPositions) {
            final HarnessDrawable d = new HarnessDrawable(new ArtMeshId("mesh_" + (counter++)), name);
            created.add(d);
            return d;
        }
    }

    private static final class HarnessDrawable implements Drawable {
        private final ArtMeshId id;
        private final String name;
        private float opacity = 1.0f;

        HarnessDrawable(final ArtMeshId id, final String name) {
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
        public int drawOrder() {
            return 500;
        }

        @Override
        public void setDrawOrder(final int drawOrder) {}

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

    private static final class HarnessTextures implements ModelTextures {
        final List<AuthorizedImageContent> registered = new ArrayList<>();
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
            registered.add(image);
            final int idx = counter++;
            return new ModelImageBinding(
                    "test",
                    new ModelId("model"),
                    1L,
                    new RawImageId("raw_" + idx),
                    new ModelImageId("img_" + idx),
                    Optional.of(target));
        }
    }

    private static final class HarnessParams implements Parameters {
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
            final HarnessParam p = new HarnessParam(definition);
            list.add(p);
            return p;
        }
    }

    private static final class HarnessParam implements Parameter {
        private final ParameterDefinition def;
        private float value;

        HarnessParam(final ParameterDefinition def) {
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
