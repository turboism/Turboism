package dev.turboism.validation.autoatlas;

import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInput;
import dev.turboism.sdk.cubism.model.AtlasPageAppendPlan;
import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.CubismExportTexture;
import dev.turboism.sdk.cubism.model.CubismMocExport;
import dev.turboism.sdk.cubism.model.CubismMocExportSettings;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.ModelImageBinding;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelImagePlacement;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/**
 * Standalone, procedural real-host probe verifying the native append-only texture atlas API.
 * Executed on an isolated prefix without external dependencies or proprietary fixtures.
 */
public final class AutoAtlasHostProbePlugin implements TurboismPlugin {

    private PluginContext context;
    private volatile String startupDocumentId;
    private volatile boolean newDocumentRequested;
    private final Map<String, Object> report = new LinkedHashMap<>();

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void enable() {
        final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "auto-atlas-probe-poller");
            t.setDaemon(true);
            return t;
        });

        final long startMillis = System.currentTimeMillis();
        final long timeoutMillis = 600_000L;

        poller.scheduleAtFixedRate(() -> {
            try {
                if (System.currentTimeMillis() - startMillis > timeoutMillis) {
                    poller.shutdown();
                    writeFailure("Probe timed out waiting for trigger file or active modeling document.");
                    return;
                }
                final boolean immediate = "true".equalsIgnoreCase(
                        System.getProperty("turboism.autoatlas.immediate", "false"));
                final Path trigger = context.paths().stateDir().resolve("probe.trigger");
                final boolean triggerPresent = Files.exists(trigger);
                final var activeDocument = context.cubism().activeDocument();
                final boolean docPresent = activeDocument.isPresent();

                if ((immediate || triggerPresent) && docPresent) {
                    if (!newDocumentRequested) {
                        startupDocumentId = activeDocument.orElseThrow().documentId();
                        newDocumentRequested = true;
                        SwingUtilities.invokeLater(() -> {
                            try {
                                final var result = context.services().require(EditorCommandService.class)
                                        .execute(EditorCommand.NEW_MODEL);
                                if (!result.executed()) {
                                    poller.shutdown();
                                    writeFailure("NEW_MODEL failed: " + result.status());
                                }
                            } catch (Throwable failure) {
                                poller.shutdown();
                                writeFailure("NEW_MODEL failed: " + failure);
                            }
                        });
                        return;
                    }
                    if (startupDocumentId.equals(activeDocument.orElseThrow().documentId())) return;
                    poller.shutdown();
                    System.out.println("[AUTO_ATLAS_PROBE] Ready marker observed (trigger="
                            + triggerPresent + ", immediate=" + immediate + "), dispatching to EDT...");
                    SwingUtilities.invokeLater(this::runProbeSequence);
                }
            } catch (Throwable t) {
                System.err.println("[AUTO_ATLAS_PROBE] Poller check failed: " + t.getMessage());
                t.printStackTrace(System.err);
            }
        }, 200, 500, TimeUnit.MILLISECONDS);
    }

    private void runProbeSequence() {
        report.put("schemaVersion", "auto-atlas-probe-v1");
        report.put("status", "RUNNING");
        try {
            final CubismModel model = context.cubism().model().active();
            final CubismHistory history = context.cubism().history();

            // Phase 0: Capability pre-check
            final boolean supported = model.textures().supportsAppendTextureAtlasPages();
            report.put("supportsAppendPreCheck", supported);
            if (!supported) {
                throw new IllegalStateException("supportsAppendTextureAtlasPages returned false on host.");
            }

            // Phase 1: Zero-atlas standalone append
            final boolean zeroAtlasAtStart = model.textures().textureAtlases().isEmpty();
            report.put("zeroAtlasAtStart", zeroAtlasAtStart);
            if (!zeroAtlasAtStart) {
                throw new IllegalStateException("Probe requires clean unatlased model document.");
            }

            final Part rootPart = model.parts().create("AutoAtlasProbePart", null, -1);
            final ArtMeshGeometry geo1 = SyntheticAssets.quadGeometry(100f, 100f, 256f, 256f);
            final Drawable mesh1 = model.drawables().create("ProbeMesh1", rootPart, -1, geo1);
            final AuthorizedImageContent img1 = SyntheticAssets.generateAuthorizedImage(
                    "SyntheticTexture1", 256, 256, new Color(40, 80, 160), Color.YELLOW);
            final ModelImageBinding bind1 = model.textures().registerImageAndBind(img1, mesh1.id());

            final AtlasPageAppendPlan plan1 = new AtlasPageAppendPlan(
                    1024,
                    1024,
                    1,
                    List.of("ProbePage1"),
                    List.of(new ModelImagePlacement(bind1.modelImageId(), 0, 10, 10, 256, 256)));

            final List<TextureAtlasId> p1Ids = model.textures().appendTextureAtlasPages(plan1);
            if (p1Ids.size() != 1) throw new IllegalStateException("Expected 1 page from plan1, got " + p1Ids.size());
            final TextureAtlasId page1Id = p1Ids.get(0);
            report.put("page1Id", page1Id.value());

            final Optional<ArtMeshTextureInput> mesh1InputPhase1 = model.textures().textureInput(mesh1.id());
            if (mesh1InputPhase1.isEmpty()
                    || mesh1InputPhase1.get().kind() != ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION
                    || !page1Id.equals(mesh1InputPhase1.get().textureAtlasId().orElse(null))) {
                throw new IllegalStateException("Mesh 1 failed to bind to texture atlas page 1: " + mesh1InputPhase1);
            }
            final List<Float> mesh1UvsPhase1 = readUvs(mesh1);
            report.put("mesh1UvsPhase1", mesh1UvsPhase1);

            // Phase 2: Single-root AuthoringTransaction append + Undo/Redo
            final HistorySnapshot hBeforeTx = history.snapshot();
            final ArtMeshGeometry geo2 = SyntheticAssets.quadGeometry(400f, 100f, 256f, 256f);
            final AuthorizedImageContent img2 = SyntheticAssets.generateAuthorizedImage(
                    "SyntheticTexture2", 256, 256, new Color(160, 40, 80), Color.CYAN);

            final var txService = context.cubism().authoringTransactions();
            final TextureAtlasId[] page2IdHolder = new TextureAtlasId[1];
            final Drawable[] mesh2Holder = new Drawable[1];

            final AuthoringTransactionResult<Void> tx2Result = txService.execute(
                    AuthoringTransactionOptions.of("SecondAppendTx"),
                    () -> {
                        final Drawable mesh2 = model.drawables().create("ProbeMesh2", rootPart, -1, geo2);
                        mesh2Holder[0] = mesh2;
                        final ModelImageBinding bind2 = model.textures().registerImageAndBind(img2, mesh2.id());
                        final AtlasPageAppendPlan plan2 = new AtlasPageAppendPlan(
                                1024,
                                1024,
                                1,
                                List.of("ProbePage2"),
                                List.of(new ModelImagePlacement(bind2.modelImageId(), 0, 20, 20, 256, 256)));
                        final List<TextureAtlasId> p2Ids = model.textures().appendTextureAtlasPages(plan2);
                        if (p2Ids.size() != 1) {
                            throw new IllegalStateException("Expected 1 page from plan2, got " + p2Ids.size());
                        }
                        page2IdHolder[0] = p2Ids.get(0);
                        return null;
                    });

            if (tx2Result.outcome() != AuthoringTransactionOutcome.COMMITTED) {
                throw new IllegalStateException("Phase 2 transaction failed: " + tx2Result.outcome());
            }

            final TextureAtlasId page2Id = page2IdHolder[0];
            final Drawable mesh2 = mesh2Holder[0];
            report.put("page2Id", page2Id.value());

            final HistorySnapshot hAfterTx = history.snapshot();
            final boolean singleRootEntry = hAfterTx.position() == hBeforeTx.position() + 1;
            report.put("singleRootHistoryEntry", singleRootEntry);
            if (!singleRootEntry) {
                throw new IllegalStateException("Phase 2 transaction produced "
                        + (hAfterTx.position() - hBeforeTx.position()) + " history entries, expected exactly 1");
            }

            final List<AtlasTexture> atlasesPhase2 = model.textures().textureAtlases();
            if (atlasesPhase2.size() != 2) {
                throw new IllegalStateException("Expected 2 atlas pages after second append, got: " + atlasesPhase2.size());
            }
            final boolean page1GuidPreserved = atlasesPhase2.get(0).id().equals(page1Id);
            report.put("page1GuidPreserved", page1GuidPreserved);
            if (!page1GuidPreserved) {
                throw new IllegalStateException("Page 1 GUID was modified by second append: " + atlasesPhase2.get(0).id());
            }

            final List<Float> mesh1UvsPhase2 = readUvs(mesh1);
            final boolean mesh1UvsPreserved = mesh1UvsPhase1.equals(mesh1UvsPhase2);
            report.put("mesh1UvsPreserved", mesh1UvsPreserved);
            if (!mesh1UvsPreserved) {
                throw new IllegalStateException("Mesh 1 UVs were altered by second append: " + mesh1UvsPhase2);
            }

            final Optional<ArtMeshTextureInput> mesh2Input = model.textures().textureInput(mesh2.id());
            if (mesh2Input.isEmpty()
                    || mesh2Input.get().kind() != ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION
                    || !page2Id.equals(mesh2Input.get().textureAtlasId().orElse(null))) {
                throw new IllegalStateException("Mesh 2 failed to bind to texture atlas page 2: " + mesh2Input);
            }

            // Undo Phase 2 by 1 step
            final HistoryMoveResult undoResult = history.undo(1);
            report.put("undoOutcome", undoResult.outcome().name());
            if (undoResult.outcome() != HistoryMoveResult.Outcome.MOVED) {
                throw new IllegalStateException("Undo failed: " + undoResult.outcome());
            }

            // Verify Undo: page 2 removed, mesh 2 removed, page 1 and mesh 1 intact
            final List<AtlasTexture> atlasesAfterUndo = model.textures().textureAtlases();
            final boolean undoRemovedPage2 = atlasesAfterUndo.size() == 1
                    && atlasesAfterUndo.get(0).id().equals(page1Id);
            report.put("undoRemovedPage2", undoRemovedPage2);
            if (!undoRemovedPage2) {
                throw new IllegalStateException("Undo failed to revert to 1 page: " + atlasesAfterUndo.size());
            }

            boolean mesh2RemovedOnUndo = false;
            try {
                model.drawables().find(mesh2.id());
            } catch (NoSuchElementException expected) {
                mesh2RemovedOnUndo = true;
            }
            report.put("mesh2RemovedOnUndo", mesh2RemovedOnUndo);
            if (!mesh2RemovedOnUndo) {
                throw new IllegalStateException("Mesh 2 still exists after Undo.");
            }

            // Redo Phase 2 by 1 step
            final HistoryMoveResult redoResult = history.redo(1);
            report.put("redoOutcome", redoResult.outcome().name());
            if (redoResult.outcome() != HistoryMoveResult.Outcome.MOVED) {
                throw new IllegalStateException("Redo failed: " + redoResult.outcome());
            }

            final List<AtlasTexture> atlasesAfterRedo = model.textures().textureAtlases();
            final boolean redoRestoredPage2 = atlasesAfterRedo.size() == 2
                    && atlasesAfterRedo.get(1).id().equals(page2Id);
            report.put("redoRestoredPage2", redoRestoredPage2);
            if (!redoRestoredPage2) {
                throw new IllegalStateException("Redo failed to restore page 2: " + atlasesAfterRedo.size());
            }

            // Phase 3: Native Bake Verification via exportMoc (minimal in-memory sampling)
            final CubismMocExportSettings exportSettings = new CubismMocExportSettings(
                    512f, 512f, 100f, true, true, true, true, false);
            final CubismMocExport mocExport = model.exportMoc(exportSettings);
            final List<CubismExportTexture> exportTextures = mocExport.textures();
            if (exportTextures.size() != 2) {
                throw new IllegalStateException("Expected 2 exported textures, got: " + exportTextures.size());
            }

            // Page 1 center sample: placed at (10, 10) 256x256 -> center at (138, 138)
            final CubismExportTexture tex1 = exportTextures.get(0);
            if (tex1.width() != 1024 || tex1.height() != 1024) {
                throw new IllegalStateException("Page 1 export texture size differs: " + tex1.width() + "x" + tex1.height());
            }
            final int pixel1 = tex1.argb()[138 * 1024 + 138];
            final int a1 = (pixel1 >>> 24) & 0xff;
            final int r1 = (pixel1 >>> 16) & 0xff;
            final int g1 = (pixel1 >>> 8) & 0xff;
            final int b1 = pixel1 & 0xff;
            // Primary color was (40, 80, 160)
            if (a1 < 250 || Math.abs(r1 - 40) > 15 || Math.abs(g1 - 80) > 15 || Math.abs(b1 - 160) > 15) {
                throw new IllegalStateException(String.format(
                        "Page 1 native bake color mismatch: argb=0x%08x (expected ~40, 80, 160)", pixel1));
            }

            // Page 2 center sample: placed at (20, 20) 256x256 -> center at (148, 148)
            final CubismExportTexture tex2 = exportTextures.get(1);
            if (tex2.width() != 1024 || tex2.height() != 1024) {
                throw new IllegalStateException("Page 2 export texture size differs: " + tex2.width() + "x" + tex2.height());
            }
            final int pixel2 = tex2.argb()[148 * 1024 + 148];
            final int a2 = (pixel2 >>> 24) & 0xff;
            final int r2 = (pixel2 >>> 16) & 0xff;
            final int g2 = (pixel2 >>> 8) & 0xff;
            final int b2 = pixel2 & 0xff;
            // Primary color was (160, 40, 80)
            if (a2 < 250 || Math.abs(r2 - 160) > 15 || Math.abs(g2 - 40) > 15 || Math.abs(b2 - 80) > 15) {
                throw new IllegalStateException(String.format(
                        "Page 2 native bake color mismatch: argb=0x%08x (expected ~160, 40, 80)", pixel2));
            }
            report.put("nativeBakeSamplingVerified", true);

            // Phase 4: Failure Transaction Rollback
            final HistorySnapshot hBeforeFail = history.snapshot();
            final int rawImagesBefore = model.textures().rawImages().size();
            final int modelImagesBefore = countModelImages(model);

            final ArtMeshGeometry geo3 = SyntheticAssets.quadGeometry(700f, 100f, 256f, 256f);
            final AuthorizedImageContent img3 = SyntheticAssets.generateAuthorizedImage(
                    "SyntheticTexture3", 256, 256, new Color(40, 160, 80), Color.WHITE);
            final ArtMeshId[] mesh3IdHolder = new ArtMeshId[1];

            final AuthoringTransactionResult<Void> failTxResult = txService.execute(
                    AuthoringTransactionOptions.of("FailingTx"),
                    () -> {
                        final Drawable mesh3 = model.drawables().create("ProbeMesh3", rootPart, -1, geo3);
                        mesh3IdHolder[0] = mesh3.id();
                        final ModelImageBinding bind3 = model.textures().registerImageAndBind(img3, mesh3.id());
                        final AtlasPageAppendPlan plan3 = new AtlasPageAppendPlan(
                                1024,
                                1024,
                                1,
                                List.of("ProbePage3"),
                                List.of(new ModelImagePlacement(bind3.modelImageId(), 0, 30, 30, 256, 256)));
                        model.textures().appendTextureAtlasPages(plan3);
                        throw new RuntimeException("Intentional failure to verify rollback");
                    });

            final boolean rolledBack = failTxResult.outcome() == AuthoringTransactionOutcome.ROLLED_BACK;
            report.put("transactionRolledBack", rolledBack);
            if (!rolledBack) {
                throw new IllegalStateException("Failure transaction did not roll back: " + failTxResult.outcome());
            }

            final HistorySnapshot hAfterFail = history.snapshot();
            final boolean historyPositionUnchanged = hAfterFail.position() == hBeforeFail.position();
            report.put("historyPositionUnchangedOnRollback", historyPositionUnchanged);
            if (!historyPositionUnchanged) {
                throw new IllegalStateException("History position changed on rolled-back transaction: "
                        + hBeforeFail.position() + " -> " + hAfterFail.position());
            }

            final boolean rollbackPreservedAtlasCount = model.textures().textureAtlases().size() == 2;
            report.put("rollbackPreservedAtlasCount", rollbackPreservedAtlasCount);
            if (!rollbackPreservedAtlasCount) {
                throw new IllegalStateException("Rollback left extra atlas page: " + model.textures().textureAtlases().size());
            }

            boolean mesh3Discarded = false;
            try {
                if (mesh3IdHolder[0] != null) {
                    model.drawables().find(mesh3IdHolder[0]);
                } else {
                    mesh3Discarded = true;
                }
            } catch (NoSuchElementException expected) {
                mesh3Discarded = true;
            }
            report.put("mesh3DiscardedOnRollback", mesh3Discarded);
            if (!mesh3Discarded) {
                throw new IllegalStateException("Mesh 3 survived rolled-back transaction.");
            }

            final boolean rawImagesDiscarded = model.textures().rawImages().size() == rawImagesBefore;
            report.put("rawImagesDiscardedOnRollback", rawImagesDiscarded);
            if (!rawImagesDiscarded) {
                throw new IllegalStateException("Raw images survived rolled-back transaction.");
            }

            final boolean modelImagesDiscarded = countModelImages(model) == modelImagesBefore;
            report.put("modelImagesDiscardedOnRollback", modelImagesDiscarded);
            if (!modelImagesDiscarded) {
                throw new IllegalStateException("Model images survived rolled-back transaction.");
            }

            // Phase 5: Scope boundary notes
            report.put("cmo3PersistenceReopenVerified", false);
            report.put("probeNote", "UV invariance, native bake sampling, single-root Undo/Redo and rollback verified; full CMO3 reopen file persistence excluded by minimal probe contract.");

            report.put("status", "PASS");
            writeSuccess();
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            report.put("status", "FAIL");
            report.put("failure", failure.getMessage() == null ? failure.toString() : failure.getMessage());
            writeFailure(failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    private static int countModelImages(final CubismModel model) {
        int count = 0;
        for (final ModelImageGroup g : model.textures().modelImageGroups()) {
            count += g.modelImages().size();
        }
        return count;
    }

    private static List<Float> readUvs(final Drawable mesh) {
        final List<Float> uvs = new ArrayList<>();
        for (final var pt : mesh.authoringSnapshot().sourceGeometry().uvs()) {
            uvs.add(pt.x());
            uvs.add(pt.y());
        }
        return uvs;
    }

    private void writeSuccess() {
        writeReportArtifacts("PASS", null);
    }

    private void writeFailure(final String message) {
        writeReportArtifacts("FAIL", message);
    }

    private void writeReportArtifacts(final String status, final String failureMessage) {
        try {
            final Path stateDir = context.paths().stateDir();
            Files.createDirectories(stateDir);

            // 1. Write properties file
            final Path propsFile = stateDir.resolve("result.properties");
            final StringBuilder props = new StringBuilder();
            props.append("status=").append(status).append("\n");
            props.append("schemaVersion=auto-atlas-probe-v1\n");
            if (failureMessage != null) {
                props.append("failure=").append(escapeProperties(failureMessage)).append("\n");
            }
            for (final var entry : report.entrySet()) {
                if (!"status".equals(entry.getKey()) && !"failure".equals(entry.getKey())) {
                    props.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
                }
            }
            Files.writeString(propsFile, props.toString());

            // 2. Write JSON file with safe string escaping
            final Path jsonFile = stateDir.resolve("auto-atlas-probe-report.json");
            final StringBuilder json = new StringBuilder("{\n");
            int idx = 0;
            for (final var entry : report.entrySet()) {
                if (idx++ > 0) json.append(",\n");
                json.append("  \"").append(escapeJson(entry.getKey())).append("\": ");
                final Object val = entry.getValue();
                if (val instanceof String s) {
                    json.append("\"").append(escapeJson(s)).append("\"");
                } else if (val instanceof Number || val instanceof Boolean) {
                    json.append(val);
                } else if (val instanceof List<?> list) {
                    json.append("[");
                    for (int l = 0; l < list.size(); l++) {
                        if (l > 0) json.append(", ");
                        final Object item = list.get(l);
                        if (item instanceof Number || item instanceof Boolean) {
                            json.append(item);
                        } else {
                            json.append("\"").append(escapeJson(String.valueOf(item))).append("\"");
                        }
                    }
                    json.append("]");
                } else {
                    json.append("\"").append(escapeJson(String.valueOf(val))).append("\"");
                }
            }
            json.append("\n}\n");
            Files.writeString(jsonFile, json.toString());

            System.out.println("[AUTO_ATLAS_PROBE] Report artifacts written successfully to " + stateDir);
        } catch (IOException ioError) {
            System.err.println("[AUTO_ATLAS_PROBE] FATAL: Failed to write report artifacts: " + ioError.getMessage());
            ioError.printStackTrace(System.err);
        }
    }

    private static String escapeProperties(final String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String escapeJson(final String s) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
