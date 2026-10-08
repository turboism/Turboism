package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.EditorCanvasSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ArtMeshKeyform;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInput;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.Canvas;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismRenderedPixels;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.InstanceRenderType;
import dev.turboism.sdk.cubism.model.ModelImageBinding;
import dev.turboism.sdk.cubism.model.ModelInstance;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBindingOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.UserFileError;
import dev.turboism.sdk.ui.UserFileErrorCode;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileHandleState;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileReadResult;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestResult;
import dev.turboism.sdk.ui.UserFileRequestStatus;
import dev.turboism.sdk.ui.UserFileWriteResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * F6 real-producer integration: an actual {@code PixelSamplingProbe.run}
 * produces an actual {@code NativeReport.json} section and the actual seven
 * PNG artifacts, then the real Python oracle verifies them — and precise
 * semantic mutations against the real artifacts are rejected for the exact
 * reasons. The synthetic host below is not native evidence; it supplies a
 * deterministic model so the producer/oracle wiring itself is exercised end
 * to end instead of helper methods only.
 */
final class PixelSamplingProducerIntegrationTest {

    private static int checks;

    static void run() throws Exception {
        var host = new FakeHost();
        var data = new LinkedHashMap<String, Object>();
        data.put("schemaVersion", "mi3d-native-t008-v1");
        // Real artifacts are preserved in an ignored per-run directory so the
        // genuine report and the seven genuine PNGs can be inspected afterwards.
        var artifactRoot = Path.of("build/model-import-3d-host");
        Files.createDirectories(artifactRoot);
        var dir = Files.createTempDirectory(artifactRoot, "t008-producer-e2e-");
        var stateDir = dir.resolve("state"); Files.createDirectories(stateDir);
        // Normal leg: the save hook is off — userFiles is supplied but unused.
        PixelSamplingProbe.run(host::current, host::history, host::restore,
            host.commands, host.transactions, host.userFiles(stateDir, false),
            stateDir, data, null);
        check(data.get("status").equals("PASS"), "producer section not PASS: " + data.get("failure"));

        // The real report JSON and the real seven PNG artifacts on disk.
        var reportJson = "{\"pixelSampling\":" + NativeReport.json(data) + "}";
        var reportFile = dir.resolve("report.json");
        Files.writeString(reportFile, reportJson);
        var pngDir = stateDir.resolve("pixel-sampling");
        var pngNames = new ArrayList<String>();
        try (var stream = Files.list(pngDir)) {
            stream.forEach(p -> pngNames.add(p.getFileName().toString()));
        }
        var expected = List.of("baseline.png", "bound-m1.png", "bound-p0.png",
            "bound-p1.png", "blank.png", "rebound.png", "unbound.png");
        check(pngNames.size() == 7 && pngNames.containsAll(expected),
            "exactly seven preserved PNG artifacts");

        var verifier = verifierScript();
        // Positive: real report + real PNGs must pass the real oracle.
        var positive = oracle(verifier, reportFile, pngDir);
        check(positive == 0, "real producer artifacts rejected by oracle");

        // Semantic negatives against the REAL artifacts.
        // 1. Duplicate frame row must be rejected by the raw list rules.
        var dup = parse(reportJson);
        @SuppressWarnings("unchecked") var dupFrames =
            (List<Object>) ((Map<String, Object>) dup.get("pixelSampling")).get("frames");
        @SuppressWarnings("unchecked") var dupFrame = (Map<String, Object>) dupFrames.get(5);
        dupFrame.put("pose", "bound-p0");
        rejectReason(verifier, writeReport(dup), pngDir, "order");
        // 2. History document drift at equal position must fail the tuple compare.
        var drift = parse(reportJson);
        @SuppressWarnings("unchecked") var driftFrames =
            (List<Map<String, Object>>) ((Map<String, Object>) drift.get("pixelSampling")).get("frames");
        @SuppressWarnings("unchecked") var stateAfter =
            (Map<String, Object>) driftFrames.get(0).get("stateAfter");
        @SuppressWarnings("unchecked") var hist =
            (Map<String, Object>) stateAfter.get("history");
        hist.put("documentBindingId", "other-document");
        rejectReason(verifier, writeReport(drift), pngDir, "protected state changed");
        // 3. Invalid PNG CRC inside a real artifact (re-signed SHA) reaches PNG rules.
        var crcDir = copyPngs(dir, pngDir);
        var mutated = Files.readAllBytes(crcDir.resolve("baseline.png"));
        mutated[29] ^= 1;
        Files.write(crcDir.resolve("baseline.png"), mutated);
        Files.write(crcDir.resolve("unbound.png"), mutated);
        rejectReason(verifier, writeReport(resigned(parse(reportJson), crcDir)), crcDir, "CRC");
        // 4. Re-signed alpha-254 at a sampled patch pixel reaches the color oracle.
        var alphaDir = copyPngs(dir, pngDir);
        var bound = decode(alphaDir.resolve("bound-p0.png"));
        bound.setRGB(289, 417, (bound.getRGB(289, 417) & 0x00FFFFFF) | (254 << 24));
        writePng(bound, alphaDir.resolve("bound-p0.png"));
        Files.write(alphaDir.resolve("rebound.png"), Files.readAllBytes(alphaDir.resolve("bound-p0.png")));
        rejectReason(verifier, writeReport(resigned(parse(reportJson), alphaDir)), alphaDir,
            "patches");
        System.out.println("T008 PRODUCER_E2E checks=" + checks + " PASS; hostExecuted=false"
            + " artifacts=" + dir);

        persistenceSaveRun();
        persistenceReopenRun();
    }

