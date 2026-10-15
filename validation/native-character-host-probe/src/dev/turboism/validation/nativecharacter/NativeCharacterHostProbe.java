package dev.turboism.validation.nativecharacter;

import dev.turboism.sdk.action.ActionCatalogService;
import dev.turboism.sdk.cubism.command.*;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.plugin.*;
import dev.turboism.sdk.ui.*;
import java.awt.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Test-only production menu, native geometry, Undo/Redo, rotation and official save probe. */
public final class NativeCharacterHostProbe implements TurboismPlugin {
    private PluginContext context;
    private volatile Thread worker;
    private final Properties report = new Properties();
    private JOptionPane lastPane;

    @Override public void init(PluginContext value) { context = value; }
    @Override public void enable() {
        worker = new Thread(this::verify, "native-character-host-probe");
        worker.setDaemon(true);
        worker.start();
    }
    @Override public void disable() { if (worker != null) worker.interrupt(); }

    private void verify() {
        report.setProperty("schemaVersion", "1");
        report.setProperty("runId", System.getProperty("turboism.validation.runId", "unknown"));
        try {
            Path state = context.paths().stateDir();
            if (Boolean.getBoolean("turboism.character.reopen")) {
                verifyReopen(state);
                report.setProperty("status", "PASS");
                return;
            }
            Path input = Path.of(System.getProperty("turboism.character.fixture"));
            Files.createDirectories(state);
            report.setProperty("source.sha256", hash(input));
            report.setProperty("source.bytes", "" + Files.size(input));
            await(() -> edt(() -> context.cubism().activeDocument().isPresent()), 90, "active document");
            String seed = edt(() -> context.cubism().activeDocument().orElseThrow().documentId());
            var commands = context.services().require(EditorCommandService.class);
            check("setup.newModel", true, edt(() -> commands.execute(EditorCommand.NEW_MODEL).executed()));
            await(() -> edt(() -> context.cubism().activeDocument().isPresent()
                    && !context.cubism().activeDocument().orElseThrow().documentId().equals(seed)), 60, "new model document");
            check("setup.canvas", true, edt(() -> commands.execute(new EditorCanvasSettingsRequest(1024, 1024)).executed()));
            String document = edt(() -> context.cubism().activeDocument().orElseThrow().documentId());
            report.setProperty("document.identity", document);
            Set<ArtMeshId> before = edt(this::ids);
            int oldPages = edt(() -> model().textures().textureAtlases().size());
            var oldParameterIds = edt(() -> model().parameters().all().stream().map(Parameter::id).collect(java.util.stream.Collectors.toSet()));
            var oldGroupIds = edt(() -> model().parameterGroups().all().stream().map(ParameterGroup::id).collect(java.util.stream.Collectors.toSet()));
            int oldParameters = oldParameterIds.size();
            var catalog = context.services().require(ActionCatalogService.class);
            await(() -> catalog.actions().stream().anyMatch(a -> a.pluginId().equals("dev.turboism.plugin.model-import-3d")
                    && a.actionId().equals("model-import-3d.import")), 60, "production import action");
            stage("production-menu");
            catalog.invoke("dev.turboism.plugin.model-import-3d", "model-import-3d.import");
            var appearance = awaitPane(30);
            if (edt(appearance::getMessageType) == JOptionPane.ERROR_MESSAGE)
                throw new AssertionError("Import setup failed: " + edt(() -> String.valueOf(appearance.getMessage())));
            edt(() -> { appearance.setValue(appearance.getOptions()[0]); return null; });
            stage("choosing-source");
            approveFile(input, 30);
            stage("preparing-source");
            var confirmation = awaitPane(Integer.getInteger("turboism.character.planningTimeoutSeconds", 180));
            if (edt(confirmation::getMessageType) == JOptionPane.ERROR_MESSAGE)
                throw new AssertionError("Import planning failed: " + edt(() -> String.valueOf(confirmation.getMessage())));
            stage("native-authoring");
            edt(() -> { confirmation.setValue(JOptionPane.OK_OPTION); return null; });
            await(() -> edt(() -> {
                var error = visible(JOptionPane.class);
                if (error != null && error.getMessageType() == JOptionPane.ERROR_MESSAGE)
                    throw new AssertionError("Native authoring failed: " + error.getMessage());
                return ids().size() > before.size();
            }), Integer.getInteger("turboism.character.authoringTimeoutSeconds", 360), "native model write");
            var success = awaitPane(60);
            if (edt(success::getMessageType) == JOptionPane.ERROR_MESSAGE)
                throw new AssertionError("Native authoring failed: " + edt(() -> String.valueOf(success.getMessage())));
            edt(() -> { success.setValue(JOptionPane.OK_OPTION); return null; });
            stage("verifying-native");
            Set<ArtMeshId> imported = edt(this::ids);
            imported.removeAll(before);
            report.setProperty("entrypoint", "production import action and real SDK chooser");
            report.setProperty("native.artMeshes", "" + imported.size());
            long triangles = edt(() -> imported.stream().map(model().drawables()::find)
                    .mapToLong(mesh -> mesh.geometry().triangleIndices().size() / 3).sum());
            check("native.triangles", Long.getLong("turboism.character.expectedTriangles", 121097L), triangles);
            check("native.parameters.added", 3+Integer.getInteger("turboism.character.expectedControls",0), edt(() -> model().parameters().all().size()) - oldParameters);
            var addedGroups = edt(() -> model().parameterGroups().all().stream().filter(g -> !oldGroupIds.contains(g.id())).toList());
            check("native.parameterGroups.added", 1, addedGroups.size());
            var importedParameters = edt(() -> model().parameters().all().stream().map(Parameter::id).filter(id -> !oldParameterIds.contains(id)).collect(java.util.stream.Collectors.toSet()));
            check("native.parameterGroup.members", importedParameters, new java.util.HashSet<>(addedGroups.get(0).parameterIds()));
            String sourceName = input.getFileName().toString();
            int extension = sourceName.lastIndexOf('.');
            if(extension > 0) sourceName = sourceName.substring(0, extension);
            check("native.parameterGroup.name", sourceName, addedGroups.get(0).name().orElseThrow());
            report.setProperty("native.parameterGroup.id", addedGroups.get(0).id().value());
            report.setProperty("native.parameterGroup.name", addedGroups.get(0).name().orElseThrow());
            report.setProperty("native.parameterGroup.parameters", importedParameters.stream().map(id -> id.value()).sorted().collect(java.util.stream.Collectors.joining(",")));
            check("native.parameterIds.scoped", true, importedParameters.stream().allMatch(id -> id.value().startsWith("Param3D_")));
            check("native.atlas.created", true, edt(() -> model().textures().textureAtlases().size()) > oldPages);
            for (ArtMeshId id : imported) {
                check("atlas.binding." + id.value(), ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION,
                        edt(() -> model().textures().textureInput(id).orElseThrow().kind()));
            }
            if (Boolean.getBoolean("turboism.character.manualInspection")) {
                capture(state.resolve("manual-inspection-neutral.png"));
                check("source.hash.unchanged", report.getProperty("source.sha256"), hash(input));
                report.setProperty("manualInspection", "PENDING: operator checks the live imported model");
                report.setProperty("validationScope", "import structure only; no history, pose, persistence or visual acceptance");
                stage("manual-inspection-ready");
                report.setProperty("status", "PASS");
                return;
            }
            capture(state.resolve("native-before-undo.png"));
            stage("native-history");
            report.setProperty("history.beforeUndo", "" + edt(() -> context.cubism().history().snapshot()));
            check("undo.command", true, edt(() -> commands.execute(EditorCommand.UNDO).executed()));
            await(() -> edt(() -> ids().equals(before)), 30, "Undo model restoration");
            report.setProperty("history.afterUndo", "" + edt(() -> context.cubism().history().snapshot()));
            check("undo.meshes.restored", before, edt(this::ids));
            check("undo.atlas.restored", oldPages, edt(() -> model().textures().textureAtlases().size()));
            check("undo.parameters.restored", oldParameters, edt(() -> model().parameters().all().size()));
            await(() -> edt(() -> commands.available().contains(EditorCommand.REDO)), 30, "Redo menu readiness");
            var redoResult = edt(() -> commands.execute(EditorCommand.REDO));
            report.setProperty("redo.command.result", redoResult.toString());
            check("redo.command", true, redoResult.executed());
            report.setProperty("history.afterRedoDispatch", "" + edt(() -> context.cubism().history().snapshot()));
            await(() -> edt(() -> ids().containsAll(imported)), 30, "Redo model restoration");
            report.setProperty("history.afterRedo", "" + edt(() -> context.cubism().history().snapshot()));
            var restored = edt(this::ids);
            restored.removeAll(before);
            check("redo.meshes.restored", imported, restored);
            stage("native-rotation");
            List<Parameter> axes = edt(() -> model().parameters().all().stream()
                    .filter(p -> p.type() == ParameterType.NORMAL && p.id().value().startsWith("Param3D")).toList());
            capture(state.resolve("native-neutral.png"));
            for (String axis : List.of("Yaw", "Pitch", "Roll")) {
                Parameter parameter = axes.stream().filter(p -> rotationParameter(p, axis))
                        .findFirst().orElseThrow();
                var neutralPositions = edt(() -> imported.stream().map(id -> model().drawables().find(id).geometry().positions()).toList());
                edt(() -> { parameter.setValue(90); return null; });
                check("rotation." + axis + ".value", 90f, edt(parameter::getValue));
                Thread.sleep(600);
                if (!axis.equals("Roll")) {
                    var rotatedPositions = edt(() -> imported.stream().map(id -> model().drawables().find(id).geometry().positions()).toList());
                    check("rotation." + axis + ".geometryChanged", true, !neutralPositions.equals(rotatedPositions));
                } else {
                    RotationDeformer rotation = edt(() -> model().rotationDeformers().all().stream()
                            .filter(d -> d.authoringSnapshot().axes().stream().anyMatch(a -> a.parameterId().equals(parameter.id()))).findFirst().orElseThrow());
                    check("rotation.Roll.angle", -90f, edt(() -> rotation.form().angle()));
                }
                capture(state.resolve("native-" + axis + "-90.png"));
                edt(() -> { parameter.setValue(0); return null; });
            }
            await(() -> edt(() -> imported.stream().filter(id -> model().drawables().find(id).visible()).map(id -> model().drawables().find(id).evaluationSnapshot())
                    .allMatch(e -> e.calculatedForm().isPresent() && e.liveParameters().parameters().equals(e.consumedParameters().parameters()))),
                    30, "neutral pose consumption before opacity");
            if(Boolean.getBoolean("turboism.character.sourceControls")) verifySourceControls();
            stage("native-opacity");
            var visibleMesh = edt(() -> imported.stream().map(id -> model().drawables().find(id))
                    .filter(m -> m.visible()).findFirst().orElseThrow());
            var outfitParent = edt(() -> model().deformers().find(visibleMesh.parentDeformerId().orElseThrow()));
            var opacityParent = edt(() -> model().deformers().find(outfitParent.parentDeformerId().orElseThrow()));
            report.setProperty("opacity.parent.name", edt(opacityParent::name));
            var originalBank = edt(visibleMesh::authoringSnapshot);
            var visiblePixels = edt(() -> model().renderPixels(1024, 1024));
            edt(() -> { opacityParent.setOpacity(0); return null; });
            check("opacity.hidden", 0f, edt(opacityParent::getOpacity));
            Thread.sleep(700);
            var hiddenPixels = edt(() -> model().renderPixels(1024, 1024));
            check("opacity.pixelsChanged", true, !java.util.Arrays.equals(visiblePixels.argb(), hiddenPixels.argb()));
            capture(state.resolve("native-group-hidden.png"));
            edt(() -> { opacityParent.setOpacity(1); return null; });
            check("opacity.restored", 1f, edt(opacityParent::getOpacity));
            check("opacity.banksPreserved", originalBank, edt(visibleMesh::authoringSnapshot));
            stage("native-save");
            Files.createDirectories(state.resolve("persistence"));
            Path output = state.resolve("persistence/native-character.cmo3");
            if (Files.exists(output)) throw new AssertionError("Save target already exists");
            var grant = context.userFiles().request(new UserFileRequest("native-character-save", "Save native character",
                    List.of("cmo3"), UserFileMode.WRITE, UserFileLifetime.ONE_OPERATION)).toCompletableFuture();
            var granted = grant.get(30, TimeUnit.SECONDS);
            if (granted.status() != UserFileRequestStatus.GRANTED) throw new AssertionError("Save grant denied");
            try (var handle = granted.handle().orElseThrow()) {
                check("save.command", true, edt(() -> {
                    if (!document.equals(context.cubism().activeDocument().orElseThrow().documentId()))
                        throw new AssertionError("Document switched");
                    return commands.execute(new EditorFileCommandRequest(EditorFileCommand.SAVE_AS, handle,
                            EditorOverwritePolicy.REJECT_EXISTING)).executed();
                }));
            }
            check("save.artifact.nonempty", true, Files.size(output) > 0);
            report.setProperty("native.ids", imported.stream().map(ArtMeshId::value).sorted().reduce((a,b) -> a + "," + b).orElseThrow());
            report.setProperty("native.banks.sha256", edt(() -> banksHash(imported)));
            if(Boolean.getBoolean("turboism.character.sourceControls"))report.setProperty("native.morphs.sha256",edt(this::morphsHash));
            report.setProperty("native.hidden", "" + edt(() -> imported.stream().filter(id -> !model().drawables().find(id).visible()).count()));
            report.setProperty("save.sha256", hash(output));
            report.setProperty("save.bytes", "" + Files.size(output));
            report.setProperty("persistence.reopen", "PENDING: requires separate fresh-process verification");
            check("source.hash.unchanged", report.getProperty("source.sha256"), hash(input));
            report.setProperty("status", "PASS");
        } catch (Throwable failure) {
            report.setProperty("status", "FAIL");
            report.setProperty("failure", failure.toString());
            context.logger().error("Native character probe failed", failure);
        } finally {
            try {
                Path state = context.paths().stateDir();
                Files.createDirectories(state);
                try (var out = Files.newOutputStream(state.resolve("result.properties"))) {
                    report.store(out, "Native character import stage; reopen acceptance is separate");
                }
                String runId = System.getProperty("turboism.validation.runId", "");
                if (Boolean.getBoolean("turboism.character.exitAfterProbe") && runId.matches("queue-[a-f0-9]{32}")
                        && state.toString().contains(runId)) {
                    Thread.sleep(2000);
                    System.exit(0);
                }
            } catch (Exception failure) { context.logger().error("Native probe result failed", failure); }
        }
    }

    private void capture(Path path) throws Exception {
        var pixels = edt(() -> model().renderPixels(1024, 1024));
        var image = new java.awt.image.BufferedImage(1024, 1024, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 1024, 1024, pixels.argb(), 0, 1024);
        if (!ImageIO.write(image, "png", path.toFile())) throw new AssertionError("Native PNG encoding unavailable");
    }

    private static boolean rotationParameter(Parameter parameter, String axis) {
        String id = parameter.id().value();
        return id.startsWith("Param3D" + axis) || id.startsWith("Param3D_") && id.endsWith("_" + axis);
    }

    private void stage(String name) throws Exception {
        report.setProperty("stage." + name + ".epochMillis", "" + System.currentTimeMillis());
        context.logger().info("NATIVE_CHARACTER_STAGE " + name);
        report.setProperty("stage", name);
        try (var out = Files.newOutputStream(context.paths().stateDir().resolve("progress.properties"))) {
            report.store(out, "Nonterminal native character progress");
        }
    }

    private void verifyReopen(Path state) throws Exception {
        Properties expected = new Properties();
        try (var in = Files.newInputStream(Path.of(System.getProperty("turboism.character.expected")))) {
            expected.load(in);
        }
        await(() -> edt(() -> context.cubism().activeDocument().isPresent()), 90, "reopened document");
        Set<ArtMeshId> imported = new LinkedHashSet<>();
        for (String id : expected.getProperty("native.ids").split(",")) imported.add(new ArtMeshId(id));
        check("reopen.meshes.present", true, edt(() -> ids().containsAll(imported)));
        check("reopen.banks.sha256", expected.getProperty("native.banks.sha256"), edt(() -> banksHash(imported)));
        check("reopen.hidden", expected.getProperty("native.hidden"), "" + edt(() -> imported.stream().filter(id -> !model().drawables().find(id).visible()).count()));
        for (ArtMeshId id : imported) {
            check("reopen.atlas." + id.value(), ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION,
                    edt(() -> model().textures().textureInput(id).orElseThrow().kind()));
        }
        check("reopen.rotation.parameters", 3L, edt(() -> model().parameters().all().stream().filter(p -> p.type() == ParameterType.NORMAL && p.id().value().startsWith("Param3D")).count()));
        if(expected.getProperty("native.parameterGroup.id")!=null) {
            var group = edt(() -> model().parameterGroups().find(new dev.turboism.sdk.cubism.id.ParameterGroupId(expected.getProperty("native.parameterGroup.id"))));
            check("reopen.parameterGroup.name", expected.getProperty("native.parameterGroup.name"), group.name().orElseThrow());
            check("reopen.parameterGroup.parameters", expected.getProperty("native.parameterGroup.parameters"), group.parameterIds().stream().map(id -> id.value()).sorted().collect(java.util.stream.Collectors.joining(",")));
        }
        if(expected.getProperty("native.morphs.sha256")!=null) {
            check("reopen.morphs.sha256",expected.getProperty("native.morphs.sha256"),edt(this::morphsHash));
            verifySourceControls();
        }
        for (String part : expected.getProperty("native.morphs.sha256")!=null?List.<String>of():List.of("Left leg", "Right leg")) {
            var body = edt(() -> model().warpDeformers().all().stream().filter(d -> d.name().equals(part)).findFirst().orElseThrow());
            check("reopen." + part + ".grid", 9, edt(() -> body.grid().controlPoints().size()));
            check("reopen." + part + ".opacity", 1f, edt(body::getOpacity));
            long outfits = edt(() -> model().warpDeformers().all().stream()
                    .filter(d -> d.parentDeformerId().equals(Optional.of(body.id()))).count());
            check("reopen." + part + ".sharedOutfits", true, outfits >= 2);
        }
        capture(state.resolve("native-reopened.png"));
        report.setProperty("persistence.reopen", "PASS");
    }