    /**
     * T014 Job A: the pixel leg runs to completion, then the live fixture is
     * saved through the REAL async grant -> typed SAVE_AS -> reject-existing
     * chain against the pinned task path. The synthetic host supplies the
     * grant/command plumbing; the produced section and file go through the
     * real Python oracle.
     */
    private static void persistenceSaveRun() throws Exception {
        var host = new FakeHost();
        var data = new LinkedHashMap<String, Object>();
        data.put("schemaVersion", "mi3d-native-t008-v1");
        var save = new LinkedHashMap<String, Object>();
        var artifactRoot = Path.of("build/model-import-3d-host");
        Files.createDirectories(artifactRoot);
        var dir = Files.createTempDirectory(artifactRoot, "t014-save-e2e-");
        var stateDir = dir.resolve("state"); Files.createDirectories(stateDir);
        var target = stateDir.resolve("persistence").resolve("mi3d-t014-model.cmo3");
        PixelSamplingProbe.run(host::current, host::history, host::restore,
            host.commands, host.transactions, host.userFiles(stateDir, true),
            stateDir, data, save);
        check(data.get("status").equals("PASS"), "Job A pixel leg failed: " + data.get("failure"));
        check(save.get("status").equals("PASS"), "Job A save section failed: " + save.get("failure"));
        check(Files.isRegularFile(target), "native SAVE_AS did not create the pinned CMO3");
        var cmo3 = Files.readAllBytes(target);
        check(cmo3.length > 0 && sha256(cmo3).equals(save.get("artifactSha256")),
            "saved artifact hash/bytes not what the report claims");

        var report = parse("{\"mi3dMode\":\"persistence-save\",\"pixelSampling\":"
            + NativeReport.json(data)
            + ",\"persistenceSave\":" + NativeReport.json(save) + "}");
        var reportFile = dir.resolve("report.json");
        Files.writeString(reportFile, NativeReport.json(report));
        var pngDir = stateDir.resolve("pixel-sampling");
        var postPng = stateDir.resolve("persistence/post-save-p0.png");
        check(Files.isRegularFile(postPng), "post-save PNG artifact missing");
        var positive = oracleSection(verifierScript(), reportFile, pngDir,
            "verify_persistence_save", "report-plus-cmo3", target, postPng);
        check(positive == 0, "real Job A artifacts rejected by oracle");

        // Denied grant must fail closed through the same code path.
        var denied = new FakeHost();
        var deniedSave = new LinkedHashMap<String, Object>();
        var deniedDir = Files.createTempDirectory(artifactRoot, "t014-denied-");
        var deniedState = deniedDir.resolve("state"); Files.createDirectories(deniedState);
        var threw = false;
        try {
            PixelSamplingProbe.run(denied::current, denied::history, denied::restore,
                denied.commands, denied.transactions,
                denied.userFiles(deniedState, false), deniedState,
                new LinkedHashMap<String, Object>(), deniedSave);
        } catch (Throwable expected) { threw = true; }
        check(threw && deniedSave.get("status").equals("FAIL")
            && !Files.exists(deniedState.resolve("persistence/mi3d-t014-model.cmo3")),
            "denied save grant did not fail closed");
        System.out.println("T014 SAVE_E2E checks=" + checks + " PASS; hostExecuted=false"
            + " artifacts=" + dir);
    }

    /**
     * T014 Job B: a fresh-process host whose seed document IS the saved model —
     * identical persistent identities, new object instances, clean history.
     * The reopen leg re-resolves the fixture by content and re-samples the
     * three bound poses; the real oracle verifies the section and PNGs. The
     * pair oracle must then REJECT this JVM-local pair — the process gate
     * proves it detects a fake reopen, and the real cross-job proof is Job A
     * vs Job B run by the controller.
     */
    private static void persistenceReopenRun() throws Exception {
        var host = FakeHost.reopened();
        var data = new LinkedHashMap<String, Object>();
        var artifactRoot = Path.of("build/model-import-3d-host");
        var dir = Files.createTempDirectory(artifactRoot, "t014-reopen-e2e-");
        var stateDir = dir.resolve("state"); Files.createDirectories(stateDir);
        PixelSamplingProbe.reopen(host::current, host::history, stateDir, data);
        check(data.get("status").equals("PASS"), "Job B reopen leg failed: " + data.get("failure"));

        var report = parse("{\"mi3dMode\":\"persistence-reopen\",\"status\":\"PASS\","
            + "\"failure\":null,\"persistenceReopen\":" + NativeReport.json(data) + "}");
        var reportFile = dir.resolve("report.json");
        Files.writeString(reportFile, NativeReport.json(report));
        var pngDir = stateDir.resolve("pixel-sampling");
        var positive = oracleSection(verifierScript(), reportFile, pngDir,
            "verify_persistence_reopen", "report", null);
        check(positive == 0, "real Job B artifacts rejected by oracle");

        // A reopened document missing the fixture parameter must fail closed.
        var broken = FakeHost.reopened();
        broken.param = null;
        var threw = false;
        var brokenDir = Files.createTempDirectory(artifactRoot, "t014-broken-");
        var brokenState = brokenDir.resolve("state"); Files.createDirectories(brokenState);
        try {
            PixelSamplingProbe.reopen(broken::current, broken::history, brokenState,
                new LinkedHashMap<String, Object>());
        } catch (Throwable expected) { threw = true; }
        check(threw, "reopen accepted a document without the fixture parameter");
        System.out.println("T014 REOPEN_E2E checks=" + checks + " PASS; hostExecuted=false"
            + " artifacts=" + dir);
    }