    private void verifySourceControls() throws Exception {
        var controls=edt(()->model().parameters().all().stream().filter(p->p.id().value().contains("_Control_")).toList());
        check("controls.count",Integer.getInteger("turboism.character.expectedControls",2),controls.size());
        for(var parameter:controls) {
            check("controls.type."+parameter.id().value(),ParameterType.BLEND_SHAPE,edt(parameter::type));
            check("controls.default."+parameter.id().value(),0f,edt(parameter::getValue));
            var baseline=edt(()->model().renderPixels(1024,1024).argb());
            float endpoint=edt(parameter::getMaximumValue);
            if(endpoint==0)endpoint=edt(parameter::getMinimumValue);
            final float requested=endpoint;
            edt(()->{parameter.setValue(requested);return null;});
            check("controls.value."+parameter.id().value(),requested,edt(parameter::getValue));
            Thread.sleep(600);
            var moved=edt(()->model().renderPixels(1024,1024).argb());
            check("controls.pixels."+parameter.id().value(),true,!Arrays.equals(baseline,moved));
            edt(()->{parameter.setValue(0);return null;});
            Thread.sleep(500);
            check("controls.restored."+parameter.id().value(),true,Arrays.equals(baseline,edt(()->model().renderPixels(1024,1024).argb())));
        }
        if (Boolean.getBoolean("turboism.character.combinations")) verifyCombinations(controls);
        report.setProperty("controls.nativeMorphKeys",""+edt(()->model().warpDeformers().all().stream().mapToInt(w->w.morphSnapshot().keys().size()).sum()));
        check("controls.nativeMorphKeys.present",true,Integer.parseInt(report.getProperty("controls.nativeMorphKeys"))>0);
    }

    private void verifyCombinations(List<Parameter> controls) throws Exception {
        var byName = new HashMap<String, Parameter>();
        for (var control : controls) byName.put(edt(() -> control.name().orElseThrow()), control);
        for (String name : List.of("Shift X", "Shift Y", "Factor A", "Factor B"))
            check("combination.parameter." + name, true, byName.containsKey(name));
        var yaw = edt(() -> model().parameters().all().stream()
                .filter(p -> rotationParameter(p, "Yaw")).findFirst().orElseThrow());
        var neutral = edt(() -> model().renderPixels(1024, 1024).argb());
        var banks = edt(() -> banksHash(ids()));
        var morphs = edt(this::morphsHash);
        try {
            for (float angle : new float[] {-30, 0, 30}) {
                edt(() -> {
                    yaw.setValue(angle);
                    byName.get("Shift X").setValue(1);
                    byName.get("Shift Y").setValue(1);
                    byName.get("Factor A").setValue(0);
                    byName.get("Factor B").setValue(0);
                    return null;
                });
                Thread.sleep(600);
                var opaque = edt(() -> model().renderPixels(1024, 1024).argb());
                long denominator = alphaSum(opaque);
                check("combination." + angle + ".nonempty", true, denominator > 0);
                for (int mask = 1; mask <= 3; mask++) {
                    final int factors = mask;
                    edt(() -> {
                        byName.get("Factor A").setValue((factors & 1) == 0 ? 0 : 1);
                        byName.get("Factor B").setValue((factors & 2) == 0 ? 0 : 1);
                        return null;
                    });
                    Thread.sleep(500);
                    long numerator = alphaSum(edt(() -> model().renderPixels(1024, 1024).argb()));
                    double expected = mask == 1 ? .5 : mask == 2 ? .25 : .125;
                    double actual = (double) numerator / denominator;
                    String key = "combination." + angle + ".factor" + mask;
                    report.setProperty(key + ".alphaRatio", "" + actual);
                    report.setProperty(key + ".expectedRatio", "" + expected);
                    check(key + ".withinTolerance", true, Math.abs(actual - expected) < .02);
                }
            }
        } finally {
            edt(() -> {
                for (var control : controls) control.setValue(0);
                yaw.setValue(0);
                return null;
            });
        }
        Thread.sleep(600);
        check("combination.zeroRestoresPixels", true,
                Arrays.equals(neutral, edt(() -> model().renderPixels(1024, 1024).argb())));
        check("combination.banksUnchanged", banks, edt(() -> banksHash(ids())));
        check("combination.morphsUnchanged", morphs, edt(this::morphsHash));
        verifyCreatedWarpRollback(controls.get(0));
    }