    /** One-function oracle call: real verifier module, real report/PNG bytes. */
    private static int oracleSection(Path verifier, Path reportFile, Path pngDir,
            String function, String mode, Path cmo3) throws Exception {
        return oracleSection(verifier, reportFile, pngDir, function, mode, cmo3, null);
    }

    private static int oracleSection(Path verifier, Path reportFile, Path pngDir,
            String function, String mode, Path cmo3, Path postPng) throws Exception {
        var script = "import importlib.util, json, pathlib, sys\n"
            + "spec = importlib.util.spec_from_file_location('v', sys.argv[1])\n"
            + "v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)\n"
            + "r = json.loads(pathlib.Path(sys.argv[2]).read_text())\n"
            + "frames = {p.stem: p.read_bytes() for p in pathlib.Path(sys.argv[3]).glob('*.png')}\n"
            + "try:\n"
            + (mode.equals("report-plus-cmo3")
                ? "    v." + function + "(r, pathlib.Path(sys.argv[4]).read_bytes(),\n"
                    + "        pathlib.Path(sys.argv[5]).read_bytes(), frames)\n"
                : "    v." + function + "(r, frames)\n")
            + "except Exception as exc:\n"
            + "    print('REJECT: ' + str(exc)); sys.exit(3)\n"
            + "print('ACCEPTED')\n";
        var scriptFile = Files.createTempFile("t014-oracle", ".py");
        Files.writeString(scriptFile, script);
        var command = new ArrayList<>(List.of("python3", scriptFile.toString(),
            verifier.toString(), reportFile.toString(), pngDir.toString()));
        if (cmo3 != null) command.add(cmo3.toString());
        if (postPng != null) command.add(postPng.toString());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var out = new String(process.getInputStream().readAllBytes());
        int rc = process.waitFor();
        if (rc != 0) System.out.println("oracle output: " + out.strip());
        oracleOutput = out;
        return rc;
    }

    // ----- real artifact plumbing -------------------------------------------------