    private void verifyCreatedWarpRollback(Parameter parameter) throws Exception {
        var before = edt(() -> model().deformers().all().stream().map(Deformer::id).toList());
        var history = edt(() -> context.cubism().history().snapshot());
        var banks = edt(() -> banksHash(ids()));
        var morphs = edt(this::morphsHash);
        var result = edt(() -> context.cubism().authoringTransactions().execute(
                dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions.of("Probe Warp rollback"), () -> {
                    var warp = model().deformers().createWarp("Probe temporary Warp", null, -1, 2, 2);
                    warp.createOpacityMorphKeyform(parameter.id(), 1, .5f);
                    throw new IllegalStateException("Intentional probe failure after Warp morph creation");
                }));
        report.setProperty("rollback.diagnostic", "" + result.diagnosticId());
        check("rollback.createdWarp.outcome",
                dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
        check("rollback.deformersRestored", before,
                edt(() -> model().deformers().all().stream().map(Deformer::id).toList()));
        check("rollback.historyRestored", history, edt(() -> context.cubism().history().snapshot()));
        check("rollback.banksRestored", banks, edt(() -> banksHash(ids())));
        check("rollback.morphsRestored", morphs, edt(this::morphsHash));
    }

    private static long alphaSum(int[] pixels) {
        long sum = 0;
        for (int pixel : pixels) sum += pixel >>> 24;
        return sum;
    }

    private String morphsHash() throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        for(var warp:model().warpDeformers().all().stream().sorted(Comparator.comparing(w->w.id().value())).toList()) {
            digest.update(warp.id().value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(warp.morphSnapshot().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String banksHash(Set<ArtMeshId> imported) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (ArtMeshId id : imported.stream().sorted(Comparator.comparing(ArtMeshId::value)).toList()) {
            var snapshot = model().drawables().find(id).authoringSnapshot();
            digest.update(snapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private CubismModel model() { return context.cubism().model().active(); }
    private Set<ArtMeshId> ids() {
        Set<ArtMeshId> ids = new LinkedHashSet<>();
        for (Drawable mesh : model().drawables().all()) ids.add(mesh.id());
        return ids;
    }
    private void check(String key, Object expected, Object actual) {
        report.setProperty(key + ".expected", "" + expected);
        report.setProperty(key + ".actual", "" + actual);
        report.setProperty(key + ".status", Objects.equals(expected, actual) ? "PASS" : "FAIL");
        if (!Objects.equals(expected, actual)) throw new AssertionError(key + ": expected " + expected + ", actual " + actual);
    }
    private static <T> T edt(Callable<T> task) {
        var future = new FutureTask<T>(task);
        try { SwingUtilities.invokeAndWait(future); return future.get(); }
        catch (Exception failure) { throw new CompletionException(failure); }
    }
    private static void await(Supplier<Boolean> condition, int seconds, String what) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        do { if (condition.get()) return; Thread.sleep(100); } while (System.nanoTime() < end);
        throw new AssertionError("Timed out waiting for " + what);
    }
    private static <T extends Component> T find(Component root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof Container parent) for (Component child : parent.getComponents()) {
            T result = find(child, type); if (result != null) return result;
        }
        return null;
    }
    private static <T extends Component> T visible(Class<T> type) {
        for (Window window : Window.getWindows()) if (window.isShowing()) {
            T found = find(window, type); if (found != null) return found;
        }
        return null;
    }
    private JOptionPane awaitPane(int seconds) throws Exception {
        await(() -> edt(() -> {
            var pane = visible(JOptionPane.class);
            return pane != null && pane != lastPane;
        }), seconds, "production dialog");
        lastPane = edt(() -> visible(JOptionPane.class));
        return lastPane;
    }
    private static void approveFile(Path path, int seconds) throws Exception {
        await(() -> edt(() -> visible(JFileChooser.class) != null), seconds, "SDK file chooser");
        edt(() -> { JFileChooser chooser = visible(JFileChooser.class);
            chooser.setSelectedFile(path.toFile()); chooser.approveSelection(); return null; });
    }
    private static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