    private static Path verifierScript() throws Exception {
        var here = Path.of("").toAbsolutePath();
        for (var dir = here; dir != null; dir = dir.getParent()) {
            var candidate = dir.resolve("validation/model-import-3d-host-probe/verify-pixel-sampling.py");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("verify-pixel-sampling.py not found above " + here);
    }

    private static int oracle(Path verifier, Path reportFile, Path pngDir) throws Exception {
        var script = "import importlib.util, json, pathlib, sys\n"
            + "spec = importlib.util.spec_from_file_location('v', sys.argv[1])\n"
            + "v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)\n"
            + "r = json.loads(pathlib.Path(sys.argv[2]).read_text())\n"
            + "frames = {p.stem: p.read_bytes() for p in pathlib.Path(sys.argv[3]).glob('*.png')}\n"
            + "try:\n"
            + "    v.verify_pixel_sampling(r, frames)\n"
            + "except Exception as exc:\n"
            + "    print('REJECT: ' + str(exc)); sys.exit(3)\n"
            + "print('ACCEPTED')\n";
        var scriptFile = Files.createTempFile("t008-oracle", ".py");
        Files.writeString(scriptFile, script);
        var process = new ProcessBuilder("python3",
            scriptFile.toString(), verifier.toString(), reportFile.toString(), pngDir.toString())
            .redirectErrorStream(true).start();
        var out = new String(process.getInputStream().readAllBytes());
        int rc = process.waitFor();
        if (rc != 0) System.out.println("oracle output: " + out.strip());
        oracleOutput = out;
        return rc;
    }

    private static String oracleOutput = "";

    private static void rejectReason(Path verifier, Path report, Path pngDir, String reason)
            throws Exception {
        int rc = oracle(verifier, report, pngDir);
        check(rc != 0 && oracleOutput.contains(reason),
            "expected rejection containing '" + reason + "' got rc=" + rc + " " + oracleOutput.strip());
    }

    // Minimal JSON reader for the mutation cases (report is producer-written JSON).
    private static Map<String, Object> parse(String text) {
        return new JsonReader(text).object();
    }

    private static Path writeReport(Map<String, Object> report) throws Exception {
        var file = Files.createTempFile("t008-report", ".json");
        Files.writeString(file, NativeReport.json(report));
        return file;
    }

    private static Path copyPngs(Path parent, Path source) throws Exception {
        var dir = Files.createTempDirectory(parent, "pngs");
        try (var stream = Files.list(source)) {
            for (var p : stream.toList()) Files.copy(p, dir.resolve(p.getFileName().toString()));
        }
        return dir;
    }

    /** Re-sign every frame row against the mutated PNG bytes on disk. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resigned(Map<String, Object> report, Path pngDir) throws Exception {
        var section = (Map<String, Object>) report.get("pixelSampling");
        for (Map<String, Object> row : (List<Map<String, Object>>) section.get("frames")) {
            var file = pngDir.resolve(row.get("pose") + ".png");
            var data = Files.readAllBytes(file);
            row.put("artifactSha256", sha256(data));
            row.put("artifactBytes", data.length);
        }
        return report;
    }

    private static String sha256(byte[] bytes) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        var hex = new StringBuilder();
        for (byte b : digest.digest(bytes)) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private static java.awt.image.BufferedImage decode(Path file) throws Exception {
        return javax.imageio.ImageIO.read(file.toFile());
    }

    private static void writePng(java.awt.image.BufferedImage image, Path file) throws Exception {
        javax.imageio.ImageIO.write(image, "PNG", file.toFile());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("T008 e2e: " + message);
        checks++;
    }

    private static dev.turboism.sdk.cubism.model.IntSequence emptyInts() {
        return new dev.turboism.sdk.cubism.model.IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(int index) { throw new IndexOutOfBoundsException(); }
        };
    }

    private static dev.turboism.sdk.cubism.model.FloatSequence emptyFloats() {
        return new dev.turboism.sdk.cubism.model.FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(int index) { throw new IndexOutOfBoundsException(); }
        };
    }

    // ----- minimal JSON reader (test-only; real reports still written by NativeReport.json)

    private static final class JsonReader {
        private final String s; private int i;
        JsonReader(String s) { this.s = s; }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private char peek() { ws(); return s.charAt(i); }
        private void expect(char c) { if (peek() != c) throw new IllegalStateException("JSON " + c + " at " + i); i++; }
        Map<String, Object> object() { return (Map<String, Object>) value(); }
        private Object value() {
            char c = peek();
            if (c == '{') {
                expect('{'); var map = new LinkedHashMap<String, Object>();
                if (peek() == '}') { i++; return map; }
                while (true) {
                    var key = (String) value(); expect(':');
                    map.put(key, value());
                    if (peek() == ',') { i++; continue; }
                    expect('}'); return map;
                }
            }
            if (c == '[') {
                expect('['); var list = new ArrayList<Object>();
                if (peek() == ']') { i++; return list; }
                while (true) {
                    list.add(value());
                    if (peek() == ',') { i++; continue; }
                    expect(']'); return list;
                }
            }
            if (c == '"') {
                expect('"'); var sb = new StringBuilder();
                while (s.charAt(i) != '"') {
                    char ch = s.charAt(i++);
                    if (ch == '\\') {
                        char esc = s.charAt(i++);
                        sb.append(switch (esc) {
                            case 'u' -> (char) Integer.parseInt(s.substring(i, i + 4), 16);
                            case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r';
                            case 'b' -> '\b'; case 'f' -> '\f';
                            default -> esc;
                        });
                        if (esc == 'u') i += 4;
                    } else sb.append(ch);
                }
                i++; return sb.toString();
            }
            if (c == 't') { i += 4; return Boolean.TRUE; }
            if (c == 'f') { i += 5; return Boolean.FALSE; }
            if (c == 'n') { i += 4; return null; }
            int start = i;
            while (i < s.length() && "-0123456789.eE+".indexOf(s.charAt(i)) >= 0) i++;
            var num = s.substring(start, i);
            if (num.contains(".") || num.contains("e") || num.contains("E"))
                return Double.parseDouble(num);
            return Long.parseLong(num);
        }
    }

    // ----- synthetic deterministic host (not native evidence) ----------------------

    /** Synthetic model surface covering exactly what the probe touches. */
    private static final class FakeHost {
        final FakeModel model = new FakeModel();
        final List<HistoryEntry> entries = new ArrayList<>();
        long revision = 1;
        FakeParameter param;
        FakeMesh mesh;
        boolean bound;
        int[][] material;   // decoded [y][x] argb of the probe material
        final ModelImageId imageId = new ModelImageId("mi3d-t008-material");
        /** Host-like initial canvas (the real host reported 1000x2000). */
        float canvasW = 1000f, canvasH = 2000f;
        int canvasEntry = -1;
        FakeUserFiles files;

        /** Fresh SDK value projection per read — mirrors the production
         * EditorModelInstanceAccess which allocates a new object each call. */
        static ModelInstance newInstance() {
            return new ModelInstance() {
                @Override public InstanceRenderType renderType() {
                    return InstanceRenderType.NORMAL;
                }
            };
        }

        CubismModel current() { return model; }

        /** Task-pinned grant seam stand-in: only the exact T014 request grants,
         * bound to the derived state-dir path; ONE_OPERATION is consumed by the
         * first file operation, mirroring the real handle lifecycle. */
        FakeUserFiles userFiles(Path stateDir, boolean enabled) {
            files = new FakeUserFiles(stateDir.resolve("persistence")
                .resolve("mi3d-t014-model.cmo3"), enabled);
            return files;
        }

        /** Deterministic stand-in for native serialization: the document bytes
         * encode the persisted fixture identities/content so a second process
         * would resolve the same objects. Never asserted as a real CMO3. */
        byte[] fakeCmo3() {
            var sb = new StringBuilder("FAKE-CMO3\n");
            if (mesh != null) {
                var s = mesh.authoringSnapshot();
                sb.append(mesh.id()).append('\n').append(s.sourceGuid()).append('\n');
                for (var p : s.sourceGeometry().positions())
                    sb.append(p.x()).append(',').append(p.y()).append(';');
                sb.append('\n');
                for (var f : s.forms()) sb.append(f.formGuid()).append(';');
                sb.append('\n');
            }
            if (param != null) sb.append(param.id().value()).append('\n');
            sb.append("bound=").append(bound).append('\n');
            sb.append("canvas=").append(canvasW).append('x').append(canvasH).append('\n');
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        /** Job B world: the saved document already loaded — identical
         * persistent ids and content, fresh object instances, clean history. */
        static FakeHost reopened() throws Exception {
            var h = new FakeHost();
            h.canvasW = 1024f; h.canvasH = 1024f;
            h.param = h.new FakeParameter(new ParameterDefinition(
                new ParameterId("ParamMI3DPixShift"), "MI3D pixel shift",
                -1f, 0f, 1f, dev.turboism.sdk.cubism.model.ParameterType.NORMAL, false));
            var mesh = h.new FakeMesh(new ArtMeshId("artmesh-mi3d_pix_plane"),
                PixelSamplingProbe.geometry());
            mesh.axis = new ArtMeshAuthoringSnapshot.Axis("axis-shift-guid",
                new ParameterId("ParamMI3DPixShift"), -1f, 0f, 1f, List.of(-1f, 0f, 1f));
            mesh.forms = new ArrayList<>(List.of(mesh.stored("form-neg", -80f),
                mesh.stored("form-default", 0f), mesh.stored("form-pos", 80f)));
            mesh.cells = List.of(
                new ArtMeshAuthoringSnapshot.Cell("form-neg",
                    List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-shift-guid", -1f))),
                new ArtMeshAuthoringSnapshot.Cell("form-default",
                    List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-shift-guid", 0f))),
                new ArtMeshAuthoringSnapshot.Cell("form-pos",
                    List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-shift-guid", 1f))));
            h.mesh = mesh;
            h.bound = true;
            h.imageBindEntry = -1;  // persisted binding is not a history entry
            h.material = decodeMaterial(PixelSamplingProbe.materialPng());
            return h;
        }

        static int[][] decodeMaterial(byte[] png) {
            try {
                var decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
                var material = new int[decoded.getHeight()][decoded.getWidth()];
                for (int y = 0; y < decoded.getHeight(); y++)
                    for (int x = 0; x < decoded.getWidth(); x++)
                        material[y][x] = decoded.getRGB(x, y);
                return material;
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("fixture material decode", failure);
            }
        }

        /** Faithful micro-contract for the user-file seam: async request result,
         * exact-request grant only, a handle bound to the derived path, and
         * ONE_OPERATION consumption on the first file use. */
        final class FakeUserFiles implements UserFileAccessService {
            private final Path target; private final boolean enabled;
            private FakeHandle granted;
            FakeUserFiles(Path target, boolean enabled) {
                this.target = target; this.enabled = enabled;
            }
            Path boundPath(UserFileHandle handle) {
                return handle == granted && granted != null ? target : null;
            }
            private <T> java.util.concurrent.CompletableFuture<T> done(T v) {
                return java.util.concurrent.CompletableFuture.completedFuture(v);
            }
            @Override public java.util.concurrent.CompletionStage<UserFileRequestResult>
                    request(UserFileRequest r) {
                boolean exact = enabled
                    && r.id().equals("mi3d-t014-persistence-save")
                    && r.mode() == UserFileMode.WRITE
                    && r.lifetime() == UserFileLifetime.ONE_OPERATION
                    && r.allowedExtensions().equals(List.of("cmo3"));
                if (!exact) return done(new UserFileRequestResult(
                    UserFileRequestStatus.UNAVAILABLE, Optional.empty(),
                    Optional.of(new UserFileError(UserFileErrorCode.RUNTIME_UNAVAILABLE,
                        "no task grant"))));
                granted = new FakeHandle();
                return done(new UserFileRequestResult(UserFileRequestStatus.GRANTED,
                    Optional.of(granted), Optional.empty()));
            }
            private UserFileReadResult<byte[]> denied() {
                return new UserFileReadResult<>(Optional.empty(),
                    Optional.of(new UserFileError(UserFileErrorCode.INVALID_GRANT,
                        "handle not usable")), false);
            }
            @Override public java.util.concurrent.CompletionStage<UserFileReadResult<String>>
                    readUtf8(UserFileHandle h, int maxBytes) {
                var denied = new UserFileReadResult<String>(Optional.empty(),
                    Optional.of(new UserFileError(UserFileErrorCode.INVALID_GRANT,
                        "unused in this fixture")), false);
                return done(denied);
            }
            @Override public java.util.concurrent.CompletionStage<UserFileReadResult<byte[]>>
                    readBytes(UserFileHandle h, int maxBytes) {
                return done(denied());
            }
            @Override public java.util.concurrent.CompletionStage<UserFileWriteResult>
                    writeUtf8Atomic(UserFileHandle h, String content) {
                return writeBytesAtomic(h, content.getBytes(StandardCharsets.UTF_8));
            }
            @Override public java.util.concurrent.CompletionStage<UserFileWriteResult>
                    writeBytesAtomic(UserFileHandle h, byte[] content) {
                if (h != granted || h.state() != UserFileHandleState.ACTIVE)
                    return done(new UserFileWriteResult(false,
                        Optional.of(new UserFileError(UserFileErrorCode.INVALID_GRANT,
                            "handle not usable"))));
                try {
                    Files.write(target, content,
                        java.nio.file.StandardOpenOption.CREATE_NEW);
                    ((FakeHandle) h).consume();
                    return done(new UserFileWriteResult(true, Optional.empty()));
                } catch (java.io.IOException failure) {
                    return done(new UserFileWriteResult(false,
                        Optional.of(new UserFileError(UserFileErrorCode.IO_FAILURE,
                            failure.toString()))));
                }
            }

            final class FakeHandle implements UserFileHandle {
                private UserFileHandleState state = UserFileHandleState.ACTIVE;
                @Override public String id() { return "grant-t014"; }
                @Override public String displayName() { return "mi3d-t014-model.cmo3"; }
                @Override public UserFileMode mode() { return UserFileMode.WRITE; }
                @Override public UserFileLifetime lifetime() {
                    return UserFileLifetime.ONE_OPERATION;
                }
                @Override public UserFileHandleState state() { return state; }
                void consume() { if (state == UserFileHandleState.ACTIVE) state = UserFileHandleState.CLOSED; }
                @Override public void revoke() { state = UserFileHandleState.REVOKED; }
                @Override public void close() { consume(); }
            }
        }

        HistorySnapshot history() {
            return new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE,
                1, revision, position(), List.copyOf(entries), position() > 0,
                position() < entries.size(), "doc-t008", "mgr-t008");
        }

        void commit(String label) {
            entries.add(new HistoryEntry(entries.size(), label, true));
            applied = entries.size();
            revision++;
        }

        int position() { return applied; }
        int applied;
        int imageBindEntry = -1;

        /** History semantics: each standalone setup edit and each transaction
         * commits its own entry; the canvas command and the image bind are
         * visible exactly at/after the position they committed at. Root restore
         * drops all fixture objects and the 1024 canvas. */
        void restore(int position) {
            applied = position;
            if (position <= 0) { param = null; mesh = null; }
            if (!(canvasEntry > 0 && position >= canvasEntry)) {
                canvasW = 1000f; canvasH = 2000f;
            }
            bound = imageBindEntry > 0 && position >= imageBindEntry;
            revision++;
        }

        /** Typed-command surface mirroring the real service: the canvas
         * settings request is an undoable native edit. */
        final EditorCommandService commands = new EditorCommandService() {
            @Override public Set<EditorCommand> available() { return Set.of(); }
            @Override public EditorCommandResult execute(EditorCommand command) {
                return new EditorCommandResult(EditorCommandResult.Status.UNAVAILABLE, command.id());
            }
            @Override public EditorCommandResult execute(EditorFileCommandRequest request) {
                if (request.command() != EditorFileCommand.SAVE_AS || files == null)
                    return new EditorCommandResult(EditorCommandResult.Status.UNAVAILABLE,
                        request.commandId());
                var handle = request.file();
                var path = files.boundPath(handle);
                if (path == null || handle.mode() != UserFileMode.WRITE)
                    return new EditorCommandResult(EditorCommandResult.Status.PERMISSION_DENIED,
                        request.commandId());
                if (handle.state() != UserFileHandleState.ACTIVE)
                    return new EditorCommandResult(EditorCommandResult.Status.INVALID_STATE,
                        request.commandId());
                try {
                    if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            && request.overwritePolicy() == EditorOverwritePolicy.REJECT_EXISTING)
                        return new EditorCommandResult(EditorCommandResult.Status.REJECTED,
                            request.commandId());
                    Files.write(path, fakeCmo3(), java.nio.file.StandardOpenOption.CREATE_NEW);
                    ((FakeUserFiles.FakeHandle) handle).consume();
                    return new EditorCommandResult(EditorCommandResult.Status.EXECUTED,
                        request.commandId());
                } catch (java.io.IOException failure) {
                    return new EditorCommandResult(EditorCommandResult.Status.FAILED,
                        request.commandId());
                }
            }
            @Override public EditorCommandResult execute(EditorParameterizedRequest request) {
                if (request instanceof EditorCanvasSettingsRequest settings) {
                    canvasW = settings.widthPixels(); canvasH = settings.heightPixels();
                    commit("MI3D canvas settings");
                    canvasEntry = applied;
                    return new EditorCommandResult(EditorCommandResult.Status.EXECUTED,
                        request.commandId());
                }
                return new EditorCommandResult(EditorCommandResult.Status.UNAVAILABLE,
                    request.commandId());
            }
        };

        final AuthoringTransactionService transactions = new AuthoringTransactionService() {
            @Override
            public <T> AuthoringTransactionResult<T> execute(
                    AuthoringTransactionOptions options, AuthoringTransactionWork<T> work) {
                var before = history();
                try {
                    var value = work.run();
                    commit(options.label());
                    return AuthoringTransactionResult.committed(value,
                        new AuthoringTransactionReceipt("tx-" + entries.size(), options.label(),
                            before, history(), Optional.of("entry-" + entries.size())));
                } catch (Exception failure) {
                    throw new IllegalStateException("fixture transaction failed", failure);
                }
            }
        };

        final class FakeModel implements CubismModel {
            @Override public ModelId id() { return new ModelId("model-t008"); }
            @Override public List<ModelInstance> modelInstances() {
                return List.of(newInstance());
            }
            @Override public Optional<ModelInstance> currentModelInstance() {
                return Optional.of(newInstance());
            }
            @Override public Parameters parameters() { return params; }
            @Override public Canvas canvas() {
                return new Canvas() {
                    @Override public float widthPixels() { return canvasW; }
                    @Override public float heightPixels() { return canvasH; }
                    @Override public float originXPixels() { return -canvasW / 2f; }
                    @Override public float originYPixels() { return canvasH / 2f; }
                    @Override public float pixelsPerUnit() { return 1f; }
                };
            }
            @Override public Drawables drawables() { return draws; }
            @Override public ModelTextures textures() { return texs; }
            @Override public ParameterBindingOperations parameterBindings(ParameterId id) { return binds; }
            @Override public void update() { /* fixture evaluation is computed lazily per read */ }
            @Override public dev.turboism.sdk.cubism.model.Parts parts() {
                return new dev.turboism.sdk.cubism.model.Parts() {
                    @Override public List<Part> all() { return List.of(); }
                    @Override public Part find(dev.turboism.sdk.cubism.model.PartId id) {
                        throw new NoSuchElementException();
                    }
                };
            }
            @Override public dev.turboism.sdk.cubism.model.Deformers deformers() {
                return new dev.turboism.sdk.cubism.model.Deformers() {
                    @Override public List<dev.turboism.sdk.cubism.model.Deformer> all() {
                        return List.of();
                    }
                    @Override public dev.turboism.sdk.cubism.model.Deformer find(
                            dev.turboism.sdk.cubism.id.DeformerId id) {
                        throw new NoSuchElementException();
                    }
                };
            }
            @Override public dev.turboism.sdk.cubism.model.Glues glues() {
                return new dev.turboism.sdk.cubism.model.Glues() {
                    @Override public List<dev.turboism.sdk.cubism.model.Glue> all() {
                        return List.of();
                    }
                    @Override public dev.turboism.sdk.cubism.model.Glue find(
                            dev.turboism.sdk.cubism.model.GlueId id) {
                        throw new NoSuchElementException();
                    }
                };
            }

            final Parameters params = new Parameters() {
                @Override public List<Parameter> all() {
                    return param == null ? List.of() : List.of(param);
                }
                @Override public Parameter find(ParameterId id) {
                    // Real SDK semantics: find throws when the id is absent —
                    // never a null that could mask a deleted fixture parameter.
                    if (param != null && param.id().equals(id)) return param;
                    throw new NoSuchElementException(String.valueOf(id));
                }
                @Override public List<Parameter> createMany(List<ParameterDefinition> definitions) {
                    var created = new ArrayList<Parameter>();
                    for (var def : definitions) {
                        param = new FakeParameter(def);
                        created.add(param);
                    }
                    commit("MI3D standalone parameter create");
                    return created;
                }
            };

            final Drawables draws = new Drawables() {
                @Override public List<Drawable> all() {
                    return mesh == null ? List.of() : List.of(mesh);
                }
                @Override public Drawable find(ArtMeshId id) {
                    if (mesh != null && mesh.id().equals(id)) return mesh;
                    throw new NoSuchElementException(String.valueOf(id));
                }
                @Override public Drawable create(String name, Part parent, int index,
                        ArtMeshGeometry geometry) {
                    mesh = new FakeMesh(new ArtMeshId("artmesh-" + name.toLowerCase()), geometry);
                    commit("MI3D standalone mesh create");
                    return mesh;
                }
            };

            final ParameterBindingOperations binds = new ParameterBindingOperations() {
                @Override public void bind(ParameterBindingTarget target, List<ParameterBindingPoint> points) {
                    commit("MI3D standalone parameter bind");
                    var keys = new ArrayList<Float>();
                    for (var p : points) keys.add(p.value());
                    mesh.axis = new ArtMeshAuthoringSnapshot.Axis("axis-shift-guid",
                        new ParameterId("ParamMI3DPixShift"), -1f, 0f, 1f, List.copyOf(keys));
                    // One form per key; cell coordinates bind keys to forms.
                    mesh.forms = new ArrayList<>(List.of(
                        mesh.stored("form-neg", -80f), mesh.stored("form-default", 0f),
                        mesh.stored("form-pos", 80f)));
                    mesh.cells = List.of(
                        new ArtMeshAuthoringSnapshot.Cell("form-neg",
                            List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-shift-guid", -1f))),
                        new ArtMeshAuthoringSnapshot.Cell("form-default",
                            List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-shift-guid", 0f))),
                        new ArtMeshAuthoringSnapshot.Cell("form-pos",
                            List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-shift-guid", 1f))));
                }
                @Override public void createPoint(ParameterBindingTarget target,
                        ParameterBindingPoint point) { throw new UnsupportedOperationException(); }
                @Override public void movePoint(ParameterBindingTarget target,
                        dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId, float value) {
                    throw new UnsupportedOperationException();
                }
                @Override public void deletePoint(ParameterBindingTarget target,
                        dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId) {
                    throw new UnsupportedOperationException();
                }
                @Override public void unbind(ParameterBindingTarget target) {
                    throw new UnsupportedOperationException();
                }
            };

            final ModelTextures texs = new ModelTextures() {
                @Override public List<dev.turboism.sdk.cubism.model.RawTexture> rawImages() {
                    return List.of();
                }
                @Override public List<dev.turboism.sdk.cubism.model.ModelImageGroup> modelImageGroups() {
                    return List.of();
                }
                @Override public List<dev.turboism.sdk.cubism.model.AtlasTexture> textureAtlases() {
                    return List.of();
                }
                @Override public void addModelImageGroup(String name) {
                    throw new UnsupportedOperationException();
                }
                @Override public void removeModelImage(ModelImageId id) {
                    throw new UnsupportedOperationException();
                }
                @Override public TextureAtlasId addTextureAtlas(String name, int w, int h) {
                    throw new UnsupportedOperationException();
                }
                @Override public void removeTextureAtlas(TextureAtlasId id) {
                    throw new UnsupportedOperationException();
                }
                @Override public void removeRawImage(dev.turboism.sdk.cubism.id.RawImageId id) {
                    throw new UnsupportedOperationException();
                }
                @Override public ModelImageBinding registerImageAndBind(
                        AuthorizedImageContent image, ArtMeshId target) {
                    try {
                        var decoded = javax.imageio.ImageIO.read(
                            new java.io.ByteArrayInputStream(image.copyBytes()));
                        material = new int[decoded.getHeight()][decoded.getWidth()];
                        for (int y = 0; y < decoded.getHeight(); y++)
                            for (int x = 0; x < decoded.getWidth(); x++)
                                material[y][x] = decoded.getRGB(x, y);
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException("fixture material decode", failure);
                    }
                    bound = true;
                    commit("MI3D image bind");
                    imageBindEntry = applied;
                    return new ModelImageBinding("fixture", id(), 1,
                        new dev.turboism.sdk.cubism.id.RawImageId("raw-1"), imageId,
                        Optional.of(target));
                }
                @Override public Optional<ArtMeshTextureInput> textureInput(ArtMeshId target) {
                    if (!bound || mesh == null || !mesh.id().equals(target)) return Optional.empty();
                    return Optional.of(new ArtMeshTextureInput(target, id(), 1,
                        ArtMeshTextureInput.Kind.MODEL_IMAGE, Optional.of(imageId), Optional.empty()));
                }
            };

            @Override public CubismRenderedPixels renderPixels(int width, int height) {
                var argb = new int[width * height];
                if (mesh != null && bound) {
                    var evaluated = mesh.geometry().positions();
                    float x0 = evaluated.get(0).x(), x1 = evaluated.get(1).x();
                    float y0 = evaluated.get(0).y(), y1 = evaluated.get(2).y();
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            if (x >= x0 && x < x1 && y >= y0 && y < y1) {
                                int sx = Math.min(255, (int) ((x - x0) / (x1 - x0) * 256));
                                int sy = Math.min(127, (int) ((y - y0) / (y1 - y0) * 128));
                                argb[y * width + x] = material[sy][sx];
                            }
                        }
                    }
                }
                return new CubismRenderedPixels(width, height, argb);
            }
        }

        final class FakeParameter implements Parameter {
            private final ParameterDefinition def;
            private float value;
            FakeParameter(ParameterDefinition def) { this.def = def; this.value = def.defaultValue(); }
            @Override public ParameterId id() { return def.id(); }
            @Override public float getValue() { return value; }
            @Override public float getMinimumValue() { return def.minimumValue(); }
            @Override public float getMaximumValue() { return def.maximumValue(); }
            @Override public float getDefaultValue() { return def.defaultValue(); }
            @Override public void setValue(float v) { value = v; }
        }

        final class FakeMesh implements Drawable {
            private final ArtMeshId id;
            private final ArtMeshGeometry source;
            private ArtMeshAuthoringSnapshot.Axis axis;
            private List<ArtMeshAuthoringSnapshot.Form> forms;
            private List<ArtMeshAuthoringSnapshot.Cell> cells = List.of();
            FakeMesh(ArtMeshId id, ArtMeshGeometry source) {
                this.id = id; this.source = source;
                forms = new ArrayList<>(List.of(stored("form-default", 0f)));
            }
            private ArtMeshAuthoringSnapshot.Form stored(String guid, float dx) {
                var pts = new ArrayList<Point2>();
                for (var p : source.positions()) pts.add(new Point2(p.x() + dx, p.y()));
                return new ArtMeshAuthoringSnapshot.Form(guid, List.copyOf(pts), 500);
            }
            @Override public ArtMeshId id() { return id; }
            @Override public byte constantFlag() { return 0; }
            @Override public byte dynamicFlag() { return 0; }
            @Override public dev.turboism.sdk.cubism.model.BlendMode blendMode() {
                return dev.turboism.sdk.cubism.model.BlendMode.NORMAL;
            }
            @Override public int textureIndex() { return 0; }
            @Override public int drawOrder() { return 500; }
            @Override public int renderOrder() { return 0; }
            @Override public float getOpacity() { return 1f; }
            @Override public dev.turboism.sdk.cubism.model.IntSequence masks() { return emptyInts(); }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
                return emptyFloats();
            }
            @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
                return emptyFloats();
            }
            @Override public dev.turboism.sdk.cubism.model.IntSequence indices() { return emptyInts(); }
            @Override public dev.turboism.sdk.cubism.model.Color multiplyColor() {
                return new dev.turboism.sdk.cubism.model.Color(1f, 1f, 1f, 1f);
            }
            @Override public dev.turboism.sdk.cubism.model.Color screenColor() {
                return new dev.turboism.sdk.cubism.model.Color(0f, 0f, 0f, 1f);
            }
            @Override public int parentPartIndex() { return -1; }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
                return emptyInts();
            }
            @Override public ArtMeshGeometry geometry() {
                var pts = new ArrayList<Point2>();
                float dx = param == null ? 0f : 80f * param.getValue();
                for (var p : source.positions()) pts.add(new Point2(p.x() + dx, p.y()));
                return new ArtMeshGeometry(List.copyOf(pts), source.uvs(), source.triangleIndices());
            }
            @Override public ArtMeshAuthoringSnapshot authoringSnapshot() {
                return new ArtMeshAuthoringSnapshot(id, "source-" + id.value(), source,
                    axis == null ? List.of() : List.of(axis), cells, List.copyOf(forms), List.of());
            }
            @Override public ArtMeshKeyform ordinaryKeyform(
                    List<ArtMeshAuthoringSnapshot.Coordinate> coordinates) {
                for (var cell : cells) {
                    if (cell.coordinates().equals(coordinates)) {
                        var formGuid = cell.formGuid();
                        return new ArtMeshKeyform() {
                            @Override public List<ArtMeshAuthoringSnapshot.Coordinate> coordinates() {
                                return cell.coordinates();
                            }
                            @Override public ArtMeshAuthoringSnapshot.Form snapshot() {
                                for (var f : forms) if (f.formGuid().equals(formGuid)) return f;
                                throw new IllegalStateException();
                            }
                            // True default-keyform semantics: only the tuple at
                            // the axis default value is the default form.
                            @Override public boolean defaultKeyform() {
                                return cell.coordinates().stream()
                                    .allMatch(c -> c.value() == axis.defaultValue());
                            }
                            @Override public void setPositions(List<Point2> positions) {
                                for (int i = 0; i < forms.size(); i++)
                                    if (forms.get(i).formGuid().equals(formGuid))
                                        forms.set(i, new ArtMeshAuthoringSnapshot.Form(
                                            formGuid, List.copyOf(positions), forms.get(i).drawOrder()));
                            }
                        };
                    }
                }
                throw new IllegalArgumentException("no such keyform");
            }
        }
    }
}
